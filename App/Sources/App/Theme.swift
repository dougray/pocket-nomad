import SwiftUI

enum Theme {
    // Sampled from the Pocket Nomad logo.
    /// Top of the "NOMAD" lettering.
    static let amber = Color(red: 0.988, green: 0.592, blue: 0.071)     // #FC9712
    /// Bottom of the lettering.
    static let orange = Color(red: 0.988, green: 0.518, blue: 0.047)    // #FC840C
    /// The compass star's highlight.
    static let highlight = Color(red: 0.996, green: 0.733, blue: 0.118) // #FEBB1E

    /// Logo amber on dark backgrounds. On white the same hue is darkened, because
    /// bright amber text is too faint to read there.
    static let accent = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .light
            ? UIColor(red: 0.761, green: 0.357, blue: 0, alpha: 1)       // #C25B00
            : UIColor(red: 0.988, green: 0.592, blue: 0.071, alpha: 1)
    })
    /// Amber to orange, top to bottom, like the logo's lettering.
    static let brandGradient = LinearGradient(colors: [amber, orange], startPoint: .top, endPoint: .bottom)
    static let danger = Color(red: 0.95, green: 0.26, blue: 0.21)
    static let card = Color(.secondarySystemBackground)
}

@MainActor @Observable
final class AppSettings {
    static let shared = AppSettings()

    /// Dark mode saves battery on OLED screens, so it is on by default.
    var forceDark: Bool { didSet { defaults.set(forceDark, forKey: "forceDark") } }
    var preferredBackend: String { didSet { defaults.set(preferredBackend, forKey: "preferredBackend") } }

    private let defaults = UserDefaults.standard

    private init() {
        forceDark = defaults.object(forKey: "forceDark") as? Bool ?? true
        preferredBackend = defaults.string(forKey: "preferredBackend") ?? "auto"
    }
}

/// A large, glove-friendly tile used across the Survive screen.
struct TileLabel: View {
    let title: String
    let symbol: String
    /// nil draws the icon in the brand gradient.
    var tint: Color?

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: symbol)
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(tint.map(AnyShapeStyle.init) ?? AnyShapeStyle(Theme.brandGradient))
            Text(title)
                .font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.center)
                .foregroundStyle(.primary)
        }
        .frame(maxWidth: .infinity, minHeight: 96)
        .padding(.vertical, 8)
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 16))
    }
}

extension Int64 {
    var byteString: String { ByteCountFormatter.string(fromByteCount: self, countStyle: .file) }
}

/// A non-lazy grid with equal-width columns: as many columns of at least `minimum`
/// width as fit, or exactly `columns`. Lazy grids leave off-screen rows out of the
/// accessibility tree, which hid the last tool tile from VoiceOver on smaller phones.
struct TileGrid: Layout {
    var minimum: CGFloat = 104
    var columns: Int?
    var spacing: CGFloat = 12

    private func columnCount(_ width: CGFloat) -> Int {
        columns ?? max(1, Int((width + spacing) / (minimum + spacing)))
    }

    private func rows(_ subviews: Subviews, width: CGFloat) -> (cell: CGFloat, heights: [CGFloat], count: Int) {
        let n = columnCount(width)
        let cell = (width - spacing * CGFloat(n - 1)) / CGFloat(n)
        var heights: [CGFloat] = []
        for start in stride(from: 0, to: subviews.count, by: n) {
            let row = subviews[start ..< min(start + n, subviews.count)]
            heights.append(row.map { $0.sizeThatFits(ProposedViewSize(width: cell, height: nil)).height }.max() ?? 0)
        }
        return (cell, heights, n)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 360
        let r = rows(subviews, width: width)
        return CGSize(width: width, height: r.heights.reduce(0, +) + spacing * CGFloat(max(0, r.heights.count - 1)))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let r = rows(subviews, width: bounds.width)
        var y = bounds.minY
        for (rowIndex, height) in r.heights.enumerated() {
            for column in 0 ..< r.count {
                let i = rowIndex * r.count + column
                guard i < subviews.count else { break }
                let x = bounds.minX + CGFloat(column) * (r.cell + spacing)
                subviews[i].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(width: r.cell, height: height))
            }
            y += height + spacing
        }
    }
}
