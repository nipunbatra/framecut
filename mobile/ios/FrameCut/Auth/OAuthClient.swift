import AuthenticationServices
import CryptoKit
import Foundation

enum AuthError: LocalizedError {
    case notConfigured
    case cancelled
    case server(String)
    case noRefreshToken

    var errorDescription: String? {
        switch self {
        case .notConfigured: "This build has no Google client id yet. See mobile/README.md."
        case .cancelled: "Sign-in was cancelled."
        case .server(let m): m
        case .noRefreshToken: "Google did not return a refresh token. Sign in again."
        }
    }
}

/// Google OAuth for an installed app: authorization code + PKCE, with the
/// refresh token kept in the keychain so sign-in survives relaunches.
///
/// Deliberately dependency-free — `ASWebAuthenticationSession` is the system
/// browser, so the user's existing Google session is reused and the token
/// never passes through a webview we control.
@MainActor
final class OAuthClient: NSObject, ObservableObject {
    static let shared = OAuthClient()

    @Published private(set) var email: String?
    @Published private(set) var isSignedIn = false

    private var accessToken: String?
    private var expiresAt: Date = .distantPast
    private var refreshTask: Task<String, Error>?
    private var session: ASWebAuthenticationSession?

    private let tokenEndpoint = URL(string: "https://oauth2.googleapis.com/token")!
    private let authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"

    private var refreshToken: String? {
        get { Keychain.get("refresh_token") }
        set { Keychain.set(newValue, for: "refresh_token") }
    }

    private override init() {
        super.init()
        email = UserDefaults.standard.string(forKey: "framecut.email")
        isSignedIn = refreshToken != nil
    }

    /// True when a stored refresh token exists, so the UI can skip the sign-in
    /// screen and go straight to browsing while the token refreshes.
    var hasStoredSession: Bool { refreshToken != nil }

    // MARK: - Interactive sign-in

    func signIn() async throws {
        guard Config.isConfigured else { throw AuthError.notConfigured }

        let verifier = Self.randomURLSafe(64)
        let challenge = Self.s256(verifier)
        let state = Self.randomURLSafe(24)

        var components = URLComponents(string: authEndpoint)!
        components.queryItems = [
            .init(name: "client_id", value: Config.clientID),
            .init(name: "redirect_uri", value: Config.redirectURI),
            .init(name: "response_type", value: "code"),
            .init(name: "scope", value: Config.scopes),
            .init(name: "code_challenge", value: challenge),
            .init(name: "code_challenge_method", value: "S256"),
            .init(name: "state", value: state),
            // Ask for a refresh token, and force the consent screen so Google
            // actually returns one rather than reusing a prior grant.
            .init(name: "access_type", value: "offline"),
            .init(name: "prompt", value: "consent"),
        ]

        let callback = try await presentAuthSession(url: components.url!)
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        guard items.first(where: { $0.name == "state" })?.value == state else {
            throw AuthError.server("Sign-in response did not match the request.")
        }
        if let err = items.first(where: { $0.name == "error" })?.value {
            throw AuthError.server("Google refused the sign-in: \(err)")
        }
        guard let code = items.first(where: { $0.name == "code" })?.value else {
            throw AuthError.cancelled
        }

        let body = [
            "client_id": Config.clientID,
            "code": code,
            "code_verifier": verifier,
            "grant_type": "authorization_code",
            "redirect_uri": Config.redirectURI,
        ]
        let token = try await postToken(body)
        guard let refresh = token.refreshToken else { throw AuthError.noRefreshToken }
        refreshToken = refresh
        apply(token)
        isSignedIn = true
        await fetchEmail()
    }

