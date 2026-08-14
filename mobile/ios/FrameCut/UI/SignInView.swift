import SwiftUI

struct SignInView: View {
    @Environment(AppModel.self) private var model
    @State private var signingIn = false

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            VStack(spacing: 20) {
                Image(systemName: "scissors")
                    .font(.system(size: 52, weight: .semibold))
                    .foregroundStyle(.tint)

                VStack(spacing: 8) {
                    Text("FrameCut")
                        .font(.largeTitle.bold())
                    Text("Cut the recording. Keep the context.")
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }

                Text("Pick a video from Google Drive, mark the useful section, and save a clean copy. The trim happens on this device — nothing is uploaded until you say so.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                if model.isConfigured {
                    Button {
                        signingIn = true
                        Task {
                            await model.signIn()
                            signingIn = false
                        }
                    } label: {
                        HStack {
                            if signingIn { ProgressView().tint(.white) }
                            Text(signingIn ? "Signing in…" : "Continue with Google")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(signingIn)
                    .padding(.horizontal, 32)
                    .padding(.top, 8)
                } else {
                    UnconfiguredNotice()
                }
            }
            Spacer()
            Text("FrameCut uses Google Drive access — not Gmail — to open the video you choose and save your trimmed copy. You stay signed in until you sign out.")
                .font(.footnote)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 28)
                .padding(.bottom, 24)
        }
    }
}

/// Shown when the build has no Google client id, so the failure is explained
/// rather than looking like a broken sign-in button.
private struct UnconfiguredNotice: View {
    var body: some View {
        VStack(spacing: 10) {
            Label("Not connected to Google yet", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(.orange)
            Text("Add your OAuth client id to **FrameCut.xcconfig** and rebuild. The steps are in mobile/README.md.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
        .background(.orange.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
        .padding(.horizontal, 24)
    }
}
