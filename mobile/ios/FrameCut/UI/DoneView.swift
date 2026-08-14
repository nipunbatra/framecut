import SwiftUI

struct DoneView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 62))
                .foregroundStyle(.green)
            Text("Saved").font(.largeTitle.bold())
            Text(model.savedSummary)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 28)

            if let link = model.savedLink, let url = URL(string: link) {
                Link(destination: url) {
                    Label("Open in Drive", systemImage: "arrow.up.forward.app")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .padding(.horizontal, 40)

                ShareLink(item: url) {
                    Label("Share link", systemImage: "square.and.arrow.up")
                }
                .buttonStyle(.bordered)
            }

            Spacer()
            Button("Trim another video") { model.backToBrowse() }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .padding(.bottom, 30)
        }
    }
}
