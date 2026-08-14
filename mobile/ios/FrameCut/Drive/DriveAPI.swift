import Foundation

enum DriveError: LocalizedError {
    case http(Int, String)
    case badResponse
    case cancelled

    var errorDescription: String? {
        switch self {
        case .http(let code, let what): "\(what) failed (HTTP \(code))"
        case .badResponse: "Drive returned an unexpected response."
        case .cancelled: "Cancelled."
        }
    }
}

/// Drive REST v3 client. Mirrors the web app's query semantics exactly so both
/// clients behave identically — see src/browser.ts and src/drive.ts.
struct DriveAPI {
    static let folderMIME = "application/vnd.google-apps.folder"
    private static let api = "https://www.googleapis.com/drive/v3"
    private static let upload = "https://www.googleapis.com/upload/drive/v3"

    let token: () async throws -> String

    // MARK: - Listing

    /// Children of a folder, following pagination. The virtual "shared with
    /// me" root has no folder id, so it uses Drive's `sharedWithMe` term,
    /// which is only valid in the user corpus.
    func list(folder: Crumb) async throws -> [DriveItem] {
        var items: [DriveItem] = []
        var pageToken: String?
        var seen = Set<String>()

        repeat {
            if !seen.insert(pageToken ?? "").inserted {
                throw DriveError.badResponse // Drive repeated a page token
            }
            var query = [URLQueryItem(name: "q", value: folder.isSharedRoot
                ? "sharedWithMe and trashed=false"
                : "'\(Self.escape(folder.id))' in parents and trashed=false")]
            query += [
                .init(name: "fields", value: "nextPageToken, files(id,name,mimeType,size,modifiedTime)"),
                .init(name: "orderBy", value: "folder,name"),
                .init(name: "pageSize", value: "1000"),
                .init(name: "supportsAllDrives", value: "true"),
                .init(name: "includeItemsFromAllDrives", value: "true"),
                .init(name: "corpora", value: folder.isSharedRoot ? "user" : "allDrives"),
            ]
            if let pageToken { query.append(.init(name: "pageToken", value: pageToken)) }

            let json = try await getJSON(path: "/files", query: query, what: "Listing folder")
            items += (json["files"] as? [[String: Any]] ?? []).map(Self.item)
            pageToken = json["nextPageToken"] as? String
        } while pageToken != nil

        return items
    }

    /// Search everywhere the user can reach — My Drive, shared with me, and
    /// shared drives — for videos and folders whose name matches.
    func search(_ text: String) async throws -> [DriveItem] {
        let q = "name contains '\(Self.escape(text))' and trashed=false "
            + "and (mimeType='\(Self.folderMIME)' or mimeType contains 'video/')"
        let json = try await getJSON(path: "/files", query: [
            .init(name: "q", value: q),
            .init(name: "fields", value: "files(id,name,mimeType,size,modifiedTime)"),
            .init(name: "pageSize", value: "1000"),
            .init(name: "supportsAllDrives", value: "true"),
            .init(name: "includeItemsFromAllDrives", value: "true"),
            .init(name: "corpora", value: "allDrives"),
        ], what: "Search")
        return (json["files"] as? [[String: Any]] ?? []).map(Self.item)
    }

    func createFolder(name: String, parent: String) async throws -> DriveItem {
        var request = try await authorized(
            URL(string: "\(Self.api)/files?fields=id,name,mimeType,modifiedTime&supportsAllDrives=true")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: [
            "name": name, "mimeType": Self.folderMIME, "parents": [parent],
        ])
        let json = try await send(request, what: "Creating folder")
        return Self.item(json)
    }

    // MARK: - Download

    private static let chunkSize: Int64 = 12 * 1024 * 1024
    private static let concurrency = 5

