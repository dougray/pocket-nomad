// RadioSettingsView.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Radio settings for one connected radio, opened from its card on the Mesh
// tab. MeshCore: frequency, bandwidth, spreading factor, coding rate and TX
// power, with "match radios near you", the official regional presets, or
// custom values. Meshtastic: region and modem preset. Nothing is sent to the
// radio until the user taps Apply.

import SwiftUI

struct RadioSettingsView: View {
    let link: MeshLink
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack(spacing: 8) {
                        Text(link.displayName).font(.headline).lineLimit(1)
                        MeshNetworkBadge(network: link.firmware)
                    }
                    if !link.connection.isConnected {
                        Label("Connect this radio to change its settings.", systemImage: "antenna.radiowaves.left.and.right.slash")
                            .foregroundStyle(.secondary)
                    }
                }
                Section {
                    Label {
                        Text("Use the frequencies allowed where you are. In the US, 902–928 MHz.")
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(MeshUI.warn)
                    }
                    Text("Radios only hear each other when they use exactly the same settings. Pick what the mesh near you uses.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                switch link.firmware {
                case .meshcore: MeshCoreRadioForm(link: link)
                case .meshtastic: MeshtasticRadioForm(link: link)
                }
                RadioApplyStatus(state: link.radioApply)
            }
            .navigationTitle("Radio settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            .onAppear { MeshService.shared.clearRadioApply(link.firmware) }
        }
    }
}

/// Progress and result of the last Apply.
private struct RadioApplyStatus: View {
    let state: RadioApplyState

    var body: some View {
        switch state {
        case .idle:
            EmptyView()
        case .working(let text), .waitingForRadio(let text):
            Section("Progress") {
                HStack(spacing: 12) {
                    ProgressView()
                    Text(text)
                }
            }
        case .done(let text):
            Section("Result") {
                Label(text, systemImage: "checkmark.circle.fill").foregroundStyle(MeshUI.ok)
            }
        case .failed(let text):
            Section("Result") {
                Label(text, systemImage: "xmark.octagon.fill").foregroundStyle(Theme.danger)
            }
        }
    }
}

// MARK: - MeshCore

private struct MeshCoreRadioForm: View {
    let link: MeshLink
    @State private var known = KnownRadioStore.shared
    @State private var frequencyText = ""
    @State private var bandwidthKHz: Double = 62.5
    @State private var spreadingFactor: UInt8 = 7
    @State private var codingRate: UInt8 = 5
    @State private var txPower: Int = 0
    @State private var presetTitle = ""
    @State private var loaded = false
    @State private var showDownload = false
    @State private var confirmRestart = false

    private var mesh: MeshService { MeshService.shared }
    private var nearby: MeshCoreNearbyConsensus? { MeshCoreNearbyConsensus.compute(known.radios) }
    private var maxTx: Int8? { link.radio.maxTxPowerDBm.flatMap { $0 > 0 ? $0 : nil } }

    /// What the fields describe, or nil when the frequency isn't a number.
    private var entered: MeshCoreRadioParams? {
        guard let mhz = MeshCoreRadioParams.parseMHz(frequencyText) else { return nil }
        return MeshCoreRadioParams(frequencyMHz: mhz, bandwidthKHz: bandwidthKHz,
                                   spreadingFactor: spreadingFactor, codingRate: codingRate)
    }

    private var problem: String? {
        guard let entered else { return "Type the frequency in MHz, for example 910.525." }
        return entered.validationError
    }

    private var unchanged: Bool {
        guard let entered, let now = link.radio.meshCoreParams else { return false }
        let txSame = maxTx == nil || Int8(clamping: txPower) == link.radio.txPowerDBm
        return entered.sameChannel(as: now) && txSame
    }

    private var outsideUSBand: Bool {
        guard let entered, Locale.current.region?.identifier == "US" else { return false }
        return !MeshCoreRadioParams.usBandMHz.contains(entered.frequencyMHz)
    }

