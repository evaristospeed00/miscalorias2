package com.evaristosuarez.miscalorias.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionEngineTest {
    private val carneDeRes = FoodNutritionRecord(
        canonicalName = "Carne de res cocida",
        aliases = setOf("res cocida"),
        per100g = NutrientValuesPer100g(250.0, 26.0, 0.0, 15.0, fiberGrams = 0.0),
        source = NutritionRecordSource("test fixture", "fixture-beef-001"),
    )

    @Test
    fun calculatesUnroundedValuesForEditablePortions() {
        val hundredGrams = carneDeRes.calculate(100.0)
        val oneHundredFiftyGrams = carneDeRes.calculate(150.0)

        assertEquals(250.0, hundredGrams.nutrients.caloriesKcal, 0.0)
        assertEquals(375.0, oneHundredFiftyGrams.nutrients.caloriesKcal, 0.0)
        assertEquals(39.0, oneHundredFiftyGrams.nutrients.proteinGrams, 0.0)
        assertEquals(carneDeRes.source, oneHundredFiftyGrams.source)
    }

    @Test
    fun resolvesOnlyExplicitAliasesAndDoesNotGuessGenericMeatForCarneAsada() {
        assertTrue(FoodNutritionResolver.findExactMatches("carne asada", listOf(carneDeRes)).isEmpty())
        assertEquals(
            listOf(carneDeRes),
            FoodNutritionResolver.findExactMatches("RES COCIDA", listOf(carneDeRes)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPortionSizes() {
        carneDeRes.calculate(0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeNutrients() {
        NutrientValuesPer100g(20.0, -1.0, 1.0, 1.0)
    }
}
