package dev.rubybox.diet.sync

import android.content.Context
import android.net.Uri
import android.util.Base64
import dev.rubybox.diet.BuildConfig
import dev.rubybox.diet.data.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Minimal Dropbox HTTP client. OAuth 2 with PKCE, no server, no app secret.
 * Built-in app: redirects back to the app via [REDIRECT_URI] (custom scheme, allowed with PKCE).
 * User-supplied app key: no redirect; Dropbox shows a code that the user pastes back.
 * The app should be created with "App folder" access and files.content.read/write scopes.
 */
class DropboxClient(context: Context, private val http: OkHttpClient, private val secrets: SecretStore) {
    private val store = context.getSharedPreferences("dropbox", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var accessToken: String? = null
    @Volatile private var accessExpiresAt = 0L

    /** The key used for the current/last connection; falls back to the built-in one. */
    val appKey: String get() = store.getString("app_key", null)?.takeIf { it.isNotBlank() } ?: BUILT_IN_KEY

    fun isConnected(): Boolean = appKey.isNotBlank() && secrets.get(SecretStore.DROPBOX_REFRESH) != null

    private val _connected = MutableStateFlow(isConnected())
    /** Observable connection state (updated by the redirect handler as well as the settings screen). */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** True between Connect and Confirm code, even if the app was restarted meanwhile. */
    fun hasPendingAuth(): Boolean = store.contains("verifier")

    /** Returns the URL to open in the browser. Blank [appKey] = use the built-in app. */
    fun startAuth(customKey: String = ""): String {
        val appKey = customKey.ifBlank { BUILT_IN_KEY }
        require(appKey.isNotBlank()) { "No Dropbox app key" }
        val useRedirect = customKey.isBlank()
        val verifier = b64url(ByteArray(64).also { SecureRandom().nextBytes(it) })
        val state = b64url(ByteArray(16).also { SecureRandom().nextBytes(it) })
        store.edit()
            .putString("app_key", appKey.trim())
            .putString("verifier", verifier)
            .putString("state", state)
            .putBoolean("redirect", useRedirect)
            .apply()
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return Uri.parse("https://www.dropbox.com/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", appKey.trim())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("token_access_type", "offline")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .apply {
                if (useRedirect) {
                    appendQueryParameter("redirect_uri", REDIRECT_URI)
                    appendQueryParameter("state", state)
                }
            }
            .build().toString()
    }

    /** True if [uri] is our OAuth redirect. */
    fun isRedirect(uri: Uri?): Boolean = uri != null && uri.toString().startsWith(REDIRECT_URI)

    /** Completes auth from the redirect URI Dropbox sent the browser to. */
    suspend fun finishAuthFromRedirect(uri: Uri) {
        uri.getQueryParameter("error")?.let { err ->
            store.edit().remove("verifier").apply()
            throw IOException(if (err == "access_denied") "Dropbox access was denied" else "Dropbox: $err")
        }
        val expected = store.getString("state", null)
        if (expected == null || uri.getQueryParameter("state") != expected) {
            throw IOException("Dropbox sign-in expired, press Connect again")
        }
        val code = uri.getQueryParameter("code") ?: throw IOException("Dropbox returned no code")
        finishAuth(code)
    }

    suspend fun finishAuth(code: String) = withContext(Dispatchers.IO) {
        val verifier = store.getString("verifier", null) ?: throw IOException("Press Connect first")
        val obj = postToken(
            FormBody.Builder()
                .add("code", code.trim())
                .add("grant_type", "authorization_code")
                .add("code_verifier", verifier)
                .add("client_id", appKey)
                .apply { if (store.getBoolean("redirect", false)) add("redirect_uri", REDIRECT_URI) }
                .build()
        )
        val refresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("Dropbox returned no refresh token")
        secrets.put(SecretStore.DROPBOX_REFRESH, refresh)
        setAccess(obj)
        store.edit().remove("verifier").remove("state").apply()
        _connected.value = true
    }

    fun disconnect() {
        secrets.put(SecretStore.DROPBOX_REFRESH, null)
        accessToken = null
        store.edit().remove("verifier").remove("state").apply()
        _connected.value = false
    }

    /** Blocking. Returns null if the file does not exist. */
    fun download(path: String): String? {
        val req = Request.Builder()
            .url("https://content.dropboxapi.com/2/files/download")
            .header("Authorization", "Bearer ${token()}")
            .header("Dropbox-API-Arg", buildJsonObject { put("path", path) }.toString())
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 409 && body.contains("not_found")) return null
            if (!r.isSuccessful) throw IOException("Dropbox download $path failed (${r.code}): ${body.take(200)}")
            return body
        }
    }

    /** Blocking. Overwrites. */
    fun upload(path: String, content: String) {
        val arg = buildJsonObject {
            put("path", path)
            put("mode", "overwrite")
            put("mute", true)
        }
        val req = Request.Builder()
            .url("https://content.dropboxapi.com/2/files/upload")
            .header("Authorization", "Bearer ${token()}")
            .header("Dropbox-API-Arg", arg.toString())
            .post(content.toByteArray(Charsets.UTF_8).toRequestBody("application/octet-stream".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                throw IOException("Dropbox upload $path failed (${r.code}): ${r.body?.string().orEmpty().take(200)}")
            }
        }
    }

    /** Blocking. */
    fun delete(path: String) {
        val req = Request.Builder()
            .url("https://api.dropboxapi.com/2/files/delete_v2")
            .header("Authorization", "Bearer ${token()}")
            .post(buildJsonObject { put("path", path) }.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                throw IOException("Dropbox delete $path failed (${r.code}): ${r.body?.string().orEmpty().take(200)}")
            }
        }
    }

    /** Checks token refresh, read and write access. Returns a short human-readable summary. */
    suspend fun test(): String = withContext(Dispatchers.IO) {
        accessToken = null // force a refresh so the stored refresh token is exercised
        val meals = download(SyncManager.MEALS_PATH)
        val days = download(SyncManager.DAYS_PATH)
        val probe = "/.connection-test"
        upload(probe, "ok")
        delete(probe)
        fun describe(name: String, content: String?) =
            if (content == null) "$name: not yet created" else "$name: ${content.length} bytes"
        "Read/write OK. ${describe("meals.yaml", meals)}, ${describe("days.yaml", days)}"
    }

    @Synchronized
    private fun token(): String {
        accessToken?.takeIf { System.currentTimeMillis() < accessExpiresAt - 60_000 }?.let { return it }
        val refresh = secrets.get(SecretStore.DROPBOX_REFRESH) ?: throw IOException("Dropbox is not connected")
        val obj = postToken(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", appKey)
                .build()
        )
        setAccess(obj)
        return accessToken ?: throw IOException("Dropbox returned no access token")
    }

    private fun setAccess(obj: JsonObject) {
        accessToken = obj["access_token"]?.jsonPrimitive?.contentOrNull
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600
        accessExpiresAt = System.currentTimeMillis() + expiresIn * 1000
    }

    private fun postToken(form: FormBody): JsonObject {
        val req = Request.Builder().url("https://api.dropboxapi.com/oauth2/token").post(form).build()
        http.newCall(req).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("Dropbox auth failed (${r.code}): ${text.take(200)}")
            return json.parseToJsonElement(text).jsonObject
        }
    }

    companion object {
        val BUILT_IN_KEY: String = BuildConfig.DROPBOX_APP_KEY
        val hasBuiltInKey: Boolean get() = BUILT_IN_KEY.isNotBlank()
        /** Must be registered under OAuth 2 → Redirect URIs in the Dropbox App Console. */
        const val REDIRECT_URI = "dev.rubybox.diet://dropbox-auth"
    }

    private fun b64url(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
