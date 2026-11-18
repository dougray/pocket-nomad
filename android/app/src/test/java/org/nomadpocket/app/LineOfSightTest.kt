package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.map.*
import kotlin.math.abs
import kotlin.math.cos

class LineOfSightTest {
    private val a = ShapePoint(30.0, -97.0)
    private fun east(km: Double) = ShapePoint(30.0, -97.0 + km / (111.32 * cos(Math.toRadians(30.0))))
    private val flat: (Double, Double) -> Double? = { _, _ -> 0.0 }

    @Test fun workedExamples() {
        assertEquals(0.3277, LineOfSight.wavelength(915.0), 0.0005)
        assertEquals(28.62, LineOfSight.fresnelRadius(5000.0, 5000.0, 915.0), 0.05)
        assertEquals(1.472, LineOfSight.bulge(5000.0, 5000.0), 0.005)
        assertEquals(4.12 * 2 * Math.sqrt(2.0), LineOfSight.horizon(2.0) * 2 / 1000, 0.1)
    }

    @Test fun flatGroundHorizon() {
        assertNotEquals(PathVerdict.BLOCKED, LineOfSight.analyze(a, east(11.0), 2.0, 2.0, 915.0, flat).verdict)
        assertEquals(PathVerdict.BLOCKED, LineOfSight.analyze(a, east(13.0), 2.0, 2.0, 915.0, flat).verdict)
    }

    @Test fun ridgeBlocksAndHighAntennasClear() {
        val mid = east(5.0).longitude
        val ridge: (Double, Double) -> Double? = { _, lon -> if (abs(lon - mid) < 0.004) 100.0 else 0.0 }
        val r = LineOfSight.analyze(a, east(10.0), 10.0, 10.0, 915.0, ridge)
        assertEquals(PathVerdict.BLOCKED, r.verdict)
        assertEquals(5000.0, r.worstAt, 400.0)
        assertEquals(PathVerdict.CLEAR, LineOfSight.analyze(a, east(10.0), 150.0, 150.0, 915.0, ridge).verdict)
    }

    @Test fun tightPathAndNoData() {
        assertEquals(PathVerdict.MARGINAL, LineOfSight.analyze(a, east(10.0), 10.0, 10.0, 915.0, flat).verdict)
        assertEquals(PathVerdict.UNKNOWN, LineOfSight.analyze(a, east(10.0), 10.0, 10.0, 915.0) { _, _ -> null }.verdict)
    }
}
