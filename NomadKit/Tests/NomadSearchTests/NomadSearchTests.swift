import Foundation
import XCTest
@testable import NomadSearch
import ZIMKit

func fixture(_ name: String, file: StaticString = #filePath) throws -> URL {
    let url = URL(fileURLWithPath: "\(file)")
        .deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Fixtures").appendingPathComponent(name)
    guard FileManager.default.fileExists(atPath: url.path) else {
        throw XCTSkip("fixture \(name) missing; run Tests/Fixtures/fetch-fixtures.sh")
    }
    return url
}

func tempIndexURL() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("nomadkit-\(UUID().uuidString).nomadidx")
}

final class HTMLTextTests: XCTestCase {
    func testExtractBasics() {
        let html = """
            <!DOCTYPE html><html><head><title>Water &amp; Fire</title>
            <style>body { color: red }</style><script>var x = "<p>no</p>";</script></head>
            <body><nav>Menu Home</nav>
            <h1>Water</h1>
            <div id="toc"><ul><li>1 Boiling</li></ul></div>
            <p>Boil water for   <b>one minute</b>.<sup class="reference">[1]</sup> It&#39;s safe&nbsp;then &#x2014; mostly.</p>
            <!-- comment <p>hidden</p> -->
            <h2>Boiling<span class="mw-editsection">[edit]</span></h2>
            <p>At altitude, boil 3 minutes.<br>Let it cool.</p>
            <h2>References</h2><ol><li>Some cite</li></ol>
            <h2>Storage</h2><p>Keep it covered.</p>
            <h2>External links</h2><ul><li><a href="x">x.org</a></li></ul>
            <footer>Footer text</footer></body></html>
            """
        let (title, text) = HTMLText.extract(Data(html.utf8))
        XCTAssertEqual(title, "Water & Fire")
        XCTAssertTrue(text.contains("Boil water for one minute. It's safe then — mostly."), text)
        XCTAssertTrue(text.contains("At altitude, boil 3 minutes.\nLet it cool."), text)
        XCTAssertTrue(text.contains("\n\nBoiling\n\n"), text)
        XCTAssertTrue(text.contains("Keep it covered."), text)
        for junk in ["color: red", "var x", "no", "Menu", "1 Boiling", "[1]", "[edit]", "hidden", "Some cite",
                     "x.org", "Footer", "References", "External links"] {
            XCTAssertFalse(text.contains(junk), "should drop \(junk): \(text)")
        }
        XCTAssertFalse(text.contains("  "))
        XCTAssertFalse(text.contains("\n\n\n"))
    }

    func testTitleFallsBackToH1AndOddInput() {
        XCTAssertEqual(HTMLText.extract("<h1>Knots</h1><p>Bowline.</p>").title, "Knots")
        XCTAssertEqual(HTMLText.extract("").text, "")
        let odd = HTMLText.extract("a < b && c > d &bogus; <p unclosed")
        XCTAssertTrue(odd.text.hasPrefix("a < b && c > d &bogus;"), odd.text)
    }

    func testTablesKeepColumnHeaders() {
        let html = """
        <p>Use this table.</p><table><tr><th>Amount of clear water</th><th>6% bleach</th><th>8.25% bleach</th></tr>
        <tr><td>1 gallon (3.8 L)</td><td>8 drops</td><td>6 drops</td></tr>
        <tr><td>2 gallons</td><td>16 drops</td><td>12 drops</td></tr></table><p>Wait 30 minutes.</p>
        """
        let text = HTMLText.extract(html).text
        XCTAssertTrue(text.contains("Amount of clear water: 1 gallon (3.8 L); 6% bleach: 8 drops; 8.25% bleach: 6 drops"), text)
        XCTAssertTrue(text.contains("Amount of clear water: 2 gallons; 6% bleach: 16 drops; 8.25% bleach: 12 drops"), text)
        XCTAssertTrue(text.hasSuffix("Wait 30 minutes."), text)
        // A table without a header row still reads as plain cells.
        XCTAssertTrue(HTMLText.extract("<table><tr><td>a</td><td>b</td></tr></table>").text.contains("a; b"))
    }

