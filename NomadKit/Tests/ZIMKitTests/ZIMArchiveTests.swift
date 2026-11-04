import Foundation
import XCTest
@testable import ZIMKit

/// Fixtures come from Tests/Fixtures/fetch-fixtures.sh; tests skip when a file is missing.
func fixture(_ name: String, file: StaticString = #filePath) throws -> URL {
    let url = URL(fileURLWithPath: "\(file)")
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Fixtures").appendingPathComponent(name)
    guard FileManager.default.fileExists(atPath: url.path) else {
        throw XCTSkip("fixture \(name) missing; run Tests/Fixtures/fetch-fixtures.sh")
    }
    return url
}

final class ZIMArchiveTests: XCTestCase {
    // small-nons.zim: openzim test suite, ZIM 6.1 new namespaces, zstd cluster.
    func testNewLayoutSmall() throws {
        let zim = try ZIMArchive(url: fixture("small-nons.zim"))
        XCTAssertEqual(zim.majorVersion, 6)
        XCTAssertEqual(zim.minorVersion, 1)
        XCTAssertTrue(zim.usesNewNamespaceScheme)
        XCTAssertEqual(zim.entryCount, 16)
        XCTAssertEqual(zim.uuid.count, 32)
        XCTAssertTrue(zim.uuid.allSatisfy(\.isHexDigit))
        XCTAssertEqual(zim.metadata("Title"), "Test ZIM file")
        XCTAssertEqual(zim.metadata("Language"), "en")
        XCTAssertNil(zim.metadata("NoSuchKey"))
        XCTAssertEqual(zim.mainPagePath, "main.html")
        XCTAssertEqual(zim.articleCount, 1)

        let page = try XCTUnwrap(zim.content(path: "main.html"))
        XCTAssertEqual(page.mimeType, "text/html")
        XCTAssertTrue(String(decoding: page.data, as: UTF8.self).contains("Test ZIM file"))

        let png = try XCTUnwrap(zim.illustration())
        XCTAssertEqual(Array(png.prefix(4)), [0x89, 0x50, 0x4E, 0x47])
        let favicon = try XCTUnwrap(zim.content(path: "favicon.png"))
        XCTAssertEqual(favicon.mimeType, "image/png")

        XCTAssertNil(zim.entry(path: "does-not-exist.html"))
        XCTAssertNil(try zim.content(path: "does-not-exist.html"))
        // Metadata namespace isn't reachable as content.
        XCTAssertNil(zim.entry(path: "Title"))

        let hits = zim.titleSearch("test z", limit: 5)
        XCTAssertEqual(hits.map(\.path), ["main.html"])
        XCTAssertEqual(zim.titleSearch("TEST", limit: 5).first?.title, "Test ZIM file")
        XCTAssertTrue(zim.titleSearch("", limit: 5).isEmpty)
        XCTAssertTrue(zim.titleSearch("zzz", limit: 5).isEmpty)

        var paths: [String] = []
        zim.forEachArticle { paths.append($0.path); return true }
        XCTAssertEqual(paths, ["main.html"])
    }

    // small-withns.zim: ZIM 5.0 old namespaces (A/I/-/M), xz cluster.
    func testOldLayoutSmall() throws {
        let zim = try ZIMArchive(url: fixture("small-withns.zim"))
        XCTAssertEqual(zim.majorVersion, 5)
        XCTAssertFalse(zim.usesNewNamespaceScheme)
        XCTAssertEqual(zim.entryCount, 17)
        XCTAssertEqual(zim.mainPagePath, "A/main.html")
        XCTAssertEqual(zim.metadata("Date"), "2020-11-15")

        // Old layout returns namespaced paths and accepts both forms.
        let main = try XCTUnwrap(zim.content(path: "A/main.html"))
        XCTAssertEqual(main.path, "A/main.html")
        XCTAssertEqual(main.mimeType, "text/html")
        XCTAssertEqual(try zim.content(path: "main.html")?.data, main.data)
        XCTAssertEqual(zim.entry(path: "main.html")?.path, "A/main.html")
        XCTAssertEqual(zim.entry(path: "I/favicon.png")?.mimeType, "image/png")
        XCTAssertEqual(zim.entry(path: "favicon.png")?.path, "I/favicon.png")

        // -/favicon is a redirect to I/favicon.png.
        let fav = try XCTUnwrap(zim.entry(path: "-/favicon"))
        XCTAssertTrue(fav.isRedirect)
        XCTAssertEqual(fav.mimeType, "image/png")
        XCTAssertEqual(try zim.content(path: "-/favicon")?.path, "I/favicon.png")
        XCTAssertNotNil(zim.illustration())

        XCTAssertEqual(zim.titleSearch("test", limit: 5).map(\.path), ["A/main.html"])
        var paths: [String] = []
        zim.forEachArticle { paths.append($0.path); return true }
        XCTAssertEqual(paths, ["A/main.html"])
    }

