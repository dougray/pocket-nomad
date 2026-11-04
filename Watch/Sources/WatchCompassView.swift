import SwiftUI
import CoreLocation

/// Heading and position from the watch's own sensors, in the same formats the
/// phone uses (Shared/CoordinateFormat.swift).
struct WatchCompassView: View {
    @State private var location = WatchLocation.shared

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                dial
                if let heading = location.heading {
                    Text("\(Int(heading.rounded()))° \(CoordinateFormat.cardinal(heading))")
                        .font(.system(size: 26, weight: .bold, design: .rounded)).monospacedDigit()
                } else {
                    Text(location.headingAvailable ? "Calibrating…" : "No compass on this watch.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                position
            }
        }
        .defaultScrollAnchor(Self.startAtPosition ? .bottom : .top)
        .navigationTitle("Compass")
        .onAppear { location.begin() }
        .onDisappear { location.end() }
    }

    /// Debug `-screen position` opens scrolled down to the coordinates.
    private static var startAtPosition: Bool {
        #if DEBUG
        UserDefaults.standard.string(forKey: "screen") == "position"
        #else
        false
        #endif
    }

    private var dial: some View {
        let heading = location.heading ?? 0
        return ZStack {
            Circle().stroke(Color.gray.opacity(0.5), lineWidth: 1.5)
            ForEach(0..<36) { i in
                Rectangle()
                    .fill(i % 9 == 0 ? WatchTheme.accent : Color.gray)
                    .frame(width: i % 3 == 0 ? 2 : 1, height: i % 3 == 0 ? 8 : 4)
                    .offset(y: -56)
                    .rotationEffect(.degrees(Double(i) * 10))
            }
            ForEach(Array(["N", "E", "S", "W"].enumerated()), id: \.offset) { i, label in
                Text(label)
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(i == 0 ? WatchTheme.danger : .white)
                    .rotationEffect(.degrees(Double(-i) * 90 + heading))
                    .offset(y: -40)
                    .rotationEffect(.degrees(Double(i) * 90))
            }
        }
        .rotationEffect(.degrees(-heading))
        .animation(.easeOut(duration: 0.2), value: heading)
        .overlay(alignment: .top) {
            Image(systemName: "arrowtriangle.down.fill").font(.caption).foregroundStyle(WatchTheme.accent).offset(y: -10)
        }
        .frame(width: 124, height: 124)
        .padding(.top, 10)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private var position: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let loc = location.location {
                let c = loc.coordinate
                row("Decimal", CoordinateFormat.decimal(c))
                row("DMS", CoordinateFormat.dms(c))
                if let mgrs = CoordinateFormat.mgrs(c) { row("MGRS", mgrs) }
                row("Altitude", String(format: "%.0f ft (%.0f m)", loc.altitude * 3.28084, loc.altitude))
                row("Accuracy", String(format: "±%.0f m", loc.horizontalAccuracy))
            } else if location.isDenied {
                Text("Location is off for Pocket Nomad. Turn it on in the Watch app or Settings › Privacy › Location Services on the watch.")
                    .font(.footnote).foregroundStyle(.secondary)
            } else {
                Text("Getting a GPS fix… go outside with a view of the sky.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(8)
        .background(WatchTheme.card, in: RoundedRectangle(cornerRadius: 12))
    }

    private func row(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(label).font(.caption2).foregroundStyle(WatchTheme.accent)
            Text(value).font(.system(.footnote, design: .monospaced)).minimumScaleFactor(0.7).lineLimit(2)
        }
        .accessibilityElement(children: .combine)
    }
}