    func testPassageSplitter() {
        let para = String(repeating: "Water is life. ", count: 20) // ~300 chars
        let text = (0 ..< 12).map { "P\($0) " + para }.joined(separator: "\n\n")
        let passages = PassageSplitter.split(text)
        XCTAssertGreaterThan(passages.count, 2)
        for p in passages.dropLast() { XCTAssertTrue((900 ... 1200).contains(p.count), "\(p.count)") }
        XCTAssertTrue(passages.allSatisfy { $0.count <= 1500 })

        let long = String(repeating: "Sentence number here. ", count: 200) // one 4400-char paragraph
        let chunks = PassageSplitter.split(long)
        XCTAssertGreaterThan(chunks.count, 3)
        XCTAssertTrue(chunks.allSatisfy { $0.count <= 1500 })
        XCTAssertTrue(chunks[0].hasSuffix("."))

        XCTAssertEqual(PassageSplitter.split("Short stub."), ["Short stub."])
        XCTAssertEqual(PassageSplitter.split(""), [])
    }
}

final class QuerySanitizerTests: XCTestCase {
    func testSanitizer() {
        XCTAssertEqual(SearchIndex.ftsQuery("How do I purify water?"), "\"purify\" OR \"water\"")
        XCTAssertEqual(SearchIndex.ftsQuery("\"water\" AND fire*"), "\"water\" OR \"fire\"")
        // All stopwords: kept, but quoted, so operators are inert.
        XCTAssertEqual(SearchIndex.ftsQuery("NEAR(a b) OR NOT x"), "\"near\" OR \"or\" OR \"not\"")
        XCTAssertNil(SearchIndex.ftsQuery(""))
        XCTAssertNil(SearchIndex.ftsQuery("   \"\" * ** () - ^ :"))
        XCTAssertEqual(SearchIndex.ftsQuery("the who"), "\"the\" OR \"who\"")
        XCTAssertEqual(SearchIndex.ftsQuery("Café 911 x"), "\"café\" OR \"911\"")
        XCTAssertEqual(SearchIndex.ftsQuery("water water WATER"), "\"water\"")
    }
}

final class IndexTests: XCTestCase {
    func testDocumentIndexAndOddQueries() throws {
        let docs = [
            IndexDocument(path: "guides/water.html", title: "Water purification",
                          html: Data("<p>Boil water for one minute to kill pathogens. Chlorine tablets also work.</p>".utf8)),
            IndexDocument(path: "guides/fire.html", title: "Starting a fire",
                          html: Data("<p>Use a ferro rod and dry tinder. Build a teepee of kindling.</p>".utf8)),
            IndexDocument(path: "guides/empty.html", title: "Empty", html: Data("<script>x</script>".utf8)),
            IndexDocument(path: "guides/redirect.html", title: "Moved",
                          html: Data("<html><head><meta http-equiv=\"refresh\" content=\"0;URL='./x'\"></head><body>Moved</body></html>".utf8)),
        ]
        let out = tempIndexURL()
        defer { try? FileManager.default.removeItem(at: out) }
        let progressCalls = Locked(0)
        let stats = try IndexBuilder.build(documents: docs, output: out, progress: { _, _ in progressCalls.withLock { $0 += 1 } },
                                           isCancelled: { false })
        XCTAssertEqual(stats.articles, 2)
        XCTAssertGreaterThan(progressCalls.withLock { $0 }, 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: out.path + ".tmp"))

        let index = try SearchIndex(url: out)
        XCTAssertTrue(index.isComplete)
        XCTAssertNil(index.zimUUID)
        XCTAssertEqual(index.meta("schema_version"), "1")
        XCTAssertEqual(index.meta("articles"), "2")

        let hits = try index.search("how do I boil water?", limit: 5)
        XCTAssertEqual(hits.first?.path, "guides/water.html")
        XCTAssertEqual(hits.first?.title, "Water purification")
        XCTAssertTrue(hits.first?.snippet.contains("**") ?? false)
        XCTAssertGreaterThan(hits.first?.score ?? 0, 0)
        // Title weighting: "fire" in title.
        XCTAssertEqual(try index.search("fire", limit: 5).first?.path, "guides/fire.html")
        // Stemming via porter: "boiling" matches "Boil".
        XCTAssertEqual(try index.search("boiling", limit: 5).first?.path, "guides/water.html")

