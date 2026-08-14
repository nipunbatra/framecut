import AVFoundation
import Foundation
import Observation
import SwiftUI

@Observable
@MainActor
final class AppModel {
    enum Screen { case signIn, browse, edit, done }

    struct Busy {
        var title: String
        var detail: String = ""
        var fraction: Double?
        var onCancel: (() -> Void)?
    }

    // MARK: - Published state

    var screen: Screen = .signIn
    var busy: Busy?
    var errorMessage: String?
    var showingError = false
    var email: String?

    // Browser
    var path: [Crumb] = [.myDrive]
    var items: [DriveItem] = []
    var filterText = ""
    var isSearchResults = false
    var isLoadingList = false
    var listError: String?
    var sortKey: SortKey = .name {
        didSet { persistSort() }
    }
    var sortAscending = true {
        didSet { persistSort() }
    }

    // Editor
    var picked: DriveItem?
    var localURL: URL?
    var duration: Double = 0
    var trimStart: Double = 0
    var trimEnd: Double = 0
    var outputName = ""
    var outputDescription = ""
    var destination: Crumb = .myDrive
    var makeShareable = false

    // Result
    var savedLink: String?
    var savedSummary = ""

    private let auth = OAuthClient.shared
    private var drive: DriveAPI { DriveAPI { try await OAuthClient.shared.token() } }
    private var work: Task<Void, Never>?

    var visibleItems: [DriveItem] {
        items.filtered(filterText).sorted(by: sortKey, ascending: sortAscending)
    }

    var isConfigured: Bool { Config.isConfigured }

    init() {
        if let raw = UserDefaults.standard.string(forKey: "framecut.sortKey"),
           let key = SortKey(rawValue: raw) {
            sortKey = key
            sortAscending = UserDefaults.standard.bool(forKey: "framecut.sortAsc")
        }
    }

    private func persistSort() {
        UserDefaults.standard.set(sortKey.rawValue, forKey: "framecut.sortKey")
        UserDefaults.standard.set(sortAscending, forKey: "framecut.sortAsc")
    }

    // MARK: - Session

    /// Sign-in persists: a refresh token in the keychain means we can go
    /// straight to browsing and mint an access token in the background.
    func restoreSession() async {
        guard Config.isConfigured, auth.hasStoredSession else { return }
        email = auth.email
        screen = .browse
        await loadCurrentFolder()
    }

    func signIn() async {
        do {
            try await auth.signIn()
            email = auth.email
            path = [.myDrive]
            screen = .browse
            await loadCurrentFolder()
        } catch AuthError.cancelled {
            // User backed out; stay on the sign-in screen silently.
        } catch {
            report(error)
        }
    }

    func signOut() async {
        await auth.signOut()
        email = nil
        items = []
        path = [.myDrive]
        screen = .signIn
    }

    // MARK: - Browsing

    func go(to root: Crumb) {
        path = [root]
        filterText = ""
        isSearchResults = false
        Task { await loadCurrentFolder() }
    }

    func open(folder item: DriveItem) {
        // Search results have no known parent chain, so restart at the root.
        path = isSearchResults
            ? [path.first ?? .myDrive, Crumb(id: item.id, name: item.name)]
            : path + [Crumb(id: item.id, name: item.name)]
        filterText = ""
        isSearchResults = false
        Task { await loadCurrentFolder() }
    }

    func popTo(index: Int) {
        guard index < path.count else { return }
        path = Array(path.prefix(index + 1))
        filterText = ""
        isSearchResults = false
        Task { await loadCurrentFolder() }
    }

    func loadCurrentFolder() async {
        guard let current = path.last else { return }
        isLoadingList = true
        listError = nil
        isSearchResults = false
        defer { isLoadingList = false }
        do {
            items = try await drive.list(folder: current)
        } catch {
            items = []
            listError = error.localizedDescription
        }
    }