    var body: some View {
        Section("Now on the radio") {
            if let now = link.radio.meshCoreParams {
                Text(now.fullSummary).font(.body.monospacedDigit())
                if let tx = link.radio.txPowerDBm, maxTx != nil {
                    LabeledContent("TX power", value: "\(tx) dBm")
                }
                if now.sameChannel(as: .firmwareDefault) {
                    Text("These are the firmware's defaults (Europe). Radios in the Americas almost never use them.")
                        .font(.footnote).foregroundStyle(MeshUI.warn)
                }
            } else {
                Text("Not read yet. Connect the radio.").foregroundStyle(.secondary)
            }
        }

        Section {
            nearbyRow
            Picker("Regional preset", selection: $presetTitle) {
                Text("Custom").tag("")
                ForEach(MeshCoreRadioPreset.official) { p in
                    Text("\(p.title) · \(p.params.frequencyText)").tag(p.title)
                }
            }
            .onChange(of: presetTitle) { _, title in
                if let p = MeshCoreRadioPreset.official.first(where: { $0.title == title }) { fill(p.params) }
            }
        } header: { Text("Choose") } footer: {
            Text("Presets are the MeshCore community's suggestions per region. USA and Canada: 910.525 MHz, SF7, BW 62.5, CR5.")
        }

        Section {
            HStack {
                Text("Frequency")
                Spacer()
                TextField("910.525", text: $frequencyText)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 140)
                Text("MHz").foregroundStyle(.secondary)
            }
            Picker("Bandwidth", selection: $bandwidthKHz) {
                ForEach(MeshCoreRadioParams.allowedBandwidthsKHz, id: \.self) {
                    Text("\(MeshCoreRadioParams.format($0, maxDecimals: 2)) kHz").tag($0)
                }
            }
            Picker("Spreading factor", selection: $spreadingFactor) {
                ForEach(Array(MeshCoreRadioParams.spreadingFactors), id: \.self) { Text("SF\($0)").tag($0) }
            }
            Picker("Coding rate", selection: $codingRate) {
                ForEach(Array(MeshCoreRadioParams.codingRates), id: \.self) { Text("4/\($0) (CR\($0))").tag($0) }
            }
            if let maxTx {
                Stepper(value: $txPower, in: Int(MeshCoreRadioParams.minTxPowerDBm) ... Int(maxTx)) {
                    LabeledContent("TX power", value: "\(txPower) dBm")
                }
            }
            if let problem {
                Text(problem).font(.footnote).foregroundStyle(Theme.danger)
            } else if outsideUSBand {
                Text("This frequency is outside 902–928 MHz, the band allowed for these radios in the US.")
                    .font(.footnote).foregroundStyle(Theme.danger)
            }
        } header: { Text("Settings") }
        .onChange(of: frequencyText) { _, _ in syncPresetTitle() }
        .onChange(of: bandwidthKHz) { _, _ in syncPresetTitle() }
        .onChange(of: spreadingFactor) { _, _ in syncPresetTitle() }
        .onChange(of: codingRate) { _, _ in syncPresetTitle() }

        Section {
            Button {
                guard let entered else { return }
                mesh.applyMeshCoreRadio(entered, txPowerDBm: maxTx == nil ? nil : Int8(clamping: txPower))
            } label: {
                Label("Apply to radio", systemImage: "checkmark.circle")
            }
            .buttonStyle(MeshBigButtonStyle())
            .disabled(problem != nil || unchanged || link.radioApply.isBusy || !link.connection.isConnected)
            .listRowBackground(Color.clear)
        } footer: {
            Text("The radio saves the settings, restarts, and reconnects by itself. Its contacts and channels are kept.")
        }

        Section {
            Button("Restart radio", systemImage: "arrow.clockwise") { confirmRestart = true }
                .disabled(!link.connection.isConnected || link.radioApply.isBusy)
        }
        .confirmationDialog("Restart \(link.displayName)?", isPresented: $confirmRestart, titleVisibility: .visible) {
            Button("Restart") { mesh.restartMeshCoreRadio() }
        } message: {
            Text("It disconnects for a few seconds and reconnects by itself.")
        }
        .sheet(isPresented: $showDownload) { KnownRadiosSheet(mapCenter: nil) }
        .onAppear(perform: load)
    }

    @ViewBuilder
    private var nearbyRow: some View {
        if let nearby {
            Button {
                fill(nearby.params)
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Label("Match radios near you", systemImage: "dot.radiowaves.left.and.right").font(.headline)
                    Text(nearby.params.fullSummary).font(.subheadline.monospacedDigit())
                    Text(nearby.usageText).font(.footnote).foregroundStyle(.secondary)
                }
            }
        } else {
            VStack(alignment: .leading, spacing: 6) {
                Label("Match radios near you", systemImage: "dot.radiowaves.left.and.right").font(.headline)
                Text(known.meshcoreCount > 0
                     ? "Your saved list of known radios has no radio settings. Update it once (needs internet), then this works offline."
                     : "Download the known radios around you once (needs internet). After that this works offline.")
                    .font(.footnote).foregroundStyle(.secondary)
                Button(known.meshcoreCount > 0 ? "Update known radios" : "Download known radios") { showDownload = true }
            }
        }
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        // Start from the radio's settings; a radio still on the firmware
        // default starts from what is used nearby, else the USA preset.
        let now = link.radio.meshCoreParams
        let start: MeshCoreRadioParams
        if let now, !now.sameChannel(as: .firmwareDefault) {
            start = now
        } else {
            start = nearby?.params ?? MeshCoreRadioPreset.usa.params
        }
        fill(start)
        txPower = Int(link.radio.txPowerDBm ?? maxTx ?? 0)
    }

    private func fill(_ p: MeshCoreRadioParams) {
        frequencyText = MeshCoreRadioParams.format(p.frequencyMHz, maxDecimals: 3)
        bandwidthKHz = MeshCoreRadioParams.allowedBandwidthsKHz.min { abs($0 - p.bandwidthKHz) < abs($1 - p.bandwidthKHz) } ?? 62.5
        spreadingFactor = min(max(p.spreadingFactor, 5), 12)
        codingRate = min(max(p.codingRate, 5), 8)
        syncPresetTitle()
    }

    private func syncPresetTitle() {
        let match = entered.flatMap { MeshCoreRadioPreset.matching($0) }?.title ?? ""
        // Keep a chosen preset that shares these values (USA and Canada are the same).
        if let current = MeshCoreRadioPreset.official.first(where: { $0.title == presetTitle }),
           let entered, current.params.sameChannel(as: entered) { return }
        if presetTitle != match { presetTitle = match }
    }
}

