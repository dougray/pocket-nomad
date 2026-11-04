import Foundation
import XCTest
@testable import NomadSearch
#if canImport(PDFKit)
import PDFKit

final class PDFTextTests: XCTestCase {
    func testRejoinsLinesIntoParagraphs() {
        let page = "Add 8 drops of bleach per gal-\nlon of clear water\nand wait 30 minutes.\n\nIf cloudy, use 16 drops."
        XCTAssertEqual(PDFText.paragraphs(page),
                       "Add 8 drops of bleach per gallon of clear water and wait 30 minutes.\n\nIf cloudy, use 16 drops.")
    }

    func testTitleRules() {
        XCTAssertEqual(PDFText.usableTitle("Microsoft Word - Where There Is No Doctor"), "Where There Is No Doctor")
        XCTAssertNil(PDFText.usableTitle("Preface"))
        XCTAssertNil(PDFText.usableTitle("22\\wq22.PDF"))
        XCTAssertNil(PDFText.usableTitle("doc1.docx"))
        XCTAssertNil(PDFText.usableTitle("12"))
        XCTAssertEqual(PDFText.fileTitle(title: "", path: "files/Water_Treatment-Guide.pdf"),
                       "Water Treatment Guide")
    }

    func testExtractsTextFromAPDF() throws {
        let data = NSMutableData()
        var box = CGRect(x: 0, y: 0, width: 612, height: 792)
        let ctx = try XCTUnwrap(CGContext(consumer: CGDataConsumer(data: data)!, mediaBox: &box,
                                          [kCGPDFContextTitle: "Field Water Guide"] as CFDictionary))
        ctx.beginPDFPage(nil)
        let line = NSAttributedString(string: "Boil water for one minute.",
                                      attributes: [.font: CTFontCreateWithName("Helvetica" as CFString, 14, nil)])
        ctx.textPosition = CGPoint(x: 72, y: 700)
        CTLineDraw(CTLineCreateWithAttributedString(line), ctx)
        ctx.endPDFPage()
        ctx.closePDF()
        let pdf = try XCTUnwrap(PDFText.extract(data as Data))
        XCTAssertEqual(pdf.title, "Field Water Guide")
        XCTAssertTrue(pdf.text.contains("Boil water for one minute."))
    }
}
#endif
