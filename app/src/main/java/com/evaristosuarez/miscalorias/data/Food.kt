package com.evaristosuarez.miscalorias.data

import java.time.LocalDate
import kotlin.math.roundToInt

data class Food(
    val id: Long = 0,
    val name: String,
    val caloriesPer100g: Double,
    val proteinPer100g: Double,
    val carbsPer100g: Double,
    val fatPer100g: Double,
    val custom: Boolean = false,
)

data class Nutrition(
    val calories: Int,
    val protein: Int,
    val carbs: Int,
    val fat: Int,
)

data class MealEntry(
    val id: Long,
    val day: LocalDate,
    val meal: String,
    val foodName: String,
    val grams: Int,
    val nutrition: Nutrition,
)

object NutritionEstimator {
    fun forPortion(food: Food, grams: Int): Nutrition {
        require(grams in 1..5000) { "La porción debe estar entre 1 y 5000 g." }
        val factor = grams / 100.0
        return Nutrition(
            calories = (food.caloriesPer100g * factor).roundToInt(),
            protein = (food.proteinPer100g * factor).roundToInt(),
            carbs = (food.carbsPer100g * factor).roundToInt(),
            fat = (food.fatPer100g * factor).roundToInt(),
        )
    }
}
