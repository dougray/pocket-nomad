// HamSettingsView.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0

import SwiftUI

struct HamSettingsView: View {
    @State private var ham = HamService.shared
    @Bindable private var s = HamSettings.shared
    @State private var password = ""
    @State private var savedPassword = false
    @State private var confirmDelete = false

    private let symbols: [(String, String)] = [("/[", "Person"), ("/>", "Car"), ("/b", "Bicycle"), ("/-", "House"),
                                               ("/;", "Campsite"), ("/k", "Truck"), ("/s", "Boat"), ("/'", "Aircraft"),
                                               ("/+", "Red Cross"), ("\\!", "Emergency")]

    var body: some View {
        Form {
            Section {
                Label {
                    Text("Sending on amateur radio bands needs an amateur radio licence. In the US that's an FCC licence, and the callsign is how you prove it. Winlink also needs a free account created with that callsign. Pocket Nomad doesn't check your licence: you are responsible for what your station sends.")
                } icon: { Image(systemName: "exclamationmark.shield.fill").foregroundStyle(MeshUI.warn) }
                    .font(.footnote)
                TextField("Callsign, e.g. KF0ABC", text: $s.callsign)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled()
                if !s.callsign.isEmpty, !s.callsignValid {
                    Text("That doesn't look like an amateur callsign.").font(.footnote).foregroundStyle(Theme.danger)
                }
                Toggle("I hold an amateur radio licence for this callsign", isOn: $s.licensed)
                    .disabled(!s.callsignValid)
                LabeledContent("Mode", value: s.canTransmit ? "Licensed: sending on" : "Listen only")
            } header: { Text("Licence") } footer: {
                Text("Listen only (no callsign) still decodes everything the radio hears: APRS stations, messages, bulletins, weather and the packet monitor. It never transmits.")
            }

            Section {
                SecureField(s.hasWinlinkPassword ? "Password saved (type to change)" : "Winlink password", text: $password)
                    .textContentType(.password)
                HStack {
                    Button("Save password") {
                        s.winlinkPassword = password
                        password = ""
                        savedPassword = true
                    }
                    .disabled(password.isEmpty)
                    Spacer()
                    if s.hasWinlinkPassword {
                        Button("Remove", role: .destructive) { s.winlinkPassword = nil }
                    }
                }
                if savedPassword { Text("Saved in the Keychain on this phone.").font(.footnote).foregroundStyle(.secondary) }
            } header: { Text("Winlink") } footer: {
                Text("Create your Winlink account (free) at winlink.org or on your first connection with another Winlink app. The password stays on this phone and is only used to answer the gateway's login challenge; it is never sent in the clear.")
            }

            Section {
                Picker("APRS SSID", selection: $s.aprsSSID) {
                    ForEach(0..<16, id: \.self) { n in Text(ssidLabel(n)).tag(n) }
                }
                Picker("Map symbol", selection: $s.symbol) {
                    ForEach(symbols, id: \.0) { Text($0.1).tag($0.0) }
                }
                TextField("Path", text: $s.aprsPath).textInputAutocapitalization(.characters).autocorrectionDisabled()
                TextField("Beacon comment", text: $s.beaconComment)
                Picker("Send my position", selection: $s.beaconMinutes) {
                    Text("Only when I tap").tag(0)
                    Text("Every 10 minutes").tag(10)
                    Text("Every 20 minutes").tag(20)
                    Text("Every 30 minutes").tag(30)
                    Text("Every hour").tag(60)
                }
                Toggle("Acknowledge messages to me", isOn: $s.autoAck)
            } header: { Text("APRS") } footer: {
                Text("Path WIDE1-1,WIDE2-1 suits most areas; use WIDE2-1 in busy cities, or ARISS to reach the space station's digipeater on 145.825 MHz. Position beacons share your location with everyone listening, and APRS-IS puts them on public websites.")
            }

            Section {
                Toggle("Accept incoming connections", isOn: $s.acceptIncoming)
            } footer: {
                Text("Lets another station connect to \(s.callsignValid ? s.baseCall : "your callsign") and chat in the packet terminal.")
            }

            Section("TNC and link (advanced)") {
                Stepper("TX delay \(s.txDelay * 10) ms", value: $s.txDelay, in: 5...100, step: 5)
                Stepper("Persistence \(s.persistence)", value: $s.persistence, in: 0...255, step: 16)
                Stepper("Slot time \(s.slotTime * 10) ms", value: $s.slotTime, in: 1...50)
                Stepper("Window \(s.window) frames", value: $s.window, in: 1...7)
                Stepper("Packet length \(s.paclen) bytes", value: $s.paclen, in: 32...256, step: 32)
            }

            Section {
                Button("Delete all ham data on this phone", role: .destructive) { confirmDelete = true }
            } footer: {
                Text("Stations, APRS messages, bulletins and Winlink mail are stored only on this phone and are not backed up.")
            }
        }
        .navigationTitle("Station settings")
        .confirmationDialog("Delete all stations, messages and mail?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { ham.deleteAllData() }
        }
    }

