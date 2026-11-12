package com.evaristosuarez.miscalorias.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import java.time.LocalDate

class FoodDatabase(context: Context) : SQLiteOpenHelper(context, "miscalorias.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE foods(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL COLLATE NOCASE UNIQUE,
                kcal REAL NOT NULL, protein REAL NOT NULL,
                carbs REAL NOT NULL, fat REAL NOT NULL,
                custom INTEGER NOT NULL DEFAULT 0
            )""",
        )
        db.execSQL(
            """CREATE TABLE meals(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                day TEXT NOT NULL, meal TEXT NOT NULL, food_name TEXT NOT NULL,
                grams INTEGER NOT NULL, kcal INTEGER NOT NULL,
                protein INTEGER NOT NULL, carbs INTEGER NOT NULL, fat INTEGER NOT NULL
            )""",
        )
        db.execSQL("CREATE INDEX meals_by_day ON meals(day)")
        db.transaction {
            seedFoods.forEach { food ->
                insertOrThrow("foods", null, foodValues(food))
            }
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun searchFoods(query: String): List<Food> {
        val trimmed = query.trim()
        val escaped = trimmed
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val pattern = "%$escaped%"
        val rows = readableDatabase.query(
            "foods",
            arrayOf("id", "name", "kcal", "protein", "carbs", "fat", "custom"),
            if (trimmed.isBlank()) null else "name LIKE ? ESCAPE '\\'",
            if (trimmed.isBlank()) null else arrayOf(pattern),
            null,
            null,
            "custom ASC, name COLLATE NOCASE ASC",
            "60",
        )
        return rows.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Food(
                            id = cursor.getLong(0),
                            name = cursor.getString(1),
                            caloriesPer100g = cursor.getDouble(2),
                            proteinPer100g = cursor.getDouble(3),
                            carbsPer100g = cursor.getDouble(4),
                            fatPer100g = cursor.getDouble(5),
                            custom = cursor.getInt(6) == 1,
                        ),
                    )
                }
            }
        }
    }

    fun addCustomFood(food: Food): Food {
        val trimmedName = food.name.trim()
        require(trimmedName.isNotBlank()) { "Escribe un nombre para el alimento." }
        require(food.caloriesPer100g in 0.0..2000.0) { "Las calorías deben estar entre 0 y 2000 kcal por 100 g." }
        require(food.proteinPer100g in 0.0..100.0) { "La proteína debe estar entre 0 y 100 g por 100 g." }
        require(food.carbsPer100g in 0.0..100.0) { "Los carbohidratos deben estar entre 0 y 100 g por 100 g." }
        require(food.fatPer100g in 0.0..100.0) { "Las grasas deben estar entre 0 y 100 g por 100 g." }
        val id = writableDatabase.insertOrThrow("foods", null, foodValues(food.copy(name = trimmedName, custom = true)))
        return food.copy(id = id, name = trimmedName, custom = true)
    }

    fun addMeal(day: LocalDate, meal: String, food: Food, grams: Int): Long {
        val nutrition = NutritionEstimator.forPortion(food, grams)
        val values = ContentValues().apply {
            put("day", day.toString())
            put("meal", meal)
            put("food_name", food.name)
            put("grams", grams)
            put("kcal", nutrition.calories)
            put("protein", nutrition.protein)
            put("carbs", nutrition.carbs)
            put("fat", nutrition.fat)
        }
        return writableDatabase.insertOrThrow("meals", null, values)
    }

    fun mealsFor(day: LocalDate): List<MealEntry> {
        val rows = readableDatabase.query(
            "meals",
            arrayOf("id", "day", "meal", "food_name", "grams", "kcal", "protein", "carbs", "fat"),
            "day = ?",
            arrayOf(day.toString()),
            null,
            null,
            "id DESC",
        )
        return rows.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MealEntry(
                            id = cursor.getLong(0),
                            day = LocalDate.parse(cursor.getString(1)),
                            meal = cursor.getString(2),
                            foodName = cursor.getString(3),
                            grams = cursor.getInt(4),
                            nutrition = Nutrition(
                                calories = cursor.getInt(5),
                                protein = cursor.getInt(6),
                                carbs = cursor.getInt(7),
                                fat = cursor.getInt(8),
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun dailyCalories(day: LocalDate): Int {
        val cursor = readableDatabase.rawQuery("SELECT COALESCE(SUM(kcal), 0) FROM meals WHERE day = ?", arrayOf(day.toString()))
        return cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun deleteMeal(id: Long) {
        writableDatabase.delete("meals", "id = ?", arrayOf(id.toString()))
    }

    fun clearMeals() {
        writableDatabase.delete("meals", null, null)
    }

    private fun foodValues(food: Food) = ContentValues().apply {
        put("name", food.name.trim())
        put("kcal", food.caloriesPer100g)
        put("protein", food.proteinPer100g)
        put("carbs", food.carbsPer100g)
        put("fat", food.fatPer100g)
        put("custom", if (food.custom) 1 else 0)
    }

    companion object {
        val seedFoods = listOf(
            Food(name = "Aguacate", caloriesPer100g = 160.0, proteinPer100g = 2.0, carbsPer100g = 8.5, fatPer100g = 14.7),
            Food(name = "Almendras", caloriesPer100g = 579.0, proteinPer100g = 21.2, carbsPer100g = 21.6, fatPer100g = 49.9),
            Food(name = "Arroz blanco cocido", caloriesPer100g = 130.0, proteinPer100g = 2.7, carbsPer100g = 28.0, fatPer100g = 0.3),
            Food(name = "Avena seca", caloriesPer100g = 389.0, proteinPer100g = 16.9, carbsPer100g = 66.3, fatPer100g = 6.9),
            Food(name = "Atún en agua", caloriesPer100g = 116.0, proteinPer100g = 25.5, carbsPer100g = 0.0, fatPer100g = 0.8),
            Food(name = "Bebida de leche entera", caloriesPer100g = 61.0, proteinPer100g = 3.2, carbsPer100g = 4.8, fatPer100g = 3.3),
            Food(name = "Brócoli cocido", caloriesPer100g = 35.0, proteinPer100g = 2.4, carbsPer100g = 7.2, fatPer100g = 0.4),
            Food(name = "Carne de res cocida", caloriesPer100g = 250.0, proteinPer100g = 26.0, carbsPer100g = 0.0, fatPer100g = 15.0),
            Food(name = "Camarón cocido", caloriesPer100g = 99.0, proteinPer100g = 24.0, carbsPer100g = 0.2, fatPer100g = 0.3),
            Food(name = "Camote cocido", caloriesPer100g = 90.0, proteinPer100g = 2.0, carbsPer100g = 20.7, fatPer100g = 0.2),
            Food(name = "Espagueti cocido", caloriesPer100g = 158.0, proteinPer100g = 5.8, carbsPer100g = 30.9, fatPer100g = 0.9),
            Food(name = "Espinaca", caloriesPer100g = 23.0, proteinPer100g = 2.9, carbsPer100g = 3.6, fatPer100g = 0.4),
            Food(name = "Frijoles cocidos", caloriesPer100g = 127.0, proteinPer100g = 8.7, carbsPer100g = 22.8, fatPer100g = 0.5),
            Food(name = "Huevo entero", caloriesPer100g = 155.0, proteinPer100g = 13.0, carbsPer100g = 1.1, fatPer100g = 11.0),
            Food(name = "Jitomate", caloriesPer100g = 18.0, proteinPer100g = 0.9, carbsPer100g = 3.9, fatPer100g = 0.2),
            Food(name = "Lechuga", caloriesPer100g = 15.0, proteinPer100g = 1.4, carbsPer100g = 2.9, fatPer100g = 0.2),
            Food(name = "Lentejas cocidas", caloriesPer100g = 116.0, proteinPer100g = 9.0, carbsPer100g = 20.0, fatPer100g = 0.4),
            Food(name = "Manzana", caloriesPer100g = 52.0, proteinPer100g = 0.3, carbsPer100g = 13.8, fatPer100g = 0.2),
            Food(name = "Mantequilla de cacahuate", caloriesPer100g = 588.0, proteinPer100g = 25.0, carbsPer100g = 20.0, fatPer100g = 50.0),
            Food(name = "Naranja", caloriesPer100g = 47.0, proteinPer100g = 0.9, carbsPer100g = 11.8, fatPer100g = 0.1),
            Food(name = "Pan integral", caloriesPer100g = 247.0, proteinPer100g = 13.0, carbsPer100g = 41.0, fatPer100g = 4.2),
            Food(name = "Papa cocida", caloriesPer100g = 87.0, proteinPer100g = 1.9, carbsPer100g = 20.1, fatPer100g = 0.1),
            Food(name = "Pechuga de pollo cocida", caloriesPer100g = 165.0, proteinPer100g = 31.0, carbsPer100g = 0.0, fatPer100g = 3.6),
            Food(name = "Pepino", caloriesPer100g = 15.0, proteinPer100g = 0.7, carbsPer100g = 3.6, fatPer100g = 0.1),
            Food(name = "Pera", caloriesPer100g = 57.0, proteinPer100g = 0.4, carbsPer100g = 15.2, fatPer100g = 0.1),
            Food(name = "Pescado salmón cocido", caloriesPer100g = 208.0, proteinPer100g = 20.4, carbsPer100g = 0.0, fatPer100g = 13.4),
            Food(name = "Plátano", caloriesPer100g = 89.0, proteinPer100g = 1.1, carbsPer100g = 22.8, fatPer100g = 0.3),
            Food(name = "Queso cheddar", caloriesPer100g = 403.0, proteinPer100g = 25.0, carbsPer100g = 1.3, fatPer100g = 33.0),
            Food(name = "Queso panela", caloriesPer100g = 200.0, proteinPer100g = 20.0, carbsPer100g = 3.0, fatPer100g = 12.0),
            Food(name = "Tortilla de maíz", caloriesPer100g = 218.0, proteinPer100g = 5.7, carbsPer100g = 44.6, fatPer100g = 2.9),
            Food(name = "Tofu firme", caloriesPer100g = 144.0, proteinPer100g = 17.3, carbsPer100g = 2.8, fatPer100g = 8.7),
            Food(name = "Yogur natural", caloriesPer100g = 61.0, proteinPer100g = 3.5, carbsPer100g = 4.7, fatPer100g = 3.3),
            Food(name = "Aceite de oliva", caloriesPer100g = 884.0, proteinPer100g = 0.0, carbsPer100g = 0.0, fatPer100g = 100.0),
        )
    }
}
