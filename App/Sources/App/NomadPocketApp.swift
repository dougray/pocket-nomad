import SwiftUI

@main
struct NomadPocketApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @State private var content = ContentStore.shared
    @State private var settings = AppSettings.shared

    init() {
        MeshService.shared.onHeard = { CoverageStore.shared.record($0) }
        MeshService.shared.start()
        CoverageStore.shared.start()
        #if DEBUG
        // -screen map:demo (recordings) or map:shared (what sharing would publish)
        if let launch = LaunchScreen.parts(), launch.0 == "map", !launch.1.isEmpty {
            MeshService.shared.loadDemoNodes()
            CoverageStore.shared.loadDemo()
            if launch.1 == "shared" { CoverageStore.shared.sharing = true }
            UserDefaults.standard.set(launch.1 == "shared" ? "shared" : "recorded", forKey: "map.coverage")
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(content)
                .environment(settings)
                .preferredColorScheme(settings.forceDark ? .dark : nil)
                .tint(Theme.accent)
                .task {
                    await GuideStore.prepareIndex()
                    await content.refresh()
                }
                .onOpenURL { url in Task { await content.importFile(at: url) } }
        }
    }
}

enum AppTab: Hashable { case survive, ask, library, map, mesh }

/// Debug builds accept `-screen <name>` to open straight to a screen, for
/// screenshots and quick checks: survive, ask, library, map, mesh, settings,
/// packs, book, tool:<compass|sos|morse|water|medicalID|checklists|sun>, guide:<file>,
/// category:<id>, query:<text> (Survive search), ask:<question>, coverage (scanner
/// settings) and map:demo / map:shared (demo nodes and coverage around Denver).
enum LaunchScreen {
    static let value: String? = {
        #if DEBUG
        UserDefaults.standard.string(forKey: "screen")
        #else
        nil
        #endif
    }()

    static func parts() -> (String, String)? {
        guard let value else { return nil }
        let p = value.split(separator: ":", maxSplits: 1).map(String.init)
        return (p[0], p.count > 1 ? p[1] : "")
    }

    static var tab: AppTab {
        switch parts()?.0 {
        case "ask": .ask
        case "library", "packs", "book": .library
        case "map": .map
        case "mesh", "coverage": .mesh
        default: .survive
        }
    }
}

struct RootView: View {
    @State private var tab: AppTab = LaunchScreen.tab
    @State private var showLaunchLogo = true

    var body: some View {
        tabs
            .overlay {
                // Continues the system launch screen (same logo, same place) and fades it
                // out, so the logo dissolves into the app instead of vanishing. Never blocks taps.
                if showLaunchLogo {
                    ZStack {
                        Color("LaunchBackground")
                        Image("Logo")
                    }
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
                    .transition(.opacity)
                }
            }
            .task {
                try? await Task.sleep(for: .milliseconds(150))
                withAnimation(.easeOut(duration: 0.35)) { showLaunchLogo = false }
            }
    }

    private var tabs: some View {
        TabView(selection: $tab) {
            SurviveView()
                .tabItem { Label("Survive", systemImage: "cross.case.fill") }
                .tag(AppTab.survive)
            AskView()
                .tabItem { Label("Ask", systemImage: "bubble.left.and.text.bubble.right.fill") }
                .tag(AppTab.ask)
            LibraryView()
                .tabItem { Label("Library", systemImage: "books.vertical.fill") }
                .tag(AppTab.library)
            OfflineMapView()
                .tabItem { Label("Map", systemImage: "map.fill") }
                .tag(AppTab.map)
            MeshView()
                .tabItem { Label("Mesh", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(AppTab.mesh)
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    /// Background downloads finished while the app was closed.
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        guard identifier == DownloadManager.sessionID else { return completionHandler() }
        MainActor.assumeIsolated { DownloadManager.shared.backgroundCompletion = completionHandler }
    }
}
