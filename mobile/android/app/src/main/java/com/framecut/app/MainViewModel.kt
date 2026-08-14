package com.framecut.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.framecut.app.auth.AuthManager
import com.framecut.app.auth.AuthRequiredException
import com.framecut.app.drive.AppProperties
import com.framecut.app.drive.Crumb
import com.framecut.app.drive.DriveApi
import com.framecut.app.drive.DriveItem
import com.framecut.app.drive.Downloader
import com.framecut.app.drive.MY_DRIVE
import com.framecut.app.drive.SHARED_WITH_ME
import com.framecut.app.drive.SortKey
import com.framecut.app.drive.UploadMeta
import com.framecut.app.drive.Uploader
import com.framecut.app.drive.filterItems
import com.framecut.app.drive.isSharedRoot
import com.framecut.app.drive.sortItems
import com.framecut.app.net.HttpException
import com.framecut.app.trim.DeviceSaver
import com.framecut.app.trim.Trimmer
import com.framecut.app.util.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class Screen { SIGN_IN, BROWSE, TRIM, DONE }

enum class AuthPhase { UNCONFIGURED, RESTORING, SIGNED_OUT, SIGNED_IN }

data class AuthState(
    val phase: AuthPhase = AuthPhase.RESTORING,
    val email: String? = null,
    val signingIn: Boolean = false,
)

data class BrowseState(
    val path: List<Crumb> = listOf(MY_DRIVE),
    val items: List<DriveItem> = emptyList(),
    val visible: List<DriveItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val searchMode: Boolean = false,
    val searchQuery: String = "",
    val filterText: String = "",
    val sortKey: SortKey = SortKey.NAME,
    val sortAscending: Boolean = true,
) {
    val current: Crumb get() = path.last()
}

data class FolderPickerState(
    val path: List<Crumb> = listOf(MY_DRIVE),
    val folders: List<DriveItem> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
) {
    val current: Crumb get() = path.last()
    val canChoose: Boolean get() = !current.isSharedRoot()
}

/** Long-running operation banner: null when idle. */
data class BusyState(
    val title: String,
    val progress: Float? = null,
    val status: String = "",
    val cancellable: Boolean = true,
)

