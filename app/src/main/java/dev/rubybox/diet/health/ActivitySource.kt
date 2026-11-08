package dev.rubybox.diet.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.rubybox.diet.data.Prefs
import dev.rubybox.diet.data.Repository
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Active calories from Android Health Connect. Any app can be the source: Huawei Health via Health Sync,
 * Samsung Health, Google Fit, Garmin, … Health Connect de-duplicates overlapping sources.
 */
class ActivitySource(private val context: Context) {

    enum class Status { AVAILABLE, NEEDS_INSTALL, UNAVAILABLE }

    val permissions = setOf(HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class))

    fun status(): Status = when (HealthConnectClient.getSdkStatus(context, PROVIDER)) {
        HealthConnectClient.SDK_AVAILABLE -> Status.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Status.NEEDS_INSTALL
        else -> Status.UNAVAILABLE
    }

    private fun client() = HealthConnectClient.getOrCreate(context)

    suspend fun hasPermission(): Boolean =
        status() == Status.AVAILABLE && client().permissionController.getGrantedPermissions().containsAll(permissions)

    /** Total active kcal burned on [date] (local time zone). */
    suspend fun activeKcal(date: LocalDate): Double {
        val result = client().aggregate(
            AggregateRequest(
                metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(date.atStartOfDay(), date.plusDays(1).atStartOfDay()),
            )
        )
        return result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
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
        val today = LocalDate.now()
        var todayKcal = 0.0
        for (i in 0 until days) {
            val d = today.minusDays(i.toLong())
            val kcal = source.activeKcal(d)
            repo.setActivity(d, kcal)
            if (i == 0) todayKcal = kcal
        }
        todayKcal
    }
}
