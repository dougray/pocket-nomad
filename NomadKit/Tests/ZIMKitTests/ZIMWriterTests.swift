import Foundation
import XCTest
@testable import ZIMKit

final class ZIMWriterTests: XCTestCase {
    func testRoundTripsThroughTheReader() throws {
        var w = ZIMWriter()
        w.clusterSize = 64 // force several clusters
        w.setMetadata("Title", "Test Pack")
        w.setMetadata("Language", "eng")
        w.add(.init(path: "index", title: "Home", mimeType: "text/html", data: Data("<h1>Home</h1>".utf8), isArticle: false))
        for n in 1 ... 20 {
            w.add(.init(path: "pmid/\(n)", title: "Snake bite review \(n)", mimeType: "text/html",
                        data: Data("<p>Keep the limb still. Paper \(n).</p>".utf8), isArticle: true))
        }
        w.add(.init(path: "logo.png", title: "logo.png", mimeType: "image/png", data: Data([0x89, 0x50, 0x4e, 0x47]), isArticle: false))
        w.mainPath = "index"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("writer-\(UUID()).zim")
        defer { try? FileManager.default.removeItem(at: url) }
        try w.write(to: url)

        let zim = try ZIMArchive(url: url)
        XCTAssertTrue(zim.usesNewNamespaceScheme)
        XCTAssertEqual(zim.metadata("Title"), "Test Pack")
        XCTAssertEqual(zim.mainPagePath, "index")
        XCTAssertEqual(zim.articleCount, 20)
        XCTAssertGreaterThan(zim.clusterCount, 2)

        let page = try XCTUnwrap(zim.content(path: "pmid/7"))
        XCTAssertEqual(String(decoding: page.data, as: UTF8.self), "<p>Keep the limb still. Paper 7.</p>")
        XCTAssertEqual(page.mimeType, "text/html")
        XCTAssertEqual(try zim.content(path: "logo.png")?.data, Data([0x89, 0x50, 0x4e, 0x47]))

        XCTAssertEqual(zim.titleSearch("snake bite review 1", limit: 5).first?.title, "Snake bite review 1")
        var seen = 0
        zim.forEachArticle { _ in seen += 1; return true }
        XCTAssertEqual(seen, 20)
    }

    func testRejectsDuplicatePaths() {
        var w = ZIMWriter()
        w.add(.init(path: "a", title: "A", mimeType: "text/html", data: Data(), isArticle: true))
        w.add(.init(path: "a", title: "A", mimeType: "text/html", data: Data(), isArticle: true))
        XCTAssertThrowsError(try w.write(to: FileManager.default.temporaryDirectory.appendingPathComponent("dup.zim")))
    }
}