// MARK: - Meshtastic

private struct MeshtasticRadioForm: View {
    let link: MeshLink
    @State private var region: UInt32 = MeshtasticRegion.unset
    @State private var preset: UInt32 = MeshtasticModemPreset.longFast
    @State private var loaded = false

    private var mesh: MeshService { MeshService.shared }
    private var suggested: UInt32? { MeshtasticRegion.suggested(forCountry: Locale.current.region?.identifier) }

    private var unchanged: Bool {
        link.radio.region == region && link.radio.usePreset == true && link.radio.modemPreset == preset
    }

    var body: some View {
        Section("Now on the radio") {
            if let r = link.radio.region {
                if r == MeshtasticRegion.unset {
                    Label("Region not set. This radio will not transmit until you choose one.", systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(Theme.danger)
                } else {
                    Text(link.radio.settingsSummary ?? MeshtasticRegion.shortName(r))
                    if let name = MeshtasticRegion.find(r)?.name { Text(name).font(.footnote).foregroundStyle(.secondary) }
                }
            } else {
                Text("Not read yet. Connect the radio.").foregroundStyle(.secondary)
            }
        }

        Section {
            Picker("Region", selection: $region) {
                if region == MeshtasticRegion.unset { Text("Choose…").tag(MeshtasticRegion.unset) }
                Section {
                    ForEach(MeshtasticRegion.all.filter { !$0.recentFirmware }) { r in
                        Text(r.code == suggested ? "\(r.name) · suggested" : r.name).tag(r.code)
                    }
                }
                Section("Recent firmware only") {
                    ForEach(MeshtasticRegion.all.filter(\.recentFirmware)) { r in Text(r.name).tag(r.code) }
                }
            }
            .pickerStyle(.navigationLink)
            Picker("Modem preset", selection: $preset) {
                Section {
                    ForEach(MeshtasticModemPreset.all.filter { !$0.recentFirmware }) { p in
                        Text("\(p.short) · \(p.name)").tag(p.code)
                    }
                }
                Section("Recent firmware only") {
                    ForEach(MeshtasticModemPreset.all.filter(\.recentFirmware)) { p in
                        Text("\(p.short) · \(p.name)").tag(p.code)
                    }
                }
            }
            .pickerStyle(.navigationLink)
        } header: { Text("Choose") } footer: {
            Text("Most meshes use LongFast. Everyone you want to reach must use the same region and preset. Choices marked recent firmware may not work on older radios.")
        }

        Section {
            Button {
                mesh.applyMeshtasticLoRa(region: region, modemPreset: preset)
            } label: {
                Label("Apply to radio", systemImage: "checkmark.circle")
            }
            .buttonStyle(MeshBigButtonStyle())
            .disabled(region == MeshtasticRegion.unset || unchanged || link.radioApply.isBusy || !link.connection.isConnected)
            .listRowBackground(Color.clear)
        } footer: {
            Text("Only the region and preset change; the radio's other settings are kept. It restarts to use them and reconnects by itself.")
        }
        .onAppear(perform: load)
    }

    private func load() {
        guard !loaded else { return }
        loaded = true
        let current = link.radio.region ?? MeshtasticRegion.unset
        region = current != MeshtasticRegion.unset ? current : (suggested ?? MeshtasticRegion.unset)
        preset = link.radio.usePreset == false ? MeshtasticModemPreset.longFast : (link.radio.modemPreset ?? MeshtasticModemPreset.longFast)
    }
}
