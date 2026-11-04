import SwiftUI

/// The phone's dark/orange look (App/Sources/App/Theme.swift), for the watch.
/// The watch is always dark, so it uses the logo amber directly.
enum WatchTheme {
    static let amber = Color(red: 0.988, green: 0.592, blue: 0.071)     // #FC9712
    static let orange = Color(red: 0.988, green: 0.518, blue: 0.047)    // #FC840C
    static let accent = amber
    static let danger = Color(red: 0.95, green: 0.26, blue: 0.21)
    static let card = Color(white: 0.14)
    static let brandGradient = LinearGradient(colors: [amber, orange], startPoint: .top, endPoint: .bottom)
}
