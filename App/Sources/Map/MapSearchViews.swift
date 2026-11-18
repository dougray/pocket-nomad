// MapSearchViews.swift
// Pocket Nomad - Map module
// SPDX-License-Identifier: Apache-2.0
//
// Search by coordinates, favorites, quick spots, and the Go to panel.

import CoreLocation
import SwiftUI

/// Star and Go to for any place with a position. Used on radio, station and pin screens.
struct PlaceActions: View {
    let name: String
    let latitude: Double
    let longitude: Double
    var onGoTo: (() -> Void)?
    @State private var favorites = FavoritesStore.shared
    @State private var showDirections = false
    @State private var showPath = false

    var body: some View {
        let isFavorite = favorites.isFavorite(latitude: latitude, longitude: longitude)
        VStack(alignment: .leading, spacing: 8) {
        HStack(spacing: 10) {
            Button { favorites.toggle(name: name, latitude: latitude, longitude: longitude) } label: {
                Label(isFavorite ? "Favorited" : "Favorite", systemImage: isFavorite ? "star.fill" : "star")
            }
            Button {
                NavigationState.shared.start(name: name, latitude: latitude, longitude: longitude)
                onGoTo?()
            } label: { Label("Go to", systemImage: "location.north.line.fill") }
        }
        HStack(spacing: 10) {
            Button { showDirections = true } label: { Label("Route", systemImage: "arrow.triangle.turn.up.right.diamond") }
            Button { showPath = true } label: { Label("Radio path", systemImage: "chart.xyaxis.line") }
        }
        }
        .buttonStyle(.bordered)
        .tint(Theme.accent)
        .sheet(isPresented: $showDirections) { DirectionsSheet(name: name, latitude: latitude, longitude: longitude) }
        .sheet(isPresented: $showPath) { RadioPathSheet(name: name, latitude: latitude, longitude: longitude) }
    }
}

struct MapSearchSheet: View {
    let controller: MapController
    var onDropPin: (CLLocationCoordinate2D) -> Void
    @State private var favorites = FavoritesStore.shared
    @State private var shapes = ShapeStore.shared
    @State private var text = ""
    @AppStorage("map.coordFormat") private var formatRaw = PositionFormat.decimal.rawValue
    @State private var namingFavorite: ParsedCoordinate?
    @State private var favoriteName = ""
    @State private var renaming: Favorite?
    @State private var routeTo: ParsedCoordinate?
    @State private var newName = ""
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: Bool