    // wikibooks_be: real 2017 Kiwix file, ZIM 5.0 old namespaces, xz, Cyrillic titles, redirects.
    func testOldLayoutWikibooksXZ() throws {
        let zim = try ZIMArchive(url: fixture("wikibooks_be_all_nopic_2017-02.zim"))
        XCTAssertEqual(zim.metadata("Language"), "bel")
        XCTAssertEqual(zim.metadata("Creator"), "Wikibooks")
        let mainPath = try XCTUnwrap(zim.mainPagePath)
        XCTAssertTrue(mainPath.hasPrefix("A/"))
        let main = try XCTUnwrap(zim.content(path: mainPath))
        XCTAssertGreaterThan(main.data.count, 1000)

        // A/index.htm is a redirect to the main page.
        let index = try XCTUnwrap(zim.entry(path: "A/index.htm"))
        XCTAssertTrue(index.isRedirect)
        XCTAssertEqual(try zim.content(path: "A/index.htm")?.path, mainPath)

        // Assets in the "-" namespace.
        let css = try XCTUnwrap(zim.content(path: "-/s/style.css"))
        XCTAssertEqual(css.mimeType, "text/css")

        // Case-insensitive Cyrillic prefix search.
        let hits = zim.titleSearch("італьянская мова", limit: 20)
        XCTAssertTrue(hits.contains { $0.title == "Італьянская мова" })
        XCTAssertEqual(hits.first?.title, "Італьянская мова") // exact match first
        XCTAssertGreaterThanOrEqual(hits.count, 5)

        var count = 0
        zim.forEachArticle { e in
            XCTAssertFalse(e.isRedirect)
            XCTAssertTrue(e.mimeType.hasPrefix("text/html"))
            XCTAssertTrue(e.path.hasPrefix("A/"))
            count += 1
            return true
        }
        XCTAssertEqual(count, zim.articleCount)
        XCTAssertGreaterThan(count, 30)

        // Early stop.
        var seen = 0
        zim.forEachArticle { _ in seen += 1; return seen < 3 }
        XCTAssertEqual(seen, 3)
    }

    // Climate change mini: ZIM 6.2, zstd, ~3800 articles, header title list.
    func testNewLayoutClimateZstd() throws {
        let zim = try ZIMArchive(url: fixture("wikipedia_en_climate_change_mini_2024-06.zim"))
        XCTAssertEqual(zim.minorVersion, 2)
        XCTAssertEqual(zim.metadata("Flavour"), "mini")
        XCTAssertEqual(zim.articleCount, 3821)
        XCTAssertEqual(zim.mainPagePath, "index")

        let article = try XCTUnwrap(zim.content(path: "Sea_level_rise"))
        XCTAssertEqual(article.mimeType, "text/html")
        XCTAssertTrue(String(decoding: article.data, as: UTF8.self).contains("Sea level rise"))

        // Redirect resolution.
        let redirect = try XCTUnwrap(zim.entry(path: "10-year_flood"))
        XCTAssertTrue(redirect.isRedirect)
        XCTAssertEqual(redirect.mimeType, "text/html")
        XCTAssertEqual(try zim.content(path: "10-year_flood")?.path, "100-year_flood")

        let hits = zim.titleSearch("global warming", limit: 10)
        XCTAssertEqual(hits.count, 10)
        XCTAssertEqual(hits.first?.title, "Global warming")
        XCTAssertTrue(hits.allSatisfy { $0.title.lowercased().hasPrefix("global warming") })
        XCTAssertFalse(zim.titleSearch("sea LEVEL r", limit: 3).isEmpty)
    }

    // Ray Charles maxi: ZIM 6.3, zstd, no header title list -> uses X/listing/titleOrdered/v1.
    func testNewLayoutRayCharlesListingFallback() throws {
        let zim = try ZIMArchive(url: fixture("wikipedia_en_ray-charles_maxi.zim"))
        XCTAssertEqual(zim.minorVersion, 3)
        XCTAssertEqual(zim.metadata("Title"), "Ray Charles")
        XCTAssertEqual(zim.mainPagePath, "index")
        XCTAssertNotNil(try zim.content(path: "index"))

        let hits = zim.titleSearch("georgia on", limit: 5)
        XCTAssertFalse(hits.isEmpty)
        XCTAssertTrue(hits.allSatisfy { $0.title.lowercased().hasPrefix("georgia on") })
        let page = try XCTUnwrap(zim.content(path: hits[0].path))
        XCTAssertEqual(page.path, "Georgia_on_My_Mind")

        // The two orders yield the same set of articles.
        var listing = Set<String>(), storage = Set<String>()
        var lastPos = 0
        zim.forEachArticle { listing.insert($0.path); return true }
        zim.forEachArticle(order: .storage) { e, pos in
            storage.insert(e.path)
            XCTAssertLessThanOrEqual(pos, zim.articleCount)
            lastPos = pos
            return true
        }
        XCTAssertEqual(listing, storage)
        XCTAssertEqual(lastPos, zim.articleCount)
        XCTAssertGreaterThan(listing.count, 100)
    }

    func testRejectsNonZIM() throws {
        let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("notzim-\(UUID().uuidString).zim")
        try Data(repeating: 0x41, count: 200).write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }
        XCTAssertThrowsError(try ZIMArchive(url: tmp)) { XCTAssertEqual($0 as? ZIMError, .notAZIMFile) }
        XCTAssertThrowsError(try ZIMArchive(url: URL(fileURLWithPath: "/nonexistent/x.zim")))
    }

    func testConcurrentReads() throws {
        let zim = try ZIMArchive(url: fixture("wikipedia_en_climate_change_mini_2024-06.zim"))
        var paths: [String] = []
        zim.forEachArticle { paths.append($0.path); return paths.count < 200 }
        let expected = try paths.map { try XCTUnwrap(zim.content(path: $0)).data.count }
        let failures = Locked(0)
        DispatchQueue.concurrentPerform(iterations: 8) { worker in
            for (i, p) in paths.enumerated() where i % 8 == worker || i % 3 == 0 {
                if (try? zim.content(path: p))?.data.count != expected[i] { failures.withLock { $0 += 1 } }
            }
        }
        XCTAssertEqual(failures.withLock { $0 }, 0)
    }

    func testLRUCache() {
        var c = LRUCache<Int, String>(capacity: 2)
        c.set(1, "a"); c.set(2, "b")
        _ = c.get(1)
        c.set(3, "c") // evicts 2
        XCTAssertEqual(c.get(1), "a")
        XCTAssertNil(c.get(2))
        XCTAssertEqual(c.get(3), "c")
    }
}
