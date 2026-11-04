import Foundation
import NomadSearch

struct GuideCategory: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let symbol: String
    let summary: String?
    let guides: [Guide]
}

struct Guide: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let summary: String
    let file: String
    let urgent: Bool?
    let keywords: [String]?
}

struct Checklist: Codable, Identifiable, Hashable, Sendable {
    let id: String
    let title: String
    let symbol: String
    let items: [String]
}

/// The survival guides bundled with the app. They work with nothing downloaded.
enum GuideStore {
    static let root = Bundle.main.url(forResource: "Guides", withExtension: nil)!

    static let categories: [GuideCategory] = {
        struct Index: Codable { let categories: [GuideCategory] }
        guard let data = try? Data(contentsOf: root.appending(path: "index.json")),
              let index = try? JSONDecoder().decode(Index.self, from: data) else { return [] }
        return index.categories
    }()

    static let checklists: [Checklist] = {
        guard let data = try? Data(contentsOf: root.appending(path: "checklists.json")) else { return [] }
        return (try? JSONDecoder().decode([Checklist].self, from: data)) ?? []
    }()

    static var allGuides: [Guide] { categories.flatMap(\.guides) }
    static var urgent: [Guide] { allGuides.filter { $0.urgent == true } }

    static func guide(file: String) -> Guide? { allGuides.first { $0.file == file } }

    static func html(for guide: Guide) -> String {
        (try? String(contentsOf: root.appending(path: guide.file), encoding: .utf8)) ?? "<p>This guide is missing.</p>"
    }

    /// Builds the full-text index of the guides once per app build, so Ask and
    /// search work on a brand-new install with no downloads.
    static func prepareIndex() async {
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "0"
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
        let url = URL.cachesDirectory.appending(path: "guides-\(version)-\(build)-v3.nomadidx")
        if !FileManager.default.fileExists(atPath: url.path) {
            let guides = allGuides
            let root = root
            await Task.detached(priority: .userInitiated) {
                let docs = guides.compactMap { guide -> IndexDocument? in
                    guard let html = try? Data(contentsOf: root.appending(path: guide.file)) else { return nil }
                    // Keywords go in the title field, which search weights 4x, so "purify"
                    // finds Making Water Safe before the surface-cleaning guide.
                    let title = ([guide.title] + (guide.keywords ?? [])).joined(separator: " · ")
                    return IndexDocument(path: guide.file, title: title, html: html)
                }
                try? IndexBuilder.build(documents: docs, output: url, progress: { _, _ in }, isCancelled: { false })
            }.value
        }
        await SearchService.shared.setGuideIndex(url: url)
    }
}
