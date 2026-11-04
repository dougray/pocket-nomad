import SwiftUI
import WatchKit

/// Full-screen SOS light: white flashes in the Morse SOS rhythm (· · · — — — · · ·)
/// with a haptic tap on each flash. Works with no phone.
struct SignalView: View {
    @State private var isOn = false
    @Environment(\.dismiss) private var dismiss
    /// About 8 words per minute, slow enough to read from a distance.
    private let unit = 0.15

    var body: some View {
        ZStack {
            (isOn ? Color.white : Color.black).ignoresSafeArea()
            VStack(spacing: 4) {
                Spacer()
                Text("SOS")
                    .font(.system(size: 40, weight: .heavy, design: .rounded))
                    .foregroundStyle(isOn ? WatchTheme.danger : WatchTheme.accent)
                Text("· · · — — — · · ·").font(.footnote.monospaced())
                    .foregroundStyle(isOn ? .black : .gray)
                Spacer()
                MeshSOSButton(compact: true)
                Text("Tap the screen to stop").font(.caption2).foregroundStyle(isOn ? .black : .gray)
            }
            .padding(.horizontal, 6)
        }
        .contentShape(Rectangle())
        .onTapGesture { dismiss() }
        .toolbar(.hidden, for: .navigationBar)
        .task { await flash() }
        .onDisappear { isOn = false }
        .accessibilityLabel("Flashing SOS. Tap to stop.")
    }

    private func flash() async {
        let steps = Morse.timeline("SOS")
        while !Task.isCancelled {
            for step in steps {
                if Task.isCancelled { break }
                isOn = step.on
                if step.on { WKInterfaceDevice.current().play(step.units > 1 ? .directionUp : .click) }
                try? await Task.sleep(for: .seconds(unit * Double(step.units)))
            }
        }
        isOn = false
    }
}

/// "Send mesh SOS", shown only when the phone is reachable and on a radio.
/// Always asks before sending.
struct MeshSOSButton: View {
    var compact = false
    @State private var connector = WatchConnector.shared
    @State private var location = WatchLocation.shared
    @State private var confirming = false
    @State private var sending = false
    @State private var result: WatchRelay.Response?

    var body: some View {
        Group {
            if connector.canUseMesh {
                Button { confirming = true } label: {
                    Label(sending ? "Sending…" : "Send mesh SOS", systemImage: "antenna.radiowaves.left.and.right")
                        .font(compact ? .footnote.weight(.semibold) : .headline)
                        .frame(maxWidth: .infinity)
                }
                .disabled(sending)
                .modifier(MeshButtonStyle(compact: compact))
            } else if !compact {
                Text("Mesh SOS needs your iPhone nearby with a radio connected.")
                    .font(.footnote).foregroundStyle(.secondary)
                    .listRowBackground(Color.clear)
            }
        }
        .confirmationDialog("Send SOS on the mesh?", isPresented: $confirming, titleVisibility: .visible) {
            Button("Send SOS", role: .destructive) { send() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(location.recentFix != nil
                 ? "Everyone on the primary channel gets SOS with this watch's location."
                 : "Everyone on the primary channel gets SOS with your iPhone's location, if it has one.")
        }
        .alert(result?.ok == true ? "SOS sent" : "Not sent", isPresented: Binding(
            get: { result != nil }, set: { if !$0 { result = nil } })) {
            Button("OK") { result = nil }
        } message: {
            Text(result?.detail ?? "")
        }
        .onAppear { location.begin() }
        .onDisappear { location.end() }
    }

    private func send() {
        sending = true
        let fix = location.recentFix
        Task {
            result = await connector.send(.sos(latitude: fix?.latitude, longitude: fix?.longitude))
            sending = false
            WKInterfaceDevice.current().play(result?.ok == true ? .success : .failure)
        }
    }
}

private struct MeshButtonStyle: ViewModifier {
    let compact: Bool
    func body(content: Content) -> some View {
        if compact {
            content.buttonStyle(.borderedProminent).tint(WatchTheme.orange)
        } else {
            content.listRowBackground(RoundedRectangle(cornerRadius: 14).fill(WatchTheme.orange.opacity(0.85)))
        }
    }
}
