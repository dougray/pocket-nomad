import SwiftUI
import CoreLocation

struct CompassView: View {
    @State private var location = LocationService.shared
    @State private var useTrueNorth = true
    @State private var lockedBearing: Double?
    @State private var copied = false

    private var heading: Double? {
        guard let h = location.heading else { return nil }
        if useTrueNorth, h.trueHeading >= 0 { return h.trueHeading }
        return h.magneticHeading
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                dial
                if let heading {
                    Text("\(Int(heading.rounded()))° \(CoordinateFormat.cardinal(heading))")
                        .font(.system(size: 44, weight: .bold, design: .rounded)).monospacedDigit()
                    if let lockedBearing {
                        let diff = ((lockedBearing - heading + 540).truncatingRemainder(dividingBy: 360)) - 180
                        Text(abs(diff) < 3 ? "On bearing \(Int(lockedBearing))°" :
                                "Turn \(diff > 0 ? "right" : "left") \(Int(abs(diff)))° to \(Int(lockedBearing))°")
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(abs(diff) < 3 ? .green : Theme.accent)
                    }
                    HStack {
                        Button(lockedBearing == nil ? "Lock this bearing" : "Clear bearing") {
                            lockedBearing = lockedBearing == nil ? heading : nil
                        }
                        .buttonStyle(.borderedProminent).controlSize(.large)
                    }
                    Picker("North", selection: $useTrueNorth) {
                        Text("True north").tag(true)
                        Text("Magnetic").tag(false)
                    }
                    .pickerStyle(.segmented)
                } else {
                    Text(CLLocationManager.headingAvailable() ? "Hold the phone flat and away from metal." : "This device has no compass sensor.")
                        .foregroundStyle(.secondary)
                }
                position
            }
            .padding()
        }
        .navigationTitle("Compass")
        .onAppear { location.begin(); UIApplication.shared.isIdleTimerDisabled = true }
        .onDisappear { location.end(); UIApplication.shared.isIdleTimerDisabled = false }
    }

    private var dial: some View {
        ZStack {
            Circle().stroke(Color.secondary.opacity(0.4), lineWidth: 2)
            ForEach(0..<72) { i in
                Rectangle()
                    .fill(i % 18 == 0 ? Theme.accent : Color.secondary)
                    .frame(width: i % 6 == 0 ? 3 : 1, height: i % 6 == 0 ? 16 : 8)
                    .offset(y: -128)
                    .rotationEffect(.degrees(Double(i) * 5))
            }
            ForEach(Array(["N", "E", "S", "W"].enumerated()), id: \.offset) { i, label in
                Text(label)
                    .font(.title2.bold())
                    .foregroundStyle(i == 0 ? Theme.danger : .primary)
                    .rotationEffect(.degrees(Double(-i) * 90 + (heading ?? 0)))
                    .offset(y: -100)
                    .rotationEffect(.degrees(Double(i) * 90))
            }
            if let lockedBearing {
                Image(systemName: "arrowtriangle.down.fill")
                    .foregroundStyle(.green).font(.title2)
                    .offset(y: -146)
                    .rotationEffect(.degrees(lockedBearing))
            }
        }
        .rotationEffect(.degrees(-(heading ?? 0)))
        .animation(.easeOut(duration: 0.2), value: heading)
        .overlay(alignment: .top) {
            Image(systemName: "arrowtriangle.down.fill").font(.title).foregroundStyle(Theme.accent).offset(y: -18)
        }
        .frame(width: 280, height: 280)
        .padding(.top, 20)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private var position: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Your position").font(.headline)
            if let loc = location.location {
                let c = loc.coordinate
                row("Decimal", CoordinateFormat.decimal(c))
                row("DMS", CoordinateFormat.dms(c))
                if let mgrs = CoordinateFormat.mgrs(c) { row("MGRS", mgrs) }
                row("Altitude", String(format: "%.0f ft (%.0f m)", loc.altitude * 3.28084, loc.altitude))
                row("Accuracy", String(format: "±%.0f m", loc.horizontalAccuracy))
                Button(copied ? "Copied" : "Copy coordinates") {
                    UIPasteboard.general.string = "\(CoordinateFormat.decimal(c)) (\(CoordinateFormat.mgrs(c) ?? ""))"
                    copied = true
                }
                .buttonStyle(.bordered)
                Text("Read these to rescuers over a radio or phone. GPS works with no cell service; it needs a view of the sky.")
                    .font(.footnote).foregroundStyle(.secondary)
            } else if location.authorization == .denied || location.authorization == .restricted {
                Text("Location is off for Pocket Nomad. Turn it on in Settings › Privacy › Location Services.")
                    .foregroundStyle(.secondary)
            } else {
                Text("Getting a GPS fix… go outside or near a window.").foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(Theme.card, in: RoundedRectangle(cornerRadius: 14))
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label).foregroundStyle(.secondary).frame(width: 80, alignment: .leading)
            Text(value).font(.body.monospaced()).textSelection(.enabled)
        }
    }
}
