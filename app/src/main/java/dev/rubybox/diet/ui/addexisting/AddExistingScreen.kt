package dev.rubybox.diet.ui.addexisting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.rubybox.diet.data.Repository
import dev.rubybox.diet.data.SavedMeal
import dev.rubybox.diet.ui.BackTopBar
import dev.rubybox.diet.ui.appViewModel
import dev.rubybox.diet.ui.kcalStr
import dev.rubybox.diet.ui.numStr
import dev.rubybox.diet.ui.toNum
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class AddExistingViewModel(private val repo: Repository) : ViewModel() {
    val meals = repo.activeMeals().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var query by mutableStateOf("")
        private set
    var selected by mutableStateOf<SavedMeal?>(null)
        private set
    var weight by mutableStateOf("")
    var count by mutableStateOf("1")
    private var adding = false

    fun onQuery(q: String) {
        query = q
        if (selected?.title != q) selected = null
    }

    fun select(m: SavedMeal) {
        selected = m
        query = m.title
        weight = m.defaultWeightG.numStr()
        count = "1"
    }

    val totalWeight: Double?
        get() {
            val w = weight.toNum() ?: return null
            val c = count.toNum() ?: return null
            return w * c
        }

    val totalKcal: Double? get() = selected?.let { m -> totalWeight?.let { it * m.kcalPer100 / 100 } }

    val canAdd: Boolean get() = selected != null && (totalWeight ?: 0.0) > 0

    fun add(onDone: () -> Unit) {
        val m = selected ?: return
        val w = totalWeight ?: return
        if (adding) return
        adding = true
        val c = count.toNum() ?: 1.0
        val title = if (c == 1.0) m.title else "${m.title} ×${c.numStr()}"
        viewModelScope.launch {
            repo.logSaved(LocalDate.now(), m, w, title)
            onDone()
        }
    }
}

/** Case-insensitive; every word of the query must appear in the title. Keeps usage ordering. */
private fun filter(meals: List<SavedMeal>, query: String): List<SavedMeal> {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return meals.take(HOT_LIST_SIZE)
    return meals.filter { m -> val t = m.title.lowercase(); words.all { t.contains(it) } }
}

private const val HOT_LIST_SIZE = 20

@Composable
fun AddExistingScreen(onBack: () -> Unit) {
    val vm = appViewModel { AddExistingViewModel(it.repo) }
    val meals by vm.meals.collectAsStateWithLifecycle()
    val shown = remember(meals, vm.query) { filter(meals, vm.query) }

    Scaffold(
        topBar = { BackTopBar("Add existing meal", onBack) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = vm.query,
                onValueChange = vm::onQuery,
                label = { Text("Meal") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Default.Close, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = vm.weight,
                    onValueChange = { vm.weight = it },
                    label = { Text("Weight, g") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = vm.selected != null,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = vm.count,
                    onValueChange = { vm.count = it },
                    label = { Text("Count") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = vm.selected != null,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                vm.totalKcal?.let { "Total: ${vm.totalWeight!!.numStr()} g · ${it.kcalStr()} kcal" }
                    ?: "Select a meal from the list",
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = { vm.add(onBack) }, enabled = vm.canAdd, modifier = Modifier.fillMaxWidth()) {
                Text("Add")
            }
            Text(
                if (vm.query.isBlank()) "Frequent" else "Matches",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            LazyColumn(Modifier.weight(1f)) {
                if (shown.isEmpty()) {
                    item {
                        Text(
                            if (meals.isEmpty()) "No saved meals yet. Use Add new → Add & save." else "Nothing found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                items(shown, key = { it.id }) { m ->
                    val isSelected = m.id == vm.selected?.id
                    ListItem(
                        headlineContent = { Text(m.title) },
                        supportingContent = {
                            Text("${m.defaultWeightG.numStr()} g · ${m.kcalPer100.kcalStr()} kcal/100 g")
                        },
                        colors = if (isSelected) {
                            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            ListItemDefaults.colors()
                        },
                        modifier = Modifier.clickable { vm.select(m) },
                    )
                }
            }
        }
    }
}
