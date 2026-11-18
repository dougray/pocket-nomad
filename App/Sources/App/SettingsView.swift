import SwiftUI

struct SettingsView: View {
    @Environment(AppSettings.self) private var settings
    @Environment(ContentStore.self) private var content
    @Environment(\.dismiss) private var dismiss
    @State private var mesh = MeshService.shared
    @State private var confirmMeshDelete = false
    @State private var showCoverage = false
    @AppStorage(WorksWith.dismissedKey) private var warWalkerDismissed = false
    @State private var favorites = FavoritesStore.shared
    @State private var shapes = ShapeStore.shared
    @State private var mesh2 = MeshService.shared

    var body: some View {
        @Bindable var settings = settings
        NavigationStack {
            Form {
                Section {
                    Toggle("Always dark (saves battery)", isOn: $settings.forceDark)
                } footer: {
                    Text("On iPhones with OLED screens, black pixels use almost no power.")
                }
                Section {
                    Picker("Language", selection: $settings.language) {
                        ForEach(AppLanguage.allCases) { Text($0 == .system ? "System default" : $0.nativeName).tag($0) }
                    }
                } footer: {
                    Text("Menus and screens are translated (first-pass translations, still being reviewed). Guides and books stay in English for now: first-aid and survival text needs a qualified human translator.")
                }
                Section("AI answers") {
                    LabeledContent("Apple on-device model", value: ModelAvailability.appleStatus == nil ? "Ready" : "Not available")
                    if let status = ModelAvailability.appleStatus { Text(status).font(.footnote).foregroundStyle(.secondary) }
                    LabeledContent("Downloaded models", value: ModelAvailability.llamaSupported ? "\(content.models.count)" : "Not in this build")
                }
                Section("Search index") {
                    if content.indexProgress.isEmpty {
                        Text("All books are ready to search.").foregroundStyle(.secondary)
                    } else {
                        ForEach(content.indexProgress.sorted(by: { $0.key < $1.key }), id: \.key) { id, p in
                            LabeledContent(content.book(id: id)?.title ?? id, value: "\(Int(p * 100))%")
                        }
                        Button("Pause indexing", role: .destructive) { content.cancelIndexing() }
                    }
                }
                if WorksWith.showWarWalker(usesMap: !favorites.favorites.isEmpty || !shapes.shapes.isEmpty,
                                           usesRadio: !HamService.shared.stations.isEmpty || !mesh2.rememberedLinks.isEmpty, dismissed: warWalkerDismissed) {
                    Section("Works with") {
                        Text("WarWalker turns your runs and walks into a game: it maps the Wi-Fi and Bluetooth around you, and it uses Pocket Nomad's targets and coverage hexagons. Free and open source.").font(.footnote)
                        Link("WarWalker on GitHub", destination: WorksWith.warWalkerURL)
                        Button("Not interested", role: .cancel) { warWalkerDismissed = true }
                    }
                }
                Section("Battery tips") {
                    Text("Turn on Low Power Mode. Lower screen brightness. Use Airplane Mode with Bluetooth on to keep mesh messaging while saving power. Keep the phone warm in cold weather; cold batteries die fast.")
                        .font(.footnote)
                }
                Section {
                    NavigationLink("How Pocket Nomad handles your data") { PrivacySummaryView() }
                    Button("Coverage map settings…") { showCoverage = true }
                    Button("Delete mesh messages and nodes", role: .destructive) { confirmMeshDelete = true }
                        .disabled(mesh.messages.isEmpty && mesh.sortedNodes.isEmpty)
                } header: { Text("Privacy") } footer: {
                    Text("No accounts, analytics or tracking. Nothing leaves this phone unless you send it: a mesh message, an SOS, or a file you export.")
                }
                Section("About") {
                    // Always on black: the logo's white lettering vanishes on a light background.
                    Image("Logo")
                        .resizable().scaledToFit()
                        .frame(maxWidth: 220)
                        .padding(.vertical, 12)
                        .frame(maxWidth: .infinity)
                        .background(.black, in: RoundedRectangle(cornerRadius: 16))
                        .listRowInsets(EdgeInsets())
                        .accessibilityLabel("Pocket Nomad")
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                    Text("Pocket Nomad is free, open-source software (Apache 2.0). It is an independent project, inspired by Project NOMAD, and not affiliated with it.")
                        .font(.footnote)
                    Text("Map data © OpenStreetMap contributors (ODbL), tiles by Protomaps. Books from the Kiwix library under their own licenses. Fonts: Noto Sans (OFL). AI runs locally with llama.cpp (MIT) or Apple's on-device model.")
                        .font(.footnote).foregroundStyle(.secondary)
                    Text("Guides in this app don't replace first-aid training. Call emergency services whenever you can.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .confirmationDialog("Delete all mesh messages and nodes from this phone?", isPresented: $confirmMeshDelete, titleVisibility: .visible) {
                Button("Delete", role: .destructive) { mesh.deleteHistory() }
            } message: {
                Text("Your radio keeps its own node list and sends it again next time you connect.")
            }
            .sheet(isPresented: $showCoverage) { CoverageSettingsView() }
            .navigationTitle("Settings")
            .toolbar { Button("Done") { dismiss() } }
        }
    }
}