        for odd in ["", "\"", "\"\"\"", "*", "water*", "AND", "OR OR", "NEAR(", "water AND", "(", "-x", "a:b", "^", "'; DROP TABLE meta; --", "🔥"] {
            XCTAssertNoThrow(try index.search(odd, limit: 5), odd)
        }
        XCTAssertTrue(try index.search("", limit: 5).isEmpty)
        XCTAssertEqual(try index.search("\"water\" AND*", limit: 5).first?.path, "guides/water.html")
        XCTAssertTrue(try index.search("water", limit: 0).isEmpty)
    }

    func testCancelLeavesNoFiles() throws {
        let docs = (0 ..< 10).map { IndexDocument(path: "\($0)", title: "T\($0)", html: Data("<p>text \($0)</p>".utf8)) }
        let out = tempIndexURL()
        XCTAssertThrowsError(try IndexBuilder.build(documents: docs, output: out, progress: { _, _ in }, isCancelled: { true }))
        XCTAssertFalse(FileManager.default.fileExists(atPath: out.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: out.path + ".tmp"))
    }

    func testZIMIndexBuildAndSearch() throws {
        let zim = try ZIMArchive(url: fixture("wikipedia_en_ray-charles_maxi.zim"))
        let out = tempIndexURL()
        defer { try? FileManager.default.removeItem(at: out) }
        let last = Locked((0, 0))
        let stats = try IndexBuilder.build(archive: zim, output: out, progress: { d, t in last.withLock { $0 = (d, t) } },
                                           isCancelled: { false })
        XCTAssertGreaterThan(stats.articles, 100)
        XCTAssertGreaterThan(stats.passages, stats.articles)
        XCTAssertEqual(last.withLock { $0.0 }, last.withLock { $0.1 })

        let index = try SearchIndex(url: out)
        XCTAssertEqual(index.zimUUID, zim.uuid)
        XCTAssertEqual(index.meta("passages"), String(stats.passages))

        let hits = try index.search("Georgia on my mind state song", limit: 5)
        XCTAssertEqual(hits.first?.path, "Georgia_on_My_Mind")
        XCTAssertTrue(hits.allSatisfy { (900 ... 1600).contains($0.body.count) || $0.body.count < 900 })
        // Paths in the index open in the archive.
        for h in hits { XCTAssertNotNil(try zim.content(path: h.path), h.path) }
        // Scores are higher-is-better and sorted.
        XCTAssertEqual(hits.map(\.score), hits.map(\.score).sorted(by: >))

        let piano = try index.search("what instrument did ray charles play", limit: 10)
        XCTAssertFalse(piano.isEmpty)
        // Boilerplate sections are not indexed.
        XCTAssertTrue(try index.search("\"retrieved\"", limit: 50).count < 10)
    }

    func testZIMIndexOldLayoutMaxArticles() throws {
        let zim = try ZIMArchive(url: fixture("wikibooks_be_all_nopic_2017-02.zim"))
        let out = tempIndexURL()
        defer { try? FileManager.default.removeItem(at: out) }
        let stats = try IndexBuilder.build(archive: zim, output: out, maxArticles: 10, progress: { _, _ in }, isCancelled: { false })
        XCTAssertLessThanOrEqual(stats.articles, 10)
        XCTAssertGreaterThan(stats.articles, 0)
        let index = try SearchIndex(url: out)
        let hits = try index.search("кухня", limit: 5)
        XCTAssertFalse(hits.isEmpty)
        XCTAssertTrue(hits.allSatisfy { $0.path.hasPrefix("A/") })
        XCTAssertNotNil(try zim.content(path: hits[0].path))
    }

    func testOpenGarbageFails() throws {
        let tmp = tempIndexURL()
        try Data("not sqlite".utf8).write(to: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }
        XCTAssertThrowsError(try SearchIndex(url: tmp))
    }
}

final class Locked<Value>: @unchecked Sendable {
    private var value: Value
    private let lock = NSLock()
    init(_ value: Value) { self.value = value }
    func withLock<R>(_ body: (inout Value) -> R) -> R { lock.lock(); defer { lock.unlock() }; return body(&value) }
}
