package dev.rubybox.diet.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import dev.rubybox.diet.DietApp
import dev.rubybox.diet.data.AppDatabase
import dev.rubybox.diet.data.DayActivity
import dev.rubybox.diet.data.LogEntry
import dev.rubybox.diet.data.Prefs
import dev.rubybox.diet.data.SavedMeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.concurrent.TimeUnit

// ---- YAML file formats ----

@Serializable
data class MealsFile(val version: Int = 1, val meals: List<MealDto> = emptyList())

@Serializable
data class MealDto(
    val id: String,
    val title: String,
    @SerialName("default_weight_g") val defaultWeightG: Double,
    @SerialName("kcal_per_100g") val kcalPer100: Double,
    @SerialName("fat_per_100g") val fatPer100: Double = 0.0,
    @SerialName("carbs_per_100g") val carbsPer100: Double = 0.0,
    @SerialName("protein_per_100g") val proteinPer100: Double = 0.0,
    @SerialName("updated_at") val updatedAt: String,
    val deleted: Boolean = false,
)

@Serializable
data class DaysFile(
    val version: Int = 1,
    val days: Map<String, List<EntryDto>> = emptyMap(),
    /** Active kcal per day (from Health Connect), which raised that day's limit. */
    @SerialName("activity_kcal") val activityKcal: Map<String, Double> = emptyMap(),
    /** Manual activity correction per day, added to activity_kcal. */
    @SerialName("activity_correction_kcal") val activityCorrectionKcal: Map<String, Double> = emptyMap(),
)

@Serializable
data class EntryDto(
    val title: String,
    @SerialName("weight_g") val weightG: Double,
    val kcal: Double,
    @SerialName("fat_g") val fatG: Double = 0.0,
    @SerialName("carbs_g") val carbsG: Double = 0.0,
    @SerialName("protein_g") val proteinG: Double = 0.0,
    @SerialName("created_at") val createdAt: String,
)

private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()
private fun millis(iso: String) = runCatching { Instant.parse(iso).toEpochMilli() }.getOrDefault(0L)

private fun SavedMeal.toDto() = MealDto(
    id, title, defaultWeightG, kcalPer100, fatPer100, carbsPer100, proteinPer100, iso(updatedAt), deleted,
)

private fun MealDto.toEntity(local: SavedMeal?) = SavedMeal(
    id = id, title = title, defaultWeightG = defaultWeightG,
    kcalPer100 = kcalPer100, fatPer100 = fatPer100, carbsPer100 = carbsPer100, proteinPer100 = proteinPer100,
    updatedAt = millis(updatedAt), deleted = deleted,
    useCount = local?.useCount ?: 0, lastUsedAt = local?.lastUsedAt ?: 0,
)

private fun LogEntry.toDto() = EntryDto(title, weightG, kcal, fatG, carbsG, proteinG, iso(createdAt))

private fun EntryDto.toEntity(date: String) = LogEntry(
    date = date, title = title, weightG = weightG, kcal = kcal,
    fatG = fatG, carbsG = carbsG, proteinG = proteinG, createdAt = millis(createdAt),
)

// ---- Sync ----

/**
 * meals.yaml: merged by id, newer updated_at wins (tombstones included).
 * days.yaml: a day with local entries overwrites the remote day; remote-only days are imported.
 *   activity_kcal and activity_correction_kcal: same rule per day.
 * The OpenAI key is never part of any synced file.
 */
class SyncManager(
    private val db: AppDatabase,
    private val dropbox: DropboxClient,
    private val prefs: Prefs,
) {
    private val mutex = Mutex()
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))

    suspend fun sync(): Result<Unit> = mutex.withLock {
        val result = withContext(Dispatchers.IO) { runCatching { doSync() } }
        prefs.recordSync(result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
        result
    }

    private suspend fun doSync() {
        check(dropbox.isConnected()) { "Dropbox is not connected" }

        // Saved meals
        val remoteMeals = dropbox.download(MEALS_PATH)?.takeIf { it.isNotBlank() }
            ?.let { yaml.decodeFromString(MealsFile.serializer(), it) } ?: MealsFile()
        val local = db.meals().all().associateBy { it.id }.toMutableMap()
        val incoming = remoteMeals.meals.mapNotNull { r ->
            val l = local[r.id]
            if (l == null || millis(r.updatedAt) > l.updatedAt) r.toEntity(l) else null
        }
        if (incoming.isNotEmpty()) db.meals().upsertAll(incoming)
        incoming.forEach { local[it.id] = it }
        val mealsOut = MealsFile(meals = local.values.sortedBy { it.title.lowercase() }.map { it.toDto() })
        dropbox.upload(MEALS_PATH, yaml.encodeToString(MealsFile.serializer(), mealsOut))

        // Day logs
        val remoteDays = dropbox.download(DAYS_PATH)?.takeIf { it.isNotBlank() }
            ?.let { yaml.decodeFromString(DaysFile.serializer(), it) } ?: DaysFile()
        val localDays = db.log().all().groupBy { it.date }
        val toImport = remoteDays.days.filter { (date, entries) -> date !in localDays && entries.isNotEmpty() }
        if (toImport.isNotEmpty()) {
            db.withTransaction {
                toImport.forEach { (date, entries) -> db.log().insertAll(entries.map { it.toEntity(date) }) }
            }
        }
        val merged = (remoteDays.days + localDays.mapValues { (_, es) -> es.sortedBy { it.createdAt }.map { it.toDto() } })
            .filterValues { it.isNotEmpty() }
            .toSortedMap()

        // Activity and correction: a day stored locally wins; remote-only days are imported.
        val localActivity = db.activity().all().associateBy { it.date }
        val remoteActivityDates = remoteDays.activityKcal.keys + remoteDays.activityCorrectionKcal.keys
        val now = System.currentTimeMillis()
        val activityImport = remoteActivityDates.filter { it !in localActivity }.map { d ->
            DayActivity(d, remoteDays.activityKcal[d] ?: 0.0, now, remoteDays.activityCorrectionKcal[d] ?: 0.0)
        }
        if (activityImport.isNotEmpty()) db.activity().upsertAll(activityImport)
        val allActivity = localActivity.values + activityImport
        val mergedActivity = allActivity.associate { it.date to it.activeKcal }.filterValues { it > 0 }.toSortedMap()
        val mergedCorrection = allActivity.associate { it.date to it.correctionKcal }.filterValues { it != 0.0 }.toSortedMap()

        dropbox.upload(
            DAYS_PATH,
            yaml.encodeToString(
                DaysFile.serializer(),
                DaysFile(days = merged, activityKcal = mergedActivity, activityCorrectionKcal = mergedCorrection),
            ),
        )
    }

    companion object {
        const val MEALS_PATH = "/meals.yaml"
        const val DAYS_PATH = "/days.yaml"
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        val c = (applicationContext as DietApp).container
        if (!c.dropbox.isConnected()) return ListenableWorker.Result.success()
        return when {
            c.sync.sync().isSuccess -> ListenableWorker.Result.success()
            runAttemptCount < 3 -> ListenableWorker.Result.retry()
            else -> ListenableWorker.Result.failure()
        }
    }
}

object SyncScheduler {
    /** Debounced: each change replaces the pending sync. */
    fun schedule(context: Context, delaySeconds: Long = 15) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("dropbox-sync", ExistingWorkPolicy.REPLACE, request)
    }
}