data class TrimState(
    val sourceId: String,
    val sourceName: String,
    val sourceSize: Long,
    val localPath: String,
    val durationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val outputName: String,
    val description: String = "",
    val destination: Crumb? = null,
) {
    val keptMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

data class DoneState(
    val headline: String,
    val detail: String,
    val webViewLink: String? = null,
)

class MainViewModel(
    application: Application,
    private val saved: SavedStateHandle,
) : AndroidViewModel(application) {

    private val auth = AuthManager(application)
    private val api = DriveApi(auth)
    private val downloader = Downloader(api)
    private val uploader = Uploader(api)
    private val prefs = application.getSharedPreferences("framecut.prefs", Context.MODE_PRIVATE)

    private val _screen = MutableStateFlow(Screen.SIGN_IN)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _authState = MutableStateFlow(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    private val _picker = MutableStateFlow<FolderPickerState?>(null)
    val picker: StateFlow<FolderPickerState?> = _picker.asStateFlow()

    private val _busy = MutableStateFlow<BusyState?>(null)
    val busy: StateFlow<BusyState?> = _busy.asStateFlow()

    private val _trim = MutableStateFlow<TrimState?>(null)
    val trim: StateFlow<TrimState?> = _trim.asStateFlow()

    private val _done = MutableStateFlow<DoneState?>(null)
    val done: StateFlow<DoneState?> = _done.asStateFlow()

    private val messages = Channel<String>(Channel.BUFFERED)
    val snackbars = messages.receiveAsFlow()

    private var browseJob: Job? = null
    private var pickerJob: Job? = null
    private var operationJob: Job? = null

    init {
        restoreSortPreference()
        restoreSession()
        bootstrap()
    }

    // ---------------------------------------------------------------- auth

    private fun bootstrap() {
        if (!Config.isConfigured) {
            _authState.value = AuthState(AuthPhase.UNCONFIGURED)
            return
        }
        if (!auth.hasRefreshToken) {
            _authState.value = AuthState(AuthPhase.SIGNED_OUT)
            _screen.value = Screen.SIGN_IN
            return
        }
        // Silent restore: swap the stored refresh token for an access token so
        // the user never sees the sign-in screen again.
        _authState.value = AuthState(AuthPhase.RESTORING, auth.accountEmail)
        viewModelScope.launch {
            try {
                auth.accessToken()
                onSignedIn(refreshEmail = auth.accountEmail == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _authState.value = AuthState(AuthPhase.SIGNED_OUT)
                _screen.value = Screen.SIGN_IN
                if (e !is AuthRequiredException) report(e)
            }
        }
    }

    /** Builds the PKCE authorization URL. The caller opens it in the browser. */
    fun beginSignIn(): Uri {
        _authState.value = _authState.value.copy(signingIn = true)
        return auth.buildAuthorizationUri()
    }

    fun onSignInLaunchFailed() {
        _authState.value = _authState.value.copy(signingIn = false)
        message("No browser app is available to complete sign-in.")
    }

    fun isRedirect(uri: Uri?): Boolean = auth.isRedirect(uri)

    fun onRedirect(uri: Uri) {
        viewModelScope.launch {
            try {
                auth.completeAuthorization(uri)
                onSignedIn(refreshEmail = auth.accountEmail == null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _authState.value = AuthState(AuthPhase.SIGNED_OUT)
                _screen.value = Screen.SIGN_IN
                report(e)
            }
        }
    }

    private fun onSignedIn(refreshEmail: Boolean) {
        _authState.value = AuthState(AuthPhase.SIGNED_IN, auth.accountEmail)
        if (_screen.value == Screen.SIGN_IN) _screen.value = Screen.BROWSE
        if (_trim.value == null && _screen.value == Screen.TRIM) _screen.value = Screen.BROWSE
        loadFolder()
        if (refreshEmail) {
            viewModelScope.launch {
                val email = api.fetchUserEmail()
                if (email.isNotEmpty()) {
                    auth.rememberEmail(email)
                    _authState.value = _authState.value.copy(email = email)
                }
            }
        }
    }

    fun signOut() {
        operationJob?.cancel()
        browseJob?.cancel()
        viewModelScope.launch {
            auth.signOut()
            clearCache()
            _trim.value = null
            _done.value = null
            _browse.value = BrowseState(sortKey = _browse.value.sortKey, sortAscending = _browse.value.sortAscending)
            saved.remove<ArrayList<String>>(KEY_SAVED_PATH)
            saved.remove<ArrayList<String>>(KEY_SAVED_TRIM)
            _authState.value = AuthState(AuthPhase.SIGNED_OUT)
            _screen.value = Screen.SIGN_IN
        }
    }

    // ------------------------------------------------------------- browsing

    fun openRoot(shared: Boolean) {
        _browse.value = _browse.value.copy(
            path = listOf(if (shared) SHARED_WITH_ME else MY_DRIVE),
            filterText = "",
            searchMode = false,
            searchQuery = "",
        )
        loadFolder()
    }

    fun openFolder(item: DriveItem) {
        val state = _browse.value
        // Search results carry no parent chain, so restart from the current root.
        val path = if (state.searchMode) {
            listOf(state.path.first(), Crumb(item.id, item.name))
        } else {
            state.path + Crumb(item.id, item.name)
        }
        _browse.value = state.copy(path = path, filterText = "", searchMode = false, searchQuery = "")
        loadFolder()
    }

    fun navigateToCrumb(index: Int) {
        val state = _browse.value
        if (index !in state.path.indices) return
        _browse.value = state.copy(
            path = state.path.subList(0, index + 1),
            filterText = "",
            searchMode = false,
            searchQuery = "",
        )
        loadFolder()
    }

    fun refreshFolder() {
        if (_browse.value.searchMode) submitSearch(_browse.value.searchQuery) else loadFolder()
    }

    private fun loadFolder() {
        browseJob?.cancel()
        val folderId = _browse.value.current.id
        persistPath()
        _browse.value = _browse.value.copy(loading = true, error = null, items = emptyList(), visible = emptyList())
        browseJob = viewModelScope.launch {
            try {
                val accumulated = mutableListOf<DriveItem>()
                api.listFolder(folderId) { page ->
                    accumulated += page
                    // Paint each page as it lands; big lecture folders feel instant.
                    _browse.value = recompute(_browse.value.copy(items = accumulated.toList()))
                }
                _browse.value = recompute(_browse.value.copy(items = accumulated.toList(), loading = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _browse.value = _browse.value.copy(loading = false, error = describe(e))
                bounceIfAuthFailure(e)
            }
        }
    }

    /** Instant, client-side filter of the loaded folder. No network. */
    fun setFilter(text: String) {
        val state = _browse.value
        if (state.searchMode && text.isBlank()) {
            // Clearing the box leaves search results and returns to the folder.
            _browse.value = state.copy(filterText = "", searchMode = false, searchQuery = "")
            loadFolder()
            return
        }
        _browse.value = recompute(state.copy(filterText = text))
    }

    /** Enter/submit in the filter box escalates to a Drive-wide search. */
    fun submitSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        browseJob?.cancel()
        _browse.value = _browse.value.copy(
            loading = true,
            error = null,
            items = emptyList(),
            visible = emptyList(),
            searchMode = true,
            searchQuery = trimmed,
            filterText = trimmed,
        )
        browseJob = viewModelScope.launch {
            try {
                val results = api.searchDrive(trimmed)
                _browse.value = recompute(_browse.value.copy(items = results, loading = false))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _browse.value = _browse.value.copy(loading = false, error = describe(e))
                bounceIfAuthFailure(e)
            }
        }
    }

    fun exitSearch() {
        _browse.value = _browse.value.copy(searchMode = false, searchQuery = "", filterText = "")
        loadFolder()
    }

    fun setSort(key: SortKey) {
        val state = _browse.value
        val ascending = if (state.sortKey == key) {
            !state.sortAscending
        } else {
            key == SortKey.NAME // newest / largest first feels right for the others
        }
        prefs.edit().putString(PREF_SORT_KEY, key.name).putBoolean(PREF_SORT_ASC, ascending).apply()
        _browse.value = recompute(state.copy(sortKey = key, sortAscending = ascending))
    }

    fun createFolderHere(name: String) {
        val parent = _browse.value.current
        if (name.isBlank() || parent.isSharedRoot()) return
        viewModelScope.launch {
            try {
                api.createFolder(name.trim(), parent.id)
                loadFolder()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    private fun recompute(state: BrowseState): BrowseState {
        // Drive's `name contains` is looser than a literal substring, so while the
        // box still holds the submitted query the client filter would wrongly hide
        // legitimate server hits. Narrowing beyond the query filters normally.
        val filter = if (state.searchMode && state.filterText == state.searchQuery) {
            ""
        } else {
            state.filterText
        }
        return state.copy(
            visible = sortItems(
                filterItems(state.items, filter),
                state.sortKey,
                state.sortAscending,
            ),
        )
    }

    // ------------------------------------------------------ open + download

    fun openVideo(item: DriveItem) {
        val from = _browse.value.current
        val destination = if (from.isSharedRoot() || _browse.value.searchMode) null else from
        val extension = item.name.substringAfterLast('.', "mp4")
        val dest = File(sourceDir(), "${item.id}.$extension")

        startOperation {
            _busy.value = BusyState("Downloading from Drive", null, "")
            try {
                if (!(dest.exists() && item.size > 0 && dest.length() == item.size)) {
                    downloader.download(item.id, item.size, dest) { received, total ->
                        _busy.value = BusyState(
                            title = "Downloading from Drive",
                            progress = if (total > 0) received.toFloat() / total else null,
                            status = transferStatus(received, total),
                        )
                    }
                }
                val info = Trimmer.probe(dest)
                if (info.durationMs <= 0) {
                    throw IOException("This device cannot read that video's format.")
                }
                val base = item.name.substringBeforeLast('.', item.name)
                _trim.value = TrimState(
                    sourceId = item.id,
                    sourceName = item.name,
                    sourceSize = item.size,
                    localPath = dest.absolutePath,
                    durationMs = info.durationMs,
                    startMs = 0,
                    endMs = info.durationMs,
                    outputName = "$base-trimmed.${Trimmer.outputExtension(item.name)}",
                    destination = destination,
                )
                persistTrim()
                _screen.value = Screen.TRIM
            } catch (e: CancellationException) {
                dest.delete()
                throw e
            } catch (e: Exception) {
                dest.delete()
                report(e)
                bounceIfAuthFailure(e)
            } finally {
                _busy.value = null
            }
        }
    }

    fun cancelOperation() {
        operationJob?.cancel()
        message("Cancelled.")
    }

    /**
     * Runs the one long operation at a time, waiting for the previous one to
     * finish unwinding first. Without the join, a cancelled download's cleanup
     * (`dest.delete()`) can land after its replacement has already recreated the
     * same cache file.
     */
    private fun startOperation(block: suspend () -> Unit) {
        val previous = operationJob
        operationJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            block()
        }
    }

    // ------------------------------------------------------------ trim edit

    fun setSelection(startMs: Long, endMs: Long) {
        val state = _trim.value ?: return
        val start = startMs.coerceIn(0, state.durationMs)
        val end = endMs.coerceIn(0, state.durationMs)
        // Dragging a handle past its partner simply stops; no message, or every
        // drag frame would queue a snackbar.
        if (end - start < MIN_SELECTION_MS) return
        _trim.value = state.copy(startMs = start, endMs = end)
        persistTrim()
    }

    /** Button actions: unlike a drag, a press that cannot apply deserves a reason. */
    fun setStartAt(ms: Long) {
        val state = _trim.value ?: return
        if (state.endMs - ms < MIN_SELECTION_MS) {
            message("Move the playhead before the end point first.")
            return
        }
        setSelection(ms, state.endMs)
    }

    fun setEndAt(ms: Long) {
        val state = _trim.value ?: return
        if (ms - state.startMs < MIN_SELECTION_MS) {
            message("Move the playhead after the start point first.")
            return
        }
        setSelection(state.startMs, ms)
    }

    fun setOutputName(name: String) {
        _trim.value = _trim.value?.copy(outputName = name)
        persistTrim()
    }

    fun setDescription(text: String) {
        _trim.value = _trim.value?.copy(description = text)
        persistTrim()
    }

    fun backToBrowse() {
        operationJob?.cancel()
        _trim.value?.let { File(it.localPath).delete() }
        _trim.value = null
        _done.value = null
        saved.remove<ArrayList<String>>(KEY_SAVED_TRIM)
        _screen.value = Screen.BROWSE
        refreshFolder()
    }

    // ------------------------------------------------------- folder picker

    fun openFolderPicker() {
        val start = _browse.value.path
        _picker.value = FolderPickerState(path = start)
        loadPicker()
    }

    fun closeFolderPicker() {
        pickerJob?.cancel()
        _picker.value = null
    }

    fun pickerOpenRoot(shared: Boolean) {
        _picker.value = FolderPickerState(path = listOf(if (shared) SHARED_WITH_ME else MY_DRIVE))
        loadPicker()
    }

    fun pickerEnter(item: DriveItem) {
        val state = _picker.value ?: return
        _picker.value = state.copy(path = state.path + Crumb(item.id, item.name))
        loadPicker()
    }

    fun pickerNavigate(index: Int) {
        val state = _picker.value ?: return
        if (index !in state.path.indices) return
        _picker.value = state.copy(path = state.path.subList(0, index + 1))
        loadPicker()
    }

    fun pickerCreateFolder(name: String) {
        val state = _picker.value ?: return
        if (name.isBlank() || state.current.isSharedRoot()) return
        viewModelScope.launch {
            try {
                api.createFolder(name.trim(), state.current.id)
                loadPicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
            }
        }
    }

    fun pickerConfirm() {
        val state = _picker.value ?: return
        if (!state.canChoose) return
        _trim.value = _trim.value?.copy(destination = state.current)
        persistTrim()
        _picker.value = null
    }

    private fun loadPicker() {
        pickerJob?.cancel()
        val folderId = _picker.value?.current?.id ?: return
        _picker.value = _picker.value?.copy(loading = true, error = null, folders = emptyList())
        pickerJob = viewModelScope.launch {
            try {
                val items = api.listFolder(folderId).filter { it.isFolder }
                _picker.value = _picker.value?.copy(
                    folders = sortItems(items, SortKey.NAME, true),
                    loading = false,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _picker.value = _picker.value?.copy(loading = false, error = describe(e))
            }
        }
    }

    // ------------------------------------------------------------- save out

    fun saveToDrive() {
        val state = _trim.value ?: return
        startOperation {
            try {
                val trimmed = runTrim(state) ?: return@startOperation
                val properties = AppProperties.build(
                    fixed = linkedMapOf(
                        "tool" to "framecut",
                        "sourceFileId" to state.sourceId,
                        "trimStart" to Trimmer.toTimestamp(state.startMs / 1000.0),
                        "trimEnd" to Trimmer.toTimestamp(state.endMs / 1000.0),
                    ),
                    user = emptyMap(),
                )
                if (properties.omitted > 0 || properties.truncated > 0) {
                    message("Some Drive metadata exceeded API limits and was shortened.")
                }

                _busy.value = BusyState("Uploading to Drive", 0f, "")
                val result = uploader.upload(
                    file = trimmed.file,
                    meta = UploadMeta(
                        name = finalName(state.outputName, trimmed.file),
                        mimeType = trimmed.mimeType,
                        description = state.description.takeIf { it.isNotBlank() },
                        parents = state.destination?.id?.let { listOf(it) },
                        appProperties = properties.properties,
                    ),
                ) { sent, total ->
                    _busy.value = BusyState(
                        "Uploading to Drive",
                        if (total > 0) sent.toFloat() / total else null,
                        transferStatus(sent, total),
                    )
                }

                _done.value = DoneState(
                    headline = "Saved to Drive",
                    detail = "${result.name} (${formatBytes(trimmed.file.length())}) " +
                        "in ${state.destination?.name ?: MY_DRIVE.name}.",
                    webViewLink = result.webViewLink.takeIf { it.isNotBlank() },
                )
                trimmed.file.delete()
                _screen.value = Screen.DONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
                bounceIfAuthFailure(e)
            } finally {
                _busy.value = null
            }
        }
    }

    fun saveToDevice() {
        val state = _trim.value ?: return
        startOperation {
            try {
                val trimmed = runTrim(state) ?: return@startOperation
                _busy.value = BusyState("Saving to device", null, "", cancellable = false)
                val name = finalName(state.outputName, trimmed.file)
                val path = DeviceSaver.save(
                    getApplication(),
                    trimmed.file,
                    name,
                    trimmed.mimeType,
                )
                _done.value = DoneState(
                    headline = "Saved to this device",
                    detail = "$name (${formatBytes(trimmed.file.length())}) in $path.",
                )
                trimmed.file.delete()
                _screen.value = Screen.DONE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report(e)
            } finally {
                _busy.value = null
            }
        }
    }

    /** Runs the lossless remux; returns null (with a message) if the cut is empty. */
    private suspend fun runTrim(state: TrimState): com.framecut.app.trim.TrimResult? {
        if (state.keptMs < MIN_SELECTION_MS) {
            message("The selection is too short to trim.")
            return null
        }
        _busy.value = BusyState("Trimming (lossless copy)", 0f, "")
        val out = File(outputDir(), "framecut-${System.currentTimeMillis()}.${Trimmer.outputExtension(state.sourceName)}")
        return Trimmer.trim(
            source = File(state.localPath),
            sourceName = state.sourceName,
            dest = out,
            startUs = state.startMs * 1000,
            endUs = state.endMs * 1000,
        ) { fraction ->
            _busy.value = BusyState(
                "Trimming (lossless copy)",
                fraction,
                "${(fraction * 100).toInt()}%",
            )
        }
    }

    /** Keeps the user's name but forces the extension the muxer actually wrote. */
    private fun finalName(requested: String, produced: File): String {
        val wanted = produced.extension.ifEmpty { "mp4" }
        val base = requested.trim().ifEmpty { produced.nameWithoutExtension }
        return if (base.endsWith(".$wanted", ignoreCase = true)) {
            base
        } else {
            "${base.substringBeforeLast('.', base)}.$wanted"
        }
    }

    // ------------------------------------------------------------ plumbing

    private fun bounceIfAuthFailure(e: Throwable) {
        if (e is AuthRequiredException) {
            _authState.value = AuthState(AuthPhase.SIGNED_OUT)
            _screen.value = Screen.SIGN_IN
        }
    }

    private fun transferStatus(done: Long, total: Long): String =
        if (total > 0) "${formatBytes(done)} of ${formatBytes(total)}" else formatBytes(done)

    private fun report(e: Throwable) {
        message(describe(e))
    }

    private fun message(text: String) {
        messages.trySend(text)
    }

    private fun describe(e: Throwable): String = when (e) {
        is AuthRequiredException -> e.message ?: "Please sign in again."
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "No network connection. Check your connection and try again."
        is HttpException -> when (e.code) {
            403 -> "Google Drive refused this request (quota or permissions)."
            404 -> "That file or folder is no longer available."
            else -> "Drive error ${e.code}."
        }
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    // ------------------------------------------------------- persistence

    private fun restoreSortPreference() {
        val key = prefs.getString(PREF_SORT_KEY, null)?.let { name ->
            SortKey.entries.firstOrNull { it.name == name }
        } ?: SortKey.NAME
        val ascending = prefs.getBoolean(PREF_SORT_ASC, true)
        _browse.value = _browse.value.copy(sortKey = key, sortAscending = ascending)
    }

    /**
     * Process death: the browse path and the trim session are cheap to restore.
     * If the cached source file did not survive we fall back to the folder view
     * rather than showing a trim screen with nothing behind it.
     */
    private fun restoreSession() {
        saved.get<ArrayList<String>>(KEY_SAVED_PATH)?.let { encoded ->
            val path = encoded.mapNotNull(::decodeCrumb)
            if (path.isNotEmpty()) _browse.value = _browse.value.copy(path = path)
        }
        val trimFields = saved.get<ArrayList<String>>(KEY_SAVED_TRIM) ?: return
        if (trimFields.size < 9) return
        val file = File(trimFields[3])
        if (!file.exists() || file.length() == 0L) {
            saved.remove<ArrayList<String>>(KEY_SAVED_TRIM)
            return
        }
        _trim.value = TrimState(
            sourceId = trimFields[0],
            sourceName = trimFields[1],
            sourceSize = trimFields[2].toLongOrNull() ?: 0,
            localPath = trimFields[3],
            durationMs = trimFields[4].toLongOrNull() ?: 0,
            startMs = trimFields[5].toLongOrNull() ?: 0,
            endMs = trimFields[6].toLongOrNull() ?: 0,
            outputName = trimFields[7],
            destination = decodeCrumb(trimFields[8]),
            description = trimFields.getOrElse(9) { "" },
        )
        _screen.value = Screen.TRIM
    }

    private fun persistPath() {
        saved[KEY_SAVED_PATH] = ArrayList(_browse.value.path.map { "${it.id}\u0000${it.name}" })
    }

    private fun persistTrim() {
        val state = _trim.value ?: return
        saved[KEY_SAVED_TRIM] = arrayListOf(
            state.sourceId,
            state.sourceName,
            state.sourceSize.toString(),
            state.localPath,
            state.durationMs.toString(),
            state.startMs.toString(),
            state.endMs.toString(),
            state.outputName,
            state.destination?.let { "${it.id}\u0000${it.name}" } ?: "",
            state.description,
        )
    }

    private fun decodeCrumb(encoded: String): Crumb? {
        val parts = encoded.split('\u0000')
        if (parts.size != 2 || parts[0].isEmpty()) return null
        return Crumb(parts[0], parts[1])
    }

    // --------------------------------------------------------------- cache

    private fun sourceDir() = File(getApplication<Application>().cacheDir, "source").apply { mkdirs() }

    private fun outputDir() = File(getApplication<Application>().cacheDir, "trimmed").apply { mkdirs() }

    private fun clearCache() {
        runCatching { sourceDir().listFiles()?.forEach { it.delete() } }
        runCatching { outputDir().listFiles()?.forEach { it.delete() } }
    }

    /** Frees anything left behind by a previous run that is not the live source. */
    fun pruneCache() {
        val keep = _trim.value?.localPath
        runCatching {
            val stale: List<File> =
                sourceDir().listFiles().orEmpty().toList() + outputDir().listFiles().orEmpty().toList()
            stale.filter { it.absolutePath != keep }.forEach { it.delete() }
        }
    }

    private companion object {
        const val KEY_SAVED_PATH = "framecut.path"
        const val KEY_SAVED_TRIM = "framecut.trim"
        const val PREF_SORT_KEY = "sort.key"
        const val PREF_SORT_ASC = "sort.ascending"
        const val MIN_SELECTION_MS = 200L
    }
}
