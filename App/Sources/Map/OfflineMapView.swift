import SwiftUI
import MapLibre

struct MapPin: Codable, Identifiable, Hashable {
    enum Kind: String, Codable, CaseIterable, Identifiable {
        case water, shelter, rally, medical, supplies, danger, other
        var id: Self { self }
        var label: String {
            switch self {
            case .water: "Water"
            case .shelter: "Shelter"
            case .rally: "Rally point"
            case .medical: "Medical"
            case .supplies: "Supplies"
            case .danger: "Danger"
            case .other: "Other"
            }
        }
        var symbol: String {
            switch self {
            case .water: "drop.fill"
            case .shelter: "house.fill"
            case .rally: "flag.fill"
            case .medical: "cross.fill"
            case .supplies: "shippingbox.fill"
            case .danger: "exclamationmark.triangle.fill"
            case .other: "mappin"
            }
        }
    }
    var id = UUID()
    var name: String
    var kind: Kind
    var latitude: Double
    var longitude: Double
    var created = Date()

    var coordinate: CLLocationCoordinate2D { .init(latitude: latitude, longitude: longitude) }

    static let url = URL.applicationSupportDirectory.appending(path: "pins.json")
    static func load() -> [MapPin] {
        (try? JSONDecoder().decode([MapPin].self, from: Data(contentsOf: url))) ?? []
    }
    static func save(_ pins: [MapPin]) {
        try? FileManager.default.createDirectory(at: URL.applicationSupportDirectory, withIntermediateDirectories: true)
        try? JSONEncoder().encode(pins).write(to: url, options: .atomic)
    }
}

/// What the coverage layer shows.
enum CoverageLayer: String, CaseIterable, Identifiable {
    case off, recorded, shared
    var id: Self { self }

    static let key = "map.coverage"
    static let migratedKey = "map.trailOffMigrated"

    /// The saved choice. The recorded layer draws where you drove, which read as
    /// your own radios on the map, so it is turned off once after this update;
    /// after that the user's choice stands.
    static func saved(_ defaults: UserDefaults = .standard) -> CoverageLayer {
        if !defaults.bool(forKey: migratedKey) {
            defaults.set(true, forKey: migratedKey)
            defaults.set(CoverageLayer.off.rawValue, forKey: key)
            return .off
        }
        return CoverageLayer(rawValue: defaults.string(forKey: key) ?? "") ?? .off
    }
    var label: String {
        switch self {
        case .off: "Off"
        case .recorded: "Where I drove (private)"
        case .shared: "What sharing would publish"
        }
    }
}

/// Which network's coverage the map shows. Recordings from both radios are
/// kept apart by their firmware tag.
enum CoverageNetworkFilter: String, CaseIterable, Identifiable {
    case both, meshtastic, meshcore
    var id: Self { self }
    var label: String {
        switch self {
        case .both: "Both networks"
        case .meshtastic: "Meshtastic only"
        case .meshcore: "MeshCore only"
        }
    }
    var network: MeshFirmware? { MeshFirmware(rawValue: rawValue) }
    func includes(_ f: MeshFirmware) -> Bool { network == nil || network == f }
}

/// What colours the coverage hexagons.
enum CoverageColorMode: String, CaseIterable, Identifiable {
    case signal, network
    var id: Self { self }
    var label: String {
        switch self {
        case .signal: "Colour by signal"
        case .network: "Colour by network"
        }
    }
}

@MainActor @Observable
final class MapController {
    weak var mapView: MLNMapView?
    var pins = MapPin.load() { didSet { MapPin.save(pins) } }
    var showNodes = UserDefaults.standard.object(forKey: "map.showNodes") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showNodes, forKey: "map.showNodes") }
    }
    /// Contacts learned through relayed adverts, not heard directly. Off by default: they can be far out of range.
    var showMeshKnown = UserDefaults.standard.object(forKey: "map.showMeshKnown") as? Bool ?? false {
        didSet { UserDefaults.standard.set(showMeshKnown, forKey: "map.showMeshKnown") }
    }
    var showKnown = UserDefaults.standard.object(forKey: "map.showKnown") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showKnown, forKey: "map.showKnown") }
    }
    /// Measure / draw mode: taps add points to the draft instead of selecting things.
    var measuring = false
    var draft: [CLLocationCoordinate2D] = []
    var draftClosed = false
    var showFavorites = UserDefaults.standard.object(forKey: "map.showFavorites") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showFavorites, forKey: "map.showFavorites") }
    }
    var showShapes = UserDefaults.standard.object(forKey: "map.showShapes") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showShapes, forKey: "map.showShapes") }
    }
    /// Earthquakes from the Conditions snapshot.
    var showQuakes = UserDefaults.standard.object(forKey: "map.showQuakes") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showQuakes, forKey: "map.showQuakes") }
    }
    /// APRS stations and objects heard by the Ham tab's TNC.
    var showAPRS = UserDefaults.standard.object(forKey: "map.showAPRS") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showAPRS, forKey: "map.showAPRS") }
    }
    /// OpenFreeMap streets as the base map (downloaded, or live when online) when no region map is installed.
    var showStreets = UserDefaults.standard.object(forKey: "map.streets") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showStreets, forKey: "map.streets") }
    }
    var coverage = CoverageLayer.saved() {
        didSet { UserDefaults.standard.set(coverage.rawValue, forKey: "map.coverage") }
    }
    var coverageNetwork = CoverageNetworkFilter(rawValue: UserDefaults.standard.string(forKey: "map.coverageNetwork") ?? "") ?? .both {
        didSet { UserDefaults.standard.set(coverageNetwork.rawValue, forKey: "map.coverageNetwork") }
    }
    var coverageColor = CoverageColorMode(rawValue: UserDefaults.standard.string(forKey: "map.coverageColor") ?? "") ?? .signal {
        didSet { UserDefaults.standard.set(coverageColor.rawValue, forKey: "map.coverageColor") }
    }

    func fly(to c: CLLocationCoordinate2D, zoom: Double) {
        mapView?.setCenter(c, zoomLevel: zoom, animated: true)
    }

    func fit(_ info: PMTilesInfo) {
        let bounds = MLNCoordinateBounds(sw: info.southWest, ne: info.northEast)
        mapView?.setVisibleCoordinateBounds(bounds, edgePadding: UIEdgeInsets(top: 40, left: 20, bottom: 40, right: 20), animated: true, completionHandler: nil)
    }

    /// How the map looks (streets, topo, relief).
    var look = MapLook(rawValue: UserDefaults.standard.string(forKey: "map.look") ?? "") ?? .streets {
        didSet { UserDefaults.standard.set(look.rawValue, forKey: "map.look") }
    }
    /// Recenter button: tap once to follow you, again to turn the map with your heading, again to stop.
    var followMode: MLNUserTrackingMode = .none

    func cycleFollow() {
        let next: MLNUserTrackingMode = switch followMode {
        case .none: .follow
        case .follow: .followWithHeading
        default: .none
        }
        followMode = next
        mapView?.setUserTrackingMode(next, animated: true, completionHandler: nil)
    }

    func followUser() { cycleFollow() }
}

struct OfflineMapView: View {
    @Environment(ContentStore.self) private var content
    @Environment(\.colorScheme) private var scheme
    @State private var controller = MapController()
    @State private var location = LocationService.shared
    @State private var pendingPin: CLLocationCoordinate2D?
    @State private var pinName = ""
    @State private var pinKind: MapPin.Kind = .rally
    @State private var showList = false
    @State private var showCoverageSettings = false
    @State private var mesh = MeshService.shared
    @State private var coverageStore = CoverageStore.shared
    @State private var known = KnownRadioStore.shared
    @State private var showKnownSheet = false
    @State private var shapes = ShapeStore.shared
    @State private var favorites = FavoritesStore.shared
    @State private var navigation = NavigationState.shared
    @State private var compass = NavigationCompass.shared
    @State private var showSearch = LaunchScreen.value == "map:search"
    @State private var showShapesList = false
    @State private var namingShape = false
    @State private var shapeName = ""
    @State private var showStreetsSheet = false
    @State private var showTerrainSheet = false
    @State private var terrain = TerrainStore.shared
    @State private var tappedRadio: KnownRadio?
    @State private var tappedNode: MeshNode?
    @State private var tappedNodeDirect = true
    @State private var streets = StreetMapStore.shared
    @State private var network = NetworkStatus.shared

