package dev.rubybox.diet.ui.main

import androidx.compose.animation.core.animateFloatAsState
import dev.rubybox.diet.ui.toNum
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.draw.clip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.rubybox.diet.data.LogEntry
import dev.rubybox.diet.data.Prefs
import dev.rubybox.diet.data.Repository
import dev.rubybox.diet.health.ActivityTracker
import dev.rubybox.diet.ui.Routes
import dev.rubybox.diet.ui.appViewModel
import dev.rubybox.diet.ui.gramsStr
import dev.rubybox.diet.ui.kcalStr
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class MainState(
    val date: LocalDate = LocalDate.now(),
    val entries: List<LogEntry> = emptyList(),
    val goal: Int = Prefs.DEFAULT_GOAL,
    /** Health Connect part of the activity (0 when the feature is off). */
    val healthKcal: Double = 0.0,
    /** Manual correction added to [healthKcal]. */
    val correction: Double = 0.0,
    val useHealthConnect: Boolean = false,
) {
    /** Shown activity, never below 0. */
    val activity = maxOf(0.0, healthKcal + correction)
    val limit = goal + activity
    val kcal = entries.sumOf { it.kcal }
    val fat = entries.sumOf { it.fatG }
    val protein = entries.sumOf { it.proteinG }
    val carbs = entries.sumOf { it.carbsG }
}

class MainViewModel(
    private val repo: Repository,
    prefs: Prefs,
    private val tracker: ActivityTracker,
) : ViewModel() {
    private val date = MutableStateFlow(LocalDate.now())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state = combine(
        date,
        date.flatMapLatest { repo.day(it) },
        prefs.kcalGoal,
        date.flatMapLatest { repo.activity(it) },
        prefs.useActivity,
    ) { d, e, g, a, use ->
        MainState(
            date = d,
            entries = e,
            goal = g,
            healthKcal = if (use) a?.activeKcal ?: 0.0 else 0.0,
            correction = a?.correctionKcal ?: 0.0,
            useHealthConnect = use,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainState())

    /** Called on resume: rolls the date over and pulls activity calories from Health Connect. */
    fun refresh() {
        date.value = LocalDate.now()
        viewModelScope.launch { tracker.refresh() }
    }

    /** Sets today's shown activity to [value]; null resets the correction. */
    fun setActivity(value: Double?) {
        val s = state.value
        viewModelScope.launch { repo.setActivityManual(s.date, value, s.useHealthConnect) }
    }

    fun delete(entry: LogEntry) {
        viewModelScope.launch { repo.deleteEntry(entry) }
    }
}

private data class MenuEntry(val label: String, val icon: ImageVector, val route: String)

private val menu = listOf(
    MenuEntry("History", Icons.Default.CalendarMonth, Routes.HISTORY),
    MenuEntry("Goals", Icons.Default.Flag, Routes.GOALS),
    MenuEntry("My meals", Icons.Default.Restaurant, Routes.MEALS),
    MenuEntry("Settings", Icons.Default.Settings, Routes.SETTINGS),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onNavigate: (String) -> Unit) {
    val vm = appViewModel { MainViewModel(it.repo, it.prefs, it.activity) }
    val s by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<LogEntry?>(null) }
    var editActivity by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text("Diet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(24.dp))
                menu.forEach { m ->
                    NavigationDrawerItem(
                        label = { Text(m.label) },
                        icon = { Icon(m.icon, contentDescription = null) },
                        selected = false,
                        onClick = {
                            scope.launch { drawer.close() }
                            onNavigate(m.route)
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(s.date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                )
            },
        ) { pad ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item { CalorieGauge(s.kcal, s.goal, s.activity, { editActivity = true }, Modifier.fillMaxWidth()) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { onNavigate(Routes.ADD_NEW) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add new")
                        }
                        FilledTonalButton(onClick = { onNavigate(Routes.ADD_EXISTING) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add existing")
                        }
                    }
                }
                item { MacrosCard(s) }
                if (s.entries.isEmpty()) {
                    item {
                        Text(
                            "No meals logged today",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                }
                items(s.entries, key = { it.id }) { e -> EntryRow(e, onDelete = { toDelete = e }) }
            }
        }
    }

    if (editActivity) {
        ActivityDialog(
            s = s,
            onSave = { vm.setActivity(it); editActivity = false },
            onDismiss = { editActivity = false },
        )
    }

    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete meal?") },
            text = { Text("${e.title} · ${e.kcal.kcalStr()} kcal") },
            confirmButton = {
                TextButton(onClick = { vm.delete(e); toDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CalorieGauge(
    consumed: Double,
    baseGoal: Int,
    activity: Double,
    onActivityClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val goal = baseGoal + activity
    val over = consumed > goal
    val progress = if (goal > 0) (consumed / goal).toFloat().coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(progress, label = "gauge")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Box(modifier.height(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(230.dp)) {
            val stroke = 20.dp.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 150f, 240f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (animated > 0f) {
                drawArc(color, 150f, 240f * animated, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(consumed.kcalStr(), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            Text("of ${goal.kcalStr()} kcal", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onActivityClick)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "$baseGoal + ${activity.kcalStr()} activity",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit activity calories",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            val left = goal - consumed
            Text(
                if (left >= 0) "${left.kcalStr()} left" else "${(-left).kcalStr()} over",
                style = MaterialTheme.typography.titleMedium,
                color = color,
            )
        }
    }
}

@Composable
private fun ActivityDialog(s: MainState, onSave: (Double?) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(s.activity.kcalStr()) }
    val value = text.toNum()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Activity calories") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Today, kcal") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                val sign = if (s.correction >= 0) "+" else "−"
                Text(
                    "Health Connect: ${s.healthKcal.kcalStr()}, correction: $sign${kotlin.math.abs(s.correction).kcalStr()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = value != null && value >= 0) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave(null) }) { Text("Reset") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun MacrosCard(s: MainState) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Macro("Fat", s.fat, Modifier.weight(1f))
            Macro("Protein", s.protein, Modifier.weight(1f))
            Macro("Carbs", s.carbs, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Macro(label: String, grams: Double, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${grams.gramsStr()} g", style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EntryRow(e: LogEntry, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(e.title) },
        supportingContent = { Text("${e.weightG.gramsStr()} g") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${e.kcal.kcalStr()} kcal", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
            }
        },
    )
}
