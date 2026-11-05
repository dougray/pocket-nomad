// DriveLog.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// A plain-text diary of each drive: when drive mode started and stopped (and
// why), when the app went to the background or was relaunched, radio links
// coming and going, pings and their answers, and a heartbeat each minute. It
// never leaves the phone; it is there so a drive that maps nothing can be
// explained afterwards. Locations are never written to it.

import Foundation
import UIKit

@MainActor
final class DriveLog {
    static let shared = DriveLog()

    /// Oldest lines are dropped past this many.
    static let maxLines = 4000

    private let fileURL: URL
    private let now: () -> Date
    private var lines: [String]
    private var observers: [NSObjectProtocol] = []
    private let stamp: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        f.timeZone = .current
        return f
    }()

    init(fileURL: URL = URL.applicationSupportDirectory.appending(path: "drive-log.txt"),
         now: @escaping () -> Date = Date.init) {
        self.fileURL = fileURL
        self.now = now
        lines = (try? String(contentsOf: fileURL, encoding: .utf8))?.split(separator: "\n").map(String.init) ?? []
    }

    var text: String { lines.joined(separator: "\n") }

    func note(_ event: String) {
        lines.append("\(stamp.string(from: now())) \(event)")
        if lines.count > Self.maxLines { lines.removeFirst(lines.count - Self.maxLines) }
        save()
    }

    /// Notes app lifecycle changes while a drive is running.
    func watchApp(while active: @escaping @MainActor () -> Bool) {
        guard observers.isEmpty else { return }
        let events: [(Notification.Name, String)] = [
            (UIApplication.didEnterBackgroundNotification, "app went to the background"),
            (UIApplication.willEnterForegroundNotification, "app came back to the front"),
            (UIApplication.didReceiveMemoryWarningNotification, "iOS warned the app about low memory"),
            (UIApplication.willTerminateNotification, "app is being closed"),
            (UIApplication.protectedDataWillBecomeUnavailableNotification, "phone locked"),
            (UIApplication.protectedDataDidBecomeAvailableNotification, "phone unlocked"),
        ]
        for (name, text) in events {
            observers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { if active() { self.note(text) } }
            })
        }
    }

    private func save() {
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        // Readable while the phone is locked, so a locked-phone drive still logs.
        try? Data(text.utf8).write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}