    /// Both radio layers, worked out from the coverage recordings and live packets.
    /// The phone's own radios are never in either.
    private var radioLayers: HeardRadios.Layers {
        guard controller.showNodes || controller.showMeshKnown else { return HeardRadios.Layers() }
        return HeardRadios.layers(in: mesh, samples: coverageStore.samples)
    }

    /// Radios heard directly, where they say they are.
    private var mappedNodes: [MeshNode] {
        controller.showNodes ? radioLayers.direct : []
    }

    /// Radios only known from adverts passed along the mesh.
    private var meshKnownNodes: [MeshNode] {
        controller.showMeshKnown ? radioLayers.mesh : []
    }

    /// Community-map radios, minus this phone's own (they may have uploaded their adverts).
    private var shownKnown: [KnownRadio] {
        guard controller.showKnown else { return [] }
        let ownTastic = mesh.link(.meshtastic).radio.myNodeNum
        let ownCore = mesh.ownMeshCoreKeyHex.map { "c" + $0 }
        let hidden = HiddenRadioStore.shared
        return known.radios.filter { r in
            if hidden.hides(known: r) { return false }
            if r.firmware == .meshtastic { return ownTastic == 0 || r.id != "t\(ownTastic)" }
            guard let ownCore else { return true }
            return !r.id.lowercased().hasPrefix(ownCore)
        }
    }

    private var baseMap: BaseMap {
        BaseMap.choose(hasRegions: !content.maps.isEmpty, streetsOn: controller.showStreets,
                       hasPacks: streets.hasPacks, online: network.online)
    }

    /// Coverage hexagons: all recordings (private) or only what sharing would publish.
    private var shownCells: [CoverageShare.Cell] {
        switch controller.coverage {
        case .off: []
        case .recorded: coverageStore.privateCells(for: controller.coverageNetwork.network)
        case .shared: coverageStore.sharedCells(for: controller.coverageNetwork.network)
        }
    }

    /// Drive-test results, one per hexagon and network (never a dot per ping).
    private var shownPingCells: [CoverageShare.PingCell] {
        controller.coverage == .recorded ? coverageStore.pingCells(for: controller.coverageNetwork.network) : []
    }

    /// More than one network has recorded anything (or two radios are set up).
    private var twoNetworks: Bool {
        Set(coverageStore.samples.map(\.firmware) + coverageStore.pings.map(\.firmware)).count > 1 || mesh.rememberedLinks.count > 1
    }

    var body: some View {
        NavigationStack {
            MapLibreView(controller: controller, dark: scheme == .dark, regions: content.maps.map(\.url),
                         base: baseMap, streetsKey: streets.pinnedTiles ?? "",
                         look: controller.look, terrainKey: "\(terrain.tileCount)",
                         nodes: mappedNodes, meshNodes: meshKnownNodes, known: shownKnown,
                         aprs: controller.showAPRS ? HamService.shared.stations.filter(\.hasPosition) : [],
                         quakes: controller.showQuakes ? ConditionsStore.shared.nearbyQuakes : [],
                         shapes: controller.showShapes ? shapes.shapes : [],
                         favorites: controller.showFavorites ? favorites.favorites : [],
                         goToLine: goToLine, routeLine: navigation.target?.line.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) } ?? [],
                         draft: controller.draft, draftClosed: controller.draftClosed,
                         coverage: controller.coverage,
                         colorMode: controller.coverageColor,
                         networkKey: controller.coverageNetwork.rawValue,
                         cells: shownCells,
                         pingCells: shownPingCells,
                         onTapRadio: { id in tappedRadio = known.radios.first { $0.id == id } },
                         onTapNode: { id in
                             if let n = mappedNodes.first(where: { $0.listID == id }) {
                                 tappedNode = n
                                 tappedNodeDirect = true
                             } else {
                                 tappedNode = meshKnownNodes.first { $0.listID == id }
                                 tappedNodeDirect = false
                             }
                         }) { coord in
                pendingPin = coord
                pinName = ""
            }
            .ignoresSafeArea(edges: .top)
            .overlay(alignment: .topTrailing) {
                VStack(spacing: 10) {
                    mapButton("magnifyingglass") { showSearch = true }
                    mapButton(controller.followMode == .followWithHeading ? "location.north.line.fill" : controller.followMode == .follow ? "location.fill" : "location") { controller.cycleFollow() }
                    mapButton("list.bullet") { showList = true }
                    mapButton(controller.measuring ? "ruler.fill" : "ruler") { controller.measuring.toggle() }
                    layersMenu
                }
                .padding(.trailing, 12)
                .padding(.top, 60)
            }
            .overlay(alignment: .bottom) {
                if navigation.target != nil, !controller.measuring {
                    GoToPanel().padding(.horizontal, 12).padding(.bottom, 96)
                }
            }
            .overlay(alignment: .bottom) {
                if controller.measuring {
                    MeasurePanel(controller: controller, onSave: { shapeName = ""; namingShape = true }, onList: { showShapesList = true })
                        .padding(.horizontal, 12).padding(.bottom, 96)
                }
            }
            .overlay(alignment: .bottomLeading) {
                VStack(alignment: .leading, spacing: 8) {
                  if !controller.measuring {
                    if controller.coverage != .off { coverageLegend }
                    if (controller.showNodes && (!mappedNodes.isEmpty || !coverageStore.isEmpty))
                        || (controller.showMeshKnown && !meshKnownNodes.isEmpty)
                        || (controller.showKnown && !known.radios.isEmpty) { radioLegend }
                    if controller.showKnown && known.radios.isEmpty { knownPrompt }
                  }
                }
                .padding(.leading, 12)
                .padding(.bottom, 100)
            }
            .overlay(alignment: .bottom) {
                if content.maps.isEmpty && baseMap == .protomaps {
                    Button { showStreetsSheet = true } label: {
                        Text("World overview only. Tap to download streets around you, or add a region map in Library › Get Content.")
                            .font(.footnote).foregroundStyle(.primary).padding(10)
                            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
                    }
                    .padding()
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if baseMap != .protomaps {
                    // OpenFreeMap and OpenStreetMap ask for visible credit.
                    Text(OpenFreeMap.attribution)
                        .font(.system(size: 10)).padding(.horizontal, 5).padding(.vertical, 2)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 4))
                        .padding(.trailing, 34).padding(.bottom, 6) // clear of MapLibre's (i) button
                        .allowsHitTesting(false)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .onAppear {
                location.begin()
                streets.start()
            }
            .onDisappear { location.end() }
            .sheet(isPresented: Binding(get: { pendingPin != nil }, set: { if !$0 { pendingPin = nil } })) {
                pinEditor
            }
            .sheet(isPresented: $showList) {
                MapListSheet(controller: controller)
            }
            .sheet(isPresented: $showSearch) {
                MapSearchSheet(controller: controller) { coord in pendingPin = coord; pinName = "" }
            }
            .sheet(isPresented: $showShapesList) {
                ShapesListSheet(controller: controller)
            }
            .alert("Save drawing", isPresented: $namingShape) {
                TextField("Name", text: $shapeName)
                Button("Save") { saveDraft() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(controller.draftClosed && controller.draft.count >= 3 ? "Saved as an area." : "Saved as a line.")
            }
            .sheet(isPresented: $showCoverageSettings) {
                CoverageSettingsView()
            }
            .sheet(isPresented: $showKnownSheet) {
                KnownRadiosSheet(mapCenter: controller.mapView?.centerCoordinate)
            }
            .sheet(isPresented: $showTerrainSheet) {
                TerrainSheet(mapCenter: controller.mapView?.centerCoordinate ?? controller.mapView?.userLocation?.coordinate)
            }
            .sheet(isPresented: $showStreetsSheet) {
                StreetMapsSheet(mapCenter: controller.mapView?.centerCoordinate)
            }
            .sheet(item: $tappedRadio) { radio in
                KnownRadioDetail(radio: radio).presentationDetents([.height(330)])
            }
            .sheet(item: $tappedNode) { node in
                HeardRadioDetail(node: node, direct: tappedNodeDirect).presentationDetents([.height(370)])
            }
        }
    }

