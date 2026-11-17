// HamWinlinkViews.swift
// Pocket Nomad - Ham module
// SPDX-License-Identifier: Apache-2.0

import SwiftUI

struct WinlinkHomeView: View {
    @State private var ham = HamService.shared
    @State private var composing = false

    var body: some View {
        List {
            Section {
                ForEach(WinlinkFolder.allCases) { f in
                    NavigationLink(value: HamRoute.mailFolder(f)) {
                        HStack {
                            Label(f.rawValue, systemImage: f == .inbox ? "tray.fill" : f == .outbox ? "tray.and.arrow.up.fill" : "paperplane.fill")
                            Spacer()
                            Text("\(ham.messages(in: f).count)").foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Section {
                NavigationLink(value: HamRoute.winlink) {
                    Label("Connect to a gateway to send and receive", systemImage: "arrow.up.arrow.down.circle.fill")
                }
            } footer: {
                Text("Messages wait in the Outbox until you connect. Your address is \(ham.settings.callsignValid ? ham.settings.baseCall : "YOURCALL")@winlink.org.")
            }
        }
        .navigationTitle("Winlink mail")
        .toolbar {
            Button { composing = true } label: { Image(systemName: "square.and.pencil") }
                .disabled(!ham.settings.callsignValid)
        }
        .sheet(isPresented: $composing) { WinlinkComposeView() }
    }
}

struct WinlinkFolderView: View {
    let folder: WinlinkFolder
    @State private var ham = HamService.shared

    var body: some View {
        List {
            let msgs = ham.messages(in: folder)
            if msgs.isEmpty { Text("Nothing here.").foregroundStyle(.secondary) }
            ForEach(msgs) { m in
                NavigationLink(value: HamRoute.mailMessage(m.mid, folder)) {
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            if folder == .inbox, !ham.readMIDs.contains(m.mid) { Circle().fill(Theme.accent).frame(width: 8, height: 8) }
                            Text(folder == .inbox ? WinlinkMessage.displayAddress(m.from) : m.to.map(WinlinkMessage.displayAddress).joined(separator: ", "))
                                .font(.headline).lineLimit(1)
                            Spacer()
                            Text(m.date.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                        }
                        Text(m.subject).lineLimit(1)
                        Text(m.body).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                    }
                }
                .swipeActions { Button("Delete", role: .destructive) { ham.delete(m, from: folder) } }
            }
        }
        .navigationTitle(folder.rawValue)
    }
}

struct WinlinkMessageView: View {
    let mid: String
    let folder: WinlinkFolder
    @State private var ham = HamService.shared
    @State private var replying: WinlinkMessage?

    var body: some View {
        let m = ham.messages(in: folder).first { $0.mid == mid }
        ScrollView {
            if let m {
                VStack(alignment: .leading, spacing: 12) {
                    Text(m.subject).font(.title3.bold())
                    Group {
                        Text("From: ") + Text(WinlinkMessage.displayAddress(m.from)).bold()
                        Text("To: " + m.to.map(WinlinkMessage.displayAddress).joined(separator: ", "))
                        if !m.cc.isEmpty { Text("Cc: " + m.cc.map(WinlinkMessage.displayAddress).joined(separator: ", ")) }
                        Text(m.date.formatted(date: .complete, time: .shortened))
                    }
                    .font(.subheadline).foregroundStyle(.secondary)
                    Divider()
                    Text(m.body).textSelection(.enabled)
                    ForEach(m.attachments, id: \.name) { a in
                        ShareLink(item: HamAttachmentFile(attachment: a), preview: SharePreview(a.name)) {
                            Label("\(a.name) (\(ByteCountFormatter.string(fromByteCount: Int64(a.data.count), countStyle: .file)))",
                                  systemImage: "paperclip")
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
            }
        }
        .navigationTitle(folder.rawValue)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let m, folder == .inbox, ham.settings.callsignValid {
                Button("Reply") { replying = m }
            }
        }
        .sheet(item: $replying) { WinlinkComposeView(replyTo: $0) }
        .onAppear { if folder == .inbox { ham.markRead(mid: mid) } }
    }
}

struct HamAttachmentFile: Transferable {
    let attachment: WinlinkAttachment
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .data) { f in
            let url = FileManager.default.temporaryDirectory.appending(path: f.attachment.name.replacingOccurrences(of: "/", with: "_"))
            try f.attachment.data.write(to: url)
            return SentTransferredFile(url)
        }
    }
}

struct WinlinkComposeView: View {
    var replyTo: WinlinkMessage?
    @State private var ham = HamService.shared
    @Environment(\.dismiss) private var dismiss
    @State private var to = ""
    @State private var cc = ""
    @State private var subject = ""
    @State private var bodyText = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("To: callsign or email", text: $to).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Cc (optional)", text: $cc).textInputAutocapitalization(.never).autocorrectionDisabled()
                    TextField("Subject", text: $subject)
                } footer: {
                    Text("Separate several addresses with commas. Callsigns reach other Winlink users; full addresses reach any email (the recipient may need to whitelist you).")
                }
                Section("Message") {
                    TextEditor(text: $bodyText).frame(minHeight: 200)
                }
                Section {
                    Text("Keep it short: packet sends about 100 characters a second at best. No encryption or business use on amateur radio.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(replyTo == nil ? "New message" : "Reply")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Outbox") {
                        let split = { (s: String) in s.split(whereSeparator: { $0 == "," || $0 == ";" || $0 == " " }).map(String.init) }
                        if ham.compose(to: split(to), cc: split(cc), subject: subject, body: bodyText) { dismiss() }
                    }
                    .disabled(to.trimmingCharacters(in: .whitespaces).isEmpty || subject.isEmpty)
                }
            }
            .onAppear {
                if let r = replyTo, to.isEmpty {
                    to = WinlinkMessage.displayAddress(r.from)
                    subject = r.subject.lowercased().hasPrefix("re:") ? r.subject : "Re: " + r.subject
                    bodyText = "\n\n--- \(WinlinkMessage.displayAddress(r.from)) wrote:\n" + r.body
                }
            }
        }
    }
}

