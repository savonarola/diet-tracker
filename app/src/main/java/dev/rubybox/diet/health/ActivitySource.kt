package dev.rubybox.diet.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.time.TimeRangeFilter
import dev.rubybox.diet.data.Prefs
import dev.rubybox.diet.data.Repository
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlin.math.roundToLong
import kotlin.reflect.KClass

/**
 * Active calories from Android Health Connect. Any app can be the source: Huawei Health via Health Sync,
 * Samsung Health, Google Fit, Garmin, … Health Connect de-duplicates overlapping sources.
 */
class ActivitySource(private val context: Context) {

    enum class Status { AVAILABLE, NEEDS_INSTALL, UNAVAILABLE }

    /** Required for the limit. */
    val permissions = setOf(HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class))

    /** Requested together with [permissions]; total and basal calories are read only by [diagnose]. */
    val allPermissions = permissions + setOf(
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
    )

    fun status(): Status = when (HealthConnectClient.getSdkStatus(context, PROVIDER)) {
        HealthConnectClient.SDK_AVAILABLE -> Status.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Status.NEEDS_INSTALL
        else -> Status.UNAVAILABLE
    }

    private fun client() = HealthConnectClient.getOrCreate(context)

    suspend fun hasPermission(): Boolean = granted().containsAll(permissions)

    suspend fun hasAllPermissions(): Boolean = granted().containsAll(allPermissions)

    private suspend fun granted(): Set<String> =
        if (status() == Status.AVAILABLE) client().permissionController.getGrantedPermissions() else emptySet()

    /**
     * Total active kcal burned on [date] (local time zone).
     * When [preferHealthSync] is set and Health Sync has data for the day, only Health Sync data counts.
     * Otherwise all sources count, by Health Connect source priority.
     */
    suspend fun activeKcal(date: LocalDate, preferHealthSync: Boolean = false): Double {
        if (preferHealthSync) {
            val hs = aggregate(date, setOf(DataOrigin(HEALTH_SYNC)))
            if (hs > 0) return hs
        }
        return aggregate(date, emptySet())
    }

    private suspend fun aggregate(
        date: LocalDate,
        origins: Set<DataOrigin>,
        metric: AggregateMetric<Energy> = ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
    ): Double {
        val result = client().aggregate(
            AggregateRequest(
                metrics = setOf(metric),
                timeRangeFilter = dayRange(date),
                dataOriginFilter = origins,
            )
        )
        return result[metric]?.inKilocalories ?: 0.0
    }

    private fun dayRange(date: LocalDate) =
        TimeRangeFilter.between(date.atStartOfDay(), date.plusDays(1).atStartOfDay())

    /**
     * Per-source kcal for [date], one line per data type: the aggregated value over all sources, then each source.
     * Used to find out which app writes which calories.
     */
    suspend fun diagnose(date: LocalDate): String = listOf(
        breakdown("Active", ActiveCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, date),
        breakdown("Total", TotalCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord.ENERGY_TOTAL, date),
        breakdown("Basal", BasalMetabolicRateRecord::class, BasalMetabolicRateRecord.BASAL_CALORIES_TOTAL, date),
    ).joinToString("\n")

    private suspend fun <T : Record> breakdown(
        label: String,
        type: KClass<T>,
        metric: AggregateMetric<Energy>,
        date: LocalDate,
    ): String = try {
        val origins = client()
            .readRecords(ReadRecordsRequest(recordType = type, timeRangeFilter = dayRange(date)))
            .records.map { it.metadata.dataOrigin.packageName }.toSortedSet()
        val all = aggregate(date, emptySet(), metric).roundToLong()
        if (origins.isEmpty()) {
            "$label: $all (no records)"
        } else {
            val per = origins.map { "$it ${aggregate(date, setOf(DataOrigin(it)), metric).roundToLong()}" }
            "$label: $all = " + per.joinToString(", ")
        }
    } catch (e: SecurityException) {
        "$label: no access"
    }

    fun installIntent(): Intent = Intent(Intent.ACTION_VIEW).apply {
        data = Uri.parse("market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding")
        setPackage("com.android.vending")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun settingsIntent(): Intent =
        Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    companion object {
        const val PROVIDER = "com.google.android.apps.healthdata"
        const val HEALTH_SYNC = "nl.appyhapps.healthsync"
    }
}

/** Pulls recent days from Health Connect into the local DB (reads only while the app is in the foreground). */
class ActivityTracker(
    private val source: ActivitySource,
    private val repo: Repository,
    private val prefs: Prefs,
) {
    /** Refreshes today and the two days before (sources such as Health Sync can lag). Returns today's kcal. */
    suspend fun refresh(days: Int = 3): Result<Double?> = runCatching {
        if (!prefs.useActivity.first() || !source.hasPermission()) return@runCatching null
        val preferHealthSync = prefs.preferHealthSync.first()
        val today = LocalDate.now()
        var todayKcal = 0.0
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            val kcal = source.activeKcal(d, preferHealthSync)
            repo.setActivity(d, kcal)
            if (i == 0) todayKcal = kcal
        }
        todayKcal
    }
}
