package dev.rubybox.diet.ui.goals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.rubybox.diet.ui.BackTopBar
import dev.rubybox.diet.ui.rememberContainer
import kotlinx.coroutines.launch

@Composable
fun GoalsScreen(onBack: () -> Unit) {
    val c = rememberContainer()
    val goal by c.prefs.kcalGoal.collectAsStateWithLifecycle(initialValue = null)
    var text by remember(goal) { mutableStateOf(goal?.toString() ?: "") }
    val scope = rememberCoroutineScope()
    val value = text.toIntOrNull()?.takeIf { it > 0 }

    Scaffold(topBar = { BackTopBar("Goals", onBack) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(5) },
                label = { Text("Daily calorie target, kcal") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { scope.launch { c.prefs.setKcalGoal(value!!); onBack() } },
                enabled = value != null,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
