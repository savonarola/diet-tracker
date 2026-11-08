package dev.rubybox.diet.data

import java.time.LocalDate
import java.util.UUID

class Repository(private val db: AppDatabase, private val onChanged: () -> Unit) {

    fun day(date: LocalDate) = db.log().observeDay(date.toString())
    fun activeMeals() = db.meals().observeActive()
    fun dailyTotals() = db.log().observeDailyTotals()
    fun activity(date: LocalDate) = db.activity().observe(date.toString())
    fun allActivity() = db.activity().observeAll()

    /** Stores a day's active kcal; schedules a sync only if the value changed. */
    suspend fun setActivity(date: LocalDate, kcal: Double) {
        val rounded = Math.round(kcal).toDouble()
        if (db.activity().get(date.toString())?.activeKcal == rounded) return
        db.activity().upsert(DayActivity(date.toString(), rounded, System.currentTimeMillis()))
        onChanged()
    }

    suspend fun logMeal(date: LocalDate, title: String, weightG: Double, per100: Per100) {
        val f = weightG / 100.0
        db.log().insert(
            LogEntry(
                date = date.toString(),
                title = title.trim(),
                weightG = weightG,
                kcal = per100.kcal * f,
                fatG = per100.fat * f,
                carbsG = per100.carbs * f,
                proteinG = per100.protein * f,
                createdAt = System.currentTimeMillis(),
            )
        )
        onChanged()
    }

    suspend fun logSaved(date: LocalDate, meal: SavedMeal, weightG: Double, title: String) {
        logMeal(date, title, weightG, meal.per100())
        db.meals().markUsed(meal.id, System.currentTimeMillis())
    }

    suspend fun deleteEntry(entry: LogEntry) {
        db.log().delete(entry)
        onChanged()
    }

    suspend fun saveMeal(title: String, defaultWeightG: Double, per100: Per100) {
        val now = System.currentTimeMillis()
        db.meals().upsert(
            SavedMeal(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                defaultWeightG = defaultWeightG,
                kcalPer100 = per100.kcal,
                fatPer100 = per100.fat,
                carbsPer100 = per100.carbs,
                proteinPer100 = per100.protein,
                updatedAt = now,
                useCount = 1,
                lastUsedAt = now,
            )
        )
        onChanged()
    }

    suspend fun updateMeal(meal: SavedMeal) {
        db.meals().upsert(meal.copy(updatedAt = System.currentTimeMillis()))
        onChanged()
    }

    suspend fun deleteMeal(meal: SavedMeal) {
        db.meals().upsert(meal.copy(deleted = true, updatedAt = System.currentTimeMillis()))
        onChanged()
    }
}
