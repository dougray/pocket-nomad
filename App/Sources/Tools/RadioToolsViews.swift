import SwiftUI

/// Antenna length calculator: type a frequency, get cut lengths.
struct AntennaCalcView: View {
    @State private var freq: Double? = 144.39

    private let presets: [(name: String, mhz: Double)] = [
        ("APRS (North America)", 144.39), ("Weather radio", 162.55), ("Weather satellite", 137.5), ("Aircraft (ADS-B)", 1090), ("Mesh radio (LoRa, US)", 915),
        ("70 cm amateur", 433), ("20 m FT8", 14.074), ("40 m FT8", 7.074),
    ]

    var body: some View {
        Form {
            Section("Frequency") {
                HStack {
                    TextField("MHz", value: $freq, format: .number.precision(.fractionLength(0...4))).keyboardType(.decimalPad).font(.title3.weight(.semibold))
                    Text("MHz").foregroundStyle(.secondary)
                }
                Menu { ForEach(presets, id: \.name) { p in Button(p.name) { freq = p.mhz } } } label: { Label("Pick a common one", systemImage: "list.bullet") }
            }
            if let f = freq, let a = RadioMath.antenna(freqMHz: f) {
                Section("Whip or ground plane (one element)") {
                    LabeledContent("Quarter wave", value: RadioMath.lengthText(a.quarterWaveMetres))
                    LabeledContent("Start cutting at", value: RadioMath.lengthText(a.cutLong(a.quarterWaveMetres)))
                }
                Section("Dipole (two equal legs)") {
                    LabeledContent("Each leg", value: RadioMath.lengthText(a.halfWaveLegMetres))
                    LabeledContent("Half wave, tip to tip", value: RadioMath.lengthText(a.halfWaveTotalMetres))
                }
                Section("V-dipole (weather satellites)") {
                    LabeledContent("Each element", value: RadioMath.lengthText(a.vDipoleElementMetres))
                    LabeledContent("Angle between them", value: "\(AntennaLengthsConstants.angle)°")
                }
                Section {
                    LabeledContent("Wavelength in air", value: RadioMath.lengthText(a.wavelengthMetres))
                    Text("Cut about 3 % long, then trim a little at a time for the best signal. Lengths assume a bare wire or rod; a thick element or one near other metal needs to be a little shorter.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                Section { Text("Enter a frequency between 0.1 and 6000 MHz.").foregroundStyle(.secondary) }
            }
        }
        .navigationTitle("Antenna Lengths")
    }
}

enum AntennaLengthsConstants { static let angle = RadioMath.AntennaLengths.vDipoleAngleDegrees }

/// Solar node power calculator: will the panel and battery keep a node alive?
struct SolarNodeView: View {
    @State private var panelWatts: Double? = 2
    @State private var sunHours = 3.5
    @State private var batteryVolts: Double? = 3.7
    @State private var batteryMAh: Double? = 5000
    @State private var drawMA: Double? = 75

    private var result: RadioMath.SolarResult? {
        RadioMath.solar(panelWatts: panelWatts ?? -1, sunHours: sunHours, batteryVolts: batteryVolts ?? 0, batteryMAh: batteryMAh ?? 0, drawMA: drawMA ?? 0)
    }

    private func field(_ label: LocalizedStringKey, _ unit: LocalizedStringKey, _ value: Binding<Double?>) -> some View {
        HStack {
            Text(label); Spacer()
            TextField("0", value: value, format: .number.precision(.fractionLength(0...2))).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(maxWidth: 110)
            Text(unit).foregroundStyle(.secondary).frame(width: 48, alignment: .leading)
        }
    }

    var body: some View {
        Form {
            Section("Panel") {
                field("Rated power", "W", $panelWatts)
                Picker("Sun", selection: $sunHours) {
                    Text("Winter or cloudy").tag(2.0); Text("Average").tag(3.5); Text("Summer, clear").tag(5.0)
                }
            }
            Section("Battery (all cells together)") {
                field("Voltage", "V", $batteryVolts)
                field("Capacity", "mAh", $batteryMAh)
            }
            Section("Node") {
                field("Average draw", "mA", $drawMA)
                Text("About 75 mA while listening for the Heltec V4 (its datasheet). Measure yours with a USB power meter for the best answer.").font(.footnote).foregroundStyle(.secondary)
            }
            if let r = result {
                Section("Result") {
                    LabeledContent("Node uses", value: String(format: "%.1f Wh a day", r.loadWhPerDay))
                    LabeledContent("Panel makes", value: String(format: "%.1f Wh a day", r.panelWhPerDay))
                    LabeledContent("Battery holds", value: String(format: "%.1f Wh", r.batteryWh))
                    LabeledContent("With no sun, a full battery lasts", value: String(format: "%.1f days", r.autonomyDays))
                    switch r.verdict {
                    case .keepsUp: Label("The panel keeps up, with room to spare.", systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                    case .tight: Label("Tight: it only just keeps up. A few cloudy days will drain the battery.", systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                    case .runsDown(let days): Label(String(format: NSLocalizedString("The panel is too small: even in this sun a full battery runs down in about %.1f days.", comment: ""), days), systemImage: "xmark.octagon.fill").foregroundStyle(.red)
                    }
                }
                Section {
                    Text("Real runtime is shorter: batteries should not be run flat, cold cuts capacity, and panels rarely reach their rating. Use more panel and battery than the sum asks for.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                Section { Text("Fill in every box with a number above zero.").foregroundStyle(.secondary) }
            }
        }
        .navigationTitle("Solar Node Power")
    }
}
