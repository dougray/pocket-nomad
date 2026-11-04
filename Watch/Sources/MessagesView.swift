import SwiftUI
import WatchKit

/// Recent mesh messages, relayed from the phone. Read-only until you tap a reply.
struct MessagesView: View {
    @State private var connector = WatchConnector.shared

    var body: some View {
        List {
            Section {
                statusLine
            }
            if connector.canUseMesh {
                NavigationLink(value: WatchRoute.reply(.channel(0), "Primary channel")) {
                    Label("Message the channel", systemImage: "arrowshape.turn.up.left.fill")
                }
            }
            ForEach(connector.status?.messages ?? []) { m in
                NavigationLink(value: WatchRoute.reply(m.conversation, m.conversationTitle)) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(m.sender).font(.caption.weight(.bold))
                                .foregroundStyle(m.isOutgoing ? .secondary : WatchTheme.accent)
                            Spacer()
                            Text(m.date, style: .relative).font(.caption2).foregroundStyle(.secondary)
                        }
                        Text(m.text).font(.footnote)
                        if case .direct = m.conversation {
                            Text("Direct").font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
                .disabled(!connector.canUseMesh)
            }
        }
        .navigationTitle("Messages")
        .task {
            // Ask while the screen is open; the phone also pushes changes.
            while !Task.isCancelled {
                await connector.refresh()
                try? await Task.sleep(for: .seconds(15))
            }
        }
    }

    @ViewBuilder
    private var statusLine: some View {
        if !connector.isReachable {
            Text("iPhone not reachable. Messages come through Pocket Nomad on your phone.")
                .font(.footnote).foregroundStyle(.secondary)
        } else if connector.status?.radioConnected == true {
            Label(connector.status?.radioName ?? "Radio connected", systemImage: "dot.radiowaves.left.and.right")
                .font(.footnote).foregroundStyle(.green)
        } else {
            Text("Your iPhone isn't connected to a mesh radio.").font(.footnote).foregroundStyle(.secondary)
        }
    }
}

/// Quick replies and dictation. Each one is sent only when tapped; dictated text
/// is shown back for a second tap before it goes.
struct QuickReplyView: View {
    let conversation: WatchRelay.Conversation
    let title: String
    @State private var connector = WatchConnector.shared
    @State private var dictated: String?
    @State private var sending = false
    @State private var result: WatchRelay.Response?

    var body: some View {
        List {
            Section {
                Text("To \(title)").font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(WatchRelay.quickReplies, id: \.self) { reply in
                Button { send(reply) } label: {
                    Text(reply).font(.headline)
                }
                .listRowBackground(RoundedRectangle(cornerRadius: 14).fill(WatchTheme.card))
            }
            TextFieldLink(prompt: Text("Say a reply")) {
                Label("Dictate…", systemImage: "mic.fill")
            } onSubmit: { text in
                dictated = WatchRelay.cleaned(text)
            }
        }
        .disabled(sending || !connector.canUseMesh)
        .navigationTitle("Reply")
        .confirmationDialog("Send this?", isPresented: Binding(
            get: { dictated != nil }, set: { if !$0 { dictated = nil } }), titleVisibility: .visible) {
            Button("Send") { if let d = dictated { send(d) }; dictated = nil }
            Button("Cancel", role: .cancel) { dictated = nil }
        } message: {
            Text(dictated ?? "")
        }
        .alert(result?.ok == true ? "Sent" : "Not sent", isPresented: Binding(
            get: { result != nil }, set: { if !$0 { result = nil } })) {
            Button("OK") { result = nil }
        } message: {
            Text(result?.detail ?? "")
        }
    }

    private func send(_ text: String) {
        sending = true
        Task {
            result = await connector.send(.reply(text: text, conversation: conversation))
            sending = false
            WKInterfaceDevice.current().play(result?.ok == true ? .success : .failure)
        }
    }
}
