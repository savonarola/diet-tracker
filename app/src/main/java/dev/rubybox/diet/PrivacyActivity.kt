package dev.rubybox.diet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rubybox.diet.ui.theme.DietTheme

/** Shown by Health Connect when the user asks why the app wants access (required by Health Connect). */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DietTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.systemBarsPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("How Diet uses health data", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Diet reads your active calories burned. It adds them to your daily calorie goal, " +
                                "so days with more activity allow more food. Diet also reads total and basal " +
                                "calories, but only to show a per-app breakdown when you press Test in Settings."
                        )
                        Text(
                            "The data stays on this phone. If you turn on Dropbox sync, each day's active-calorie " +
                                "total is saved with your meal log in your own Dropbox app folder. " +
                                "Nothing is sent anywhere else, and Diet never writes to Health Connect."
                        )
                        TextButton(onClick = { finish() }) { Text("Close") }
                    }
                }
            }
        }
    }
}
