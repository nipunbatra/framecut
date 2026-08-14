import Foundation

/// Google connection settings, all derived from one value in FrameCut.xcconfig.
///
/// Create an **iOS OAuth client** in the Google Cloud console for this bundle
/// identifier and paste its id suffix into `GOOGLE_CLIENT_ID_SUFFIX` there.
/// iOS clients have no secret — the PKCE exchange in `OAuthClient` is what
/// proves the request is genuine.
enum Config {
    /// e.g. "754571415429-a1b2c3.apps.googleusercontent.com"
    static let clientID: String =
        Bundle.main.object(forInfoDictionaryKey: "GoogleClientID") as? String ?? ""

    /// Google's redirect scheme is the client id reversed. It is declared in
    /// Info.plist from the same xcconfig value, so the two cannot drift apart.
    static var redirectScheme: String {
        "com.googleusercontent.apps.\(clientID.replacingOccurrences(of: ".apps.googleusercontent.com", with: ""))"
    }

    static var redirectURI: String { "\(redirectScheme):/oauth2redirect" }

    /// Full Drive access: the app opens a video the user owns or was shared,
    /// and writes the trimmed copy wherever they choose.
    static let scopes = "https://www.googleapis.com/auth/drive openid email"

    static var isConfigured: Bool {
        !clientID.isEmpty && !clientID.contains("PASTE_CLIENT_ID_SUFFIX_HERE")
    }
}
