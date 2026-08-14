import AVKit
import SwiftUI

struct EditorView: View {
    @Environment(AppModel.self) private var model
    @State private var player = AVPlayer()
    @State private var playhead: Double = 0
    @State private var observer: Any?
    @State private var stopAt: Double?
    @State private var showingFolderPicker = false

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VideoPlayer(player: player)
                        .aspectRatio(16 / 9, contentMode: .fit)
                        .frame(maxWidth: .infinity)
                        .background(.black)
                        .clipShape(RoundedRectangle(cornerRadius: 12))

                    TrimTimeline(
                        duration: model.duration,
                        start: $model.trimStart,
                        end: $model.trimEnd,
                        playhead: playhead
                    ) { time in
                        stopAt = nil
                        player.pause()
                        seek(to: time)
                    }

                    readout

                    HStack(spacing: 10) {
                        Button("Start here", systemImage: "arrow.right.to.line") {
                            model.trimStart = min(playhead, model.trimEnd - 0.2)
                        }
                        Button("End here", systemImage: "arrow.left.to.line") {
                            model.trimEnd = max(playhead, model.trimStart + 0.2)
                        }
                    }
                    .buttonStyle(.bordered)
                    .font(.subheadline)

                    Button("Preview the cut", systemImage: "play.circle") {
                        seek(to: model.trimStart)
                        stopAt = model.trimEnd
                        player.play()
                    }
                    .buttonStyle(.bordered)
                    .font(.subheadline)

                    Divider()
                    saveSection
                }
                .padding()
            }
            .navigationTitle(model.picked?.name ?? "Trim")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Back") {
                        player.pause()
                        model.backToBrowse()
                    }
                }
            }
            .sheet(isPresented: $showingFolderPicker) {
                FolderPickerView(selection: $model.destination)
            }
        }
        .onAppear(perform: start)
        .onDisappear(perform: stop)
    }

    private var readout: some View {
        HStack {
            label("Start", model.trimStart.clockText)
            Spacer()
            label("End", model.trimEnd.clockText)
            Spacer()
            label("Keeps", (model.trimEnd - model.trimStart).clockText)
        }
        .font(.footnote)
    }

    private func label(_ title: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(title).foregroundStyle(.secondary)
            Text(value).font(.footnote.monospacedDigit().weight(.semibold))
        }
    }

    private var saveSection: some View {
        @Bindable var model = model
        return VStack(alignment: .leading, spacing: 14) {
            Text("Save").font(.headline)

            TextField("File name", text: $model.outputName)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()

            TextField("Description (optional)", text: $model.outputDescription, axis: .vertical)
                .lineLimit(2...4)
                .textFieldStyle(.roundedBorder)

            Button {
                showingFolderPicker = true
            } label: {
                HStack {
                    Text("Save in")
                    Spacer()
                    Text(model.destination.name).foregroundStyle(.secondary)
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                }
            }
            .buttonStyle(.plain)

            Toggle("Anyone with the link can view", isOn: $model.makeShareable)
                .font(.subheadline)

            Text("Cuts snap to the nearest keyframe, which keeps the trim lossless and near-instant — the start can land a moment earlier than the handle.")
                .font(.caption)
                .foregroundStyle(.secondary)

            Button("Trim and save to Drive") {
                player.pause()
                model.trimAndUpload()
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .frame(maxWidth: .infinity)

            Button("Save to this device instead") {
                player.pause()
                model.trimAndSaveToDevice()
            }
            .buttonStyle(.bordered)
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Player wiring

    private func start() {
        guard let url = model.localURL else { return }
        player.replaceCurrentItem(with: AVPlayerItem(url: url))
        observer = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.05, preferredTimescale: 600), queue: .main
        ) { time in
            let seconds = CMTimeGetSeconds(time)
            playhead = seconds
            // Stop at the out point when previewing the selection.
            if let stopAt, seconds >= stopAt {
                player.pause()
                self.stopAt = nil
            }
        }
    }

    private func stop() {
        if let observer { player.removeTimeObserver(observer) }
        observer = nil
        player.pause()
    }

    private func seek(to time: Double) {
        player.seek(to: CMTime(seconds: time, preferredTimescale: 600),
                    toleranceBefore: .zero, toleranceAfter: .zero)
    }
}
