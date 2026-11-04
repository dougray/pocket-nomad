package org.nomadpocket.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.nomadpocket.app.tools.WaterCalculator
import org.nomadpocket.app.tools.WaterCalculator.Strength

class WaterCalculatorTest {
    @Test fun drops() {
        assertEquals(8, WaterCalculator.drops(1.0, Strength.SIX, false))
        assertEquals(6, WaterCalculator.drops(1.0, Strength.EIGHT, false))
        assertEquals(16, WaterCalculator.drops(1.0, Strength.SIX, true))
        assertEquals(3, WaterCalculator.drops(WaterCalculator.gallons(1.0, WaterCalculator.Unit.LITERS), Strength.SIX, false))
        assertEquals(2, WaterCalculator.drops(0.25, Strength.SIX, false))
    }

    @Test fun kitchenMeasure() {
        assertEquals("1/8 teaspoon", WaterCalculator.kitchenMeasure(0.125))
        assertEquals("1/2 teaspoon", WaterCalculator.kitchenMeasure(0.5))
        assertEquals("1 1/2 teaspoons", WaterCalculator.kitchenMeasure(1.5))
        assertEquals("1 teaspoon", WaterCalculator.kitchenMeasure(0.98))
        assertEquals("2 1/3 tablespoons", WaterCalculator.kitchenMeasure(7.0))
    }

    @Test fun teaspoons() {
        assertEquals("Use a clean dropper or the tip of a spoon.", WaterCalculator.teaspoons(6))
        assertEquals("About 1/8 teaspoon (1 mL)", WaterCalculator.teaspoons(8))
        assertEquals("About 1 teaspoon (5 mL)", WaterCalculator.teaspoons(64))
    }

    @Test fun doseFromQuestion() {
        assertEquals("5 gallons of cloudy water with 8.25% bleach: 60 drops (about 1 teaspoon). Stir, wait 30 minutes.",
            WaterCalculator.dose("How much bleach for 5 gallons of cloudy water with 8.25% bleach?"))
        assertEquals("1 gallon of clear water with 6% bleach: 8 drops (about 1/8 teaspoon). Stir, wait 30 minutes.",
            WaterCalculator.dose("bleach for 1 gallon"))
        assertEquals("1 gallon of clear water with 6% bleach: 8 drops (about 1/8 teaspoon). Stir, wait 30 minutes.",
            WaterCalculator.dose("How much bleach purifies a gallon of water?"))
        assertEquals("2 liters of cloudy water with 6% bleach: 9 drops (about 1/8 teaspoon). Stir, wait 30 minutes.",
            WaterCalculator.dose("bleach for two liters of murky water"))
        assertEquals("0.5 gallons of clear water with 6% bleach: 4 drops. Stir, wait 30 minutes.",
            WaterCalculator.dose("bleach for half a gallon"))
        assertEquals("1 gallon of clear water with 6% bleach: 8 drops (about 1/8 teaspoon). Stir, wait 30 minutes.",
            WaterCalculator.dose("How much bleach makes drinking water safe?"))
        assertNull(WaterCalculator.dose("How much bleach to clean a kitchen counter?"))
        assertNull(WaterCalculator.dose("How much water should I store for 3 days?"))
    }

    @Test fun boilAdviceUsesFiveThousandFeet() {
        assertEquals("Bring to a rolling boil for 1 minute, or 3 minutes above 5,000 ft (1,524 m).", WaterCalculator.boilText(null))
        assertEquals("Bring to a rolling boil for 1 minute. You are at about 4921 ft.", WaterCalculator.boilText(1500.0))
        assertEquals("Bring to a rolling boil for 3 minutes. You are at about 5249 ft, which needs the longer boil.", WaterCalculator.boilText(1600.0))
    }

    @Test fun formatAmount() {
        assertEquals("5", WaterCalculator.formatAmount(5.0))
        assertEquals("0.25", WaterCalculator.formatAmount(0.25))
        assertEquals("1.5", WaterCalculator.formatAmount(1.5))
    }
}