    /// A dashed line from you to the waypoint you're heading for.
    private var goToLine: [CLLocationCoordinate2D] {
        guard let t = navigation.target, let loc = compass.location else { return [] }
        return [loc.coordinate, CLLocationCoordinate2D(latitude: t.current.latitude, longitude: t.current.longitude)]
    }

    private func saveDraft() {
        let pts = controller.draft.map { MapShape.Coordinate(latitude: $0.latitude, longitude: $0.longitude) }
        guard pts.count >= 2 else { return }
        let name = shapeName.trimmingCharacters(in: .whitespaces)
        let shape = MapShape(name: name.isEmpty ? "Drawing \(shapes.shapes.count + 1)" : name, colorIndex: shapes.shapes.count % ShapeMath.palette.count,
                             points: pts, closed: controller.draftClosed && pts.count >= 3)
        shapes.add(shape)
        controller.draft = []
        controller.draftClosed = false
    }

    private var layersMenu: some View {
        Menu {
            Toggle(isOn: $controller.showNodes) { Label("Radios heard directly", systemImage: "antenna.radiowaves.left.and.right") }
            Toggle(isOn: $controller.showMeshKnown) { Label("Known on the mesh (relayed)", systemImage: "point.3.connected.trianglepath.dotted") }
            Toggle(isOn: $controller.showKnown) { Label("Known radios (community maps)", systemImage: "dot.radiowaves.up.forward") }
            Toggle(isOn: $controller.showAPRS) { Label("Ham APRS stations heard", systemImage: "mappin.and.ellipse") }
            Toggle(isOn: $controller.showFavorites) { Label("Favorites", systemImage: "star.fill") }
            Button { showSearch = true } label: { Label("Search and favorites…", systemImage: "magnifyingglass") }
            Toggle(isOn: $controller.showShapes) { Label("My drawings", systemImage: "scribble.variable") }
            Button { showShapesList = true } label: { Label("Drawings…", systemImage: "list.bullet.rectangle") }
            Toggle(isOn: $controller.showQuakes) { Label("Earthquakes (Conditions)", systemImage: "waveform.path.ecg") }
            Button { showKnownSheet = true } label: { Label("Download known radios…", systemImage: "arrow.down.circle") }
            Picker(selection: $controller.look) {
                ForEach(MapLook.allCases) { Label($0.label, systemImage: $0.symbol).tag($0) }
            } label: { Label("Map look", systemImage: "mountain.2") }
            .pickerStyle(.menu)
            Button { showTerrainSheet = true } label: { Label("Download elevation (hills, line of sight)…", systemImage: "arrow.down.circle") }
            Toggle(isOn: $controller.showStreets) { Label("Street map (OpenFreeMap)", systemImage: "road.lanes") }
            Button { showStreetsSheet = true } label: { Label("Download streets around me…", systemImage: "map") }
            Picker(selection: $controller.coverage) {
                ForEach(CoverageLayer.allCases) { Text($0.label).tag($0) }
            } label: { Label("Coverage hexagons", systemImage: "circle.hexagongrid") }
            .pickerStyle(.menu)
            if controller.coverage != .off {
                Picker(selection: $controller.coverageNetwork) {
                    ForEach(CoverageNetworkFilter.allCases) { Text($0.label).tag($0) }
                } label: { Label("Coverage networks", systemImage: "point.3.connected.trianglepath.dotted") }
                .pickerStyle(.menu)
                Picker(selection: $controller.coverageColor) {
                    ForEach(CoverageColorMode.allCases) { Text($0.label).tag($0) }
                } label: { Label("Coverage colours", systemImage: "paintpalette") }
                .pickerStyle(.menu)
            }
            Button { showCoverageSettings = true } label: { Label("Coverage settings…", systemImage: "gearshape") }
        } label: {
            Image(systemName: "square.3.layers.3d").font(.title3).frame(width: 48, height: 48)
                .background(.regularMaterial, in: Circle())
        }
        .accessibilityLabel("Map layers")
    }