    private func ssidLabel(_ n: Int) -> String {
        let names = [0: "home station", 1: "digi / extra", 2: "digi / extra", 3: "extra", 4: "extra", 5: "phone / other",
                     6: "satellite / special", 7: "handheld", 8: "boat", 9: "car", 10: "internet", 11: "balloon / aircraft",
                     12: "tracker", 13: "weather", 14: "truck", 15: "extra"]
        return "-\(n) \(names[n] ?? "")"
    }
}

struct HamReferenceView: View {
    private let frequencies: [(String, String)] = [
        ("144.390 MHz", "APRS, North America"),
        ("144.800 MHz", "APRS, Europe and Africa"),
        ("145.175 MHz", "APRS, Australia"),
        ("145.825 MHz", "Space station (ISS) packet digipeater, path ARISS"),
        ("145.010–145.090 MHz", "Common 1200-baud packet, BBS and Winlink (US)"),
        ("146.520 MHz", "2 m FM national simplex calling (US)"),
        ("446.000 MHz", "70 cm FM national simplex calling (US)"),
        ("52.525 MHz", "6 m FM simplex calling (US)"),
        ("14.300 MHz USB", "Maritime Mobile Service Net (HF)"),
        ("14.325 MHz USB", "Hurricane Watch Net (HF, when active)"),
    ]
    private let qCodes: [(String, String)] = [
        ("QTH", "Location"), ("QSL", "I confirm / received"), ("QRZ", "Who is calling me?"), ("QSY", "Change frequency"),
        ("QRM", "Interference from other stations"), ("QRN", "Static / natural noise"), ("QRT", "Stopping / closing down"),
        ("QRX", "Wait / stand by"), ("QSO", "A contact"), ("QRP", "Low power"),
    ]
    private let phonetic = ["Alfa", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliett",
                            "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango",
                            "Uniform", "Victor", "Whiskey", "X-ray", "Yankee", "Zulu"]

    var body: some View {
        List {
            Section {
                ForEach(frequencies, id: \.0) { f in
                    VStack(alignment: .leading) {
                        Text(f.0).font(.headline.monospacedDigit())
                        Text(f.1).font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            } header: { Text("Frequencies") } footer: {
                Text("Local repeaters, nets and Winlink gateways vary: write yours down before an emergency. Band plans differ by country.")
            }
            Section {
                ForEach(ConditionsParsers.radioChannels, id: \.0) { f in
                    VStack(alignment: .leading) {
                        Text("\(f.0) MHz").font(.headline.monospacedDigit())
                        Text("Channel \(f.1)").font(.subheadline).foregroundStyle(.secondary)
                    }
                }
            } header: { Text("NOAA Weather Radio") } footer: {
                Text("Receive only; most handhelds hear this band. The Conditions screen (Survive tab) lists the nearest transmitters once you've updated it.")
            }
            Section("Getting on the air (US)") {
                Text("The entry-level Technician licence covers every amateur band above 50 MHz, which includes 2 m and 70 cm where APRS and packet live. The exam is 35 multiple-choice questions, no Morse code, run by volunteer examiners; the FCC charges a small fee.")
                Text("Identify with your callsign at least every 10 minutes during a contact and at the end. No encryption, no business traffic, no music.")
            }
            Section("Q codes") {
                ForEach(qCodes, id: \.0) { LabeledContent($0.0, value: $0.1) }
            }
            Section("Prosigns and shorthand") {
                LabeledContent("CQ", value: "Calling anyone")
                LabeledContent("DE", value: "From (\"KF0XYZ de KF0ABC\")")
                LabeledContent("73", value: "Best regards")
                LabeledContent("K / over", value: "Your turn")
                LabeledContent("SK / clear", value: "End of contact")
                LabeledContent("Mayday / Emergency", value: "Life or property at risk: everyone else stands by")
            }
            Section("Phonetic alphabet") {
                Text(phonetic.joined(separator: " · ")).font(.callout)
            }
            Section("APRS symbols you'll see") {
                ForEach([("/[", "Person"), ("/>", "Car"), ("/#", "Digipeater"), ("/&", "Gateway / iGate"), ("/_", "Weather station"),
                         ("/-", "House"), ("/+", "Red Cross"), ("\\!", "Emergency")], id: \.0) { sym in
                    Label(sym.1, systemImage: HamSymbols.systemImage(sym.0, weather: false))
                }
            }
        }
        .navigationTitle("Reference")
    }
}
