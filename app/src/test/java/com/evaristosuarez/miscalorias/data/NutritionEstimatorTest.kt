package com.evaristosuarez.miscalorias.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NutritionEstimatorTest {
    private val rice = Food(name = "Arroz", caloriesPer100g = 130.0, proteinPer100g = 2.7, carbsPer100g = 28.0, fatPer100g = 0.3)

    @Test
    fun scalesValuesToEnteredPortion() {
        assertEquals(Nutrition(195, 4, 42, 0), NutritionEstimator.forPortion(rice, 150))
    }

    @Test
    fun roundsToWholeGramsAndCalories() {
        val food = Food(name = "Prueba", caloriesPer100g = 52.0, proteinPer100g = 0.3, carbsPer100g = 13.8, fatPer100g = 0.2)
        assertEquals(Nutrition(26, 0, 7, 0), NutritionEstimator.forPortion(food, 50))
    }

    @Test
    fun rejectsInvalidPortions() {
        assertThrows(IllegalArgumentException::class.java) {
            NutritionEstimator.forPortion(rice, 0)
        }
    }
}