    private var coverageLegend: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text((controller.coverage == .shared ? "Would be shared" : "Heard here (private)")
                 + (controller.coverageNetwork == .both ? "" : " · \(controller.coverageNetwork.network?.displayName ?? "")"))
                .font(.caption.weight(.semibold))
            if controller.coverageColor == .signal {
                HStack(spacing: 6) {
                    ForEach(Array(CoverageColors.stops.enumerated()), id: \.offset) { _, stop in
                        Circle().fill(Color(uiColor: stop.1)).frame(width: 10, height: 10)
                    }
                    Text("weak → strong").font(.caption2).foregroundStyle(.secondary)
                }
            }
            if controller.coverageColor == .network || twoNetworks {
                // Network colours: the fill in "colour by network", otherwise the hexagon edge.
                HStack(spacing: 8) {
                    if controller.coverageNetwork.includes(.meshtastic) { networkKey(RadioColors.meshtastic, "Meshtastic") }
                    if controller.coverageNetwork.includes(.meshcore) { networkKey(RadioColors.meshcore, "MeshCore") }
                    if controller.coverageNetwork == .both { networkKey(.white, "Both") }
                }
                if controller.coverageColor == .signal {
                    Text("Edge: network").font(.caption2).foregroundStyle(.secondary)
                }
            }
            if controller.coverage == .recorded && !coverageStore.pings.isEmpty {
                Text("Drive-test pings (best per hexagon, inner shape)").font(.caption.weight(.semibold)).padding(.top, 2)
                HStack(spacing: 8) {
                    legendDot(PingColors.reached, "Answered")
                    legendDot(PingColors.relayed, "Repeated")
                    legendDot(PingColors.none, "Nothing")
                }
            }
            if controller.coverage == .recorded && coverageStore.samples.isEmpty && coverageStore.pings.isEmpty {
                Text(coverageStore.recording ? "Nothing yet. Connect a radio and move around." : "Recording is off.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    /// Shown while the known-radios layer is on but nothing has been downloaded.
    private var knownPrompt: some View {
        Button { showKnownSheet = true } label: {
            Label(known.downloading ? "Downloading known radios…" : "Get known radios near you", systemImage: "arrow.down.circle")
                .font(.caption.weight(.semibold))
        }
        .padding(10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    private var radioLegend: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Mesh radios").font(.caption.weight(.semibold))
            HStack(spacing: 10) {
                legendDot(RadioColors.meshtastic, "Meshtastic")
                legendDot(RadioColors.meshcore, "MeshCore")
            }
            HStack(spacing: 6) {
                Circle().fill(Color.gray).frame(width: 12, height: 12).overlay(Circle().stroke(.white, lineWidth: 2))
                Text("Repeater or router").font(.caption2)
                Circle().fill(Color.gray).frame(width: 7, height: 7)
                Text("Other").font(.caption2)
            }
            Label(mesh.rememberedLinks.count > 1 ? "Heard by your radios" : "Heard by your radio", systemImage: "antenna.radiowaves.left.and.right").font(.caption2)
            if controller.showNodes {
                Text(HeardRadios.summary(mappedNodes)).font(.caption2).foregroundStyle(.secondary)
                if let note = HeardRadios.unidentifiedSummary(radioLayers.unidentified) {
                    Text(note).font(.caption2).foregroundStyle(.secondary).lineLimit(2)
                }
            }
            if controller.showMeshKnown {
                HStack(spacing: 6) {
                    Circle().fill(Color.gray.opacity(0.45)).frame(width: 8, height: 8)
                    Text("Known on the mesh (\(meshKnownNodes.count), not heard directly)").font(.caption2)
                }
            }
        }
        .foregroundStyle(.primary)
        .padding(10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    /// A network swatch: filled when colouring by network, a ring otherwise.
    private func networkKey(_ color: UIColor, _ label: String) -> some View {
        HStack(spacing: 4) {
            if controller.coverageColor == .network {
                Circle().fill(Color(uiColor: color)).frame(width: 10, height: 10)
            } else {
                Circle().strokeBorder(Color(uiColor: color), lineWidth: 2.5).frame(width: 12, height: 12)
            }
            Text(label).font(.caption2)
        }
    }

    private func legendDot(_ color: UIColor, _ label: String) -> some View {
        HStack(spacing: 4) {
            Circle().fill(Color(uiColor: color)).frame(width: 10, height: 10)
            Text(label).font(.caption2)
        }
    }

    private func mapButton(_ symbol: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol).font(.title3).frame(width: 48, height: 48)
                .background(.regularMaterial, in: Circle())
        }
    }

    private var pinEditor: some View {
        NavigationStack {
            Form {
                TextField("Name", text: $pinName)
                Picker("Type", selection: $pinKind) {
                    ForEach(MapPin.Kind.allCases) { Label($0.label, systemImage: $0.symbol).tag($0) }
                }
                if let c = pendingPin {
                    Text(CoordinateFormat.decimal(c)).font(.footnote.monospaced()).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Drop a pin")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { pendingPin = nil } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if let c = pendingPin {
                            controller.pins.append(MapPin(name: pinName.isEmpty ? pinKind.label : pinName, kind: pinKind,
                                                          latitude: c.latitude, longitude: c.longitude))
                        }
                        pendingPin = nil
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

private struct MapListSheet: View {
    let controller: MapController
    @Environment(ContentStore.self) private var content
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section("Saved places") {
                    if controller.pins.isEmpty {
                        Text("Press and hold on the map to drop a pin for water, shelter or a rally point.").foregroundStyle(.secondary)
                    }
                    ForEach(controller.pins) { pin in
                        Button {
                            controller.fly(to: pin.coordinate, zoom: 15)
                            dismiss()
                        } label: {
                            Label {
                                VStack(alignment: .leading) {
                                    Text(pin.name).foregroundStyle(.primary)
                                    Text(CoordinateFormat.decimal(pin.coordinate)).font(.caption.monospaced()).foregroundStyle(.secondary)
                                }
                            } icon: { Image(systemName: pin.kind.symbol) }
                        }
                        .swipeActions {
                            Button("Go to") { NavigationState.shared.start(name: pin.name, latitude: pin.latitude, longitude: pin.longitude); dismiss() }.tint(.green)
                            Button("Favorite") { FavoritesStore.shared.toggle(name: pin.name, latitude: pin.latitude, longitude: pin.longitude) }.tint(.yellow)
                        }
                    }
                    .onDelete { controller.pins.remove(atOffsets: $0) }
                }
                Section("Map regions") {
                    ForEach(content.maps) { map in
                        Button {
                            if let info = PMTilesInfo(url: map.url) { controller.fit(info) }
                            dismiss()
                        } label: {
                            LabeledContent(map.name, value: map.size.byteString)
                        }
                    }
                    Button("World") { controller.fly(to: .init(latitude: 20, longitude: 0), zoom: 1); dismiss() }
                }
            }
            .navigationTitle("Places")
            .toolbar { Button("Done") { dismiss() } }
        }
    }
}

/// Drive-test ping colours: answered, repeated only, nothing.
enum PingColors {
    static let reached = UIColor(red: 0.20, green: 0.75, blue: 0.30, alpha: 1)
    static let relayed = UIColor(red: 0.99, green: 0.75, blue: 0.10, alpha: 1)
    static let none = UIColor(red: 0.85, green: 0.16, blue: 0.16, alpha: 1)
    static var expression: NSExpression {
        NSExpression(format: "TERNARY(outcome == 'reached', %@, TERNARY(outcome == 'relayed', %@, %@))", reached, relayed, none)
    }
}

/// Signal colours, weak (red) to strong (green), by SNR in dB.
enum CoverageColors {
    static let stops: [(Double, UIColor)] = [
        (-15, UIColor(red: 0.85, green: 0.16, blue: 0.16, alpha: 1)),
        (-7, UIColor(red: 0.99, green: 0.52, blue: 0.05, alpha: 1)),
        (0, UIColor(red: 0.99, green: 0.80, blue: 0.10, alpha: 1)),
        (8, UIColor(red: 0.30, green: 0.78, blue: 0.33, alpha: 1)),
    ]
    static var expression: NSExpression {
        let dict = Dictionary(uniqueKeysWithValues: stops.map { (NSNumber(value: $0.0), $0.1) })
        return NSExpression(format: "mgl_interpolate:withCurveType:parameters:stops:(snr, 'linear', nil, %@)", dict)
    }
}

/// One colour per mesh system, shared by the map dots, live node icons and legend.
enum RadioColors {
    static let meshtastic = UIColor(red: 0.40, green: 0.85, blue: 0.47, alpha: 1)
    static let meshcore = UIColor(red: 0.25, green: 0.55, blue: 0.98, alpha: 1)
    static func color(_ firmware: MeshFirmware) -> UIColor { firmware == .meshcore ? meshcore : meshtastic }
    /// By a feature's "fw" attribute: meshtastic, meshcore or both (white).
    static var expression: NSExpression {
        NSExpression(format: "TERNARY(fw == 'meshcore', %@, TERNARY(fw == 'both', %@, %@))", meshcore, UIColor.white, meshtastic)
    }
}

struct MapLibreView: UIViewRepresentable {
    let controller: MapController
    let dark: Bool
    let regions: [URL]
    var base: BaseMap = .protomaps
    /// Changes when the downloaded streets' tile version does, so the style is rebuilt.
    var streetsKey = ""
    var look: MapLook = .streets
    /// Changes when saved elevation tiles do, so the hillshade source is rebuilt.
    var terrainKey = ""
    var nodes: [MeshNode] = []
    /// Radios only known from relayed adverts: drawn faint and small, no names.
    var meshNodes: [MeshNode] = []
    var known: [KnownRadio] = []
    /// APRS stations heard by the Ham TNC.
    var aprs: [HamStation] = []
    /// Recent earthquakes near the user (from Conditions).
    var quakes: [Quake] = []
    /// Saved drawings and the one being drawn.
    var shapes: [MapShape] = []
    var favorites: [Favorite] = []
    /// From the user to the current Go to waypoint (empty when not navigating).
    var goToLine: [CLLocationCoordinate2D] = []
    /// The planned road route, when one is active.
    var routeLine: [CLLocationCoordinate2D] = []
    var draft: [CLLocationCoordinate2D] = []
    var draftClosed = false
    var coverage: CoverageLayer = .off
    var colorMode: CoverageColorMode = .signal
    /// Changes when the network filter does, so the layer is rebuilt.
    var networkKey = ""
    var cells: [CoverageShare.Cell] = []
    var pingCells: [CoverageShare.PingCell] = []
    var onTapRadio: (String) -> Void = { _ in }
    /// A heard radio was tapped (its `listID`).
    var onTapNode: (String) -> Void = { _ in }
    let onLongPress: (CLLocationCoordinate2D) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> MLNMapView {
        let view = MLNMapView(frame: .zero)
        view.delegate = context.coordinator
        view.showsUserLocation = true
        view.compassView.isHidden = false
        view.logoView.isHidden = true
        view.attributionButton.isHidden = false
        view.setCenter(.init(latitude: 39, longitude: -98), zoomLevel: 2.5, animated: false)
        let press = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.longPress(_:)))
        view.addGestureRecognizer(press)
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tap(_:)))
        tap.delegate = context.coordinator
        view.addGestureRecognizer(tap)
        controller.mapView = view
        context.coordinator.applyStyle(to: view)
        return view
    }

    func updateUIView(_ view: MLNMapView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.applyStyle(to: view)
        context.coordinator.syncPins(on: view)
        context.coordinator.syncNodes(on: view)
        context.coordinator.syncCoverage(on: view)
        context.coordinator.syncKnown(on: view)
        context.coordinator.syncAPRS(on: view)
        context.coordinator.syncQuakes(on: view)
        context.coordinator.syncShapes(on: view)
        context.coordinator.syncNavigation(on: view)
        context.coordinator.syncTerrain(on: view)
    }

    @MainActor
    final class Coordinator: NSObject, MLNMapViewDelegate, UIGestureRecognizerDelegate {
        var parent: MapLibreView
        private var styleKey = ""
        private var shownPins: [UUID: MLNPointAnnotation] = [:]
        private var nodesKey = ""
        private var coverageKey = ""
        private var knownKey = ""
        private var aprsKey = ""
        private var quakesKey = ""
        private var shapesKey = ""
        private var navKey = ""
        private var terrainSynced = ""
        private var regionsFitted = false
        /// Set once the map has centred on the first good GPS fix, so the region
        /// fit doesn't pull it away and later fixes don't fight the user's panning.
        private var snappedToUser = false

        init(_ parent: MapLibreView) { self.parent = parent }

        func applyStyle(to view: MLNMapView) {
            let key = "\(parent.dark)|\(parent.base)|\(parent.streetsKey)|\(parent.regions.map(\.path).joined(separator: ","))"
            guard key != styleKey else { return }
            styleKey = key
            // Overlays (radios, coverage, known radios) go back on in didFinishLoading.
            if let streets = StreetMapStore.shared.styleURL(for: parent.base, dark: parent.dark) {
                view.styleURL = streets
            } else if let url = try? MapStyle.write(dark: parent.dark, regions: parent.regions) {
                view.styleURL = url
            }
            if !regionsFitted, let first = parent.regions.first, let info = PMTilesInfo(url: first) {
                regionsFitted = true
                // Fit the whole region once the view has its size, so a city map opens at
                // street level and a state map shows the state.
                DispatchQueue.main.async { [weak self] in
                    guard self?.snappedToUser != true else { return }
                    let bounds = MLNCoordinateBounds(sw: info.southWest, ne: info.northEast)
                    if view.bounds.width > 0 {
                        view.setVisibleCoordinateBounds(bounds, edgePadding: UIEdgeInsets(top: 40, left: 20, bottom: 80, right: 20), animated: false)
                    } else {
                        view.setCenter(info.center, zoomLevel: Double(max(info.centerZoom, 8)), animated: false)
                    }
                }
            }
        }

        func syncPins(on view: MLNMapView) {
            let pins = parent.controller.pins
            let ids = Set(pins.map(\.id))
            for (id, annotation) in shownPins where !ids.contains(id) {
                view.removeAnnotation(annotation)
                shownPins[id] = nil
            }
            for pin in pins where shownPins[pin.id] == nil {
                let a = MLNPointAnnotation()
                a.coordinate = pin.coordinate
                a.title = pin.name
                a.subtitle = "\(pin.kind.label) · \(CoordinateFormat.decimal(pin.coordinate))"
                view.addAnnotation(a)
                shownPins[pin.id] = a
            }
        }

        /// Heard radios as a circle layer (network colour; repeaters larger with a
        /// white ring, like the community-map radios) with their names beside them.
        func syncNodes(on view: MLNMapView) {
            guard let style = view.style else { return }
            let key = (parent.nodes.map { "d\($0.listID)|\($0.displayName)|\($0.latitude ?? 0)|\($0.longitude ?? 0)|\($0.isRepeater)" }
                + parent.meshNodes.map { "m\($0.listID)|\($0.displayName)|\($0.latitude ?? 0)|\($0.longitude ?? 0)|\($0.isRepeater)" })
                .sorted().joined(separator: ";")
            guard key != nodesKey else { return }
            nodesKey = key
            func feature(_ node: MeshNode, direct: Bool) -> MLNPointFeature? {
                guard let lat = node.latitude, let lon = node.longitude else { return nil }
                let f = MLNPointFeature()
                f.coordinate = CLLocationCoordinate2D(latitude: lat, longitude: lon)
                f.attributes = ["lid": node.listID, "fw": node.firmware.rawValue, "big": node.isRepeater ? 1 : 0,
                                "name": direct ? node.displayName : "", "far": direct ? 0 : 1]
                return f
            }
            // Known-on-the-mesh first, so heard-directly dots draw on top.
            let features = parent.meshNodes.compactMap { feature($0, direct: false) } + parent.nodes.compactMap { feature($0, direct: true) }
            if let source = style.source(withIdentifier: "heard-radios") as? MLNShapeSource {
                source.shape = MLNShapeCollectionFeature(shapes: features)
                return
            }
            let source = MLNShapeSource(identifier: "heard-radios", features: features, options: nil)
            style.addSource(source)
            let dots = MLNCircleStyleLayer(identifier: "heard-radios", source: source)
            dots.circleColor = RadioColors.expression
            dots.circleRadius = NSExpression(format: "TERNARY(far == 1, TERNARY(big == 1, 5, 3.5), TERNARY(big == 1, 9, 6.5))")
            dots.circleStrokeColor = NSExpression(format: "TERNARY(big == 1, %@, %@)", UIColor.white, UIColor.black.withAlphaComponent(0.5))
            dots.circleStrokeWidth = NSExpression(format: "TERNARY(far == 1, 0.5, TERNARY(big == 1, 3, 1))")
            dots.circleOpacity = NSExpression(format: "TERNARY(far == 1, 0.45, 1)")
            style.addLayer(dots)
            let labels = MLNSymbolStyleLayer(identifier: "heard-radio-names", source: source)
            labels.text = NSExpression(forKeyPath: "name")
            labels.textFontNames = NSExpression(forConstantValue: ["Noto Sans Regular"])
            labels.textFontSize = NSExpression(forConstantValue: 12)
            labels.textAnchor = NSExpression(forConstantValue: "top")
            labels.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: 0, dy: 0.9)))
            labels.textColor = RadioColors.expression
            labels.textHaloColor = NSExpression(forConstantValue: parent.dark ? UIColor.black : UIColor.white)
            labels.textHaloWidth = NSExpression(forConstantValue: 1.5)
            labels.textOptional = NSExpression(forConstantValue: true)
            style.addLayer(labels)
        }

        /// Shaded relief under the roads and labels, from the saved (or live) elevation tiles.
        func syncTerrain(on view: MLNMapView) {
            guard let style = view.style else { return }
            let key = "\(parent.look.rawValue)|\(parent.terrainKey)|\(parent.dark)"
            guard key != terrainSynced else { return }
            terrainSynced = key
            if let old = style.layer(withIdentifier: "terrain-hillshade") { style.removeLayer(old) }
            if let old = style.source(withIdentifier: "terrain-dem") { style.removeSource(old) }
            guard parent.look.hillshadeOpacity > 0 else { return }
            let source = MLNRasterDEMSource(identifier: "terrain-dem", tileURLTemplates: [TerrainStore.shared.template],
                                            options: [.tileSize: 256, .minimumZoomLevel: 0, .maximumZoomLevel: 11,
                                                      .demEncoding: NSNumber(value: MLNDEMEncoding.terrarium.rawValue)])
            style.addSource(source)
            let layer = MLNHillshadeStyleLayer(identifier: "terrain-hillshade", source: source)
            layer.hillshadeExaggeration = NSExpression(forConstantValue: parent.look == .relief ? 0.9 : 0.35)
            // Above the land and water fills, below the roads, boundaries and labels.
            if let firstLine = style.layers.first(where: { $0 is MLNLineStyleLayer || $0 is MLNSymbolStyleLayer }) {
                style.insertLayer(layer, below: firstLine)
            } else {
                style.addLayer(layer)
            }
        }

        /// Favorites (gold stars with names) and the line to the Go to waypoint.
        func syncNavigation(on view: MLNMapView) {
            guard let style = view.style else { return }
            let key = parent.favorites.map { "\($0.id)|\($0.name)|\($0.latitude)|\($0.longitude)" }.joined(separator: ";")
                + "#" + parent.goToLine.map { "\($0.latitude),\($0.longitude)" }.joined(separator: ";")
                + "#" + String(parent.routeLine.count) + "|" + (parent.routeLine.first.map { "\($0.latitude)" } ?? "")
            guard key != navKey else { return }
            navKey = key
            let stars: [MLNPointFeature] = parent.favorites.map { f in
                let p = MLNPointFeature()
                p.coordinate = CLLocationCoordinate2D(latitude: f.latitude, longitude: f.longitude)
                p.attributes = ["name": f.name]
                return p
            }
            var lineShapes: [MLNShape & MLNFeature] = []
            if parent.routeLine.count >= 2 {
                var rc = parent.routeLine
                let rf = MLNPolylineFeature(coordinates: &rc, count: UInt(rc.count))
                rf.attributes = ["kind": "route"]
                lineShapes.append(rf)
            }
            if parent.goToLine.count == 2 {
                var c = parent.goToLine
                lineShapes.append(MLNPolylineFeature(coordinates: &c, count: 2))
                let t = MLNPointFeature()
                t.coordinate = parent.goToLine[1]
                lineShapes.append(t)
            }
            if let s = style.source(withIdentifier: "favorites") as? MLNShapeSource {
                s.shape = MLNShapeCollectionFeature(shapes: stars)
                (style.source(withIdentifier: "goto") as? MLNShapeSource)?.shape = MLNShapeCollectionFeature(shapes: lineShapes)
                return
            }
            let favSource = MLNShapeSource(identifier: "favorites", shape: MLNShapeCollectionFeature(shapes: stars), options: nil)
            let goSource = MLNShapeSource(identifier: "goto", shape: MLNShapeCollectionFeature(shapes: lineShapes), options: nil)
            style.addSource(favSource)
            style.addSource(goSource)
            let line = MLNLineStyleLayer(identifier: "goto-line", source: goSource)
            line.predicate = NSPredicate(format: "$geometryType != 'Point' AND kind != 'route'")
            line.lineColor = NSExpression(forConstantValue: UIColor.systemGreen)
            line.lineWidth = NSExpression(forConstantValue: 4)
            line.lineDashPattern = NSExpression(forConstantValue: [1.5, 1.5])
            style.addLayer(line)
            let routeLayer = MLNLineStyleLayer(identifier: "route-line", source: goSource)
            routeLayer.predicate = NSPredicate(format: "kind == 'route'")
            routeLayer.lineColor = NSExpression(forConstantValue: UIColor.systemBlue)
            routeLayer.lineWidth = NSExpression(forConstantValue: 6)
            routeLayer.lineCap = NSExpression(forConstantValue: "round")
            routeLayer.lineJoin = NSExpression(forConstantValue: "round")
            style.insertLayer(routeLayer, below: line)
            let target = MLNCircleStyleLayer(identifier: "goto-target", source: goSource)
            target.predicate = NSPredicate(format: "$geometryType == 'Point'")
            target.circleRadius = NSExpression(forConstantValue: 11)
            target.circleColor = NSExpression(forConstantValue: UIColor.clear)
            target.circleStrokeColor = NSExpression(forConstantValue: UIColor.systemGreen)
            target.circleStrokeWidth = NSExpression(forConstantValue: 4)
            style.addLayer(target)
            let dots = MLNCircleStyleLayer(identifier: "favorite-dots", source: favSource)
            dots.circleRadius = NSExpression(forConstantValue: 8)
            dots.circleColor = NSExpression(forConstantValue: UIColor(red: 1, green: 0.8, blue: 0.1, alpha: 1))
            dots.circleStrokeColor = NSExpression(forConstantValue: UIColor.black)
            dots.circleStrokeWidth = NSExpression(forConstantValue: 2)
            style.addLayer(dots)
            let names = MLNSymbolStyleLayer(identifier: "favorite-names", source: favSource)
            names.text = NSExpression(forKeyPath: "name")
            names.textFontNames = NSExpression(forConstantValue: ["Noto Sans Regular"])
            names.textFontSize = NSExpression(forConstantValue: 12)
            names.textAnchor = NSExpression(forConstantValue: "top")
            names.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: 0, dy: 0.9)))
            names.textColor = NSExpression(forConstantValue: UIColor(red: 1, green: 0.8, blue: 0.1, alpha: 1))
            names.textHaloColor = NSExpression(forConstantValue: parent.dark ? UIColor.black : UIColor.white)
            names.textHaloWidth = NSExpression(forConstantValue: 1.5)
            names.textOptional = NSExpression(forConstantValue: true)
            style.addLayer(names)
        }

        /// Saved drawings (lines and areas, in their colours, with names) and the draft being drawn.
        func syncShapes(on view: MLNMapView) {
            guard let style = view.style else { return }
            let key = parent.shapes.map { "\($0.id)|\($0.name)|\($0.colorIndex)|\($0.points.count)" }.joined(separator: ";")
                + "#" + parent.draft.map { "\($0.latitude),\($0.longitude)" }.joined(separator: ";") + "#\(parent.draftClosed)"
            guard key != shapesKey else { return }
            shapesKey = key
            func line(_ c: [CLLocationCoordinate2D], ci: Int) -> MLNPolylineFeature {
                var cc = c
                let f = MLNPolylineFeature(coordinates: &cc, count: UInt(cc.count))
                f.attributes = ["cidx": ci]
                return f
            }
            func polygon(_ c: [CLLocationCoordinate2D], ci: Int) -> MLNPolygonFeature {
                var cc = c
                let f = MLNPolygonFeature(coordinates: &cc, count: UInt(cc.count))
                f.attributes = ["cidx": ci]
                return f
            }
            var saved: [MLNShape & MLNFeature] = []
            var labels: [MLNPointFeature] = []
            for s in parent.shapes where s.points.count >= 2 {
                saved.append(s.isArea ? polygon(s.coordinates, ci: s.colorIndex) : line(s.coordinates, ci: s.colorIndex))
                let c = ShapeMath.centre(s.points)
                let l = MLNPointFeature()
                l.coordinate = CLLocationCoordinate2D(latitude: c.latitude, longitude: c.longitude)
                l.attributes = ["name": s.name, "cidx": s.colorIndex]
                labels.append(l)
            }
            var draftFeatures: [MLNShape & MLNFeature] = []
            if parent.draft.count >= 2 {
                draftFeatures.append(parent.draftClosed && parent.draft.count >= 3 ? polygon(parent.draft, ci: 0) : line(parent.draft, ci: 0))
            }
            for c in parent.draft {
                let p = MLNPointFeature()
                p.coordinate = c
                draftFeatures.append(p)
            }
            let palette = ShapeMath.palette.map { UIColor(red: CGFloat(($0.1 >> 16) & 0xFF) / 255, green: CGFloat(($0.1 >> 8) & 0xFF) / 255, blue: CGFloat($0.1 & 0xFF) / 255, alpha: 1) }
            let colour = NSExpression(format: "TERNARY(cidx == 0, %@, TERNARY(cidx == 1, %@, TERNARY(cidx == 2, %@, TERNARY(cidx == 3, %@, TERNARY(cidx == 4, %@, %@)))))",
                                      palette[0], palette[1], palette[2], palette[3], palette[4], palette[5])
            if let s = style.source(withIdentifier: "shapes") as? MLNShapeSource {
                s.shape = MLNShapeCollectionFeature(shapes: saved)
                (style.source(withIdentifier: "shape-labels") as? MLNShapeSource)?.shape = MLNShapeCollectionFeature(shapes: labels)
                (style.source(withIdentifier: "shape-draft") as? MLNShapeSource)?.shape = MLNShapeCollectionFeature(shapes: draftFeatures)
                return
            }
            let source = MLNShapeSource(identifier: "shapes", shape: MLNShapeCollectionFeature(shapes: saved), options: nil)
            let labelSource = MLNShapeSource(identifier: "shape-labels", shape: MLNShapeCollectionFeature(shapes: labels), options: nil)
            let draftSource = MLNShapeSource(identifier: "shape-draft", shape: MLNShapeCollectionFeature(shapes: draftFeatures), options: nil)
            [source, labelSource, draftSource].forEach { style.addSource($0) }
            let fill = MLNFillStyleLayer(identifier: "shape-fills", source: source)
            fill.predicate = NSPredicate(format: "$geometryType == 'Polygon'")
            fill.fillColor = colour
            fill.fillOpacity = NSExpression(forConstantValue: 0.25)
            style.addLayer(fill)
            let outline = MLNLineStyleLayer(identifier: "shape-lines", source: source)
            outline.lineColor = colour
            outline.lineWidth = NSExpression(forConstantValue: 3)
            outline.lineCap = NSExpression(forConstantValue: "round")
            outline.lineJoin = NSExpression(forConstantValue: "round")
            style.addLayer(outline)
            let names = MLNSymbolStyleLayer(identifier: "shape-names", source: labelSource)
            names.text = NSExpression(forKeyPath: "name")
            names.textFontNames = NSExpression(forConstantValue: ["Noto Sans Regular"])
            names.textFontSize = NSExpression(forConstantValue: 12)
            names.textColor = colour
            names.textHaloColor = NSExpression(forConstantValue: parent.dark ? UIColor.black : UIColor.white)
            names.textHaloWidth = NSExpression(forConstantValue: 1.5)
            names.textOptional = NSExpression(forConstantValue: true)
            style.addLayer(names)
            let dfill = MLNFillStyleLayer(identifier: "draft-fill", source: draftSource)
            dfill.predicate = NSPredicate(format: "$geometryType == 'Polygon'")
            dfill.fillColor = NSExpression(forConstantValue: UIColor.systemYellow)
            dfill.fillOpacity = NSExpression(forConstantValue: 0.2)
            style.addLayer(dfill)
            let dline = MLNLineStyleLayer(identifier: "draft-line", source: draftSource)
            dline.predicate = NSPredicate(format: "$geometryType != 'Point'")
            dline.lineColor = NSExpression(forConstantValue: UIColor.systemYellow)
            dline.lineWidth = NSExpression(forConstantValue: 3)
            dline.lineDashPattern = NSExpression(forConstantValue: [2, 1.5])
            style.addLayer(dline)
            let dots = MLNCircleStyleLayer(identifier: "draft-points", source: draftSource)
            dots.predicate = NSPredicate(format: "$geometryType == 'Point'")
            dots.circleRadius = NSExpression(forConstantValue: 6)
            dots.circleColor = NSExpression(forConstantValue: UIColor.systemYellow)
            dots.circleStrokeColor = NSExpression(forConstantValue: UIColor.black)
            dots.circleStrokeWidth = NSExpression(forConstantValue: 2)
            style.addLayer(dots)
        }

        /// Earthquakes as rings sized and coloured by magnitude, under everything else.
        func syncQuakes(on view: MLNMapView) {
            guard let style = view.style else { return }
            let key = parent.quakes.map { "\($0.id)|\($0.magnitude)" }.joined(separator: ";")
            guard key != quakesKey else { return }
            quakesKey = key
            let features: [MLNPointFeature] = parent.quakes.map { q in
                let f = MLNPointFeature()
                f.coordinate = CLLocationCoordinate2D(latitude: q.latitude, longitude: q.longitude)
                f.attributes = ["r": 4 + q.magnitude * 2.2, "big": q.magnitude >= 5 ? 2 : (q.magnitude >= 4 ? 1 : 0),
                                "label": String(format: "M%.1f", q.magnitude)]
                return f
            }
            if let source = style.source(withIdentifier: "quakes") as? MLNShapeSource {
                source.shape = MLNShapeCollectionFeature(shapes: features)
                return
            }
            let source = MLNShapeSource(identifier: "quakes", features: features, options: nil)
            style.addSource(source)
            let rings = MLNCircleStyleLayer(identifier: "quakes", source: source)
            rings.circleRadius = NSExpression(forKeyPath: "r")
            rings.circleColor = NSExpression(format: "TERNARY(big == 2, %@, TERNARY(big == 1, %@, %@))",
                                             UIColor.systemRed, UIColor.systemOrange, UIColor.systemYellow)
            rings.circleOpacity = NSExpression(forConstantValue: 0.35)
            rings.circleStrokeColor = NSExpression(format: "TERNARY(big == 2, %@, TERNARY(big == 1, %@, %@))",
                                                   UIColor.systemRed, UIColor.systemOrange, UIColor.systemYellow)
            rings.circleStrokeWidth = NSExpression(forConstantValue: 1.5)
            style.insertLayer(rings, at: 0)
            let labels = MLNSymbolStyleLayer(identifier: "quake-labels", source: source)
            labels.text = NSExpression(forKeyPath: "label")
            labels.textFontNames = NSExpression(forConstantValue: ["Noto Sans Regular"])
            labels.textFontSize = NSExpression(forConstantValue: 11)
            labels.textColor = NSExpression(forConstantValue: UIColor.systemOrange)
            labels.textHaloColor = NSExpression(forConstantValue: parent.dark ? UIColor.black : UIColor.white)
            labels.textHaloWidth = NSExpression(forConstantValue: 1.5)
            labels.textOptional = NSExpression(forConstantValue: true)
            style.addLayer(labels)
        }

        /// APRS stations as purple dots (teal for weather, red in an emergency) with their callsigns.
        func syncAPRS(on view: MLNMapView) {
            guard let style = view.style else { return }
            let key = parent.aprs.map { "\($0.id)|\($0.latitude ?? 0)|\($0.longitude ?? 0)|\($0.emergency)" }.joined(separator: ";")
            guard key != aprsKey else { return }
            aprsKey = key
            let features: [MLNPointFeature] = parent.aprs.compactMap { s in
                guard let lat = s.latitude, let lon = s.longitude else { return nil }
                let f = MLNPointFeature()
                f.coordinate = CLLocationCoordinate2D(latitude: lat, longitude: lon)
                f.attributes = ["name": s.id, "sos": s.emergency ? 1 : 0, "wx": s.weather != nil ? 1 : 0]
                return f
            }
            if let source = style.source(withIdentifier: "aprs-stations") as? MLNShapeSource {
                source.shape = MLNShapeCollectionFeature(shapes: features)
                return
            }
            let source = MLNShapeSource(identifier: "aprs-stations", features: features, options: nil)
            style.addSource(source)
            let dots = MLNCircleStyleLayer(identifier: "aprs-stations", source: source)
            dots.circleColor = NSExpression(format: "TERNARY(sos == 1, %@, TERNARY(wx == 1, %@, %@))",
                                            UIColor.systemRed, UIColor.systemTeal, UIColor.systemPurple)
            dots.circleRadius = NSExpression(forConstantValue: 6)
            dots.circleStrokeColor = NSExpression(forConstantValue: UIColor.white)
            dots.circleStrokeWidth = NSExpression(forConstantValue: 2)
            style.addLayer(dots)
            let labels = MLNSymbolStyleLayer(identifier: "aprs-station-names", source: source)
            labels.text = NSExpression(forKeyPath: "name")
            labels.textFontNames = NSExpression(forConstantValue: ["Noto Sans Regular"])
            labels.textFontSize = NSExpression(forConstantValue: 11)
            labels.textAnchor = NSExpression(forConstantValue: "top")
            labels.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: 0, dy: 0.8)))
            labels.textColor = NSExpression(forConstantValue: UIColor.systemPurple)
            labels.textHaloColor = NSExpression(forConstantValue: parent.dark ? UIColor.black : UIColor.white)
            labels.textHaloWidth = NSExpression(forConstantValue: 1.5)
            labels.textOptional = NSExpression(forConstantValue: true)
            style.addLayer(labels)
        }

        /// Rebuilds the coverage hexagons when the data or mode changes. Only
        /// hexagons are ever drawn: points would trace the route you drove.
        func syncCoverage(on view: MLNMapView) {
            guard let style = view.style else { return }
            let pingKey = parent.pingCells.map { "\($0.q),\($0.r),\($0.firmware.rawValue),\($0.best.rawValue)" }.joined(separator: ";")
            let key = "\(parent.coverage.rawValue)|\(parent.colorMode.rawValue)|\(parent.networkKey)|\(parent.cells.count)|\(parent.cells.map(\.count).reduce(0, +))|\(pingKey.hashValue)"
            guard key != coverageKey else { return }
            coverageKey = key
            for id in ["coverage-cells", "coverage-outline", "coverage-ping-cells", "coverage-ping-outline"] {
                if let layer = style.layer(withIdentifier: id) { style.removeLayer(layer) }
            }
            for id in ["coverage", "coverage-ping-source"] {
                if let source = style.source(withIdentifier: id) { style.removeSource(source) }
            }
            guard parent.coverage != .off else { return }
            // Coverage sits under the heard radios, so their dots and names stay readable.
            let below = style.layer(withIdentifier: "heard-radios")
            func add(_ layer: MLNStyleLayer) {
                if let below { style.insertLayer(layer, below: below) } else { style.addLayer(layer) }
            }

            let features: [MLNPolygonFeature] = parent.cells.map { cell in
                var coords = cell.corners.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
                let f = MLNPolygonFeature(coordinates: &coords, count: UInt(coords.count))
                // A hexagon with only pings is coloured as if signal matched its best ping.
                let snr: Double = if let best = cell.bestSNR { Double(best) }
                    else if cell.pingsReached > 0 { 8 } else if cell.pingsRelayed > 0 { 0 } else { -15 }
                let fw = cell.firmwares.count > 1 ? "both" : (cell.firmwares.first?.rawValue ?? "meshtastic")
                f.attributes = ["snr": snr, "fw": fw]
                return f
            }
            let source = MLNShapeSource(identifier: "coverage", features: features, options: nil)
            style.addSource(source)
            let byNetwork = parent.colorMode == .network
            let fill = MLNFillStyleLayer(identifier: "coverage-cells", source: source)
            fill.fillColor = byNetwork ? RadioColors.expression : CoverageColors.expression
            fill.fillOpacity = NSExpression(forConstantValue: 0.45)
            add(fill)
            // The edge says which networks were heard in the hexagon (white: both).
            let outline = MLNLineStyleLayer(identifier: "coverage-outline", source: source)
            outline.lineColor = RadioColors.expression
            outline.lineWidth = NSExpression(forConstantValue: byNetwork ? 1.2 : 2)
            add(outline)

            // Drive-test results: a smaller hexagon inside each one, filled by the
            // best outcome, edged by the network. With both networks in one
            // hexagon, MeshCore's sits inside Meshtastic's.
            guard !parent.pingCells.isEmpty else { return }
            let shared = Set(parent.pingCells.filter { $0.firmware == .meshtastic }.map { "\($0.q),\($0.r)" })
            let pingFeatures: [MLNPolygonFeature] = parent.pingCells.map { cell in
                let inner = cell.firmware == .meshcore && shared.contains("\(cell.q),\(cell.r)")
                var coords = cell.corners(scale: inner ? 0.35 : 0.6).map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
                let f = MLNPolygonFeature(coordinates: &coords, count: UInt(coords.count))
                f.attributes = ["outcome": cell.best.rawValue, "fw": cell.firmware.rawValue]
                return f
            }
            let pingSource = MLNShapeSource(identifier: "coverage-ping-source", features: pingFeatures, options: nil)
            style.addSource(pingSource)
            let pingFill = MLNFillStyleLayer(identifier: "coverage-ping-cells", source: pingSource)
            pingFill.fillColor = PingColors.expression
            pingFill.fillOpacity = NSExpression(forConstantValue: 0.85)
            add(pingFill)
            let pingOutline = MLNLineStyleLayer(identifier: "coverage-ping-outline", source: pingSource)
            pingOutline.lineColor = RadioColors.expression
            pingOutline.lineWidth = NSExpression(forConstantValue: 2.5)
            add(pingOutline)
        }

        /// Community-map radios as one circle layer (thousands of annotations would be slow).
        func syncKnown(on view: MLNMapView) {
            guard let style = view.style else { return }
            let key = "\(parent.known.count)|\(parent.known.first?.id ?? "")|\(parent.known.last?.id ?? "")"
            guard key != knownKey else { return }
            knownKey = key
            if let layer = style.layer(withIdentifier: "known-radios") { style.removeLayer(layer) }
            if let source = style.source(withIdentifier: "known-radios") { style.removeSource(source) }
            guard !parent.known.isEmpty else { return }
            let features: [MLNPointFeature] = parent.known.map { r in
                let f = MLNPointFeature()
                f.coordinate = CLLocationCoordinate2D(latitude: r.latitude, longitude: r.longitude)
                // "id" is reserved in GeoJSON and dropped from attributes, so the radio id goes in "rid".
                f.attributes = ["rid": r.id, "fw": r.firmware.rawValue, "big": r.role == .repeater || r.role == .room ? 1 : 0]
                return f
            }
            let source = MLNShapeSource(identifier: "known-radios", features: features, options: nil)
            style.addSource(source)
            let layer = MLNCircleStyleLayer(identifier: "known-radios", source: source)
            layer.circleColor = NSExpression(format: "TERNARY(fw == 'meshcore', %@, %@)", RadioColors.meshcore, RadioColors.meshtastic)
            layer.circleRadius = NSExpression(format: "TERNARY(big == 1, 6.5, 4)")
            layer.circleStrokeColor = NSExpression(format: "TERNARY(big == 1, %@, %@)", UIColor.white, UIColor.black.withAlphaComponent(0.35))
            layer.circleStrokeWidth = NSExpression(format: "TERNARY(big == 1, 2, 0.5)")
            layer.circleOpacity = NSExpression(forConstantValue: 0.9)
            // Under the coverage layers and heard radios so those stay readable.
            if let below = style.layer(withIdentifier: "coverage-cells") ?? style.layer(withIdentifier: "heard-radios") {
                style.insertLayer(layer, below: below)
            } else {
                style.addLayer(layer)
            }
        }

        @objc func tap(_ g: UITapGestureRecognizer) {
            guard let view = g.view as? MLNMapView else { return }
            let p = g.location(in: view)
            if parent.controller.measuring {
                parent.controller.draft.append(view.convert(p, toCoordinateFrom: view))
                return
            }
            let box = CGRect(x: p.x - 12, y: p.y - 12, width: 24, height: 24)
            if let f = view.visibleFeatures(in: box, styleLayerIdentifiers: ["heard-radios"]).first,
               let id = f.attribute(forKey: "lid") as? String {
                parent.onTapNode(id)
            } else if let f = view.visibleFeatures(in: box, styleLayerIdentifiers: ["known-radios"]).first,
               let id = f.attribute(forKey: "rid") as? String {
                parent.onTapRadio(id)
            }
        }

        nonisolated func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

        @objc func longPress(_ g: UILongPressGestureRecognizer) {
            guard g.state == .began, let view = g.view as? MLNMapView else { return }
            parent.onLongPress(view.convert(g.location(in: view), toCoordinateFrom: view))
        }

        nonisolated func mapView(_ mapView: MLNMapView, annotationCanShowCallout annotation: any MLNAnnotation) -> Bool { true }

        /// On the first reasonable fix, centre on the user at street level and follow.
        /// Panning stops following (MapLibre's normal behaviour); the location button resumes it.
        nonisolated func mapView(_ mapView: MLNMapView, didUpdate userLocation: MLNUserLocation?) {
            guard let fix = userLocation?.location, fix.horizontalAccuracy >= 0, fix.horizontalAccuracy <= 500 else { return }
            MainActor.assumeIsolated {
                guard !snappedToUser else { return }
                snappedToUser = true
                mapView.setCenter(fix.coordinate, zoomLevel: max(mapView.zoomLevel, 14), animated: false)
                mapView.setUserTrackingMode(.follow, animated: false, completionHandler: nil)
            }
        }

        nonisolated func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            MainActor.assumeIsolated {
                syncPins(on: mapView)
                nodesKey = "" // a new style has no heard-radio, coverage or known-radio layer yet
                coverageKey = ""
                knownKey = ""
                aprsKey = ""
                quakesKey = ""
                shapesKey = ""
                navKey = ""
                terrainSynced = ""
                syncTerrain(on: mapView)
                syncNodes(on: mapView)
                syncCoverage(on: mapView)
                syncKnown(on: mapView)
                syncAPRS(on: mapView)
                syncQuakes(on: mapView)
                syncShapes(on: mapView)
                syncNavigation(on: mapView)
            }
        }
    }
}