    /// Drive-wide search: everything the user can reach, not just this folder.
    func runSearch() async {
        let query = filterText.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return }
        isLoadingList = true
        listError = nil
        defer { isLoadingList = false }
        do {
            items = try await drive.search(query)
            isSearchResults = true
            filterText = ""
        } catch {
            listError = error.localizedDescription
        }
    }

    // MARK: - Opening a video

    func open(video item: DriveItem) {
        work?.cancel()
        work = Task {
            busy = Busy(title: "Downloading from Drive", onCancel: cancelWork)
            let sourceFile = FileManager.default.temporaryDirectory
                .appendingPathComponent("source-\(item.id).\(item.name.pathExtensionOrMP4)")
            do {
                try await drive.download(
                    fileID: item.id, size: item.size, to: sourceFile
                ) { received, total in
                    Task { @MainActor in
                        self.busy?.fraction = total > 0 ? Double(received) / Double(total) : nil
                        self.busy?.detail = "\(received.formattedBytes) of \(total.formattedBytes)"
                    }
                }
                try Task.checkCancellation()

                let asset = AVURLAsset(url: sourceFile)
                let loaded = try await asset.load(.duration)
                let seconds = CMTimeGetSeconds(loaded)
                guard seconds.isFinite, seconds > 0 else {
                    throw TrimError.failed("This video's duration could not be read.")
                }

                picked = item
                localURL = sourceFile
                duration = seconds
                trimStart = 0
                trimEnd = seconds
                outputName = item.name.trimmedCopyName
                outputDescription = ""
                // Default the save destination to where the video came from,
                // unless that is the virtual shared root, which cannot hold files.
                let from = path.last ?? .myDrive
                destination = from.isSharedRoot ? .myDrive : from
                busy = nil
                screen = .edit
            } catch is CancellationError {
                busy = nil
                try? FileManager.default.removeItem(at: sourceFile)
            } catch {
                busy = nil
                try? FileManager.default.removeItem(at: sourceFile)
                report(error)
            }
        }
    }

    private func cancelWork() { work?.cancel() }

    // MARK: - Trim and save

    func trimAndUpload() {
        work?.cancel()
        work = Task {
            do {
                let output = try await runTrim()
                busy = Busy(title: "Uploading to Drive", onCancel: cancelWork)

                let fixed = [
                    ("tool", "framecut"),
                    ("sourceFileId", picked?.id ?? ""),
                    ("trimStart", trimStart.timestamp),
                    ("trimEnd", trimEnd.timestamp),
                ]
                let built = AppProperties.build(fixed: fixed, user: [])

                let result = try await drive.upload(
                    file: output.url,
                    meta: .init(
                        name: finalName(ext: output.suggestedExtension),
                        mimeType: VideoTrimmer.mimeType(for: output.fileType),
                        description: outputDescription,
                        parents: [destination.id],
                        appProperties: built.properties)
                ) { sent, total in
                    Task { @MainActor in
                        self.busy?.fraction = Double(sent) / Double(max(total, 1))
                        self.busy?.detail = "\(sent.formattedBytes) of \(total.formattedBytes)"
                    }
                }

                if makeShareable {
                    busy?.detail = "Making it shareable…"
                    try await drive.shareAnyone(fileID: result.id)
                }

                let size = (try? FileManager.default
                    .attributesOfItem(atPath: output.url.path)[.size] as? NSNumber)??.int64Value ?? 0
                savedLink = result.webViewLink
                savedSummary = "\(result.name) (\(size.formattedBytes)) saved to \(destination.name)."
                    + (makeShareable ? " Anyone with the link can view it." : "")
                try? FileManager.default.removeItem(at: output.url)
                busy = nil
                screen = .done
            } catch is CancellationError {
                busy = nil
            } catch {
                busy = nil
                report(error)
            }
        }
    }

    /// Save the trimmed copy into the device's photo library instead of Drive.
    func trimAndSaveToDevice() {
        work?.cancel()
        work = Task {
            do {
                let output = try await runTrim()
                busy = Busy(title: "Saving to your device")
                try await PhotoLibrary.save(output.url)
                try? FileManager.default.removeItem(at: output.url)
                savedLink = nil
                savedSummary = "\(finalName(ext: output.suggestedExtension)) saved to your Photos library."
                busy = nil
                screen = .done
            } catch is CancellationError {
                busy = nil
            } catch {
                busy = nil
                report(error)
            }
        }
    }

    private func runTrim() async throws -> VideoTrimmer.Output {
        guard let localURL else { throw TrimError.failed("No video loaded.") }
        guard trimEnd - trimStart >= 0.2 else {
            throw TrimError.failed("The selection is empty — move the handles apart.")
        }
        busy = Busy(title: "Trimming", detail: "Lossless copy", onCancel: cancelWork)
        let output = try await VideoTrimmer.trim(
            source: localURL, start: trimStart, end: trimEnd,
            outputName: outputName
        ) { fraction in
            Task { @MainActor in self.busy?.fraction = fraction }
        }
        try Task.checkCancellation()
        return output
    }

    private func finalName(ext: String) -> String {
        let base = outputName.trimmingCharacters(in: .whitespaces)
        let name = base.isEmpty ? "trimmed" : base
        return name.lowercased().hasSuffix(".\(ext)")
            ? name
            : name.replacingOccurrences(of: "\\.[^.]+$", with: "", options: .regularExpression) + ".\(ext)"
    }

    // MARK: - Navigation helpers

    func backToBrowse() {
        if let localURL { try? FileManager.default.removeItem(at: localURL) }
        localURL = nil
        picked = nil
        screen = .browse
        Task { await loadCurrentFolder() }
    }

    func report(_ error: Error) {
        errorMessage = error.localizedDescription
        showingError = true
    }
}

// MARK: - Small formatting helpers

extension Int64 {
    var formattedBytes: String {
        if self <= 0 { return "" }
        if self >= 1_000_000_000 { return String(format: "%.2f GB", Double(self) / 1e9) }
        if self >= 1_000_000 { return String(format: "%.1f MB", Double(self) / 1e6) }
        return "\(self / 1000) KB"
    }
}

extension Double {
    /// mm:ss.t, or h:mm:ss.t past an hour.
    var clockText: String {
        guard isFinite else { return "–" }
        let hours = Int(self) / 3600
        let minutes = (Int(self) % 3600) / 60
        let seconds = self.truncatingRemainder(dividingBy: 60)
        return hours > 0
            ? String(format: "%d:%02d:%04.1f", hours, minutes, seconds)
            : String(format: "%d:%04.1f", minutes, seconds)
    }

    /// HH:MM:SS.mmm, the form stored in Drive appProperties.
    var timestamp: String {
        let hours = Int(self) / 3600
        let minutes = (Int(self) % 3600) / 60
        let seconds = self.truncatingRemainder(dividingBy: 60)
        return String(format: "%02d:%02d:%06.3f", hours, minutes, seconds)
    }
}

extension String {
    var pathExtensionOrMP4: String {
        let ext = (self as NSString).pathExtension
        return ext.isEmpty ? "mp4" : ext
    }

    var trimmedCopyName: String {
        let base = (self as NSString).deletingPathExtension
        return "\(base)-trimmed.\(pathExtensionOrMP4)"
    }
}
