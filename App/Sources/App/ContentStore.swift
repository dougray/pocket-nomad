import Foundation
import Observation
import ZIMKit
import NomadSearch

/// An offline library book (a Kiwix ZIM file).
struct Book: Identifiable, Sendable {
    let id: String              // ZIM uuid
    let url: URL
    let archive: ZIMArchive
    let title: String
    let summary: String
    let size: Int64
    var articleCount: Int
    var indexURL: URL?
}

struct MapPack: Identifiable, Hashable, Sendable {
    var id: String { url.lastPathComponent }
    let url: URL
    let size: Int64
    var name: String { url.deletingPathExtension().lastPathComponent }
}

struct ModelFile: Identifiable, Hashable, Sendable {
    var id: String { url.lastPathComponent }
    let url: URL
    let size: Int64
    var name: String { url.deletingPathExtension().lastPathComponent }
}

/// Everything the user has added: books, map regions and AI models.
///
/// Files live in the app's Documents folder, which is visible in Finder (and the
/// Files app), so packs can be dropped in from a Mac with no network at all.
@MainActor @Observable
final class ContentStore {
    static let shared = ContentStore()

    private(set) var books: [Book] = []
    private(set) var maps: [MapPack] = []
    private(set) var models: [ModelFile] = []
    private(set) var indexProgress: [String: Double] = [:]
    private(set) var lastError: String?

    let documents = URL.documentsDirectory
    let indexDirectory = URL.applicationSupportDirectory.appending(path: "Indexes", directoryHint: .isDirectory)

    static let autoIndexLimit = 200_000
    private(set) var indexingPaused = false
    private var indexQueue: [String] = []
    private var indexTask: Task<Void, Never>?

    private init() {
        try? FileManager.default.createDirectory(at: indexDirectory, withIntermediateDirectories: true)
    }

