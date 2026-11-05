import Foundation

/// What the Apple Watch and the iPhone say to each other over WatchConnectivity.
/// Every message is one JSON blob under `payloadKey`, so both sides decode the
/// same Codable types. The phone only ever sends to the mesh when a request
/// arrives, and the watch only makes a request when the user taps.
enum WatchRelay {
    static let payloadKey = "relay"

    static let quickReplies = ["OK", "On my way", "Need help", "At the meeting point"]

    enum Request: Codable, Equatable, Sendable {
        /// "What can you do right now, and what's new?"
        case status
        /// Send the mesh SOS on the primary channel. Coordinates are the watch's
        /// own fix; nil means the phone uses its own location (if allowed).
        case sos(latitude: Double?, longitude: Double?)
        /// Send `text` to a conversation (see `Conversation`). `network` is the
        /// mesh it belongs to ("meshtastic" or "meshcore"); nil (older watch
        /// apps) means the phone's default radio.
        case reply(text: String, conversation: Conversation, network: String? = nil)
    }

    /// Mirrors the phone's MeshConversation without depending on the mesh code.
    enum Conversation: Codable, Hashable, Sendable {
        case channel(UInt32)
        case direct(UInt32)
    }

    struct Message: Codable, Identifiable, Equatable, Sendable {
        let id: UUID
        let sender: String
        let text: String
        let date: Date
        let isOutgoing: Bool
        let conversation: Conversation
        /// "Primary channel", or the other person's name for a direct message.
        let conversationTitle: String
        /// The mesh it came in on ("meshtastic" or "meshcore"), so a reply goes back on it.
        var network: String? = nil
    }

    struct Status: Codable, Equatable, Sendable {
        /// The phone is connected to a mesh radio and can send now.
        var radioConnected: Bool
        var radioName: String?
        var maxTextBytes: Int
        /// Most recent first, at most `maxMessages`.
        var messages: [Message]
        /// Networks with a connected radio, when the phone has more than one
        /// kind ("meshtastic", "meshcore"). Nil from older phone apps.
        var networks: [String]? = nil
    }

    struct Response: Codable, Equatable, Sendable {
        var ok: Bool
        /// What was sent, or why nothing was.
        var detail: String
        var status: Status?
    }

    /// WatchConnectivity messages must stay small; this keeps a status well under 64 KB.
    static let maxMessages = 20

    static func encode<T: Encodable>(_ value: T) throws -> [String: Any] {
        [payloadKey: try JSONEncoder().encode(value)]
    }

    static func decode<T: Decodable>(_ type: T.Type, from message: [String: Any]) throws -> T {
        guard let data = message[payloadKey] as? Data else { throw RelayError.missingPayload }
        return try JSONDecoder().decode(type, from: data)
    }

    enum RelayError: Error { case missingPayload }

    /// "Meshtastic" or "MeshCore" for a network's raw name.
    static func networkName(_ network: String?) -> String? {
        switch network {
        case "meshtastic": "Meshtastic"
        case "meshcore": "MeshCore"
        default: nil
        }
    }

    /// Trims a reply and refuses empty ones. Length is checked by the phone,
    /// which knows the radio's limit.
    static func cleaned(_ text: String) -> String? {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    /// Newest first, capped, with outgoing and incoming mixed as they happened.
    static func recent(_ messages: [Message]) -> [Message] {
        Array(messages.sorted { $0.date > $1.date }.prefix(maxMessages))
    }
}
