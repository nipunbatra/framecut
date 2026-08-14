import SwiftUI

@main
struct FrameCutApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .task { await model.restoreSession() }
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Group {
            switch model.screen {
            case .signIn: SignInView()
            case .browse: BrowseView()
            case .edit: EditorView()
            case .done: DoneView()
            }
        }
        .animation(.default, value: model.screen)
        .overlay { if let busy = model.busy { BusyOverlay(state: busy) } }
        .alert("Something went wrong",
               isPresented: $model.showingError,
               presenting: model.errorMessage) { _ in
            Button("OK", role: .cancel) {}
        } message: { Text($0) }
    }
}

/// Full-screen progress for the long operations: download, trim, upload.
struct BusyOverlay: View {
    let state: AppModel.Busy

    var body: some View {
        ZStack {
            Color.black.opacity(0.45).ignoresSafeArea()
            VStack(spacing: 16) {
                Text(state.title).font(.headline)
                if let fraction = state.fraction {
                    ProgressView(value: fraction).progressViewStyle(.linear)
                } else {
                    ProgressView().progressViewStyle(.linear)
                }
                if !state.detail.isEmpty {
                    Text(state.detail)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                if let cancel = state.onCancel {
                    Button("Cancel", role: .cancel, action: cancel)
                        .buttonStyle(.bordered)
                }
            }
            .padding(24)
            .frame(maxWidth: 320)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
            .padding(40)
        }
        .transition(.opacity)
    }
}
