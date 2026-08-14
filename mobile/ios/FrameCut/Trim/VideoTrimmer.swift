import AVFoundation
import Foundation

enum TrimError: LocalizedError {
    case unsupported
    case failed(String)

    var errorDescription: String? {
        switch self {
        case .unsupported: "This video's format cannot be trimmed without re-encoding."
        case .failed(let m): m
        }
    }
}

/// Lossless trim: `AVAssetExportPresetPassthrough` copies the compressed
/// samples straight into a new container with no decode/encode step, so a cut
/// from a 30-minute source finishes in seconds and the quality is bit-identical.
///
/// The cost of that speed is keyframe alignment — the start lands on the last
/// sync sample at or before the requested time, so it can sit a couple of
/// seconds earlier than the handle. That is the right trade for lecture
/// top-and-tail trimming, and it is what keeps this fast on a phone.
enum VideoTrimmer {
    struct Output {
        let url: URL
        let fileType: AVFileType
        let suggestedExtension: String
    }

    static func trim(
        source: URL,
        start: Double,
        end: Double,
        outputName: String,
        progress: @escaping @Sendable (Double) -> Void
    ) async throws -> Output {
        let asset = AVURLAsset(url: source)
        guard let session = AVAssetExportSession(
            asset: asset, presetName: AVAssetExportPresetPassthrough) else {
            throw TrimError.unsupported
        }

        // Prefer the source container so the copy stays lossless; fall back to
        // whatever passthrough can write for this asset.
        let supported = session.supportedFileTypes
        let sourceType: AVFileType = source.pathExtension.lowercased() == "mov" ? .mov : .mp4
        guard let fileType = supported.contains(sourceType) ? sourceType : supported.first else {
            throw TrimError.unsupported
        }

        let scale = CMTimeScale(600)
        session.timeRange = CMTimeRange(
            start: CMTime(seconds: start, preferredTimescale: scale),
            end: CMTime(seconds: end, preferredTimescale: scale))

        let ext = fileType == .mov ? "mov" : "mp4"
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("trimmed-\(UUID().uuidString).\(ext)")

        let monitor = Task {
            for await state in session.states(updateInterval: 0.2) {
                if case .exporting(let p) = state { progress(p.fractionCompleted) }
            }
        }
        defer { monitor.cancel() }

        do {
            try await session.export(to: destination, as: fileType)
        } catch {
            throw TrimError.failed(error.localizedDescription)
        }
        progress(1)
        return Output(url: destination, fileType: fileType, suggestedExtension: ext)
    }

    /// MIME type Drive should store the trimmed copy under.
    static func mimeType(for fileType: AVFileType) -> String {
        fileType == .mov ? "video/quicktime" : "video/mp4"
    }
}
