package com.framecut.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.framecut.app.AuthPhase
import com.framecut.app.MainViewModel
import com.framecut.app.Screen

@Composable
fun FrameCutApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val screen by viewModel.screen.collectAsStateWithLifecycle()
    val auth by viewModel.authState.collectAsStateWithLifecycle()
    val browse by viewModel.browse.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val trim by viewModel.trim.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbars.collect { snackbarHostState.showSnackbar(it) }
    }

    // System back mirrors the in-app hierarchy instead of killing the task.
    BackHandler(enabled = screen != Screen.SIGN_IN) {
        when (screen) {
            Screen.TRIM, Screen.DONE -> viewModel.backToBrowse()
            Screen.BROWSE -> if (browse.searchMode) {
                viewModel.exitSearch()
            } else if (browse.path.size > 1) {
                viewModel.navigateToCrumb(browse.path.lastIndex - 1)
            }

            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("FrameCut")
                        auth.email?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (auth.phase == AuthPhase.SIGNED_IN) {
                        IconButton(onClick = viewModel::signOut) {
                            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sign out")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            busy?.let { BusyCard(it, onCancel = viewModel::cancelOperation) }

            when (screen) {
                Screen.SIGN_IN -> SignInScreen(
                    state = auth,
                    onSignIn = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, viewModel.beginSignIn())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (_: ActivityNotFoundException) {
                            viewModel.onSignInLaunchFailed()
                        }
                    },
                )

                Screen.BROWSE -> BrowseScreen(
                    state = browse,
                    onOpenRoot = viewModel::openRoot,
                    onNavigate = viewModel::navigateToCrumb,
                    onOpenFolder = viewModel::openFolder,
                    onOpenVideo = viewModel::openVideo,
                    onFilterChange = viewModel::setFilter,
                    onSubmitSearch = viewModel::submitSearch,
                    onSort = viewModel::setSort,
                    onRefresh = viewModel::refreshFolder,
                    onCreateFolder = viewModel::createFolderHere,
                )

                Screen.TRIM -> trim?.let { state ->
                    TrimScreen(
                        state = state,
                        busy = busy != null,
                        onSetSelection = viewModel::setSelection,
                        onSetStartAt = viewModel::setStartAt,
                        onSetEndAt = viewModel::setEndAt,
                        onNameChange = viewModel::setOutputName,
                        onDescriptionChange = viewModel::setDescription,
                        onPickDestination = viewModel::openFolderPicker,
                        onSaveToDrive = viewModel::saveToDrive,
                        onSaveToDevice = viewModel::saveToDevice,
                        onBack = viewModel::backToBrowse,
                    )
                }

                Screen.DONE -> done?.let { state ->
                    DoneScreen(
                        state = state,
                        onTrimAnother = viewModel::backToBrowse,
                        onOpenLinkFailed = { },
                    )
                }
            }
        }
    }

    picker?.let { state ->
        FolderPickerDialog(
            state = state,
            onOpenRoot = viewModel::pickerOpenRoot,
            onEnter = viewModel::pickerEnter,
            onNavigate = viewModel::pickerNavigate,
            onCreateFolder = viewModel::pickerCreateFolder,
            onConfirm = viewModel::pickerConfirm,
            onDismiss = viewModel::closeFolderPicker,
        )
    }
}
