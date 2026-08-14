package com.framecut.app

/**
 * Single source of truth for the Google OAuth configuration.
 *
 * `OAUTH_CLIENT_ID` is the ONLY value you need to change. app/build.gradle.kts
 * parses this constant at configuration time and injects the reversed-client-id
 * URL scheme into the manifest, so the redirect scheme can never drift from the
 * client id.
 */
object Config {

    /**
     * The Google OAuth client id. Create it at
     * https://console.cloud.google.com/apis/credentials
     *
     * IMPORTANT — pick the client type carefully. Google withdrew custom URI
     * scheme redirects for **Android**-type clients (their native-app guide is
     * now titled "OAuth 2.0 for iOS & Desktop Apps" and says custom URI schemes
     * are no longer supported on Android), and steers Android apps to
     * Credential Manager / Google Identity Services instead — an SDK this app
     * deliberately does not depend on.
     *
     * So for the reversed-client-id flow implemented here, register an **iOS**
     * client with bundle id `com.framecut.app`. It is a public client, needs no
     * secret, requires PKCE (which this app implements), and its reversed
     * client id is exactly the scheme the manifest registers.
     *
     * An Android-type client (package `com.framecut.app` + the signing SHA-1
     * from `keytool -list -v -alias androiddebugkey -keystore
     * ~/.android/debug.keystore -storepass android -keypass android`) will be
     * rejected at the authorize step with redirect_uri_mismatch.
     *
     * It looks like: "123456789012-abc123def456.apps.googleusercontent.com"
     */
    const val OAUTH_CLIENT_ID = "754571415429-pe7pc3f91lal9625g6ojhj2h5lseqkk6.apps.googleusercontent.com"

    /** Full Drive access (browse any folder, open any video, save anywhere) + identity. */
    const val SCOPES = "https://www.googleapis.com/auth/drive openid email"

    const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke"

    /**
     * Android OAuth clients redirect to the reversed client id. This must match
     * the `oauthRedirectScheme` manifest placeholder derived in build.gradle.kts.
     */
    val REDIRECT_URI: String
        get() = "com.googleusercontent.apps." +
            OAUTH_CLIENT_ID.removeSuffix(".apps.googleusercontent.com") + ":/oauth2redirect"

    /** False while the placeholder above is still in place; the UI says so instead of failing obscurely. */
    val isConfigured: Boolean
        get() = !OAUTH_CLIENT_ID.startsWith("TODO-")
}
