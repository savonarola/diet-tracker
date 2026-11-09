package dev.rubybox.diet.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.rubybox.diet.data.Prefs
import dev.rubybox.diet.data.Repository
import dev.rubybox.diet.ui.BackTopBar
import dev.rubybox.diet.ui.appViewModel
import dev.rubybox.diet.ui.kcalStr
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class HistoryState(
    val totals: Map<LocalDate, Double> = emptyMap(),
    val goal: Int = Prefs.DEFAULT_GOAL,
    /** Shown activity per day: Health Connect kcal (when used) plus manual correction. */
    val activity: Map<LocalDate, Double> = emptyMap(),
) {
    fun limit(date: LocalDate): Double = goal + (activity[date] ?: 0.0)
}

class HistoryViewModel(repo: Repository, prefs: Prefs) : ViewModel() {
    val state = combine(repo.dailyTotals(), prefs.kcalGoal, repo.allActivity(), prefs.useActivity) { totals, goal, act, use ->
        HistoryState(
            totals.mapNotNull { t -> runCatching { LocalDate.parse(t.date) to t.kcal }.getOrNull() }.toMap(),
            goal,
            act.mapNotNull { a -> runCatching { LocalDate.parse(a.date) to a.total(use) }.getOrNull() }.toMap(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState())
}

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val vm = appViewModel { HistoryViewModel(it.repo, it.prefs) }
    val s by vm.state.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    val months = remember(s.totals) {
        val now = YearMonth.from(today)
        val earliest = s.totals.keys.minOrNull()?.let(YearMonth::from)?.takeIf { it.isBefore(now) } ?: now
        generateSequence(now) { it.minusMonths(1) }.takeWhile { !it.isBefore(earliest) }.toList()
    }

    Scaffold(topBar = { BackTopBar("History", onBack) }) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            items(months, key = { it.toString() }) { ym -> MonthGrid(ym, s, today) }
        }
    }
}

private val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy")

@Composable
private fun MonthGrid(ym: YearMonth, s: HistoryState, today: LocalDate) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(ym.format(monthFormat), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach {
                Text(
                    it.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val offset = ym.atDay(1).dayOfWeek.value - 1 // Monday-first
        val days = ym.lengthOfMonth()
        val rows = (offset + days + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0..6) {
                    val day = r * 7 + c - offset + 1
                    Box(Modifier.weight(1f).aspectRatio(0.8f).padding(2.dp)) {
                        if (day in 1..days) {
                            val date = ym.atDay(day)
                            DayCell(date, s.totals[date], s.limit(date), isToday = date == today, isFuture = date.isAfter(today))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, kcal: Double?, goal: Double, isToday: Boolean, isFuture: Boolean) {
    val bg = when {
        kcal == null -> Color.Transparent
        kcal > goal -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxSize().alpha(if (isFuture) 0.4f else 1f),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (kcal != null) {
                Text(kcal.kcalStr(), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
