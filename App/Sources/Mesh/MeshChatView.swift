// MeshChatView.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Chat thread for the primary channel or a direct message, on one network.
// Replies always go out on the thread's own network (its own radio).

import SwiftUI

struct MeshChatView: View {
    let conversation: MeshConversation
    /// The thread's network; nil means the default radio's.
    var network: MeshFirmware?
    @State private var mesh = MeshService.shared
    @State private var draft = ""
    @State private var blocking: MeshNode?
    @FocusState private var focused: Bool

    private var net: MeshFirmware { network ?? mesh.defaultNetwork }
    private var maxBytes: Int { net.maxTextBytes }
    private var thread: [MeshMessage] { mesh.messages(in: conversation, network: net) }
    private var bytes: Int { MeshText.byteCount(draft.trimmingCharacters(in: .whitespacesAndNewlines)) }
    private var tooLong: Bool { bytes > maxBytes }
    private var radioReady: Bool { mesh.canSend(on: net) }
    private var canSend: Bool { radioReady && bytes > 0 && !tooLong }
    /// Two networks in use: say which one this thread is on.
    private var labelNetwork: Bool { mesh.networksInUse.count > 1 }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 10) {
                    if thread.isEmpty {
                        emptyState.padding(.top, 40)
                    }
                    ForEach(thread) { m in
                        MeshBubble(message: m,
                                   sender: m.isOutgoing || m.from == 0 ? nil : mesh.node(m.from, network: net),
                                   showSender: isChannel)
                            .id(m.id)
                            .contextMenu {
                                if !m.isOutgoing && m.from != 0 {
                                    Button("Block Sender", systemImage: "hand.raised", role: .destructive) { blocking = mesh.node(m.from, network: net) }
                                }
                            }
                    }
                }
                .padding(16)
            }
            .scrollDismissesKeyboard(.interactively)
            .onAppear {
                mesh.markRead(conversation, network: net)
                if let last = thread.last { proxy.scrollTo(last.id, anchor: .bottom) }
            }
            .onChange(of: thread.count) {
                mesh.markRead(conversation, network: net)
                if let last = thread.last {
                    withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                }
            }
        }
        .safeAreaInset(edge: .bottom) { composer }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if labelNetwork {
                ToolbarItem(placement: .principal) {
                    VStack(spacing: 2) {
                        Text(title).font(.headline).lineLimit(1)
                        MeshNetworkBadge(network: net)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .meshBlockConfirmation($blocking)
    }

    private var isChannel: Bool {
        if case .channel = conversation { return true }
        return false
    }

    private var title: String {
        switch conversation {
        case .channel(let i):
            let name = mesh.link(net).radio.channelNames[Int32(i)] ?? ""
            return name.isEmpty ? (i == 0 ? "Primary channel" : "Channel \(i)") : name
        case .direct(let n):
            return mesh.node(n, network: net).displayName
        }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: isChannel ? "bubble.left.and.bubble.right" : "person.crop.circle.badge.questionmark")
                .font(.system(size: 44))
                .foregroundStyle(.secondary)
            Text(isChannel
                 ? "No messages yet. Anything you send here reaches every radio in range on this channel."
                 : "No messages yet. Only this person's radio can read what you send here.")
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
        }
    }

    private var composer: some View {
        VStack(spacing: 6) {
            if !radioReady {
                Text(labelNetwork ? "Connect your \(net.displayName) radio to send." : "Connect a radio to send.")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(MeshUI.warn)
            }
            HStack(alignment: .bottom, spacing: 10) {
                TextField("Message", text: $draft, axis: .vertical)
                    .font(.title3)
                    .lineLimit(1...5)
                    .focused($focused)
                    .padding(.horizontal, 14).padding(.vertical, 12)
                    .background(Theme.card, in: RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14)
                        .strokeBorder(tooLong ? Theme.danger : Color.secondary.opacity(0.4), lineWidth: tooLong ? 2 : 1))
                    .submitLabel(.send)
                Button(action: send) {
                    Image(systemName: "paperplane.fill")
                        .font(.title2.weight(.bold))
                        .frame(width: MeshUI.minTap, height: MeshUI.minTap)
                        .foregroundStyle(.black)
                        .background(Circle().fill(canSend ? Theme.accent : Color.gray.opacity(0.5)))
                }
                .disabled(!canSend)
                .accessibilityLabel("Send")
            }
            HStack {
                Text(tooLong ? "Too long, trim \(bytes - maxBytes) bytes" : "Emoji and accents use extra bytes")
                Spacer()
                Text("\(bytes)/\(maxBytes)")
                    .monospacedDigit()
                    .fontWeight(.bold)
            }
            .font(.footnote)
            .foregroundStyle(tooLong ? Theme.danger : (bytes > maxBytes - 20 ? MeshUI.warn : .secondary))
            .accessibilityElement(children: .combine)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(.bar)
    }

    private func send() {
        guard canSend else { return }
        if mesh.send(draft, to: conversation, network: net) {
            draft = ""
        }
    }
}

struct MeshBubble: View {
    let message: MeshMessage
    let sender: MeshNode?
    let showSender: Bool

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            if message.isOutgoing { Spacer(minLength: 40) }
            if let sender, !message.isOutgoing, showSender {
                MeshNodeBadge(node: sender, size: 36)
            }
            VStack(alignment: message.isOutgoing ? .trailing : .leading, spacing: 4) {
                // MeshCore channel messages carry a name even when the sender isn't a contact.
                if !message.isOutgoing, showSender, let label = message.senderName ?? sender?.displayName {
                    Text(label).font(.caption.weight(.bold)).foregroundStyle(.secondary)
                }
                Text(message.text)
                    .font(.title3)
                    .foregroundStyle(message.isOutgoing ? Color.black : Color.primary)
                    .textSelection(.enabled)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(
                        RoundedRectangle(cornerRadius: 16)
                            .fill(message.isOutgoing ? Theme.accent : Theme.card)
                    )
                footer
            }
            if !message.isOutgoing { Spacer(minLength: 40) }
        }
        .accessibilityElement(children: .combine)
    }

    private var footer: some View {
        HStack(spacing: 6) {
            Text(message.date, style: .time)
            if let snr = message.rxSNR { Text(String(format: "SNR %.1f", snr)) }
            if let h = message.hopsAway, h > 0 { Text("\(h) hop\(h == 1 ? "" : "s")") }
            if message.isOutgoing { deliveryLabel }
        }
        .font(.caption)
        .foregroundStyle(.secondary)
    }

    @ViewBuilder
    private var deliveryLabel: some View {
        switch message.delivery {
        case .sent:
            Label("Sent", systemImage: "checkmark")
        case .heardByMesh:
            Label(message.isBroadcast ? "Heard by mesh" : "Relayed", systemImage: "dot.radiowaves.left.and.right")
                .foregroundStyle(MeshUI.ok)
        case .notRepeated:
            Label("Sent · no one nearby repeated it", systemImage: "checkmark")
        case .delivered:
            Label("Delivered", systemImage: "checkmark.circle.fill").foregroundStyle(MeshUI.ok)
        case .failed(let why):
            Label("Failed: \(why)", systemImage: "exclamationmark.triangle.fill").foregroundStyle(Theme.danger)
        case .received:
            EmptyView()
        }
    }
}
