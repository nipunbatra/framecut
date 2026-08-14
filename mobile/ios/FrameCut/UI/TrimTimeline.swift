import SwiftUI

/// Two draggable handles over a track, plus a playhead. Dragging a handle
/// scrubs the player so the user sees the exact frame they are cutting at.
struct TrimTimeline: View {
    let duration: Double
    @Binding var start: Double
    @Binding var end: Double
    let playhead: Double
    /// Called continuously while dragging so the preview can follow.
    let onScrub: (Double) -> Void

    private let handleWidth: CGFloat = 22
    private let trackHeight: CGFloat = 56

    var body: some View {
        GeometryReader { geo in
            let usable = max(geo.size.width - handleWidth, 1)
            let startX = CGFloat(fraction(start)) * usable
            let endX = CGFloat(fraction(end)) * usable

            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 8)
                    .fill(.quaternary)
                    .frame(height: trackHeight)

                // Kept region
                RoundedRectangle(cornerRadius: 6)
                    .fill(Color.accentColor.opacity(0.28))
                    .frame(width: max(endX - startX, 0) + handleWidth, height: trackHeight)
                    .offset(x: startX)

                // Playhead
                Rectangle()
                    .fill(.primary)
                    .frame(width: 2, height: trackHeight)
                    .offset(x: CGFloat(fraction(playhead)) * usable + handleWidth / 2 - 1)

                handle(at: startX, systemImage: "chevron.compact.left")
                    .gesture(drag(usable: usable, isStart: true))
                handle(at: endX, systemImage: "chevron.compact.right")
                    .gesture(drag(usable: usable, isStart: false))
            }
            .frame(height: trackHeight)
            .contentShape(.rect)
        }
        .frame(height: trackHeight)
    }

    private func handle(at x: CGFloat, systemImage: String) -> some View {
        RoundedRectangle(cornerRadius: 6)
            .fill(Color.accentColor)
            .frame(width: handleWidth, height: trackHeight)
            .overlay {
                Image(systemName: systemImage)
                    .font(.headline)
                    .foregroundStyle(.white)
            }
            .offset(x: x)
    }

    private func drag(usable: CGFloat, isStart: Bool) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard duration > 0 else { return }
                let raw = Double(max(0, min(value.location.x - handleWidth / 2, usable)) / usable)
                let time = raw * duration
                if isStart {
                    start = min(time, end - 0.2)
                    onScrub(start)
                } else {
                    end = max(time, start + 0.2)
                    onScrub(end)
                }
            }
    }

    private func fraction(_ time: Double) -> Double {
        guard duration > 0 else { return 0 }
        return min(max(time / duration, 0), 1)
    }
}
