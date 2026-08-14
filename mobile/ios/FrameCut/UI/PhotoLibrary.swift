import Photos

enum PhotoLibraryError: LocalizedError {
    case denied

    var errorDescription: String? {
        "FrameCut needs permission to add videos to your photo library. Enable it in Settings › FrameCut."
    }
}

enum PhotoLibrary {
    /// Save a finished trim into the user's photo library. Requests only
    /// add-only access, which is all this app ever needs.
    static func save(_ url: URL) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw PhotoLibraryError.denied }
        try await PHPhotoLibrary.shared().performChanges {
            PHAssetCreationRequest.forAsset()
                .addResource(with: .video, fileURL: url, options: nil)
        }
    }
}
