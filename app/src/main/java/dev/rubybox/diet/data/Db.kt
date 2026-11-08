package dev.rubybox.diet.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

data class Per100(val kcal: Double, val fat: Double, val carbs: Double, val protein: Double)

@Entity(tableName = "saved_meals")
data class SavedMeal(
    @PrimaryKey val id: String,
    val title: String,
    val defaultWeightG: Double,
    val kcalPer100: Double,
    val fatPer100: Double,
    val carbsPer100: Double,
    val proteinPer100: Double,
    val updatedAt: Long,
    /** Tombstone, so deletions propagate through sync. */
    val deleted: Boolean = false,
    /** Local-only usage stats for search ordering; not synced. */
    val useCount: Int = 0,
    val lastUsedAt: Long = 0,
) {
    fun per100() = Per100(kcalPer100, fatPer100, carbsPer100, proteinPer100)
}

/** Self-contained snapshot: never references a SavedMeal. */
@Entity(tableName = "log_entries", indices = [Index("date")])
data class LogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** ISO yyyy-MM-dd */
    val date: String,
    val title: String,
    val weightG: Double,
    val kcal: Double,
    val fatG: Double,
    val carbsG: Double,
    val proteinG: Double,
    val createdAt: Long,
)

data class DayTotal(val date: String, val kcal: Double)

/** Active (exercise) calories for a day, read from Health Connect. Raises that day's limit. */
@Entity(tableName = "day_activity")
data class DayActivity(
    /** ISO yyyy-MM-dd */
    @PrimaryKey val date: String,
    val activeKcal: Double,
    val updatedAt: Long,
)

@Dao
interface ActivityDao {
    @Query("SELECT * FROM day_activity WHERE date = :date")
    fun observe(date: String): Flow<DayActivity?>

    @Query("SELECT * FROM day_activity")
    fun observeAll(): Flow<List<DayActivity>>

    @Query("SELECT * FROM day_activity WHERE date = :date")
    suspend fun get(date: String): DayActivity?

    @Query("SELECT * FROM day_activity")
    suspend fun all(): List<DayActivity>

    @Upsert
    suspend fun upsert(a: DayActivity)

    @Upsert
    suspend fun upsertAll(a: List<DayActivity>)
}

@Dao
interface MealDao {
    @Query(
        "SELECT * FROM saved_meals WHERE deleted = 0 " +
            "ORDER BY useCount DESC, lastUsedAt DESC, title COLLATE NOCASE"
    )
    fun observeActive(): Flow<List<SavedMeal>>

    @Query("SELECT * FROM saved_meals")
    suspend fun all(): List<SavedMeal>

    @Upsert
    suspend fun upsert(meal: SavedMeal)

    @Upsert
    suspend fun upsertAll(meals: List<SavedMeal>)

    @Query("UPDATE saved_meals SET useCount = useCount + 1, lastUsedAt = :now WHERE id = :id")
    suspend fun markUsed(id: String, now: Long)
}

@Dao
interface LogDao {
    @Query("SELECT * FROM log_entries WHERE date = :date ORDER BY createdAt")
    fun observeDay(date: String): Flow<List<LogEntry>>

    @Query("SELECT date, SUM(kcal) AS kcal FROM log_entries GROUP BY date")
    fun observeDailyTotals(): Flow<List<DayTotal>>

    @Query("SELECT * FROM log_entries")
    suspend fun all(): List<LogEntry>

    @Insert
    suspend fun insert(entry: LogEntry)

    @Insert
    suspend fun insertAll(entries: List<LogEntry>)

    @Delete
    suspend fun delete(entry: LogEntry)
}

@Database(entities = [SavedMeal::class, LogEntry::class, DayActivity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun meals(): MealDao
    abstract fun log(): LogDao
    abstract fun activity(): ActivityDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `day_activity` (`date` TEXT NOT NULL, `activeKcal` REAL NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`date`))"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "diet.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
