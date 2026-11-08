package dev.rubybox.diet

import android.app.Application
import android.content.Context
import dev.rubybox.diet.ai.OpenAiClient
import dev.rubybox.diet.data.AppDatabase
import dev.rubybox.diet.data.Prefs
import dev.rubybox.diet.data.Repository
import dev.rubybox.diet.data.SecretStore
import dev.rubybox.diet.health.ActivitySource
import dev.rubybox.diet.health.ActivityTracker
import dev.rubybox.diet.sync.DropboxClient
import dev.rubybox.diet.sync.SyncManager
import dev.rubybox.diet.sync.SyncScheduler
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DietApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedule(this, delaySeconds = 0)
    }
}

class AppContainer(val appContext: Context) {
    val db: AppDatabase = AppDatabase.build(appContext)
    val prefs = Prefs(appContext)
    val secrets = SecretStore(appContext)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    val repo = Repository(db) { SyncScheduler.schedule(appContext) }
    val openAi = OpenAiClient(http)
    val dropbox = DropboxClient(appContext, http, secrets)
    val sync = SyncManager(db, dropbox, prefs)
    val activitySource = ActivitySource(appContext)
    val activity = ActivityTracker(activitySource, repo, prefs)
}
