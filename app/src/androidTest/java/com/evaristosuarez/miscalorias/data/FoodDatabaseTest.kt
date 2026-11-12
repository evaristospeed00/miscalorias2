package com.evaristosuarez.miscalorias.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodDatabaseTest {
    @Test
    fun searchAndMealRecordingUseLocalDatabase() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("miscalorias.db")
        val database = FoodDatabase(context)
        try {
            val rice = database.searchFoods("arroz").single()
            val day = LocalDate.of(2026, 11, 10)
            database.addMeal(day, "Comida", rice, 150)
            val entry = database.mealsFor(day).single()

            assertEquals("Arroz blanco cocido", entry.foodName)
            assertEquals(195, entry.nutrition.calories)
            assertEquals(195, database.dailyCalories(day))
            assertTrue(database.searchFoods("inexistente").isEmpty())

            // Test special SQL characters in search
            assertTrue(database.searchFoods("\\").isEmpty())
            assertTrue(database.searchFoods("%").isEmpty())
            assertTrue(database.searchFoods("_").isEmpty())

            // Test custom food addition with trimmed name
            val custom = database.addCustomFood(
                Food(
                    name = "  Manzana Deshidratada  ",
                    caloriesPer100g = 240.0,
                    proteinPer100g = 1.0,
                    carbsPer100g = 65.0,
                    fatPer100g = 0.5,
                ),
            )
            assertEquals("Manzana Deshidratada", custom.name)
            assertTrue(custom.custom)
            assertEquals(1, database.searchFoods("Deshidratada").size)
        } finally {
            database.close()
            context.deleteDatabase("miscalorias.db")
        }
    }
}
