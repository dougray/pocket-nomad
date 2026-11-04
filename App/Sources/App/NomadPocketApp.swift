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
        WatchBridge.shared.start()
        #if DEBUG
        // -screen map:demo (recordings) or map:shared (what sharing would publish); mesh:demo for the nodes alone
        if let launch = LaunchScreen.parts(), launch.0 == "mesh", launch.1 == "demo" {
            MeshService.shared.loadDemoNodes()
        }
        if let launch = LaunchScreen.parts(), launch.0 == "map", launch.1 == "known" {
            // Real download from the community maps, kept around downtown Austin.
            Task { await KnownRadioStore.shared.download(around: .init(latitude: 30.2672, longitude: -97.7431), radiusKm: 100, areaName: nil) }
        } else if let launch = LaunchScreen.parts(), launch.0 == "map", !launch.1.isEmpty {
            MeshService.shared.loadDemoNodes()
            CoverageStore.shared.loadDemo()
            if launch.1 == "shared" { CoverageStore.shared.sharing = true }
            UserDefaults.standard.set(launch.1 == "shared" ? "shared" : "recorded", forKey: "map.coverage")
        }
        #if canImport(llama)
        ModelSelfTest.runIfRequested()
        #endif
        #endif
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(content)
                .environment(settings)
                .preferredColorScheme(settings.forceDark ? .dark : nil)
                .tint(Theme.accent)
                .task { await AppStartup.run(content) }
                .onOpenURL { url in Task { await content.importFile(at: url) } }
        }
        .commands { PadCommands() }
    }
}

/// Work done once per launch, not once per window: on iPad every window shares
/// the same stores, so a second window must not index or rescan again.
@MainActor
enum AppStartup {
    private static var started = false

    static func run(_ content: ContentStore) async {
        guard !started else { return }
        started = true
        await GuideStore.prepareIndex()
        await content.refresh()
    }
}

enum AppTab: Hashable { case survive, ask, library, map, mesh }

/// Debug builds accept `-screen <name>` to open straight to a screen, for
/// screenshots and quick checks: survive, ask, library, map, mesh, settings,
/// packs, book, tool:<compass|sos|morse|water|medicalID|checklists|sun>, guide:<file>,
/// category:<id>, query:<text> (Survive search), ask:<question>, coverage (scanner
/// settings), mesh:demo (demo nodes), map:demo / map:shared (demo nodes and
/// coverage around Denver) and map:known (downloads known radios around Austin).
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
    @Environment(\.horizontalSizeClass) private var widthClass
    /// One per window. iPhone only uses its section; iPad's split view uses all of it.
    @State private var nav = PadNavigator()
    @State private var showLaunchLogo = true

    private var isPad: Bool { UIDevice.current.userInterfaceIdiom == .pad }

    var body: some View {
        Group {
            // A narrow iPad window (Slide Over, a small Stage Manager window) gets the iPhone tabs.
            if isPad && widthClass == .regular {
                PadRootView(nav: nav)
            } else {
                tabs
            }
        }
        .focusedSceneValue(\.padNavigator, isPad ? nav : nil)
        .fullScreenCover(isPresented: $nav.showSOS) {
            NavigationStack {
                ToolKind.sos.destination
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) { Button("Done") { nav.showSOS = false } }
                    }
            }
            .tint(Theme.accent)
        }
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
        TabView(selection: $nav.section) {
            SurviveView()
                .tabItem { Label(AppTab.survive.title, systemImage: AppTab.survive.symbol) }
                .tag(AppTab.survive)
            AskView()
                .tabItem { Label(AppTab.ask.title, systemImage: AppTab.ask.symbol) }
                .tag(AppTab.ask)
            LibraryView()
                .tabItem { Label(AppTab.library.title, systemImage: AppTab.library.symbol) }
                .tag(AppTab.library)
            OfflineMapView()
                .tabItem { Label(AppTab.map.title, systemImage: AppTab.map.symbol) }
                .tag(AppTab.map)
            MeshView()
                .tabItem { Label(AppTab.mesh.title, systemImage: AppTab.mesh.symbol) }
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
