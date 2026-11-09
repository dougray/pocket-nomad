// CoverageBackupService.swift
// Pocket Nomad - Mesh module
// SPDX-License-Identifier: Apache-2.0
//
// Keeps a copy of the coverage backup (CoverageBackup) in a folder on this
// phone that the user picks outside the app, such as On My iPhone › Pocket
// Nomad Backups, so deleting and reinstalling the app doesn't lose months of
// scanning. Backups stay on the device: cloud folders (iCloud Drive or any
// other cloud provider) are refused, and so are folders inside the app, which
// are deleted with it. The folder is remembered with a security-scoped bookmark.
// Nothing is uploaded; the only way a copy leaves the phone is the user
// exporting one by hand.
//
// It writes after a drive ends (when there are new points and the last backup
// is at least 10 minutes old) and when the app goes to the background (when
// anything changed). It also makes one-off files for the share sheet and
// restores from a file, merging so nothing on the phone is lost.

import Foundation
import Observation
import UIKit

@MainActor @Observable
final class CoverageBackupService {
    static let shared = CoverageBackupService()

    /// After a drive, at most one automatic backup per this long.
    static let minInterval: TimeInterval = 10 * 60

    private(set) var folderName: String?
    private(set) var lastBackup: Date?
    private(set) var lastError: String?
    private(set) var busy = false

    var isOn: Bool { folderName != nil }

    private let defaults: UserDefaults
    private let coverage: CoverageStore
    private let drive: () -> DriveMode
    private let now: () -> Date
    /// The app's own data folder; a backup inside it would go with the app.
    private let appContainer: String
    private var observers: [NSObjectProtocol] = []

    private enum Keys {
        static let bookmark = "backup.folderBookmark"
        static let lastBackup = "backup.lastBackup"
        /// CoverageStore.fingerprint when the last backup was written.
        static let fingerprint = "backup.fingerprint"
    }

    init(defaults: UserDefaults = .standard, coverage: CoverageStore = .shared,
         drive: @escaping () -> DriveMode = { .shared }, now: @escaping () -> Date = Date.init,
         appContainer: String = URL.documentsDirectory.deletingLastPathComponent().path) {
        self.appContainer = appContainer
        self.defaults = defaults
        self.coverage = coverage
        self.drive = drive
        self.now = now
        lastBackup = defaults.object(forKey: Keys.lastBackup) as? Date
        folderName = resolveFolder().map(Self.displayName)
    }

