import SwiftUI

/// Folder-only browser for choosing where the trimmed copy lands. The virtual
/// "Shared with me" root is browsable but cannot itself be a destination —
/// Drive has no folder there to upload into.
struct FolderPickerView: View {
    @Binding var selection: Crumb
    @Environment(\.dismiss) private var dismiss
    @Environment(AppModel.self) private var model

    @State private var path: [Crumb] = [.myDrive]
    @State private var folders: [DriveItem] = []
    @State private var loading = false
    @State private var error: String?

    private var current: Crumb { path.last ?? .myDrive }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                RootTabs(selected: path.first ?? .myDrive) { root in
                    path = [root]
                    Task { await load() }
                }
                Breadcrumbs(path: path, isSearchResults: false, searchLabel: nil) { index in
                    path = Array(path.prefix(index + 1))
                    Task { await load() }
                }
                Divider()
                content
            }
            .navigationTitle("Save in…")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Choose") {
                        selection = current
                        dismiss()
                    }
                    .disabled(current.isSharedRoot)
                }
            }
        }
        .task { await load() }
    }

    @ViewBuilder private var content: some View {
        if loading {
            ContentUnavailableView { ProgressView() } description: { Text("Loading…") }
        } else if let error {
            ContentUnavailableView("Could not load", systemImage: "exclamationmark.icloud",
                                   description: Text(error))
        } else if folders.isEmpty {
            ContentUnavailableView("No sub-folders", systemImage: "folder",
                                   description: Text(current.isSharedRoot
                                       ? "Open a shared folder to save inside it."
                                       : "You can still save directly here."))
        } else {
            List(folders) { folder in
                Button {
                    path.append(Crumb(id: folder.id, name: folder.name))
                    Task { await load() }
                } label: {
                    DriveRow(item: folder)
                }
                .buttonStyle(.plain)
            }
            .listStyle(.plain)
        }
    }

    private func load() async {
        loading = true
        error = nil
        defer { loading = false }
        do {
            let drive = DriveAPI { try await OAuthClient.shared.token() }
            folders = try await drive.list(folder: current).filter(\.isFolder)
        } catch {
            folders = []
            self.error = error.localizedDescription
        }
    }
}
