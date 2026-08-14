import SwiftUI

struct BrowseView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            VStack(spacing: 0) {
                RootTabs(selected: model.path.first ?? .myDrive) { model.go(to: $0) }
                SearchBar(text: $model.filterText,
                          isSearchResults: model.isSearchResults,
                          onSubmit: { Task { await model.runSearch() } },
                          onClear: {
                              if model.isSearchResults { Task { await model.loadCurrentFolder() } }
                          })
                SortBar(key: model.sortKey, ascending: model.sortAscending) { key in
                    if model.sortKey == key {
                        model.sortAscending.toggle()
                    } else {
                        model.sortKey = key
                        // Newest and largest first read more naturally.
                        model.sortAscending = key == .name
                    }
                }
                Breadcrumbs(path: model.path,
                            isSearchResults: model.isSearchResults,
                            searchLabel: model.isSearchResults ? "Search results" : nil) {
                    model.popTo(index: $0)
                }
                Divider()
                content
            }
            .navigationTitle("Choose a video")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        if let email = model.email {
                            Text(email)
                        }
                        Button("Sign out", systemImage: "rectangle.portrait.and.arrow.right",
                               role: .destructive) {
                            Task { await model.signOut() }
                        }
                    } label: {
                        Image(systemName: "person.crop.circle")
                    }
                }
            }
        }
    }

    @ViewBuilder private var content: some View {
        if model.isLoadingList {
            ContentUnavailableView { ProgressView() } description: { Text("Loading…") }
        } else if let error = model.listError {
            ContentUnavailableView {
                Label("Could not load this folder", systemImage: "exclamationmark.icloud")
            } description: {
                Text(error)
            } actions: {
                Button("Try again") { Task { await model.loadCurrentFolder() } }
                    .buttonStyle(.borderedProminent)
            }
        } else if model.visibleItems.isEmpty {
            ContentUnavailableView(
                model.isSearchResults ? "Nothing in Drive matches" : "Nothing here",
                systemImage: "folder",
                description: Text(model.isSearchResults
                    ? "Try a different search."
                    : "This folder has no sub-folders or videos."))
        } else {
            List(model.visibleItems) { item in
                Button {
                    if item.isFolder { model.open(folder: item) } else { model.open(video: item) }
                } label: {
                    DriveRow(item: item)
                }
                .buttonStyle(.plain)
            }
            .listStyle(.plain)
        }
    }
}

struct DriveRow: View {
    let item: DriveItem

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: item.isFolder ? "folder.fill" : "play.rectangle.fill")
                .font(.title3)
                .foregroundStyle(item.isFolder ? .orange : .blue)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).lineLimit(2)
                if !metaText.isEmpty {
                    Text(metaText).font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 8)
            if item.isFolder {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
        }
        .contentShape(.rect)
        .padding(.vertical, 2)
    }

    private var metaText: String {
        let date = item.modifiedTime.map {
            $0.formatted(.dateTime.day().month(.abbreviated).year())
        }
        return [item.isFolder ? nil : item.size.formattedBytes, date]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
    }
}

struct RootTabs: View {
    let selected: Crumb
    let onSelect: (Crumb) -> Void

    var body: some View {
        HStack(spacing: 8) {
            ForEach([Crumb.myDrive, Crumb.sharedWithMe]) { root in
                Button(root.name) { onSelect(root) }
                    .buttonStyle(.bordered)
                    .tint(selected.id == root.id ? .accentColor : .secondary)
            }
            Spacer()
        }
        .font(.subheadline)
        .padding(.horizontal)
        .padding(.top, 8)
    }
}

struct SearchBar: View {
    @Binding var text: String
    let isSearchResults: Bool
    let onSubmit: () -> Void
    let onClear: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("Filter this folder — search all of Drive", text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onSubmit(onSubmit)
            if !text.isEmpty || isSearchResults {
                Button {
                    text = ""
                    onClear()
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
        .padding(.horizontal)
        .padding(.top, 8)
    }
}

struct SortBar: View {
    let key: SortKey
    let ascending: Bool
    let onPick: (SortKey) -> Void

    var body: some View {
        HStack(spacing: 8) {
            ForEach(SortKey.allCases, id: \.self) { option in
                Button {
                    onPick(option)
                } label: {
                    HStack(spacing: 3) {
                        Text(option.label)
                        if option == key {
                            Image(systemName: ascending ? "arrow.up" : "arrow.down")
                                .font(.caption2.weight(.bold))
                        }
                    }
                }
                .buttonStyle(.bordered)
                .tint(option == key ? .accentColor : .secondary)
            }
            Spacer()
        }
        .font(.footnote)
        .padding(.horizontal)
        .padding(.top, 8)
    }
}

struct Breadcrumbs: View {
    let path: [Crumb]
    let isSearchResults: Bool
    let searchLabel: String?
    let onTap: (Int) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(Array(path.enumerated()), id: \.element.id) { index, crumb in
                    if index > 0 {
                        Image(systemName: "chevron.right")
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                    Button(crumb.name) { onTap(index) }
                        .disabled(index == path.count - 1 && !isSearchResults)
                        .foregroundStyle(index == path.count - 1 && !isSearchResults
                                         ? Color.primary : Color.accentColor)
                }
                if let searchLabel {
                    Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
                    Text(searchLabel).foregroundStyle(.primary)
                }
            }
            .font(.footnote)
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
    }
}
