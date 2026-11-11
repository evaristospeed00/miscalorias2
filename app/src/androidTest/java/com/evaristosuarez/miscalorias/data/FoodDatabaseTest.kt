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
        } finally {
            database.close()
            context.deleteDatabase("miscalorias.db")
        }
    }
}
