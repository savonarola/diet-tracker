package dev.rubybox.diet

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.rubybox.diet.sync.SyncScheduler
import dev.rubybox.diet.ui.Routes
import dev.rubybox.diet.ui.addexisting.AddExistingScreen
import dev.rubybox.diet.ui.addnew.AddNewScreen
import dev.rubybox.diet.ui.goals.GoalsScreen
import dev.rubybox.diet.ui.history.HistoryScreen
import dev.rubybox.diet.ui.main.MainScreen
import dev.rubybox.diet.ui.meals.MealsScreen
import dev.rubybox.diet.ui.settings.SettingsScreen
import dev.rubybox.diet.ui.theme.DietTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { DietTheme { AppNav() } }
        if (savedInstanceState == null) handleDropboxRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDropboxRedirect(intent)
    }

    private fun handleDropboxRedirect(intent: Intent?) {
        val dropbox = (application as DietApp).container.dropbox
        val uri = intent?.data?.takeIf(dropbox::isRedirect) ?: return
        setIntent(Intent(intent).setData(null)) // don't re-handle on recreation
        lifecycleScope.launch {
            runCatching { dropbox.finishAuthFromRedirect(uri) }
                .onSuccess {
                    Toast.makeText(this@MainActivity, "Dropbox connected", Toast.LENGTH_SHORT).show()
                    SyncScheduler.schedule(this@MainActivity, delaySeconds = 0)
                }
                .onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
        }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack(Routes.MAIN, inclusive = false) }
    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) { MainScreen(onNavigate = { nav.navigate(it) { launchSingleTop = true } }) }
        composable(Routes.ADD_NEW) { AddNewScreen(onBack = back) }
        composable(Routes.ADD_EXISTING) { AddExistingScreen(onBack = back) }
        composable(Routes.HISTORY) { HistoryScreen(onBack = back) }
        composable(Routes.GOALS) { GoalsScreen(onBack = back) }
        composable(Routes.MEALS) { MealsScreen(onBack = back) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = back) }
    }
}
