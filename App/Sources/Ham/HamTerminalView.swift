// HamTerminalView.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0
//
// Connected-mode packet: BBSes, nodes and keyboard-to-keyboard chat.

import SwiftUI

struct HamTerminalView: View {
    @State private var ham = HamService.shared
    @State private var call = ""
    @State private var path = ""
    @State private var line = ""
    @FocusState private var typing: Bool

    var body: some View {
        VStack(spacing: 0) {
            if ham.terminalState == .disconnected {
                connectForm
            }
            ScrollViewReader { proxy in
                ScrollView {
                    Text(ham.terminalText.isEmpty ? "Not connected." : ham.terminalText)
                        .font(.system(.footnote, design: .monospaced))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                        .padding(10)
                    Color.clear.frame(height: 1).id("end")
                }
                .background(Color.black.opacity(0.04))
                .onChange(of: ham.terminalText) { _, _ in proxy.scrollTo("end", anchor: .bottom) }
            }
            if ham.terminalState == .connected {
                HStack {
                    TextField("Type a command and press return", text: $line)
                        .textFieldStyle(.roundedBorder)
                        .font(.system(.body, design: .monospaced))
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .focused($typing)
                        .onSubmit { send() }
                    Button("Send") { send() }
                }
                .padding()
            }
        }
        .navigationTitle(ham.terminalPeer ?? "Terminal")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if ham.terminalState == .connected || ham.terminalState == .connecting {
                Button("Disconnect", role: .destructive) { ham.disconnectTerminal() }
            }
            Menu {
                Button("Clear screen") { ham.clearTerminal() }
            } label: { Image(systemName: "ellipsis.circle") }
        }
    }

    private func send() {
        ham.sendTerminal(line)
        line = ""
        typing = true
    }

    private var connectForm: some View {
        VStack(alignment: .leading, spacing: 10) {
            if !ham.settings.canTransmit {
                Label("Listen only: connecting transmits, so it needs your callsign and licence in Station settings.",
                      systemImage: "ear").font(.footnote)
            }
            HStack {
                TextField("Callsign, e.g. KF0BBS-1", text: $call).textInputAutocapitalization(.characters).autocorrectionDisabled()
                TextField("via (optional)", text: $path).textInputAutocapitalization(.characters).autocorrectionDisabled()
            }
            .textFieldStyle(.roundedBorder)
            HStack {
                Button("Connect") { ham.connectTerminal(call: call, path: path) }
                    .buttonStyle(.borderedProminent).tint(Theme.accent)
                    .disabled(AX25Address(call) == nil || !ham.tnc.isConnected || !ham.settings.canTransmit)
                if AX25Address(call) != nil {
                    Button("Save") { ham.settings.nodes.append(HamSavedStation(call: call.uppercased(), path: path.uppercased())) }
                }
                Spacer()
            }
            if !ham.settings.nodes.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(ham.settings.nodes) { n in
                            Button(n.path.isEmpty ? n.call : "\(n.call) via \(n.path)") { call = n.call; path = n.path }
                                .buttonStyle(.bordered)
                                .contextMenu { Button("Remove", role: .destructive) { ham.settings.nodes.removeAll { $0.id == n.id } } }
                        }
                    }
                }
            }
        }
        .padding()
    }
}
