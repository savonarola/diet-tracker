package dev.rubybox.diet.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "prefs")

enum class AiProvider(
    val id: String,
    val label: String,
    /** null = user-entered (Custom). */
    val baseUrl: String?,
    val defaultModel: String,
) {
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1", "gpt-6-luna"),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-6-luna"),
    CUSTOM("custom", "Custom", null, ""),
    ;

    /** Each provider has its own key in SecretStore; OpenAI keeps the original name. */
    val secretName: String get() = if (this == OPENAI) SecretStore.OPENAI_KEY else "ai_key_$id"

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: OPENAI
    }
}

data class AiConfig(val provider: AiProvider, val baseUrl: String, val model: String)

data class SyncStatus(val lastSuccessAt: Long? = null, val lastError: String? = null)

class Prefs(context: Context) {
    private val ds = context.dataStore

    private object K {
        val goal = intPreferencesKey("kcal_goal")
        val provider = stringPreferencesKey("ai_provider")
        val customUrl = stringPreferencesKey("openai_base_url")
        fun model(p: AiProvider) = stringPreferencesKey(if (p == AiProvider.OPENAI) "openai_model" else "model_${p.id}")
        val useActivity = booleanPreferencesKey("use_activity")
        val syncAt = longPreferencesKey("sync_at")
        val syncError = stringPreferencesKey("sync_error")
    }

    val kcalGoal: Flow<Int> = ds.data.map { it[K.goal] ?: DEFAULT_GOAL }
    val customUrl: Flow<String> = ds.data.map { it[K.customUrl] ?: "" }
    val aiConfig: Flow<AiConfig> = ds.data.map { p ->
        val provider = AiProvider.fromId(p[K.provider])
        AiConfig(
            provider = provider,
            baseUrl = provider.baseUrl ?: (p[K.customUrl] ?: ""),
            model = p[K.model(provider)] ?: provider.defaultModel,
        )
    }
    /** Add Health Connect active calories to the daily goal. */
    val useActivity: Flow<Boolean> = ds.data.map { it[K.useActivity] ?: false }
    val syncStatus: Flow<SyncStatus> = ds.data.map { SyncStatus(it[K.syncAt], it[K.syncError]) }

    suspend fun setKcalGoal(value: Int) {
        ds.edit { it[K.goal] = value }
    }

    suspend fun setUseActivity(value: Boolean) {
        ds.edit { it[K.useActivity] = value }
    }

    suspend fun setProvider(p: AiProvider) {
        ds.edit { it[K.provider] = p.id }
    }

    suspend fun setModel(p: AiProvider, value: String) {
        ds.edit { it[K.model(p)] = value }
    }

    suspend fun setCustomUrl(value: String) {
        ds.edit { it[K.customUrl] = value }
    }

    suspend fun recordSync(error: String?) {
        ds.edit {
            if (error == null) {
                it[K.syncAt] = System.currentTimeMillis()
                it.remove(K.syncError)
            } else {
                it[K.syncError] = error
            }
        }
    }

    companion object {
        const val DEFAULT_GOAL = 2000
    }
}
