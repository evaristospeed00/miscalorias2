package com.evaristosuarez.miscalorias.nutrition

import java.text.Normalizer
import java.util.Locale

data class NutrientValuesPer100g(
    val caloriesKcal: Double,
    val proteinGrams: Double,
    val carbohydrateGrams: Double,
    val fatGrams: Double,
    val fiberGrams: Double? = null,
    val sodiumMilligrams: Double? = null,
) {
    init {
        require(listOf(caloriesKcal, proteinGrams, carbohydrateGrams, fatGrams).all { it.isFinite() && it >= 0.0 })
        require(fiberGrams == null || fiberGrams.isFinite() && fiberGrams >= 0.0)
        require(sodiumMilligrams == null || sodiumMilligrams.isFinite() && sodiumMilligrams >= 0.0)
    }

    operator fun times(factor: Double): NutrientValuesPer100g = NutrientValuesPer100g(
        caloriesKcal = caloriesKcal * factor,
        proteinGrams = proteinGrams * factor,
        carbohydrateGrams = carbohydrateGrams * factor,
        fatGrams = fatGrams * factor,
        fiberGrams = fiberGrams?.times(factor),
        sodiumMilligrams = sodiumMilligrams?.times(factor),
    )
}

data class NutritionRecordSource(
    val name: String,
    val recordId: String,
) {
    init {
        require(name.isNotBlank())
        require(recordId.isNotBlank())
    }
}

data class FoodNutritionRecord(
    val canonicalName: String,
    val aliases: Set<String>,
    val per100g: NutrientValuesPer100g,
    val source: NutritionRecordSource,
) {
    init {
        require(canonicalName.isNotBlank())
        require(aliases.none(String::isBlank))
    }

    fun calculate(grams: Double): PortionNutrition {
        require(grams.isFinite() && grams in 1.0..5000.0) {
            "La porción debe estar entre 1 y 5000 g."
        }
        return PortionNutrition(
            grams = grams,
            nutrients = per100g * (grams / 100.0),
            source = source,
        )
    }
}

data class PortionNutrition(
    val grams: Double,
    val nutrients: NutrientValuesPer100g,
    val source: NutritionRecordSource,
)

object FoodNutritionResolver {
    fun findExactMatches(query: String, records: Collection<FoodNutritionRecord>): List<FoodNutritionRecord> {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isEmpty()) return emptyList()
        return records.filter { record ->
            (record.aliases + record.canonicalName).any { normalize(it) == normalizedQuery }
        }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
}