    /// Download to a file on disk — never into memory, since sources run to
    /// 2 GB. Parallel ranged requests get past Drive's per-connection
    /// throttling; falls back to a single stream if ranges are unsupported.
    func download(
        fileID: String,
        size: Int64,
        to destination: URL,
        progress: @escaping @Sendable (Int64, Int64) -> Void
    ) async throws {
        let url = URL(string: "\(Self.api)/files/\(fileID)?alt=media&supportsAllDrives=true")!
        let fm = FileManager.default
        try? fm.removeItem(at: destination)
        fm.createFile(atPath: destination.path, contents: nil)
        let handle = try FileHandle(forWritingTo: destination)
        defer { try? handle.close() }

        if size > Self.chunkSize {
            do {
                try await downloadRanged(url: url, size: size, handle: handle, progress: progress)
                return
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                try handle.truncate(atOffset: 0) // ranges unsupported — start over
            }
        }

        let (bytes, response) = try await authorizedStream(url)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
            throw DriveError.http((response as? HTTPURLResponse)?.statusCode ?? 0, "Download")
        }
        let total = http.expectedContentLength > 0 ? http.expectedContentLength : size
        var buffer = Data()
        buffer.reserveCapacity(1 << 20)
        var received: Int64 = 0
        for try await byte in bytes {
            buffer.append(byte)
            if buffer.count >= (1 << 20) {
                try handle.write(contentsOf: buffer)
                received += Int64(buffer.count)
                buffer.removeAll(keepingCapacity: true)
                progress(received, total)
                try Task.checkCancellation()
            }
        }
        if !buffer.isEmpty {
            try handle.write(contentsOf: buffer)
            received += Int64(buffer.count)
        }
        progress(received, total)
    }

    private func downloadRanged(
        url: URL,
        size: Int64,
        handle: FileHandle,
        progress: @escaping @Sendable (Int64, Int64) -> Void
    ) async throws {
        let chunks = Int((size + Self.chunkSize - 1) / Self.chunkSize)
        let writer = FileWriter(handle: handle)
        let counter = ByteCounter(total: size, report: progress)

        try await withThrowingTaskGroup(of: Void.self) { group in
            var next = 0
            func addTask(_ index: Int) {
                group.addTask {
                    let start = Int64(index) * Self.chunkSize
                    let end = Swift.min(start + Self.chunkSize, size) - 1
                    var request = try await authorized(url)
                    request.setValue("bytes=\(start)-\(end)", forHTTPHeaderField: "Range")
                    let (data, response) = try await URLSession.shared.data(for: request)
                    guard (response as? HTTPURLResponse)?.statusCode == 206 else {
                        throw DriveError.http(0, "Ranged download")
                    }
                    guard data.count == Int(end - start + 1) else {
                        throw DriveError.badResponse
                    }
                    try await writer.write(data, at: start)
                    await counter.add(Int64(data.count))
                }
            }
            for _ in 0..<Swift.min(Self.concurrency, chunks) {
                addTask(next)
                next += 1
            }
            while try await group.next() != nil {
                try Task.checkCancellation()
                if next < chunks {
                    addTask(next)
                    next += 1
                }
            }
        }
    }

    // MARK: - Upload

    struct UploadResult {
        let id: String
        let name: String
        let webViewLink: String
    }

    struct UploadMeta {
        var name: String
        var mimeType: String
        var description: String?
        var parents: [String]?
        var appProperties: [String: String]?

        var json: [String: Any] {
            var out: [String: Any] = ["name": name, "mimeType": mimeType]
            if let description, !description.isEmpty { out["description"] = description }
            if let parents { out["parents"] = parents }
            if let appProperties, !appProperties.isEmpty { out["appProperties"] = appProperties }
            return out
        }
    }

    private static let uploadChunk = 8 * 1024 * 1024 // multiple of 256 KiB, required

    /// Resumable upload: required for large files and survives transient
    /// network errors, since each chunk is retried from the server's offset.
    func upload(
        file: URL,
        meta: UploadMeta,
        progress: @escaping @Sendable (Int64, Int64) -> Void
    ) async throws -> UploadResult {
        let total = Int64((try FileManager.default.attributesOfItem(atPath: file.path)[.size] as? NSNumber)?.int64Value ?? 0)

        var start = try await authorized(URL(string:
            "\(Self.upload)/files?uploadType=resumable&supportsAllDrives=true&fields=id,name,webViewLink")!)
        start.httpMethod = "POST"
        start.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        start.setValue(meta.mimeType, forHTTPHeaderField: "X-Upload-Content-Type")
        start.setValue(String(total), forHTTPHeaderField: "X-Upload-Content-Length")
        start.httpBody = try JSONSerialization.data(withJSONObject: meta.json)

        let (initData, initResponse) = try await URLSession.shared.data(for: start)
        guard let initHTTP = initResponse as? HTTPURLResponse, initHTTP.statusCode == 200,
              let location = initHTTP.value(forHTTPHeaderField: "Location"),
              let session = URL(string: location) else {
            throw DriveError.http((initResponse as? HTTPURLResponse)?.statusCode ?? 0,
                                  "Upload init: \(String(data: initData, encoding: .utf8) ?? "")")
        }

        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }

        var offset: Int64 = 0
        var attempt = 0
        while offset < total {
            try Task.checkCancellation()
            let end = Swift.min(offset + Int64(Self.uploadChunk), total)
            try handle.seek(toOffset: UInt64(offset))
            let piece = try handle.read(upToCount: Int(end - offset)) ?? Data()

            var put = URLRequest(url: session)
            put.httpMethod = "PUT"
            put.setValue("bytes \(offset)-\(end - 1)/\(total)", forHTTPHeaderField: "Content-Range")

            do {
                let (data, response) = try await URLSession.shared.upload(for: put, from: piece)
                guard let http = response as? HTTPURLResponse else { throw DriveError.badResponse }
                if http.statusCode == 308 {
                    offset = Self.resumeOffset(http) ?? end
                    attempt = 0
                    progress(offset, total)
                } else if (200...299).contains(http.statusCode) {
                    progress(total, total)
                    return try Self.uploadResult(data)
                } else if http.statusCode >= 500, attempt < 5 {
                    attempt += 1
                    try await Task.sleep(nanoseconds: UInt64(attempt) * 1_000_000_000)
                    if let status = try await queryStatus(session, total: total) {
                        if let result = status.result { return result }
                        offset = status.offset
                    }
                } else {
                    throw DriveError.http(http.statusCode,
                                          "Upload: \(String(data: data, encoding: .utf8) ?? "")")
                }
            } catch let error as URLError {
                attempt += 1
                if attempt > 5 { throw error }
                try await Task.sleep(nanoseconds: UInt64(attempt) * 1_000_000_000)
                if let status = try await queryStatus(session, total: total) {
                    if let result = status.result { return result }
                    offset = status.offset
                }
            }
        }
        throw DriveError.badResponse
    }

    private func queryStatus(_ session: URL, total: Int64) async throws
        -> (offset: Int64, result: UploadResult?)? {
        var request = URLRequest(url: session)
        request.httpMethod = "PUT"
        request.setValue("bytes */\(total)", forHTTPHeaderField: "Content-Range")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse else { return nil }
        if http.statusCode == 308 { return (Self.resumeOffset(http) ?? 0, nil) }
        if (200...299).contains(http.statusCode) {
            return (total, try? Self.uploadResult(data))
        }
        return nil
    }

    private static func resumeOffset(_ http: HTTPURLResponse) -> Int64? {
        // "bytes=0-8388607" — the server's last confirmed byte.
        guard let range = http.value(forHTTPHeaderField: "Range"),
              let last = range.split(separator: "-").last,
              let value = Int64(last) else { return nil }
        return value + 1
    }

    private static func uploadResult(_ data: Data) throws -> UploadResult {
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = json["id"] as? String else { throw DriveError.badResponse }
        return UploadResult(
            id: id,
            name: json["name"] as? String ?? "",
            webViewLink: json["webViewLink"] as? String
                ?? "https://drive.google.com/file/d/\(id)/view")
    }

    /// Grant "anyone with the link can view".
    func shareAnyone(fileID: String) async throws {
        var request = try await authorized(
            URL(string: "\(Self.api)/files/\(fileID)/permissions?supportsAllDrives=true")!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(
            withJSONObject: ["role": "reader", "type": "anyone"])
        let (_, response) = try await URLSession.shared.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        // 400 usually means the permission already exists, which is fine.
        guard (200...299).contains(code) || code == 400 else {
            throw DriveError.http(code, "Sharing")
        }
    }

    // MARK: - Plumbing

    /// Escape a value for Drive's query language, matching the web client.
    static func escape(_ value: String) -> String {
        value.replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "'", with: "\\'")
    }

    private static func item(_ f: [String: Any]) -> DriveItem {
        DriveItem(
            id: f["id"] as? String ?? "",
            name: f["name"] as? String ?? "",
            mimeType: f["mimeType"] as? String ?? "",
            size: Int64(f["size"] as? String ?? "") ?? 0,
            modifiedTime: (f["modifiedTime"] as? String).flatMap(ISO8601DateFormatter().date(from:)))
    }

    private func authorized(_ url: URL) async throws -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        return request
    }

    private func authorizedStream(_ url: URL) async throws -> (URLSession.AsyncBytes, URLResponse) {
        try await URLSession.shared.bytes(for: authorized(url))
    }

    private func getJSON(path: String, query: [URLQueryItem], what: String) async throws -> [String: Any] {
        var components = URLComponents(string: Self.api + path)!
        components.queryItems = query
        return try await send(try await authorized(components.url!), what: what)
    }

    private func send(_ request: URLRequest, what: String) async throws -> [String: Any] {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw DriveError.http((response as? HTTPURLResponse)?.statusCode ?? 0, what)
        }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw DriveError.badResponse
        }
        return json
    }
}

/// Serializes positional writes from the parallel range workers.
private actor FileWriter {
    private let handle: FileHandle
    init(handle: FileHandle) { self.handle = handle }

    func write(_ data: Data, at offset: Int64) throws {
        try handle.seek(toOffset: UInt64(offset))
        try handle.write(contentsOf: data)
    }
}

/// Accumulates progress across concurrent chunk downloads.
private actor ByteCounter {
    private var received: Int64 = 0
    private let total: Int64
    private let report: @Sendable (Int64, Int64) -> Void

    init(total: Int64, report: @escaping @Sendable (Int64, Int64) -> Void) {
        self.total = total
        self.report = report
    }

    func add(_ count: Int64) {
        received += count
        report(received, total)
    }
}
