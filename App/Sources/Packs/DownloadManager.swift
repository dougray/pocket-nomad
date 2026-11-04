import Foundation
import Observation

/// Background downloads that keep going when the app is closed, straight into
/// Documents. Big books (tens of GB) can take hours; iOS resumes them on Wi-Fi.
@MainActor @Observable
final class DownloadManager {
    static let shared = DownloadManager()
    static let sessionID = "org.nomadpocket.downloads"

    struct Active: Identifiable {
        let id: String           // destination file name
        let title: String
        var received: Int64 = 0
        var expected: Int64 = 0
        var fraction: Double { expected > 0 ? Double(received) / Double(expected) : 0 }
    }

    private(set) var active: [String: Active] = [:]
    private(set) var lastError: String?
    var backgroundCompletion: (() -> Void)?

    @ObservationIgnored private let delegate = Delegate()
    @ObservationIgnored private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: DownloadManager.sessionID)
        config.sessionSendsLaunchEvents = true
        config.isDiscretionary = false
        config.allowsCellularAccess = true
        return URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
    }()

    private init() {
        // Re-attach to downloads that survived an app restart.
        session.getAllTasks { tasks in
            let found = tasks.compactMap { task -> (String, String)? in
                guard let name = task.taskDescription else { return nil }
                return (name, name)
            }
            Task { @MainActor in
                for (name, title) in found where self.active[name] == nil { self.active[name] = Active(id: name, title: title) }
            }
        }
    }

    func start(_ item: CatalogItem) {
        guard active[item.fileName] == nil else { return }
        let task = session.downloadTask(with: item.url)
        task.taskDescription = item.fileName
        task.countOfBytesClientExpectsToReceive = item.size
        active[item.fileName] = Active(id: item.fileName, title: item.title, expected: item.size)
        task.resume()
    }

    func cancel(_ fileName: String) {
        session.getAllTasks { tasks in
            tasks.filter { $0.taskDescription == fileName }.forEach { $0.cancel() }
        }
        active[fileName] = nil
    }

    func isDownloading(_ item: CatalogItem) -> Bool { active[item.fileName] != nil }

    fileprivate func progress(_ name: String, _ received: Int64, _ expected: Int64) {
        guard var a = active[name] else { return }
        a.received = received
        if expected > 0 { a.expected = expected }
        active[name] = a
    }

    fileprivate func finished(_ name: String, error: String?) {
        active[name] = nil
        if let error { lastError = error }
        Task { await ContentStore.shared.refresh() }
    }

    fileprivate func eventsDone() {
        backgroundCompletion?()
        backgroundCompletion = nil
    }

    final class Delegate: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
            guard let name = downloadTask.taskDescription else { return }
            Task { @MainActor in DownloadManager.shared.progress(name, totalBytesWritten, totalBytesExpectedToWrite) }
        }

        func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
            guard let name = downloadTask.taskDescription else { return }
            // The temp file is deleted when this method returns, so move it now.
            let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 200
            var error: String?
            if (200..<300).contains(status) {
                let dest = URL.documentsDirectory.appending(path: name)
                do {
                    try? FileManager.default.removeItem(at: dest)
                    try FileManager.default.moveItem(at: location, to: dest)
                } catch let e {
                    error = "Couldn't save \(name): \(e.localizedDescription)"
                }
            } else {
                error = "Download of \(name) failed (HTTP \(status))."
            }
            Task { @MainActor in DownloadManager.shared.finished(name, error: error) }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
            guard let error, let name = task.taskDescription else { return }
            let cancelled = (error as? URLError)?.code == .cancelled
            Task { @MainActor in DownloadManager.shared.finished(name, error: cancelled ? nil : "Download of \(name) stopped: \(error.localizedDescription)") }
        }

        func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
            Task { @MainActor in DownloadManager.shared.eventsDone() }
        }
    }
}
