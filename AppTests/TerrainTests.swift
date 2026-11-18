import XCTest
@testable import NomadPocket

final class TerrainTests: XCTestCase {
    private func fixture() throws -> [String: Any] {
        let url = Bundle(for: Self.self).url(forResource: "terrain-reference", withExtension: "json")!
        return try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: Any]
    }

    func testTerrariumDecodeMatchesPython() throws {
        let f = try fixture()
        let crop = f["crop"] as! [[[Int]]], elev = f["elev256"] as! [[Int]]
        for y in 0..<8 { for x in 0..<8 {
            let p = crop[y][x]
            XCTAssertEqual(TerrainMath.terrarium(UInt8(p[0]), UInt8(p[1]), UInt8(p[2])), Double(elev[y][x]) / 256, accuracy: 1e-9)
        } }
    }

    func testTileMath() throws {
        for t in try fixture()["tiles"] as! [[String: Any]] {
            let r = TerrainMath.tileXY(latitude: t["lat"] as! Double, longitude: t["lon"] as! Double, zoom: t["z"] as! Int)
            XCTAssertEqual(r.x, Double(t["x"] as! String)!, accuracy: 1e-5)
            XCTAssertEqual(r.y, Double(t["y"] as! String)!, accuracy: 1e-5)
        }
    }

    func testBilinearAndTileCoverage() {
        XCTAssertEqual(TerrainMath.bilinear(0, 10, 20, 30, fx: 0.5, fy: 0.5), 15, accuracy: 1e-9)
        let tiles = TerrainMath.tiles(centre: .init(latitude: 30.27, longitude: -97.74), radiusKm: 50, zoom: 11)
        XCTAssertTrue((16...50).contains(tiles.count), "got \(tiles.count)")
        XCTAssertEqual(TerrainMath.tiles(centre: .init(latitude: 30, longitude: -97), radiusKm: 1, zoom: 6).count, 1)
    }

    func testLookOpacities() {
        XCTAssertEqual(MapLook.streets.hillshadeOpacity, 0)
        XCTAssertTrue(MapLook.relief.hillshadeOpacity > MapLook.topo.hillshadeOpacity)
    }
}
