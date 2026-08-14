import Foundation

struct DriveItem: Identifiable, Hashable {
    let id: String
    let name: String
    let mimeType: String
    let size: Int64
    let modifiedTime: Date?

    var isFolder: Bool { mimeType == DriveAPI.folderMIME }
    var isVideo: Bool { mimeType.hasPrefix("video/") }
}

/// A step in the folder path. `Crumb.sharedWithMe` is virtual: Drive has no
/// folder id for "shared with me", so it is listed with a different query and
/// cannot receive uploads.
struct Crumb: Identifiable, Hashable {
    let id: String
    let name: String

    static let myDrive = Crumb(id: "root", name: "My Drive")
    static let sharedWithMe = Crumb(id: "shared-with-me", name: "Shared with me")

    var isSharedRoot: Bool { id == Crumb.sharedWithMe.id }
}

enum SortKey: String, CaseIterable {
    case name, modified, size

    var label: String {
        switch self {
        case .name: "Name"
        case .modified: "Modified"
        case .size: "Size"
        }
    }
}

extension Array where Element == DriveItem {
    /// Folders first, then the chosen key; ties fall back to name so the order
    /// is stable. Mirrors the web app's `sortItems`.
    func sorted(by key: SortKey, ascending: Bool) -> [DriveItem] {
        let dir = ascending ? 1 : -1
        return sorted { a, b in
            if a.isFolder != b.isFolder { return a.isFolder }
            let byName = a.name.localizedStandardCompare(b.name)
            let primary: ComparisonResult = switch key {
            case .name: byName
            case .size: a.size == b.size ? .orderedSame : (a.size < b.size ? .orderedAscending : .orderedDescending)
            case .modified:
                (a.modifiedTime ?? .distantPast) == (b.modifiedTime ?? .distantPast)
                    ? .orderedSame
                    : ((a.modifiedTime ?? .distantPast) < (b.modifiedTime ?? .distantPast) ? .orderedAscending : .orderedDescending)
            }
            let result = primary == .orderedSame ? byName : primary
            return dir == 1 ? result == .orderedAscending : result == .orderedDescending
        }
    }

    func filtered(_ text: String) -> [DriveItem] {
        let needle = text.trimmingCharacters(in: .whitespaces).lowercased()
        guard !needle.isEmpty else { return self }
        return filter { $0.name.lowercased().contains(needle) }
    }
}

/// Merge searchable metadata within Drive's appProperties limits (30 entries,
/// 124 UTF-8 bytes per key+value). Fixed provenance fields win on collision;
/// the full values still go in the file description.
enum AppProperties {
    static let maxCount = 30
    static let maxBytes = 124

    static func build(fixed: [(String, String)], user: [(String, String)])
        -> (properties: [String: String], omitted: Int, truncated: Int) {
        var properties: [String: String] = [:]
        var order: [String] = []
        var omitted = 0
        var truncated = 0

        for (key, value) in fixed {
            if order.count >= maxCount { break }
            let remaining = maxBytes - key.utf8.count
            if remaining <= 0 { continue }
            properties[key] = truncate(value, toBytes: remaining)
            order.append(key)
        }

        for (key, value) in user {
            if properties[key] != nil || order.count >= maxCount {
                omitted += 1
                continue
            }
            let remaining = maxBytes - key.utf8.count
            if remaining <= 0 {
                omitted += 1
                continue
            }
            let safe = truncate(value, toBytes: remaining)
            if safe != value { truncated += 1 }
            properties[key] = safe
            order.append(key)
        }
        return (properties, omitted, truncated)
    }

    /// Truncate on character boundaries so a multi-byte character is never cut
    /// in half.
    static func truncate(_ value: String, toBytes maxBytes: Int) -> String {
        guard maxBytes > 0 else { return "" }
        guard value.utf8.count > maxBytes else { return value }
        var result = ""
        var used = 0
        for char in value {
            let bytes = String(char).utf8.count
            if used + bytes > maxBytes { break }
            result.append(char)
            used += bytes
        }
        return result
    }
}
