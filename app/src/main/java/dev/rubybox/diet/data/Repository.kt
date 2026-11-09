package dev.rubybox.diet.data

import java.time.LocalDate
import java.util.UUID

class Repository(private val db: AppDatabase, private val onChanged: () -> Unit) {

    fun day(date: LocalDate) = db.log().observeDay(date.toString())
    fun activeMeals() = db.meals().observeActive()
    fun dailyTotals() = db.log().observeDailyTotals()
    fun activity(date: LocalDate) = db.activity().observe(date.toString())
    fun allActivity() = db.activity().observeAll()

    /** Stores a day's Health Connect kcal, keeping the correction; schedules a sync only if the value changed. */
    suspend fun setActivity(date: LocalDate, kcal: Double) {
        val rounded = Math.round(kcal).toDouble()
        val existing = db.activity().get(date.toString())
        if (existing?.activeKcal == rounded) return
        val now = System.currentTimeMillis()
        db.activity().upsert(
            existing?.copy(activeKcal = rounded, updatedAt = now) ?: DayActivity(date.toString(), rounded, now)
        )
        onChanged()
    }

    /**
     * Sets the shown activity for [date] to [value] by storing correction = value - Health Connect kcal.
     * When [useHealthConnect] is off, the Health Connect part counts as 0. [value] = null resets the correction.
     */
    suspend fun setActivityManual(date: LocalDate, value: Double?, useHealthConnect: Boolean) {
        val existing = db.activity().get(date.toString())
        val hs = if (useHealthConnect) existing?.activeKcal ?: 0.0 else 0.0
        val correction = value?.let { Math.round(it - hs).toDouble() } ?: 0.0
        val now = System.currentTimeMillis()
        db.activity().upsert(
            existing?.copy(correctionKcal = correction, updatedAt = now)
                ?: DayActivity(date.toString(), 0.0, now, correction)
        )
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
