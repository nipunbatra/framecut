package com.framecut.app.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.framecut.app.Config
import com.framecut.app.net.Http
import com.framecut.app.net.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** Thrown when no valid credentials remain; the UI drops back to the sign-in screen. */
class AuthRequiredException(message: String) : IOException(message)

/**
 * OAuth 2.0 authorization-code flow with PKCE, no Google Sign-In SDK.
 *
 * The refresh token is kept in app-private SharedPreferences (readable only by
 * this app's UID inside its sandbox) so sign-in survives app restarts; the
 * short-lived access token stays in memory only.
 */
class AuthManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("framecut.auth", Context.MODE_PRIVATE)

    private val refreshMutex = Mutex()

    @Volatile private var accessToken: String? = null
    @Volatile private var accessTokenExpiryMs: Long = 0

    val hasRefreshToken: Boolean get() = prefs.getString(KEY_REFRESH_TOKEN, null) != null

    var accountEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        private set(value) = prefs.edit().putString(KEY_EMAIL, value).apply()

    // ---- step 1: send the user to Google ----

    /**
     * Creates a fresh PKCE verifier + state, persists them (the browser round
     * trip can outlive this process), and returns the authorization URL.
     */
    fun buildAuthorizationUri(): Uri {
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(16)
        prefs.edit()
            .putString(KEY_PKCE_VERIFIER, verifier)
            .putString(KEY_PKCE_STATE, state)
            .apply()

        val params = mapOf(
            "client_id" to Config.OAUTH_CLIENT_ID,
            "redirect_uri" to Config.REDIRECT_URI,
            "response_type" to "code",
            "scope" to Config.SCOPES,
            "code_challenge" to codeChallenge(verifier),
            "code_challenge_method" to "S256",
            "state" to state,
            // Required to receive a refresh token, i.e. persistent sign-in.
            "access_type" to "offline",
            "prompt" to "consent",
        )
        return Uri.parse("${Config.AUTH_ENDPOINT}?${Http.formEncode(params)}")
    }

    /** True if this intent data is our OAuth redirect. URI schemes are case-insensitive. */
    fun isRedirect(uri: Uri?): Boolean =
        uri?.scheme?.equals(Uri.parse(Config.REDIRECT_URI).scheme, ignoreCase = true) == true

    // ---- step 2: exchange the code ----

    suspend fun completeAuthorization(redirect: Uri) {
        val expectedState = prefs.getString(KEY_PKCE_STATE, null)
        val verifier = prefs.getString(KEY_PKCE_VERIFIER, null)
        val state = redirect.getQueryParameter("state")
        // Check the state before consuming the stored transaction: any app can
        // fire an intent at our scheme, and a stray one must not be able to
        // cancel a sign-in that is genuinely in flight. Google echoes state on
        // error responses too, so real failures still clear it below.
        if (expectedState == null || verifier == null || state != expectedState) {
            throw AuthRequiredException("This sign-in response did not match a request from this app.")
        }
        prefs.edit().remove(KEY_PKCE_STATE).remove(KEY_PKCE_VERIFIER).apply()

        redirect.getQueryParameter("error")?.let {
            throw AuthRequiredException("Google refused the sign-in ($it).")
        }
        val code = redirect.getQueryParameter("code")
            ?: throw AuthRequiredException("Sign-in response contained no authorization code.")

        val json = tokenRequest(
            mapOf(
                "client_id" to Config.OAUTH_CLIENT_ID,
                "code" to code,
                "code_verifier" to verifier,
                "grant_type" to "authorization_code",
                "redirect_uri" to Config.REDIRECT_URI,
            ),
        )

        val refresh = json.optString("refresh_token").takeIf { it.isNotEmpty() }
            ?: throw AuthRequiredException(
                "Google returned no refresh token. Make sure the OAuth client is an " +
                    "\"Android\" client and consent was granted.",
            )
        storeAccessToken(json)
        val email = json.optString("id_token").takeIf { it.isNotEmpty() }?.let(::emailFromIdToken)
        prefs.edit()
            .putString(KEY_REFRESH_TOKEN, refresh)
            .apply()
        if (!email.isNullOrEmpty()) accountEmail = email
    }

    // ---- step 3: keep a live access token ----

    /** A valid access token, refreshing only when the cached one is missing or stale. */
    suspend fun accessToken(): String = token(rejected = null)

    /**
     * Call after Drive answers 401. Refreshes unless another coroutine has
     * already replaced [rejected] — five parallel range workers hitting the same
     * expired token must produce one token request, not five.
     */
    suspend fun refreshedToken(rejected: String): String = token(rejected)

    private suspend fun token(rejected: String?): String {
        if (!Config.isConfigured) throw AuthRequiredException("OAuth client id is not configured.")
        usable(rejected)?.let { return it }
        return refreshMutex.withLock {
            usable(rejected)?.let { return@withLock it }
            performRefresh()
        }
    }

    /** The cached token when it is still worth using, else null. */
    private fun usable(rejected: String?): String? {
        val cached = accessToken ?: return null
        if (cached == rejected) return null
        // A token other than the rejected one means somebody already refreshed.
        if (rejected != null) return cached
        return cached.takeIf { System.currentTimeMillis() < accessTokenExpiryMs - EXPIRY_LEEWAY_MS }
    }

    private suspend fun performRefresh(): String {
        val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
            ?: throw AuthRequiredException("Not signed in.")
        val json = try {
            tokenRequest(
                mapOf(
                    "client_id" to Config.OAUTH_CLIENT_ID,
                    "refresh_token" to refresh,
                    "grant_type" to "refresh_token",
                ),
            )
        } catch (e: HttpException) {
            // 400 invalid_grant means the grant is gone for good (revoked,
            // password change, 6-month idle). Anything else may be transient.
            if (e.code == 400 || e.code == 401) {
                clearCredentials()
                throw AuthRequiredException("Session expired. Please sign in again.")
            }
            throw e
        }
        storeAccessToken(json)
        json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let {
            prefs.edit().putString(KEY_REFRESH_TOKEN, it).apply()
        }
        return accessToken ?: throw AuthRequiredException("Google returned no access token.")
    }

    suspend fun signOut() {
        val token = prefs.getString(KEY_REFRESH_TOKEN, null) ?: accessToken
        clearCredentials()
        if (token == null) return
        // Best effort: local credentials are already gone either way.
        runCatching {
            withContext(Dispatchers.IO) {
                val conn = Http.open(Config.REVOKE_ENDPOINT, "POST")
                Http.writeBody(
                    conn,
                    Http.formEncode(mapOf("token" to token)).toByteArray(),
                    "application/x-www-form-urlencoded",
                )
                conn.responseCode
                conn.disconnect()
            }
        }
    }

    fun clearCredentials() {
        accessToken = null
        accessTokenExpiryMs = 0
        prefs.edit().clear().apply()
    }

    fun rememberEmail(email: String) {
        if (email.isNotEmpty()) accountEmail = email
    }

    // ---- internals ----

    private suspend fun tokenRequest(params: Map<String, String>): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = Http.open(Config.TOKEN_ENDPOINT, "POST")
            try {
                Http.writeBody(
                    conn,
                    Http.formEncode(params).toByteArray(),
                    "application/x-www-form-urlencoded",
                )
                JSONObject(Http.requireOk(conn))
            } finally {
                conn.disconnect()
            }
        }

    private fun storeAccessToken(json: JSONObject) {
        accessToken = json.optString("access_token").takeIf { it.isNotEmpty() }
        val expiresIn = json.optLong("expires_in", 3600L)
        accessTokenExpiryMs = System.currentTimeMillis() + expiresIn * 1000
    }

    /** Reads `email` from the unverified id_token payload — display only. */
    private fun emailFromIdToken(idToken: String): String? = runCatching {
        val payload = idToken.split('.').getOrNull(1) ?: return@runCatching null
        val decoded = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        JSONObject(String(decoded, Charsets.UTF_8)).optString("email").takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EMAIL = "email"
        const val KEY_PKCE_VERIFIER = "pkce_verifier"
        const val KEY_PKCE_STATE = "pkce_state"
        const val EXPIRY_LEEWAY_MS = 60_000L
    }
}