    func refresh() async {
        let fm = FileManager.default
        let files = (try? fm.contentsOfDirectory(at: documents, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        let byExt = Dictionary(grouping: files) { $0.pathExtension.lowercased() }

        let zims = byExt["zim"] ?? []
        let opened: [Book] = await Task.detached(priority: .userInitiated) {
            zims.compactMap { url -> Book? in
                guard let archive = try? ZIMArchive(url: url) else { return nil }
                return Book(
                    id: archive.uuid,
                    url: url,
                    archive: archive,
                    title: archive.metadata("Title") ?? url.deletingPathExtension().lastPathComponent,
                    summary: archive.metadata("Description") ?? "",
                    size: url.fileSize,
                    articleCount: archive.articleCount,
                    indexURL: nil
                )
            }
        }.value
        books = opened.map { book in
            var book = book
            book.indexURL = existingIndex(for: book)
            // Books that are mostly PDFs list one HTML page; the index counts every document.
            if let url = book.indexURL, let indexed = (try? SearchIndex(url: url))?.meta("articles").flatMap(Int.init),
               indexed > book.articleCount {
                book.articleCount = indexed
            }
            return book
        }.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }

        maps = (byExt["pmtiles"] ?? []).map { MapPack(url: $0, size: $0.fileSize) }.sorted { $0.name < $1.name }
        models = (byExt["gguf"] ?? []).map { ModelFile(url: $0, size: $0.fileSize) }.sorted { $0.name < $1.name }

        await SearchService.shared.reload(indexes: books.compactMap { b in b.indexURL.map { (b.id, $0) } })
        // Big books (all of Wikipedia) take hours to index on a phone, so those wait
        // for the user to ask, or for a prebuilt index from the Mac pack script.
        if !indexingPaused {
            for book in books where book.indexURL == nil && book.articleCount <= Self.autoIndexLimit {
                enqueueIndex(book.id)
            }
        }
    }

    func book(id: String) -> Book? { books.first { $0.id == id } }

    // MARK: Import and delete

    func importFile(at url: URL) async {
        let ext = url.pathExtension.lowercased()
        guard ["zim", "pmtiles", "gguf", "nomadidx"].contains(ext) else {
            lastError = "Pocket Nomad can open .zim books, .pmtiles maps and .gguf AI models."
            return
        }
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let dest = documents.appending(path: url.lastPathComponent)
        guard url.standardizedFileURL != dest.standardizedFileURL else { await refresh(); return }
        do {
            let fm = FileManager.default
            if fm.fileExists(atPath: dest.path) { try fm.removeItem(at: dest) }
            // Inbox copies can simply be moved; anything else is copied.
            if url.path.contains("/Inbox/") { try fm.moveItem(at: url, to: dest) } else { try fm.copyItem(at: url, to: dest) }
            await refresh()
        } catch {
            lastError = "Couldn't import \(url.lastPathComponent): \(error.localizedDescription)"
        }
    }

    func delete(_ url: URL) async {
        try? FileManager.default.removeItem(at: url)
        if url.pathExtension == "zim", let book = books.first(where: { $0.url == url }) {
            try? FileManager.default.removeItem(at: indexDirectory.appending(path: "\(book.id).nomadidx"))
            try? FileManager.default.removeItem(at: sidecarIndex(for: url))
        }
        await refresh()
    }

    // MARK: Search indexes

    private func sidecarIndex(for zim: URL) -> URL {
        zim.deletingPathExtension().appendingPathExtension("nomadidx")
    }

    /// A prebuilt index from the Mac pack script sits next to the ZIM; one built on
    /// the phone lives in Application Support. Either works if it matches the book.
    private func existingIndex(for book: Book) -> URL? {
        for candidate in [sidecarIndex(for: book.url), indexDirectory.appending(path: "\(book.id).nomadidx")] {
            guard FileManager.default.fileExists(atPath: candidate.path),
                  let index = try? SearchIndex(url: candidate),
                  index.zimUUID == nil || index.zimUUID == book.id else { continue }
            return candidate
        }
        return nil
    }

    func enqueueIndex(_ bookID: String) {
        indexingPaused = false
        guard !indexQueue.contains(bookID), indexProgress[bookID] == nil else { return }
        indexQueue.append(bookID)
        indexProgress[bookID] = 0
        runIndexQueue()
    }

    func cancelIndexing() {
        indexingPaused = true
        indexTask?.cancel()
    }

    private func runIndexQueue() {
        guard indexTask == nil, let next = indexQueue.first, let book = book(id: next) else { return }
        let output = indexDirectory.appending(path: "\(book.id).nomadidx")
        let archive = book.archive
        let progress = ProgressBox()
        let ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                self?.indexProgress[next] = progress.value
            }
        }
        indexTask = Task { [weak self] in
            let task = Task.detached(priority: .utility) {
                try IndexBuilder.build(
                    archive: archive, output: output,
                    progress: { done, total in progress.value = total > 0 ? Double(done) / Double(total) : 0 },
                    isCancelled: { Task.isCancelled }
                )
            }
            let result = await withTaskCancellationHandler { await task.result } onCancel: { task.cancel() }
            ticker.cancel()
            guard let self else { return }
            if case .failure(let error) = result, !Task.isCancelled {
                self.lastError = "Indexing \(book.title) failed: \(error.localizedDescription)"
            }
            self.indexQueue.removeAll { $0 == next }
            self.indexProgress[next] = nil
            self.indexTask = nil
            if Task.isCancelled { self.indexQueue.removeAll() }
            await self.refresh()
        }
    }
}

/// Lock-protected progress value shared with the background indexer.
final class ProgressBox: @unchecked Sendable {
    private let lock = NSLock()
    private var _value = 0.0
    var value: Double {
        get { lock.withLock { _value } }
        set { lock.withLock { _value = newValue } }
    }
}

extension URL {
    var fileSize: Int64 {
        Int64((try? resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
    }
}
