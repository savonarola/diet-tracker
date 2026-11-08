package dev.rubybox.diet.ui.meals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MealsViewModel(private val repo: Repository) : ViewModel() {
    val meals = repo.activeMeals()
        .map { list -> list.sortedBy { it.title.lowercase() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(meal: SavedMeal) {
        viewModelScope.launch { repo.updateMeal(meal) }
    }

    fun delete(meal: SavedMeal) {
        viewModelScope.launch { repo.deleteMeal(meal) }
    }
}

@Composable
fun MealsScreen(onBack: () -> Unit) {
    val vm = appViewModel { MealsViewModel(it.repo) }
    val meals by vm.meals.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SavedMeal?>(null) }
    var deleting by remember { mutableStateOf<SavedMeal?>(null) }

    Scaffold(topBar = { BackTopBar("My meals", onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            if (meals.isEmpty()) {
                item {
                    Text(
                        "No saved meals yet. Use Add new → Add & save.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(meals, key = { it.id }) { m ->
                ListItem(
                    headlineContent = { Text(m.title) },
                    supportingContent = { Text("${m.defaultWeightG.numStr()} g · ${m.kcalPer100.kcalStr()} kcal/100 g") },
                    trailingContent = {
                        IconButton(onClick = { deleting = m }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    },
                    modifier = Modifier.clickable { editing = m },
                )
            }
        }
    }

    editing?.let { m -> EditDialog(m, onDismiss = { editing = null }, onSave = { vm.update(it); editing = null }) }
    deleting?.let { m ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete saved meal?") },
            text = { Text("${m.title}\nAlready logged entries are not affected.") },
            confirmButton = { TextButton(onClick = { vm.delete(m); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EditDialog(meal: SavedMeal, onDismiss: () -> Unit, onSave: (SavedMeal) -> Unit) {
    var title by remember { mutableStateOf(meal.title) }
    var weight by remember { mutableStateOf(meal.defaultWeightG.numStr()) }
    var kcal by remember { mutableStateOf(meal.kcalPer100.numStr()) }
    val w = weight.toNum()
    val k = kcal.toNum()
    val valid = title.isNotBlank() && w != null && w > 0 && k != null && k >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit meal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    weight, { weight = it }, label = { Text("Default weight, g") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    kcal, { kcal = it }, label = { Text("kcal / 100 g") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(meal.copy(title = title.trim(), defaultWeightG = w!!, kcalPer100 = k!!)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
