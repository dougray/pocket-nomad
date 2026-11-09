import SwiftUI

/// The privacy policy in short, readable offline. The full text is docs/PRIVACY.md.
struct PrivacySummaryView: View {
    private let points: [(String, String, String)] = [
        ("iphone", "Everything stays on this phone",
         "Medical ID, messages, nodes, pins, checklists, coverage recordings and settings are stored only on this iPhone (and in its backups, if you use them). Deleting the app deletes them."),
        ("person.crop.circle.badge.xmark", "No accounts, analytics or tracking",
         "The developers never receive any of your data. There are no servers."),
        ("brain", "AI runs on the phone",
         "Ask uses Apple's on-device model or a model file you downloaded. Questions and answers aren't saved."),
        ("location", "Location only for you",
         "Used for the map, the compass, an SOS you choose to send with your position, and the coverage scanner if you turn it on. Drive mode keeps the scanner going with the phone locked until you stop it. The app never sends your location by itself."),
        ("antenna.radiowaves.left.and.right", "Mesh messages go over the air",
         "What you send through your radio reaches other people's radios. Default public channels use a well-known key, so anyone nearby can read them. Use a private channel for anything sensitive."),
        ("circle.hexagongrid", "Coverage sharing is opt-in",
         "Off by default, and it needs a private zone first. Before a file exists, the phone removes your private zones, exact positions, node names and IDs, and dates finer than a month. Nothing is sent: you export the file and choose where it goes."),
        ("arrow.down.circle", "Downloads you start",
         "Getting books or AI models connects to the Kiwix library or Hugging Face, and getting known radios connects to meshmap.net and map.meshcore.io. They see your IP address and the files you request, not your location. Web links open in Safari."),
    ]

    var body: some View {
        List {
            ForEach(points, id: \.1) { symbol, title, text in
                Label {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(title).font(.headline)
                        Text(text).font(.subheadline).foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: symbol).foregroundStyle(Theme.accent)
                }
                .padding(.vertical, 4)
            }
            Text("Effective November 3, 2026. The full policy is in the project's source code, docs/PRIVACY.md.")
                .font(.footnote).foregroundStyle(.secondary)
        }
        .navigationTitle("Your data")
        .navigationBarTitleDisplayMode(.inline)
    }
}
