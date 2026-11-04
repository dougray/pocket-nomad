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

@MainActor @Observable
final class MapController {
    weak var mapView: MLNMapView?
    var pins = MapPin.load() { didSet { MapPin.save(pins) } }
    var showNodes = UserDefaults.standard.object(forKey: "map.showNodes") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showNodes, forKey: "map.showNodes") }
    }
    var coverage = CoverageLayer(rawValue: UserDefaults.standard.string(forKey: "map.coverage") ?? "") ?? .off {
        didSet { UserDefaults.standard.set(coverage.rawValue, forKey: "map.coverage") }
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

    private var mappedNodes: [MeshNode] {
        controller.showNodes ? mesh.nodes.values.filter { $0.hasPosition && $0.num != mesh.myNodeNum } : []
    }

    var body: some View {
        NavigationStack {
            MapLibreView(controller: controller, dark: scheme == .dark, regions: content.maps.map(\.url),
                         nodes: mappedNodes, coverage: controller.coverage,
                         samples: controller.coverage == .recorded ? coverageStore.samples : [],
                         cells: controller.coverage == .shared ? coverageStore.sharedCells : []) { coord in
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
                if controller.coverage != .off { coverageLegend }
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
        }
    }

    private var layersMenu: some View {
        Menu {
            Toggle(isOn: $controller.showNodes) { Label("Mesh nodes", systemImage: "antenna.radiowaves.left.and.right") }
            Picker(selection: $controller.coverage) {
                ForEach(CoverageLayer.allCases) { Text($0.label).tag($0) }
            } label: { Label("Coverage", systemImage: "circle.hexagongrid") }
            .pickerStyle(.menu)
            Button { showCoverageSettings = true } label: { Label("Coverage settings…", systemImage: "gearshape") }
        } label: {
            Image(systemName: "square.3.layers.3d").font(.title3).frame(width: 48, height: 48)
                .background(.regularMaterial, in: Circle())
        }
        .accessibilityLabel("Map layers")
    }

    private var coverageLegend: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(controller.coverage == .shared ? "Would be shared" : "Heard here (private)").font(.caption.weight(.semibold))
            HStack(spacing: 6) {
                ForEach(Array(CoverageColors.stops.enumerated()), id: \.offset) { _, stop in
                    Circle().fill(Color(uiColor: stop.1)).frame(width: 10, height: 10)
                }
                Text("weak → strong").font(.caption2).foregroundStyle(.secondary)
            }
            if controller.coverage == .recorded && coverageStore.samples.isEmpty {
                Text(coverageStore.recording ? "Nothing yet. Connect a radio and move around." : "Recording is off.")
                    .font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
        .padding(.leading, 12)
        .padding(.bottom, 100)
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

/// A mesh node on the map (drawn with its own icon, unlike saved places).
final class NodeAnnotation: MLNPointAnnotation {
    var nodeNum: UInt32 = 0
}

struct MapLibreView: UIViewRepresentable {
    let controller: MapController
    let dark: Bool
    let regions: [URL]
    var nodes: [MeshNode] = []
    var coverage: CoverageLayer = .off
    var samples: [CoverageSample] = []
    var cells: [CoverageShare.Cell] = []
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
    }

    @MainActor
    final class Coordinator: NSObject, MLNMapViewDelegate {
        var parent: MapLibreView
        private var styleKey = ""
        private var shownPins: [UUID: MLNPointAnnotation] = [:]
        private var shownNodes: [UInt32: NodeAnnotation] = [:]
        private var coverageKey = ""
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
            let wanted = Dictionary(uniqueKeysWithValues: parent.nodes.map { ($0.num, $0) })
            for (num, a) in shownNodes where wanted[num] == nil {
                view.removeAnnotation(a)
                shownNodes[num] = nil
            }
            for node in wanted.values {
                guard let lat = node.latitude, let lon = node.longitude else { continue }
                let coord = CLLocationCoordinate2D(latitude: lat, longitude: lon)
                let heard = node.lastHeard.map { "heard " + $0.formatted(.relative(presentation: .named)) } ?? "not heard yet"
                let subtitle = [node.meshCoreType == nil ? "Meshtastic" : "MeshCore", heard].joined(separator: " · ")
                if let a = shownNodes[node.num] {
                    if a.coordinate.latitude != lat || a.coordinate.longitude != lon { a.coordinate = coord }
                    a.title = node.displayName
                    a.subtitle = subtitle
                } else {
                    let a = NodeAnnotation()
                    a.nodeNum = node.num
                    a.coordinate = coord
                    a.title = node.displayName
                    a.subtitle = subtitle
                    view.addAnnotation(a)
                    shownNodes[node.num] = a
                }
            }
        }

        /// Rebuilds the coverage source and layer when the data or mode changes.
        func syncCoverage(on view: MLNMapView) {
            guard let style = view.style else { return }
            let key = "\(parent.coverage.rawValue)|\(parent.samples.count)|\(parent.cells.count)|\(parent.cells.map(\.count).reduce(0, +))"
            guard key != coverageKey else { return }
            coverageKey = key
            for id in ["coverage-points", "coverage-cells", "coverage-outline"] {
                if let layer = style.layer(withIdentifier: id) { style.removeLayer(layer) }
            }
            if let source = style.source(withIdentifier: "coverage") { style.removeSource(source) }

            switch parent.coverage {
            case .off:
                return
            case .recorded:
                let features: [MLNPointFeature] = parent.samples.map { s in
                    let f = MLNPointFeature()
                    f.coordinate = CLLocationCoordinate2D(latitude: s.latitude, longitude: s.longitude)
                    f.attributes = ["snr": Double(s.snr ?? -10)]
                    return f
                }
                let source = MLNShapeSource(identifier: "coverage", features: features, options: nil)
                style.addSource(source)
                let layer = MLNCircleStyleLayer(identifier: "coverage-points", source: source)
                layer.circleRadius = NSExpression(forConstantValue: 6)
                layer.circleColor = CoverageColors.expression
                layer.circleOpacity = NSExpression(forConstantValue: 0.85)
                layer.circleStrokeColor = NSExpression(forConstantValue: UIColor.black.withAlphaComponent(0.4))
                layer.circleStrokeWidth = NSExpression(forConstantValue: 1)
                style.addLayer(layer)
            case .shared:
                let features: [MLNPolygonFeature] = parent.cells.map { cell in
                    var coords = cell.corners.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
                    let f = MLNPolygonFeature(coordinates: &coords, count: UInt(coords.count))
                    f.attributes = ["snr": Double(cell.bestSNR ?? -10)]
                    return f
                }
                let source = MLNShapeSource(identifier: "coverage", features: features, options: nil)
                style.addSource(source)
                let fill = MLNFillStyleLayer(identifier: "coverage-cells", source: source)
                fill.fillColor = CoverageColors.expression
                fill.fillOpacity = NSExpression(forConstantValue: 0.45)
                style.addLayer(fill)
                let outline = MLNLineStyleLayer(identifier: "coverage-outline", source: source)
                outline.lineColor = CoverageColors.expression
                outline.lineWidth = NSExpression(forConstantValue: 1.2)
                style.addLayer(outline)
            }
        }

        private lazy var nodeImage: UIImage = {
            let size = CGSize(width: 30, height: 30)
            return UIGraphicsImageRenderer(size: size).image { _ in
                UIColor(red: 0.988, green: 0.592, blue: 0.071, alpha: 1).setFill()
                UIBezierPath(ovalIn: CGRect(origin: .zero, size: size).insetBy(dx: 1, dy: 1)).fill()
                let config = UIImage.SymbolConfiguration(pointSize: 14, weight: .bold)
                if let symbol = UIImage(systemName: "antenna.radiowaves.left.and.right", withConfiguration: config)?
                    .withTintColor(.black, renderingMode: .alwaysOriginal) {
                    symbol.draw(at: CGPoint(x: (size.width - symbol.size.width) / 2, y: (size.height - symbol.size.height) / 2))
                }
            }
        }()

        nonisolated func mapView(_ mapView: MLNMapView, imageFor annotation: any MLNAnnotation) -> MLNAnnotationImage? {
            guard annotation is NodeAnnotation else { return nil }
            let image = MainActor.assumeIsolated { nodeImage }
            return MLNAnnotationImage(image: image, reuseIdentifier: "mesh-node")
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
                coverageKey = "" // a new style has no coverage layer yet
                syncCoverage(on: mapView)
            }
        }
    }
}
