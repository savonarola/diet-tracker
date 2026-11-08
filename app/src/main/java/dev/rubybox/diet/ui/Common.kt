package dev.rubybox.diet.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rubybox.diet.AppContainer
import dev.rubybox.diet.DietApp
import java.util.Locale
import kotlin.math.roundToInt

object Routes {
    const val MAIN = "main"
    const val ADD_NEW = "add_new"
    const val ADD_EXISTING = "add_existing"
    const val HISTORY = "history"
    const val GOALS = "goals"
    const val MEALS = "meals"
    const val SETTINGS = "settings"
}

@Composable
fun rememberContainer(): AppContainer = (LocalContext.current.applicationContext as DietApp).container

@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = rememberContainer()
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        },
    )
}

/** Accepts both "12.5" and "12,5". */
fun String.toNum(): Double? = replace(',', '.').trim().toDoubleOrNull()

fun Double.kcalStr(): String = roundToInt().toString()
fun Double.gramsStr(): String = if (this < 10) String.format(Locale.US, "%.1f", this) else roundToInt().toString()
fun Double.numStr(): String = if (this % 1.0 == 0.0) toLong().toString() else String.format(Locale.US, "%.1f", this)