struct WinlinkConnectView: View {
    @State private var ham = HamService.shared
    @State private var call = ""
    @State private var path = ""
    @State private var note = ""

    var body: some View {
        List {
            Section {
                switch ham.winlink {
                case .idle: Text("Choose a gateway below.").foregroundStyle(.secondary)
                case .connecting(let g): HStack { ProgressView(); Text("Connecting to \(g)…") }
                case .exchanging(let g):
                    VStack(alignment: .leading) {
                        HStack { ProgressView(); Text("Exchanging mail with \(g)") }
                        if let p = ham.winlinkProgress, p.total > 0 {
                            ProgressView(p.label, value: Double(p.done), total: Double(p.total)).font(.caption)
                        }
                    }
                case .done(let s): Label(s, systemImage: "checkmark.circle.fill").foregroundStyle(MeshUI.ok)
                case .failed(let s): Label(s, systemImage: "exclamationmark.triangle.fill").foregroundStyle(Theme.danger)
                }
                if ham.winlink.busy { Button("Stop", role: .destructive) { ham.abortWinlink() } }
            } header: { Text("Session") } footer: {
                Text("\(ham.outbox.count) message(s) in the Outbox. \(ham.settings.hasWinlinkPassword ? "" : "No Winlink password set: gateways that ask for it will refuse the login.")")
            }

            Section {
                ForEach(ham.settings.gateways) { g in
                    Button {
                        ham.connectWinlink(gateway: g)
                    } label: {
                        VStack(alignment: .leading) {
                            Text(g.call).font(.headline)
                            Text([g.path.isEmpty ? "direct" : "via " + g.path, g.note].filter { !$0.isEmpty }.joined(separator: " · "))
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .disabled(ham.winlink.busy || !ham.tnc.isConnected || !ham.settings.canTransmit)
                }
                .onDelete { ham.settings.gateways.remove(atOffsets: $0) }
            } header: { Text("Gateways") } footer: {
                Text("Find packet RMS gateways near you at winlink.org/RMSChannels (print or save the list before you need it). Tune the radio to the gateway's frequency first.")
            }

            Section("Add a gateway") {
                TextField("Gateway callsign, e.g. KF0RMS-10", text: $call).textInputAutocapitalization(.characters)
                TextField("Path (optional), e.g. KF0DIG-1", text: $path).textInputAutocapitalization(.characters)
                TextField("Note, e.g. 145.010 MHz", text: $note)
                Button("Save gateway") {
                    ham.settings.gateways.append(HamSavedStation(call: call.uppercased(), path: path.uppercased(), note: note))
                    call = ""; path = ""; note = ""
                }
                .disabled(AX25Address(call) == nil || AX25Path.parse(path) == nil)
            }

            if !ham.winlinkLog.isEmpty {
                Section("Session log") {
                    Text(ham.winlinkLog.suffix(60).joined(separator: "\n"))
                        .font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                }
            }
        }
        .navigationTitle("Winlink gateway")
    }
}
