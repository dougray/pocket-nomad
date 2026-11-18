// QsoLogView.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0

import SwiftUI
import UniformTypeIdentifiers

/// Your radio contacts, kept on this phone and exported as an ADIF file.
struct QsoLogView: View {
    @State private var store = QsoLogStore.shared
    @State private var search = ""
    @State private var editing: Qso?
    @State private var importing = false
    @State private var note = ""
    @State private var exportURL: URL?

    var body: some View {
        List {
            if store.log.isEmpty {
                Section { Text("No contacts yet. Tap + after you make one. Everything stays on this phone; Export makes an ADIF file you can open in any logging program or upload to a logbook yourself.").foregroundStyle(.secondary) }
            }
            Section("\(store.log.count) contact\(store.log.count == 1 ? "" : "s")") {
                ForEach(store.search(search)) { q in
                    Button { editing = q } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack { Text(q.call).font(.headline); Spacer(); Text("\(q.band) \(q.mode)").font(.subheadline.monospaced()).foregroundStyle(.secondary) }
                            Text("\(q.when.formatted(date: .abbreviated, time: .shortened)) UTC\(q.sigInfo.isEmpty ? "" : " · \(q.sig) \(q.sigInfo)")").font(.caption).foregroundStyle(.secondary)
                            if let line = QsoLogView.rangeLine(q) { Text(line).font(.caption.weight(.semibold)).foregroundStyle(Theme.accent) }
                        }.foregroundStyle(.primary)
                    }
                    .swipeActions { Button("Delete", role: .destructive) { store.remove(q.id) } }
                }
            }
            if !note.isEmpty { Section { Text(note).font(.footnote) } }
        }
        .searchable(text: $search, prompt: "Call, name, park or grid")
        .navigationTitle("Contact log")
        .toolbar {
            ToolbarItem(placement: .primaryAction) { Button { editing = QsoLogView.blank() } label: { Image(systemName: "plus") } }
            ToolbarItem(placement: .secondaryAction) {
                Menu {
                    if let url = exportURL { ShareLink(item: url) { Label("Share ADIF file", systemImage: "square.and.arrow.up") } }
                    Button { importing = true } label: { Label("Import ADIF…", systemImage: "square.and.arrow.down") }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .sheet(item: $editing) { QsoEditor(qso: $0) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.plainText, .data], allowsMultipleSelection: false) { result in
            guard case .success(let urls) = result, let url = urls.first else { return }
            let ok = url.startAccessingSecurityScopedResource(); defer { if ok { url.stopAccessingSecurityScopedResource() } }
            if let text = try? String(contentsOf: url, encoding: .utf8) {
                let n = store.merge(ADIF.parse(text))
                note = n == 0 ? "No new contacts found in that file." : "Imported \(n) contact\(n == 1 ? "" : "s")."
            } else { note = "Couldn't read that file." }
        }
        .onAppear { refreshExport() }
        .onChange(of: store.log) { _, _ in refreshExport() }
    }

    private func refreshExport() {
        let url = URL.temporaryDirectory.appending(path: "PocketNomad-log.adi")
        try? store.exportADIF().write(to: url, atomically: true, encoding: .utf8)
        exportURL = store.log.isEmpty ? nil : url
    }

    /// "5 W · 84 mi · 16.8 mi per watt", when the power and both grids are known.
    static func rangeLine(_ q: Qso) -> String? {
        let metric = ConditionsFormat.current.metric
        var parts: [String] = []
        if let w = q.watts { parts.append(w == w.rounded() ? "\(Int(w)) W" : String(format: "%.1f W", w)) }
        if let km = q.distanceKm { parts.append(metric ? String(format: "%.0f km", km) : String(format: "%.0f mi", km / 1.609344)) }
        if let mpw = q.milesPerWatt { parts.append(metric ? String(format: "%.1f km per watt", mpw * 1.609344) : String(format: "%.1f mi per watt", mpw)) }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    static func blank(call: String = "", mhz: Double = 0, mode: String = "FM", sig: String = "", sigInfo: String = "", name: String = "") -> Qso {
        var q = Qso(call: call, when: Date(), mhz: mhz, mode: mode)
        q.myCall = HamService.shared.settings.baseCall
        if let here = LocationService.shared.location?.coordinate { q.myGrid = Coordinates.format(latitude: here.latitude, longitude: here.longitude, as: .maidenhead) }
        q.sig = sig; q.sigInfo = sigInfo
        q.rstSent = ["CW", "RTTY"].contains(mode) ? "599" : (["FT8", "FT4", "WSPR", "JS8"].contains(mode) ? "" : "59")
        q.rstRcvd = q.rstSent
        q.comment = name
        return q
    }
}

struct QsoEditor: View {
    @State var qso: Qso
    @State private var mhzText = ""
    @State private var wattsText = ""
    @State private var store = QsoLogStore.shared
    @Environment(\.dismiss) private var dismiss
    private var isNew: Bool { !store.log.contains { $0.id == qso.id } }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Their callsign", text: $qso.call).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    DatePicker("Time (UTC)", selection: $qso.when).environment(\.timeZone, TimeZone(identifier: "UTC")!)
                    HStack { TextField("Frequency (MHz)", text: $mhzText).keyboardType(.decimalPad); Text(QsoBands.band(mhz: Double(mhzText) ?? 0) ?? "").foregroundStyle(.secondary) }
                    Picker("Mode", selection: $qso.mode) { ForEach(QsoBands.modes, id: \.self) { Text($0).tag($0) } }
                }
                Section("Details") {
                    TextField("Report sent", text: $qso.rstSent)
                    TextField("Report received", text: $qso.rstRcvd)
                    TextField("Power (watts)", text: $wattsText).keyboardType(.decimalPad)
                    TextField("Your grid", text: $qso.myGrid).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    TextField("Their grid", text: $qso.grid).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    TextField("Their name", text: $qso.name)
                    TextField("Park or summit (for example US-0001)", text: $qso.sigInfo).textInputAutocapitalization(.characters).autocorrectionDisabled()
                    TextField("Notes", text: $qso.comment, axis: .vertical)
                }
                Section { LabeledContent("Your callsign", value: qso.myCall.isEmpty ? "Set it in Station settings" : qso.myCall) }
                if !isNew { Section { Button("Delete this contact", role: .destructive) { store.remove(qso.id); dismiss() } } }
            }
            .navigationTitle(isNew ? "New contact" : qso.call)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.disabled(qso.call.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .onAppear { mhzText = qso.mhz > 0 ? String(qso.mhz) : ""; wattsText = qso.watts.map { String($0) } ?? "" }
        }
    }

    private func save() {
        qso.call = qso.call.trimmingCharacters(in: .whitespaces).uppercased()
        qso.mhz = Double(mhzText.replacingOccurrences(of: ",", with: ".")) ?? 0
        qso.grid = qso.grid.uppercased(); qso.myGrid = qso.myGrid.uppercased()
        qso.watts = Double(wattsText.replacingOccurrences(of: ",", with: "."))
        qso.sig = qso.sigInfo.isEmpty ? "" : (qso.sigInfo.hasPrefix("US-") || qso.sigInfo.contains("-") && qso.sigInfo.count <= 8 ? "POTA" : qso.sig)
        if isNew { store.add(qso) } else { store.update(qso) }
        dismiss()
    }
}