    private var format: PositionFormat { PositionFormat(rawValue: formatRaw) ?? .decimal }
    private var parsed: ParsedCoordinate? { Coordinates.parse(text) }
    private let metric = ConditionsFormat.current.metric

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("Coordinates, grid, or a saved place", text: $text)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().focused($focused)
                            .submitLabel(.go)
                            .onSubmit { if let p = parsed { show(p) } }
                        if !text.isEmpty { Button { text = "" } label: { Image(systemName: "xmark.circle.fill") }.buttonStyle(.plain).foregroundStyle(.secondary) }
                        Button { text = UIPasteboard.general.string ?? text } label: { Image(systemName: "doc.on.clipboard") }.buttonStyle(.plain)
                    }
                } footer: {
                    Text("Try 39.7392, -104.9903 or 39°44'21\"N 104°59'25\"W or 13S 500831 4398811 or 13SED0083198811 or DM79mr, or paste a map link.")
                }

                if let p = parsed { foundSection(p) }
                else if !text.trimmingCharacters(in: .whitespaces).isEmpty, matches.isEmpty {
                    Section { Text("That doesn't look like a position, and no saved place matches.").foregroundStyle(.secondary) }
                }

                if text.trimmingCharacters(in: .whitespaces).isEmpty { quickSpotsSection }
                favoritesSection
                placesSection
                drawingsSection
            }
            .navigationTitle("Search")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
                ToolbarItem(placement: .topBarLeading) {
                    Menu {
                        Picker("Show positions as", selection: $formatRaw) {
                            ForEach(PositionFormat.allCases) { Text($0.label).tag($0.rawValue) }
                        }
                    } label: { Image(systemName: "textformat.123") }
                }
            }
            .sheet(item: $routeTo) { DirectionsSheet(name: "Position", latitude: $0.latitude, longitude: $0.longitude) }
            .alert("Save favorite", isPresented: Binding(get: { namingFavorite != nil }, set: { if !$0 { namingFavorite = nil } })) {
                TextField("Name", text: $favoriteName)
                Button("Save") {
                    if let p = namingFavorite { favorites.add(name: favoriteName, latitude: p.latitude, longitude: p.longitude) }
                    namingFavorite = nil
                }
                Button("Cancel", role: .cancel) { namingFavorite = nil }
            }
            .alert("Rename", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
                TextField("Name", text: $newName)
                Button("Save") { if let r = renaming { favorites.rename(r.id, to: newName) }; renaming = nil }
                Button("Cancel", role: .cancel) { renaming = nil }
            }
        }
        .onAppear { if LaunchScreen.value == "map:search" { text = "39.7392, -104.9903" } else { focused = true } }
    }

    // MARK: Result

    private func foundSection(_ p: ParsedCoordinate) -> some View {
        Section {
            Text("Found a position (\(p.format.label))").font(.subheadline).foregroundStyle(.secondary)
            ForEach(PositionFormat.allCases.filter { $0 != p.format || true }) { f in
                Button { UIPasteboard.general.string = Coordinates.format(latitude: p.latitude, longitude: p.longitude, as: f) } label: {
                    HStack {
                        Text(f.label).font(.caption).foregroundStyle(.secondary)
                        Spacer()
                        Text(Coordinates.format(latitude: p.latitude, longitude: p.longitude, as: f)).font(.footnote.monospaced()).foregroundStyle(.primary)
                    }
                }
            }
            HStack {
                Button { show(p) } label: { Label("Show", systemImage: "scope") }
                Button { goTo("Position", p.latitude, p.longitude) } label: { Label("Go to", systemImage: "location.north.line.fill") }
                Button { routeTo = p } label: { Label("Route", systemImage: "arrow.triangle.turn.up.right.diamond") }
                Button { dismiss(); onDropPin(.init(latitude: p.latitude, longitude: p.longitude)) } label: { Label("Pin", systemImage: "mappin") }
                Button { favoriteName = ""; namingFavorite = p } label: { Label("★", systemImage: "star") }
            }
            .buttonStyle(.bordered).tint(Theme.accent).labelStyle(.titleAndIcon).font(.footnote)
        } header: { Text("Position") } footer: { Text("Tap a line to copy it.") }
    }

    // MARK: Quick spots

    private var quickSpotsSection: some View {
        Section {
            ForEach([Favorite.Kind.home, .camp, .rally, .car]) { kind in
                let spot = favorites.favorites.first { $0.kind == kind }
                HStack {
                    Label(kind.label, systemImage: kind.symbol).foregroundStyle(spot == nil ? .secondary : .primary)
                    Spacer()
                    Text(spot.map { Coordinates.format(latitude: $0.latitude, longitude: $0.longitude, as: format) } ?? "Not set")
                        .font(.caption.monospaced()).foregroundStyle(.secondary).lineLimit(1)
                    Menu {
                        if let spot {
                            Button { goTo(spot.name, spot.latitude, spot.longitude) } label: { Label("Go to \(kind.label)", systemImage: "location.north.line.fill") }
                            Button { fly(spot.latitude, spot.longitude) } label: { Label("Show on map", systemImage: "scope") }
                        }
                        Button { setHere(kind) } label: { Label(spot == nil ? "Set to where I am" : "Move to where I am", systemImage: "location.fill") }
                        Button { setAtCenter(kind) } label: { Label("Set to the map centre", systemImage: "scope") }
                        if let spot { Button(role: .destructive) { favorites.remove(spot.id) } label: { Label("Clear", systemImage: "trash") } }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
        } header: { Text("Quick spots") } footer: { Text("One each, so \"Go to Car\" is one tap from here.") }
    }

    private func setHere(_ kind: Favorite.Kind) {
        guard let loc = controller.mapView?.userLocation?.location ?? NavigationCompass.shared.location else { return }
        favorites.add(name: kind.label, kind: kind, latitude: loc.coordinate.latitude, longitude: loc.coordinate.longitude)
    }

    private func setAtCenter(_ kind: Favorite.Kind) {
        guard let c = controller.mapView?.centerCoordinate else { return }
        favorites.add(name: kind.label, kind: kind, latitude: c.latitude, longitude: c.longitude)
    }

    // MARK: Lists

    private var matches: [Favorite] { favorites.search(text) }

    private var favoritesSection: some View {
        let list = favorites.search(text).filter { !($0.kind.isQuick && text.trimmingCharacters(in: .whitespaces).isEmpty) }
        return Group {
            if !list.isEmpty {
                Section("Favorites") {
                    ForEach(list) { f in
                        Button { fly(f.latitude, f.longitude) } label: {
                            Label {
                                VStack(alignment: .leading) {
                                    Text(f.name).foregroundStyle(.primary)
                                    Text(Coordinates.format(latitude: f.latitude, longitude: f.longitude, as: format)).font(.caption.monospaced()).foregroundStyle(.secondary)
                                }
                            } icon: { Image(systemName: f.kind.symbol).foregroundStyle(Theme.accent) }
                        }
                        .swipeActions {
                            Button("Delete", role: .destructive) { favorites.remove(f.id) }
                            Button("Go to") { goTo(f.name, f.latitude, f.longitude) }.tint(.green)
                        }
                        .contextMenu {
                            Button { goTo(f.name, f.latitude, f.longitude) } label: { Label("Go to", systemImage: "location.north.line.fill") }
                            Button { renaming = f; newName = f.name } label: { Label("Rename", systemImage: "pencil") }
                            Button(role: .destructive) { favorites.remove(f.id) } label: { Label("Remove", systemImage: "star.slash") }
                        }
                    }
                }
            }
        }
    }

    private var placesSection: some View {
        let q = text.trimmingCharacters(in: .whitespaces)
        let pins = q.isEmpty ? [] : controller.pins.filter { $0.name.localizedCaseInsensitiveContains(q) || $0.kind.label.localizedCaseInsensitiveContains(q) }
        return Group {
            if !pins.isEmpty {
                Section("Places") {
                    ForEach(pins) { pin in
                        Button { fly(pin.latitude, pin.longitude) } label: { Label(pin.name, systemImage: pin.kind.symbol).foregroundStyle(.primary) }
                            .swipeActions {
                                Button("Go to") { goTo(pin.name, pin.latitude, pin.longitude) }.tint(.green)
                                Button("Favorite") { favorites.toggle(name: pin.name, latitude: pin.latitude, longitude: pin.longitude) }.tint(.yellow)
                            }
                    }
                }
            }
        }
    }

    private var drawingsSection: some View {
        let q = text.trimmingCharacters(in: .whitespaces)
        let list = q.isEmpty ? [] : shapes.shapes.filter { $0.name.localizedCaseInsensitiveContains(q) }
        return Group {
            if !list.isEmpty {
                Section("Drawings") {
                    ForEach(list) { s in
                        Button { goToRoute(s) } label: { Label("Go along \(s.name)", systemImage: "point.topleft.down.to.point.bottomright.curvepath").foregroundStyle(.primary) }
                    }
                }
            }
        }
    }

    // MARK: Actions

    private func show(_ p: ParsedCoordinate) { fly(p.latitude, p.longitude) }

    private func fly(_ lat: Double, _ lon: Double) {
        controller.fly(to: .init(latitude: lat, longitude: lon), zoom: 15)
        dismiss()
    }

    private func goTo(_ name: String, _ lat: Double, _ lon: Double) {
        NavigationState.shared.start(name: name, latitude: lat, longitude: lon)
        dismiss()
    }

    private func goToRoute(_ s: MapShape) {
        NavigationState.shared.start(route: s)
        dismiss()
    }
}

/// The compass-navigation panel over the map.
struct GoToPanel: View {
    @State private var nav = NavigationState.shared
    @State private var compass = NavigationCompass.shared
    private let metric = ConditionsFormat.current.metric

    var body: some View {
        if let t = nav.target {
            let here = compass.location.map { MapShape.Coordinate(latitude: $0.coordinate.latitude, longitude: $0.coordinate.longitude) }
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.name).font(.headline).lineLimit(1)
                        if t.isRoute { Text("Waypoint \(t.index + 1) of \(t.waypoints.count)").font(.caption).foregroundStyle(.secondary) }
                    }
                    Spacer()
                    if t.isRoute, !t.isLast { Button("Skip") { nav.skip() }.buttonStyle(.bordered).font(.footnote) }
                    Button("Stop", role: .destructive) { nav.stop() }.buttonStyle(.bordered).font(.footnote)
                }
                if nav.arrived {
                    Label("You've arrived", systemImage: "checkmark.circle.fill").font(.title3.bold()).foregroundStyle(MeshUI.ok)
                } else if let here {
                    let (distance, bearing) = GoToMath.leg(from: here, to: t.current)
                    let total = GoToMath.remaining(t, position: here)
                    HStack(spacing: 16) {
                        Image(systemName: "location.north.fill")
                            .font(.system(size: 44)).foregroundStyle(Theme.accent)
                            .rotationEffect(.degrees(compass.heading.map { GoToMath.arrowAngle(bearing: bearing, heading: $0) } ?? bearing))
                            .frame(width: 60, height: 60)
                            .animation(.easeOut(duration: 0.2), value: compass.heading)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(ShapeMath.distanceText(distance, metric: metric)).font(.title.bold().monospacedDigit())
                            Text("Heading \(ShapeMath.bearingText(bearing)) · \(GoToMath.etaText(GoToMath.eta(distance: total))) on foot")
                                .font(.caption).foregroundStyle(.secondary)
                            if t.isRoute { Text("\(ShapeMath.distanceText(total, metric: metric)) left on the route").font(.caption).foregroundStyle(.secondary) }
                            if compass.heading == nil { Text("No compass: the arrow points from north").font(.caption2).foregroundStyle(.secondary) }
                        }
                    }
                } else {
                    HStack { ProgressView(); Text("Waiting for GPS…").font(.subheadline).foregroundStyle(.secondary) }
                }
            }
            .padding(12)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
            .onAppear { compass.start() }
        }
    }
}
