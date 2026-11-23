import SwiftUI

/// A spectrum sweep drawn as a line, with the peak-hold line behind it and a drag readout.
struct SdrSpectrumChart: View {
    let frame: SdrSpectrumFrame
    let peak: [Int]
    @State private var touch: Int?

    var body: some View {
        let all = frame.db + peak
        let lo = Double((all.min() ?? -80) - 3), hi = Double(max((all.max() ?? -20) + 3, Int(lo) + 20))
        GeometryReader { geo in
            let w = geo.size.width, h = geo.size.height - 22
            ZStack(alignment: .topLeading) {
                Canvas { ctx, _ in
                    func path(_ v: [Int]) -> Path {
                        var p = Path()
                        for (i, d) in v.enumerated() {
                            let x = w * CGFloat(i) / CGFloat(max(1, v.count - 1)), y = h * (1 - CGFloat((Double(d) - lo) / (hi - lo)))
                            if i == 0 { p.move(to: CGPoint(x: x, y: y)) } else { p.addLine(to: CGPoint(x: x, y: y)) }
                        }
                        return p
                    }
                    for g in 0...4 {
                        var line = Path(); let y = h * CGFloat(g) / 4
                        line.move(to: CGPoint(x: 0, y: y)); line.addLine(to: CGPoint(x: w, y: y))
                        ctx.stroke(line, with: .color(.gray.opacity(0.25)), lineWidth: 0.5)
                    }
                    if peak.count == frame.db.count { ctx.stroke(path(peak), with: .color(.orange), lineWidth: 1.2) }
                    ctx.stroke(path(frame.db), with: .color(.white), lineWidth: 1.5)
                    if let t = touch, t < frame.db.count {
                        var v = Path(); let x = w * CGFloat(t) / CGFloat(max(1, frame.db.count - 1))
                        v.move(to: CGPoint(x: x, y: 0)); v.addLine(to: CGPoint(x: x, y: h))
                        ctx.stroke(v, with: .color(.orange), lineWidth: 1)
                    }
                }
                .frame(height: h)
                .contentShape(Rectangle())
                .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                    touch = max(0, min(frame.db.count - 1, Int((g.location.x / max(1, w)) * CGFloat(frame.db.count - 1) + 0.5)))
                }.onEnded { _ in touch = nil })
                HStack {
                    Text(label(frame.lowHz)); Spacer()
                    if let t = touch, t < frame.db.count { Text("\(label(frame.hz(at: t)))  \(frame.db[t]) dB").foregroundStyle(.orange) } else { Text(label((frame.lowHz + frame.highHz) / 2)) }
                    Spacer(); Text(label(frame.highHz))
                }
                .font(.caption2).foregroundStyle(.secondary).offset(y: h + 4)
            }
        }
        .accessibilityLabel("Spectrum from \(label(frame.lowHz)) to \(label(frame.highHz))")
    }

    private func label(_ hz: Int) -> String { String(format: "%.3f MHz", Double(hz) / 1e6) }
}
