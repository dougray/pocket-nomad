#if os(macOS)
import Foundation
import NomadSearch
import ZIMKit

let usage = """
    usage: nomad-index <file.zim> [-o out.nomadidx] [--max N]
           nomad-index info <file.zim>
           nomad-index search <index.nomadidx> <query...>
           nomad-index zim <folder> -o out.zim    build a ZIM from <folder>/manifest.json
    """

func out(_ s: String) { FileHandle.standardOutput.write(Data(s.utf8)) }

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

func info(_ path: String) throws {
    let zim = try ZIMArchive(url: URL(fileURLWithPath: path))
    print("file:          \(path)")
    print("version:       \(zim.majorVersion).\(zim.minorVersion)\(zim.usesNewNamespaceScheme ? " (new namespaces)" : " (old namespaces)")")
    print("uuid:          \(zim.uuid)")
    print("entries:       \(zim.entryCount)")
    print("clusters:      \(zim.clusterCount)")
    print("articles:      \(zim.articleCount)")
    print("main page:     \(zim.mainPagePath ?? "-")")
    for key in ["Title", "Description", "Language", "Creator", "Publisher", "Date", "Name", "Flavour"] {
        if let v = zim.metadata(key) { print("\((key + ":").padding(toLength: 15, withPad: " ", startingAt: 0))\(v)") }
    }
    if let icon = zim.illustration() { print("illustration:  \(icon.count) bytes") }
    print("metadata keys: \(zim.metadataKeys.joined(separator: ", "))")
}

func build(_ args: [String]) throws {
    var zimPath: String?
    var outPath: String?
    var maxArticles: Int?
    var i = 0
    while i < args.count {
        switch args[i] {
        case "-o", "--output":
            i += 1; guard i < args.count else { fail(usage) }
            outPath = args[i]
        case "--max":
            i += 1; guard i < args.count, let n = Int(args[i]), n > 0 else { fail(usage) }
            maxArticles = n
        case "-h", "--help":
            print(usage); exit(0)
        default:
            if zimPath != nil { fail(usage) }
            zimPath = args[i]
        }
        i += 1
    }
    guard let zimPath else { fail(usage) }
    let zimURL = URL(fileURLWithPath: zimPath)
    let output = outPath.map { URL(fileURLWithPath: $0) }
        ?? zimURL.deletingPathExtension().appendingPathExtension("nomadidx")
    let zim = try ZIMArchive(url: zimURL)
    out("indexing \(zim.metadata("Title") ?? zimURL.lastPathComponent) -> \(output.path)\n")
    let start = Date()
    let stats = try IndexBuilder.build(archive: zim, output: output, maxArticles: maxArticles, progress: { done, total in
        let secs = Date().timeIntervalSince(start)
        let rate = secs > 0 ? Double(done) / secs : 0
        let pct = total > 0 ? Double(done) * 100 / Double(total) : 100
        let line = String(format: "\r  %d / %d articles (%.1f%%)  %.0f/s   ", done, total, pct, rate)
        out(line)
    }, isCancelled: { false })
    out(String(format: "\ndone: %d articles, %d passages in %.2fs (%.0f articles/s)\n",
               stats.articles, stats.passages, stats.seconds, stats.articlesPerSecond))
}

/// Builds a ZIM from a folder holding `manifest.json`:
///
///     {"metadata": {"Title": "...", "Language": "eng", ...}, "main": "index",
///      "items": [{"path": "pmid/123", "title": "...", "file": "pages/123.html",
///                 "mime": "text/html", "article": true}, ...]}
///
/// `file` is relative to the folder. Metadata "Illustration_48x48@1" may name a PNG file.
func makeZIM(_ args: [String]) throws {
    guard args.count == 3, args[1] == "-o" else { fail(usage) }
    struct Manifest: Decodable {
        struct Item: Decodable { let path: String; let title: String; let file: String; let mime: String; let article: Bool? }
        let metadata: [String: String]
        let main: String?
        let items: [Item]
    }
    let folder = URL(fileURLWithPath: args[0])
    let manifest = try JSONDecoder().decode(Manifest.self, from: Data(contentsOf: folder.appendingPathComponent("manifest.json")))
    var writer = ZIMWriter()
    for (key, value) in manifest.metadata {
        if key.hasPrefix("Illustration_") {
            writer.metadata[key] = try Data(contentsOf: folder.appendingPathComponent(value))
        } else {
            writer.setMetadata(key, value)
        }
    }
    for item in manifest.items {
        writer.add(.init(path: item.path, title: item.title, mimeType: item.mime,
                         data: try Data(contentsOf: folder.appendingPathComponent(item.file)), isArticle: item.article ?? false))
    }
    writer.mainPath = manifest.main
    let output = URL(fileURLWithPath: args[2])
    try writer.write(to: output)
    let size = (try? output.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
    print("wrote \(output.path): \(manifest.items.count) items, \(size / 1_000_000) MB")
}

func search(_ args: [String]) throws {
    guard args.count >= 2 else { fail(usage) }
    let index = try SearchIndex(url: URL(fileURLWithPath: args[0]))
    let query = args.dropFirst().joined(separator: " ")
    for (n, p) in try index.search(query, limit: 10).enumerated() {
        print(String(format: "%2d. %.2f  %@  [%@]", n + 1, p.score, p.title, p.path))
        print("    \(p.snippet.replacingOccurrences(of: "\n", with: " "))")
    }
}

let args = Array(CommandLine.arguments.dropFirst())
do {
    switch args.first {
    case nil: fail(usage)
    case "info":
        guard args.count == 2 else { fail(usage) }
        try info(args[1])
    case "search":
        try search(Array(args.dropFirst()))
    case "zim":
        try makeZIM(Array(args.dropFirst()))
    default:
        try build(args)
    }
} catch {
    fail("error: \(error)")
}
#endif
