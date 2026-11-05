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
    var label: String {
        switch self {
        case .off: "Off"
        case .recorded: "My recordings (private)"
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

/// What colours the coverage dots and hexagons.
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
    var showKnown = UserDefaults.standard.object(forKey: "map.showKnown") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showKnown, forKey: "map.showKnown") }
    }
    var coverage = CoverageLayer(rawValue: UserDefaults.standard.string(forKey: "map.coverage") ?? "") ?? .off {
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

    func followUser() {
        mapView?.setUserTrackingMode(.follow, animated: true, completionHandler: nil)
    }
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
    @State private var tappedRadio: KnownRadio?

    /// Radios heard by either connected radio (each network's own radio left out).
    private var mappedNodes: [MeshNode] {
        guard controller.showNodes else { return [] }
        return mesh.links.flatMap { l in l.nodes.values.filter { $0.hasPosition && $0.num != l.radio.myNodeNum } }
    }

    private var shownSamples: [CoverageSample] {
        controller.coverage == .recorded ? coverageStore.samples.filter { controller.coverageNetwork.includes($0.firmware) } : []
    }

    private var shownPings: [CoveragePing] {
        controller.coverage == .recorded ? coverageStore.pings.filter { controller.coverageNetwork.includes($0.firmware) } : []
    }

    /// More than one network has recorded anything (or two radios are set up).
    private var twoNetworks: Bool {
        Set(coverageStore.samples.map(\.firmware) + coverageStore.pings.map(\.firmware)).count > 1 || mesh.rememberedLinks.count > 1
    }

    var body: some View {
        NavigationStack {
            MapLibreView(controller: controller, dark: scheme == .dark, regions: content.maps.map(\.url),
                         nodes: mappedNodes, known: controller.showKnown ? known.radios : [],
                         coverage: controller.coverage,
                         colorMode: controller.coverageColor,
                         networkKey: controller.coverageNetwork.rawValue,
                         samples: shownSamples,
                         pings: shownPings,
                         cells: controller.coverage == .shared ? coverageStore.sharedCells(for: controller.coverageNetwork.network) : [],
                         onTapRadio: { id in tappedRadio = known.radios.first { $0.id == id } }) { coord in
                pendingPin = coord
                pinName = ""
            }
            .ignoresSafeArea(edges: .top)
            .overlay(alignment: .topTrailing) {
                VStack(spacing: 10) {
                    mapButton("location.fill") { controller.followUser() }
                    mapButton("list.bullet") { showList = true }
                    layersMenu
                }
                .padding(.trailing, 12)
                .padding(.top, 60)
            }
            .overlay(alignment: .bottomLeading) {
                VStack(alignment: .leading, spacing: 8) {
                    if controller.coverage != .off { coverageLegend }
                    if !mappedNodes.isEmpty || (controller.showKnown && !known.radios.isEmpty) { radioLegend }
                    if controller.showKnown && known.radios.isEmpty { knownPrompt }
                }
                .padding(.leading, 12)
                .padding(.bottom, 100)
            }
            .overlay(alignment: .bottom) {
                if content.maps.isEmpty {
                    Text("World overview only. Add a region map in Library › Get Content for street-level detail.")
                        .font(.footnote).padding(10)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
                        .padding()
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .onAppear { location.begin() }
            .onDisappear { location.end() }
            .sheet(isPresented: Binding(get: { pendingPin != nil }, set: { if !$0 { pendingPin = nil } })) {
                pinEditor
            }
            .sheet(isPresented: $showList) {
                MapListSheet(controller: controller)
            }
            .sheet(isPresented: $showCoverageSettings) {
                CoverageSettingsView()
            }
            .sheet(isPresented: $showKnownSheet) {
                KnownRadiosSheet(mapCenter: controller.mapView?.centerCoordinate)
            }
            .sheet(item: $tappedRadio) { radio in
                KnownRadioDetail(radio: radio).presentationDetents([.height(260)])
            }
        }
    }

    private var layersMenu: some View {
        Menu {
            Toggle(isOn: $controller.showNodes) { Label("Radios I've heard", systemImage: "antenna.radiowaves.left.and.right") }
            Toggle(isOn: $controller.showKnown) { Label("Known radios (community maps)", systemImage: "dot.radiowaves.up.forward") }
            Button { showKnownSheet = true } label: { Label("Download known radios…", systemImage: "arrow.down.circle") }
            Picker(selection: $controller.coverage) {
                ForEach(CoverageLayer.allCases) { Text($0.label).tag($0) }
            } label: { Label("Coverage", systemImage: "circle.hexagongrid") }
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
                // Network colours: the fill in "colour by network", otherwise the ring or hexagon edge.
                HStack(spacing: 8) {
                    if controller.coverageNetwork.includes(.meshtastic) { networkKey(RadioColors.meshtastic, "Meshtastic") }
                    if controller.coverageNetwork.includes(.meshcore) { networkKey(RadioColors.meshcore, "MeshCore") }
                    if controller.coverage == .shared && controller.coverageNetwork == .both { networkKey(.white, "Both") }
                }
                if controller.coverageColor == .signal {
                    Text(controller.coverage == .shared ? "Edge: network" : "Ring: network").font(.caption2).foregroundStyle(.secondary)
                }
            }
            if controller.coverage == .recorded && !coverageStore.pings.isEmpty {
                Text("Drive-test pings").font(.caption.weight(.semibold)).padding(.top, 2)
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

/// A mesh node on the map (drawn with its own icon, unlike saved places).
final class NodeAnnotation: MLNPointAnnotation {
    var nodeNum: UInt32 = 0
    var firmware: MeshFirmware = .meshtastic
}

struct MapLibreView: UIViewRepresentable {
    let controller: MapController
    let dark: Bool
    let regions: [URL]
    var nodes: [MeshNode] = []
    var known: [KnownRadio] = []
    var coverage: CoverageLayer = .off
    var colorMode: CoverageColorMode = .signal
    /// Changes when the network filter does, so the layer is rebuilt.
    var networkKey = ""
    var samples: [CoverageSample] = []
    var pings: [CoveragePing] = []
    var cells: [CoverageShare.Cell] = []
    var onTapRadio: (String) -> Void = { _ in }
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
    }

    @MainActor
    final class Coordinator: NSObject, MLNMapViewDelegate, UIGestureRecognizerDelegate {
        var parent: MapLibreView
        private var styleKey = ""
        private var shownPins: [UUID: MLNPointAnnotation] = [:]
        /// By network and number: node numbers are only unique per network.
        private var shownNodes: [String: NodeAnnotation] = [:]
        private var coverageKey = ""
        private var knownKey = ""
        private var regionsFitted = false
        /// Set once the map has centred on the first good GPS fix, so the region
        /// fit doesn't pull it away and later fixes don't fight the user's panning.
        private var snappedToUser = false

        init(_ parent: MapLibreView) { self.parent = parent }

        func applyStyle(to view: MLNMapView) {
            let key = "\(parent.dark)|\(parent.regions.map(\.path).joined(separator: ","))"
            guard key != styleKey else { return }
            styleKey = key
            if let url = try? MapStyle.write(dark: parent.dark, regions: parent.regions) {
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

        func syncNodes(on view: MLNMapView) {
            let wanted = Dictionary(parent.nodes.map { ($0.listID, $0) }, uniquingKeysWith: { a, _ in a })
            for (key, a) in shownNodes where wanted[key] == nil {
                view.removeAnnotation(a)
                shownNodes[key] = nil
            }
            for node in wanted.values {
                guard let lat = node.latitude, let lon = node.longitude else { continue }
                let coord = CLLocationCoordinate2D(latitude: lat, longitude: lon)
                let heard = node.lastHeard.map { "heard " + $0.formatted(.relative(presentation: .named)) } ?? "not heard yet"
                let firmware = node.firmware
                let subtitle = [firmware.displayName, heard].joined(separator: " · ")
                if let a = shownNodes[node.listID], a.firmware == firmware {
                    if a.coordinate.latitude != lat || a.coordinate.longitude != lon { a.coordinate = coord }
                    a.title = node.displayName
                    a.subtitle = subtitle
                } else {
                    if let old = shownNodes[node.listID] { view.removeAnnotation(old) }
                    let a = NodeAnnotation()
                    a.nodeNum = node.num
                    a.firmware = firmware
                    a.coordinate = coord
                    a.title = node.displayName
                    a.subtitle = subtitle
                    view.addAnnotation(a)
                    shownNodes[node.listID] = a
                }
            }
        }

        /// Rebuilds the coverage source and layer when the data or mode changes.
        func syncCoverage(on view: MLNMapView) {
            guard let style = view.style else { return }
            let key = "\(parent.coverage.rawValue)|\(parent.colorMode.rawValue)|\(parent.networkKey)|\(parent.samples.count)|\(parent.pings.count)|\(parent.cells.count)|\(parent.cells.map(\.count).reduce(0, +))"
            guard key != coverageKey else { return }
            coverageKey = key
            for id in ["coverage-points", "coverage-pings", "coverage-cells", "coverage-outline"] {
                if let layer = style.layer(withIdentifier: id) { style.removeLayer(layer) }
            }
            for id in ["coverage", "coverage-ping-source"] {
                if let source = style.source(withIdentifier: id) { style.removeSource(source) }
            }

            switch parent.coverage {
            case .off:
                return
            case .recorded:
                let features: [MLNPointFeature] = parent.samples.map { s in
                    let f = MLNPointFeature()
                    f.coordinate = CLLocationCoordinate2D(latitude: s.latitude, longitude: s.longitude)
                    f.attributes = ["snr": Double(s.snr ?? -10), "fw": s.firmware.rawValue]
                    return f
                }
                let source = MLNShapeSource(identifier: "coverage", features: features, options: nil)
                style.addSource(source)
                let layer = MLNCircleStyleLayer(identifier: "coverage-points", source: source)
                layer.circleRadius = NSExpression(forConstantValue: 6)
                layer.circleOpacity = NSExpression(forConstantValue: 0.85)
                if parent.colorMode == .network {
                    layer.circleColor = RadioColors.expression
                    layer.circleStrokeColor = NSExpression(forConstantValue: UIColor.black.withAlphaComponent(0.4))
                    layer.circleStrokeWidth = NSExpression(forConstantValue: 1)
                } else {
                    // Fill is signal; the ring says which radio heard it.
                    layer.circleColor = CoverageColors.expression
                    layer.circleStrokeColor = RadioColors.expression
                    layer.circleStrokeWidth = NSExpression(forConstantValue: 2)
                }
                style.addLayer(layer)
                // Drive-test pings: square-ish larger dots, coloured by what came back.
                let pingFeatures: [MLNPointFeature] = parent.pings.map { p in
                    let f = MLNPointFeature()
                    f.coordinate = CLLocationCoordinate2D(latitude: p.latitude, longitude: p.longitude)
                    f.attributes = ["outcome": p.outcome.rawValue, "fw": p.firmware.rawValue]
                    return f
                }
                let pingSource = MLNShapeSource(identifier: "coverage-ping-source", features: pingFeatures, options: nil)
                style.addSource(pingSource)
                let pings = MLNCircleStyleLayer(identifier: "coverage-pings", source: pingSource)
                pings.circleRadius = NSExpression(forConstantValue: 9)
                pings.circleColor = PingColors.expression
                pings.circleOpacity = NSExpression(forConstantValue: 0.9)
                // Fill is the outcome; the ring is the network that sent the ping.
                pings.circleStrokeColor = RadioColors.expression
                pings.circleStrokeWidth = NSExpression(forConstantValue: 3)
                style.addLayer(pings)
            case .shared:
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
                style.addLayer(fill)
                // The edge says which networks were heard in the hexagon (white: both).
                let outline = MLNLineStyleLayer(identifier: "coverage-outline", source: source)
                outline.lineColor = RadioColors.expression
                outline.lineWidth = NSExpression(forConstantValue: byNetwork ? 1.2 : 2)
                style.addLayer(outline)
            }
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
            // Under the coverage layers so recordings stay readable.
            if let below = style.layer(withIdentifier: "coverage-points") ?? style.layer(withIdentifier: "coverage-cells") {
                style.insertLayer(layer, below: below)
            } else {
                style.addLayer(layer)
            }
        }

        @objc func tap(_ g: UITapGestureRecognizer) {
            guard let view = g.view as? MLNMapView else { return }
            let p = g.location(in: view)
            let box = CGRect(x: p.x - 12, y: p.y - 12, width: 24, height: 24)
            if let f = view.visibleFeatures(in: box, styleLayerIdentifiers: ["known-radios"]).first,
               let id = f.attribute(forKey: "rid") as? String {
                parent.onTapRadio(id)
            }
        }

        nonisolated func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

        private func nodeImage(_ firmware: MeshFirmware) -> UIImage {
            let size = CGSize(width: 30, height: 30)
            return UIGraphicsImageRenderer(size: size).image { _ in
                RadioColors.color(firmware).setFill()
                UIBezierPath(ovalIn: CGRect(origin: .zero, size: size).insetBy(dx: 1, dy: 1)).fill()
                let config = UIImage.SymbolConfiguration(pointSize: 14, weight: .bold)
                if let symbol = UIImage(systemName: "antenna.radiowaves.left.and.right", withConfiguration: config)?
                    .withTintColor(.black, renderingMode: .alwaysOriginal) {
                    symbol.draw(at: CGPoint(x: (size.width - symbol.size.width) / 2, y: (size.height - symbol.size.height) / 2))
                }
            }
        }

        nonisolated func mapView(_ mapView: MLNMapView, imageFor annotation: any MLNAnnotation) -> MLNAnnotationImage? {
            guard let node = annotation as? NodeAnnotation else { return nil }
            let firmware = node.firmware
            let id = "mesh-node-\(firmware.rawValue)"
            let image = MainActor.assumeIsolated { nodeImage(firmware) }
            return MLNAnnotationImage(image: image, reuseIdentifier: id)
        }

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
                syncNodes(on: mapView)
                coverageKey = "" // a new style has no coverage or known-radio layer yet
                knownKey = ""
                syncCoverage(on: mapView)
                syncKnown(on: mapView)
            }
        }
    }
}
