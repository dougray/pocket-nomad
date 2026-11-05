import SwiftUI
import CoreLocation

@main
struct NomadWatchApp: App {
    init() {
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "demo") {
            WatchConnector.shared.startDemo()
            WatchLocation.shared.setDemo(CLLocationCoordinate2D(latitude: 39.74956, longitude: -104.99654), heading: 312)
        }
        #endif
        WatchConnector.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            WatchRootView()
        }
    }
}

enum WatchRoute: Hashable {
    case signal, compass, firstAid, guide(String), messages, reply(WatchRelay.Conversation, String, String?)

    /// Complication links: nomadpocket-watch://sos and nomadpocket-watch://compass.
    init?(url: URL) {
        switch url.host() {
        case "sos": self = .signal
        case "compass": self = .compass
        case "firstaid": self = .firstAid
        case "messages": self = .messages
        default: return nil
        }
    }

    #if DEBUG
    /// `-screen sos|compass|position|firstaid|guide:<id>|messages|reply` opens straight to a screen.
    static var launch: [WatchRoute] {
        guard let s = UserDefaults.standard.string(forKey: "screen") else { return [] }
        if s.hasPrefix("guide:") { return [.firstAid, .guide(String(s.dropFirst(6)))] }
        switch s {
        case "sos": return [.signal]
        case "compass", "position": return [.compass]
        case "firstaid": return [.firstAid]
        case "messages": return [.messages]
        case "reply": return [.messages, .reply(.channel(0), "Primary channel", nil)]
        default: return []
        }
    }
    #endif
}

struct WatchRootView: View {
    @State private var path: [WatchRoute] = {
        #if DEBUG
        WatchRoute.launch
        #else
        []
        #endif
    }()
    private let cards = FirstAidLibrary.cards

    var body: some View {
        NavigationStack(path: $path) {
            HomeView(path: $path)
                .navigationDestination(for: WatchRoute.self) { route in
                    switch route {
                    case .signal: SignalView()
                    case .compass: WatchCompassView()
                    case .firstAid: FirstAidListView()
                    case .guide(let id):
                        if let card = cards.first(where: { $0.id == id }) { FirstAidDetailView(card: card) }
                    case .messages: MessagesView()
                    case .reply(let conversation, let title, let network): QuickReplyView(conversation: conversation, title: title, network: network)
                    }
                }
        }
        .tint(WatchTheme.accent)
        .onOpenURL { url in
            if let route = WatchRoute(url: url) { path = [route] }
        }
    }
}

struct HomeView: View {
    @Binding var path: [WatchRoute]
    @State private var connector = WatchConnector.shared

    var body: some View {
        List {
            Button { path.append(.signal) } label: {
                VStack(spacing: 2) {
                    Text("SOS").font(.system(size: 34, weight: .heavy, design: .rounded))
                    Text("Signal light").font(.footnote.weight(.semibold)).opacity(0.85)
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity, minHeight: 78)
            }
            .listRowBackground(RoundedRectangle(cornerRadius: 18).fill(WatchTheme.danger))
            .accessibilityHint("Flashes SOS on the screen with haptics")

            MeshSOSButton()

            row("Compass", "location.north.line.fill", .compass)
            row("First aid", "cross.case.fill", .firstAid)
            row("Messages", "antenna.radiowaves.left.and.right", .messages)
        }
        .navigationTitle("Pocket Nomad")
    }

    private func row(_ title: String, _ symbol: String, _ route: WatchRoute) -> some View {
        Button { path.append(route) } label: {
            Label {
                Text(title).font(.headline)
            } icon: {
                Image(systemName: symbol).foregroundStyle(WatchTheme.brandGradient)
            }
        }
        .listRowBackground(RoundedRectangle(cornerRadius: 14).fill(WatchTheme.card))
    }
}
