import XCTest
@testable import NomadPocket

@MainActor
final class LanguageTests: XCTestCase {
    func testTranslationsComeFromTheChosenLanguage() {
        XCTAssertEqual(tr("Settings", .es), "Ajustes")
        XCTAssertEqual(tr("Settings", .fr), "Réglages")
        XCTAssertEqual(tr("Settings", .en), "Settings")
        XCTAssertEqual(tr("A string nobody translated", .es), "A string nobody translated", "untranslated text falls back to English")
    }

    func testLanguageNamesAndLocales() {
        XCTAssertEqual(AppLanguage.es.nativeName, "Español")
        XCTAssertEqual(AppLanguage.fr.locale.identifier, "fr")
        XCTAssertNil(AppLanguage.system.code)
        XCTAssertEqual(AppLanguage.allCases.count, 4)
    }

    func testEveryKeyHasBothLanguages() throws {
        func keys(_ lang: String) throws -> Set<String> {
            let path = try XCTUnwrap(Bundle.main.path(forResource: lang, ofType: "lproj"))
            let strings = try XCTUnwrap(NSDictionary(contentsOfFile: path + "/Localizable.strings") as? [String: String])
            return Set(strings.keys)
        }
        let es = try keys("es"), fr = try keys("fr")
        XCTAssertEqual(es, fr)
        XCTAssertGreaterThan(es.count, 100)
    }
}