    /// Hooks the automatic backups up (call once at launch).
    func start() {
        guard observers.isEmpty else { return }
        observers.append(NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification,
                                                                object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.appWentToBackground() }
        })
    }

    // MARK: The folder

    /// Set when choosing a folder merged in a backup that was already there.
    private(set) var folderRestoreMessage: String?

    /// Remembers a folder from the document picker and backs up into it straight
    /// away. A backup already in the folder (from a reinstall, another phone, or
    /// the Android app) is merged in first, so it is never replaced by one with less in it.
    func chooseFolder(_ url: URL) async {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let ubiquitous = (try? url.resourceValues(forKeys: [.isUbiquitousItemKey]))?.isUbiquitousItem ?? false
        switch Self.check(path: url.path, isUbiquitous: ubiquitous, appContainer: appContainer) {
        case .local:
            break
        case .cloud:
            lastError = "Pick a folder on this iPhone (On My iPhone), not iCloud Drive or another cloud service."
            return
        case .insideApp:
            lastError = "That folder is inside Pocket Nomad and would be deleted with the app. Pick one at the top of On My iPhone, such as Pocket Nomad Backups."
            return
        }
        do {
            let bookmark = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
            defaults.set(bookmark, forKey: Keys.bookmark)
            folderName = Self.displayName(url)
            lastError = nil
        } catch {
            lastError = "Couldn't keep access to that folder. Try another one."
            return
        }
        folderRestoreMessage = nil
        let existing = url.appending(path: CoverageBackup.fileName)
        if FileManager.default.fileExists(atPath: existing.path) {
            do {
                folderRestoreMessage = "Found a backup in \(folderName ?? "that folder"). " + (try restore(from: existing))
            } catch {
                // An unreadable or newer file is left alone rather than written over.
                defaults.removeObject(forKey: Keys.bookmark)
                folderName = nil
                lastError = "That folder has a backup this app can't read (\((error as? LocalizedError)?.errorDescription ?? "unknown format")). It was left as it is; pick another folder."
                return
            }
        }
        await backUpNow()
    }

    /// Stops automatic backups. The file already in the folder stays there.
    func turnOff() {
        defaults.removeObject(forKey: Keys.bookmark)
        folderName = nil
        lastError = nil
    }

    private func resolveFolder() -> URL? {
        guard let data = defaults.data(forKey: Keys.bookmark) else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else {
            return nil
        }
        if stale {
            let scoped = url.startAccessingSecurityScopedResource()
            if let fresh = try? url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil) {
                defaults.set(fresh, forKey: Keys.bookmark)
            }
            if scoped { url.stopAccessingSecurityScopedResource() }
        }
        return url
    }

    nonisolated static func displayName(_ url: URL) -> String { url.lastPathComponent }

    enum FolderCheck: Equatable, Sendable {
        /// On this phone, outside the app: fine.
        case local
        /// iCloud Drive or another cloud provider: backups must stay on the device.
        case cloud
        /// In the app's own container, which deleting the app removes.
        case insideApp
    }

    /// Where a picked folder lives, from its path and its ubiquitous flag (set
    /// for iCloud Drive and for cloud File Provider items). Pure, for tests.
    nonisolated static func check(path: String, isUbiquitous: Bool, appContainer: String) -> FolderCheck {
        func normal(_ p: String) -> String {
            let p = p.hasPrefix("/private/") ? String(p.dropFirst("/private".count)) : p
            return p.hasSuffix("/") ? String(p.dropLast()) : p
        }
        let p = normal(path)
        let lower = p.lowercased()
        if isUbiquitous || lower.contains("/mobile documents/") || lower.hasSuffix("/mobile documents")
            || lower.contains("com~apple~clouddocs") || lower.contains("/library/clouddocs") || lower.contains("icloud") {
            return .cloud
        }
        let app = normal(appContainer)
        if !app.isEmpty, p == app || p.hasPrefix(app + "/") { return .insideApp }
        return .local
    }

    // MARK: Backing up

    func makeBackup() -> CoverageBackup {
        CoverageBackup(version: CoverageBackup.currentVersion, app: CoverageBackup.appName, createdAt: now(),
                       samples: coverage.samples, pings: coverage.pings, zones: coverage.zones,
                       settings: .init(recording: coverage.recording, sharing: coverage.sharing, retentionDays: coverage.retentionDays),
                       drive: drive().backupSettings)
    }

    /// Anything recorded or changed since the last backup.
    var hasChanges: Bool { defaults.string(forKey: Keys.fingerprint) != coverage.fingerprint }

    /// A drive just ended: back up if it found anything and the last backup isn't too recent.
    func driveStopped() {
        guard isOn, hasChanges else { return }
        if let last = lastBackup, now().timeIntervalSince(last) < Self.minInterval { return }
        Task { await backUpNow() }
    }

    private func appWentToBackground() {
        guard isOn, hasChanges, !busy else { return }
        // A little time to finish writing once the app is out of sight.
        let task = BackgroundTask()
        task.id = UIApplication.shared.beginBackgroundTask(withName: "coverage backup") {
            MainActor.assumeIsolated { task.end() }
        }
        Task {
            await backUpNow()
            task.end()
        }
    }

    /// One background-time grant, ended once whichever comes first: the write or iOS's deadline.
    private final class BackgroundTask: @unchecked Sendable {
        var id = UIBackgroundTaskIdentifier.invalid
        @MainActor func end() {
            guard id != .invalid else { return }
            UIApplication.shared.endBackgroundTask(id)
            id = .invalid
        }
    }

    /// Writes the backup into the chosen folder, replacing the previous one in one step.
    func backUpNow() async {
        guard !busy, let folder = resolveFolder() else { return }
        busy = true
        defer { busy = false }
        let fingerprint = coverage.fingerprint
        let backup = makeBackup()
        // Never write an empty backup: it could only replace a fuller one.
        guard !backup.isEmpty else { return }
        let data: Data
        do { data = try backup.encoded() } catch { lastError = "Couldn't make the backup."; return }
        let error = await Task.detached(priority: .utility) { Self.write(data, into: folder) }.value
        if let error {
            lastError = error
            return
        }
        lastError = nil
        lastBackup = now()
        defaults.set(lastBackup, forKey: Keys.lastBackup)
        defaults.set(fingerprint, forKey: Keys.fingerprint)
    }

    /// Coordinated, atomic write (the Files app may be reading the file). Returns an error message, or nil.
    nonisolated static func write(_ data: Data, into folder: URL) -> String? {
        let scoped = folder.startAccessingSecurityScopedResource()
        defer { if scoped { folder.stopAccessingSecurityScopedResource() } }
        let target = folder.appending(path: CoverageBackup.fileName)
        var coordinationError: NSError?
        var writeError: Error?
        NSFileCoordinator().coordinate(writingItemAt: target, options: .forReplacing, error: &coordinationError) { url in
            do { try data.write(to: url, options: .atomic) } catch { writeError = error }
        }
        if coordinationError != nil || writeError != nil {
            return "Couldn't write to the backup folder. Choose it again, or pick another."
        }
        return nil
    }

    // MARK: Export and restore

    /// A copy for the share sheet, in the temporary folder.
    func exportFile() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appending(path: CoverageBackup.fileName)
        try makeBackup().encoded().write(to: url, options: .atomic)
        return url
    }

    /// Reads a backup (from the file importer) and merges it in. Returns a one-line summary.
    func restore(from url: URL) throws -> String {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        var coordinationError: NSError?
        var read: Result<Data, Error> = .failure(CocoaError(.fileReadUnknown))
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { url in
            read = Result { try Data(contentsOf: url) }
        }
        if let coordinationError { throw coordinationError }
        return try restore(CoverageBackup.decode(read.get()))
    }

    /// Merges a backup into this phone: points and zones are added, never
    /// removed. Recording and sharing are never switched on by a restore (the
    /// summary says to, if they were on); drive switches come back only on a
    /// phone with no coverage data yet, and ping targets only fill gaps.
    func restore(_ backup: CoverageBackup) -> String {
        let freshPhone = coverage.isEmpty
        let before = coverage.samples.count + coverage.pings.count
        let added = coverage.merge(samples: backup.samples, pings: backup.pings, zones: backup.zones,
                                   retentionDays: backup.settings.retentionDays)
        drive().restore(backup.drive, switches: freshPhone)
        let total = coverage.samples.count + coverage.pings.count
        var parts = ["Restored \(added.samples.formatted()) points and \(added.pings.formatted()) pings"]
        if added.zones > 0 { parts.append("\(added.zones) private zone\(added.zones == 1 ? "" : "s")") }
        let skipped = backup.samples.count + backup.pings.count - added.samples - added.pings
        var line = parts.joined(separator: ", ") + "."
        if skipped > 0 { line += " \(skipped.formatted()) were already here." }
        // The longest "keep for" is a year; anything older goes at the next tidy-up.
        let pruned = added.samples + added.pings - (total - before)
        if pruned > 0 { line += " \(pruned.formatted()) older than a year weren't kept." }
        var off: [String] = []
        if backup.settings.recording == true && !coverage.recording { off.append("recording") }
        if backup.settings.sharing == true && !coverage.sharing { off.append("sharing") }
        if !off.isEmpty { line += " \(off.joined(separator: " and ").capitalizedFirst) \(off.count > 1 ? "were" : "was") on in the backup: turn \(off.count > 1 ? "them" : "it") on above if you want." }
        return line
    }
}

private extension String {
    var capitalizedFirst: String { prefix(1).uppercased() + dropFirst() }
}
