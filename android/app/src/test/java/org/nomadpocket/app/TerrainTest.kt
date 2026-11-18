package org.nomadpocket.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.map.MapLook
import org.nomadpocket.app.map.TerrainMath

class TerrainTest {
    private val ref = JSONObject(javaClass.getResource("/terrain/terrain-reference.json")!!.readText())

    @Test fun terrariumMatchesPython() {
        val crop = ref.getJSONArray("crop"); val elev = ref.getJSONArray("elev256")
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = crop.getJSONArray(y).getJSONArray(x)
            assertEquals(elev.getJSONArray(y).getLong(x) / 256.0, TerrainMath.terrarium(p.getInt(0), p.getInt(1), p.getInt(2)), 1e-9)
        }
    }

    @Test fun tileMath() {
        val tiles = ref.getJSONArray("tiles")
        for (i in 0 until tiles.length()) {
            val t = tiles.getJSONObject(i)
            val r = TerrainMath.tileXY(t.getDouble("lat"), t.getDouble("lon"), t.getInt("z"))
            assertEquals(t.getString("x").toDouble(), r.first, 1e-5)
            assertEquals(t.getString("y").toDouble(), r.second, 1e-5)
        }
    }

    @Test fun bilinearAndCoverage() {
        assertEquals(15.0, TerrainMath.bilinear(0.0, 10.0, 20.0, 30.0, 0.5, 0.5), 1e-9)
        assertTrue(TerrainMath.tiles(30.27, -97.74, 50.0, 11).size in 16..50)
        assertEquals(1, TerrainMath.tiles(30.0, -97.0, 1.0, 6).size)
        assertTrue(MapLook.RELIEF.exaggeration > MapLook.TOPO.exaggeration)
    }
}