    private func presentAuthSession(url: URL) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(
                url: url,
                callbackURLScheme: Config.redirectScheme
            ) { callback, error in
                if let callback {
                    continuation.resume(returning: callback)
                } else if let error = error as? ASWebAuthenticationSessionError,
                          error.code == .canceledLogin {
                    continuation.resume(throwing: AuthError.cancelled)
                } else {
                    continuation.resume(throwing: error ?? AuthError.cancelled)
                }
            }
            session.presentationContextProvider = self
            // Reuse the system Google session so returning users tap once.
            session.prefersEphemeralWebBrowserSession = false
            self.session = session
            session.start()
        }
    }

    // MARK: - Token vending

    /// The single entry point for callers. Returns a valid access token,
    /// refreshing silently when needed. Concurrent callers share one refresh.
    func token() async throws -> String {
        if let accessToken, expiresAt.timeIntervalSinceNow > 60 { return accessToken }
        if let refreshTask { return try await refreshTask.value }

        let task = Task<String, Error> { [weak self] in
            guard let self else { throw AuthError.noRefreshToken }
            defer { Task { @MainActor in self.refreshTask = nil } }
            guard let refresh = self.refreshToken else { throw AuthError.noRefreshToken }
            let token = try await self.postToken([
                "client_id": Config.clientID,
                "refresh_token": refresh,
                "grant_type": "refresh_token",
            ])
            await MainActor.run {
                self.apply(token)
                self.isSignedIn = true
            }
            return token.accessToken
        }
        refreshTask = task
        do {
            return try await task.value
        } catch {
            // A refresh token that Google has revoked or expired is dead; drop
            // it so the UI falls back to the sign-in screen instead of looping.
            if case AuthError.server = error { signOutLocally() }
            throw error
        }
    }

    private func apply(_ token: TokenResponse) {
        accessToken = token.accessToken
        expiresAt = Date().addingTimeInterval(TimeInterval(token.expiresIn ?? 3600))
    }

    // MARK: - Sign out

    func signOut() async {
        if let refresh = refreshToken {
            var request = URLRequest(url: URL(string: "https://oauth2.googleapis.com/revoke")!)
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            request.httpBody = "token=\(refresh)".data(using: .utf8)
            _ = try? await URLSession.shared.data(for: request)
        }
        signOutLocally()
    }

    private func signOutLocally() {
        refreshToken = nil
        accessToken = nil
        expiresAt = .distantPast
        email = nil
        UserDefaults.standard.removeObject(forKey: "framecut.email")
        isSignedIn = false
    }

    // MARK: - Helpers

    private struct TokenResponse: Decodable {
        let accessToken: String
        let refreshToken: String?
        let expiresIn: Int?

        enum CodingKeys: String, CodingKey {
            case accessToken = "access_token"
            case refreshToken = "refresh_token"
            case expiresIn = "expires_in"
        }
    }

    private func postToken(_ fields: [String: String]) async throws -> TokenResponse {
        var request = URLRequest(url: tokenEndpoint)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = fields
            .map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? $0.value)" }
            .joined(separator: "&")
            .data(using: .utf8)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            let detail = String(data: data, encoding: .utf8) ?? "unknown error"
            throw AuthError.server("Google rejected the token request: \(detail)")
        }
        return try JSONDecoder().decode(TokenResponse.self, from: data)
    }

    private func fetchEmail() async {
        guard let token = try? await token() else { return }
        var request = URLRequest(url: URL(string: "https://www.googleapis.com/oauth2/v3/userinfo")!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        guard let (data, _) = try? await URLSession.shared.data(for: request),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let mail = json["email"] as? String else { return }
        email = mail
        UserDefaults.standard.set(mail, forKey: "framecut.email")
    }

    private static func randomURLSafe(_ count: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return Data(bytes).base64URLEncoded
    }

    private static func s256(_ input: String) -> String {
        Data(SHA256.hash(data: Data(input.utf8))).base64URLEncoded
    }
}

extension OAuthClient: ASWebAuthenticationPresentationContextProviding {
    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            return scenes.first(where: { $0.activationState == .foregroundActive })?.keyWindow
                ?? scenes.first?.keyWindow
                ?? ASPresentationAnchor()
        }
    }
}

extension Data {
    var base64URLEncoded: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
