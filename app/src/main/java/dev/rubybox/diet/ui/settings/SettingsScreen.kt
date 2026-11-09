package dev.rubybox.diet.ui.settings

import dev.rubybox.diet.sync.DropboxClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.rubybox.diet.health.ActivitySource
import dev.rubybox.diet.ui.kcalStr
import java.time.LocalDate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.rubybox.diet.AppContainer
import dev.rubybox.diet.ai.ChatTurn
import dev.rubybox.diet.ai.ModelInfo
import dev.rubybox.diet.ai.usableForMeals
import dev.rubybox.diet.data.AiProvider
import dev.rubybox.diet.data.SyncStatus
import dev.rubybox.diet.ui.BackTopBar
import dev.rubybox.diet.ui.appViewModel
import dev.rubybox.diet.ui.kcalStr
import dev.rubybox.diet.ui.numStr
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

data class TestResult(val ok: Boolean, val text: String)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    // ---- AI ----
    var provider by mutableStateOf(AiProvider.OPENAI)
        private set
    var customUrl by mutableStateOf("")
        private set
    var model by mutableStateOf("")
        private set
    var keyInput by mutableStateOf("")
    var keySaved by mutableStateOf<String?>(null)
        private set
    var models by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    var modelsLoading by mutableStateOf(false)
        private set
    var modelsError by mutableStateOf<String?>(null)
        private set
    var aiTesting by mutableStateOf(false)
        private set
    var aiTestResult by mutableStateOf<TestResult?>(null)
        private set

    // ---- Dropbox ----
    var appKey by mutableStateOf(c.dropbox.appKey)
    var authCode by mutableStateOf("")
    var awaitingCode by mutableStateOf(c.dropbox.hasPendingAuth())
        private set
    var connected by mutableStateOf(c.dropbox.isConnected())
        private set
    var syncing by mutableStateOf(false)
        private set
    var dbxTesting by mutableStateOf(false)
        private set
    var dbxTestResult by mutableStateOf<TestResult?>(null)
        private set

    var message by mutableStateOf<String?>(null)

    val syncStatus = c.prefs.syncStatus.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncStatus())

    private val baseUrl: String get() = provider.baseUrl ?: customUrl.trim().trimEnd('/')

    init {
        viewModelScope.launch {
            c.dropbox.connected.collect {
                connected = it
                if (it) {
                    awaitingCode = false
                    authCode = ""
                }
            }
        }
        viewModelScope.launch {
            val cfg = c.prefs.aiConfig.first()
            provider = cfg.provider
            model = cfg.model
            customUrl = c.prefs.customUrl.first()
            keySaved = mask(c.secrets.get(provider.secretName))
            loadModels()
        }
    }

    fun selectProvider(p: AiProvider) {
        if (p == provider) return
        provider = p
        keyInput = ""
        keySaved = mask(c.secrets.get(p.secretName))
        models = emptyList()
        modelsError = null
        aiTestResult = null
        viewModelScope.launch {
            c.prefs.setProvider(p)
            model = c.prefs.aiConfig.first().model
            loadModels()
        }
    }

    fun onModel(v: String) {
        model = v
        val p = provider
        viewModelScope.launch { c.prefs.setModel(p, v.trim()) }
    }

    fun onCustomUrl(v: String) {
        customUrl = v
        viewModelScope.launch { c.prefs.setCustomUrl(v.trim().trimEnd('/')) }
    }

    fun saveKey() {
        c.secrets.put(provider.secretName, keyInput.trim().ifBlank { null })
        keySaved = mask(c.secrets.get(provider.secretName))
        keyInput = ""
        message = "${provider.label} key saved"
        loadModels()
    }

    fun clearKey() {
        c.secrets.put(provider.secretName, null)
        keySaved = null
    }

    fun loadModels() {
        val p = provider
        val url = baseUrl
        if (url.isBlank()) {
            modelsError = "Enter the base URL to load models"
            return
        }
        val key = c.secrets.get(p.secretName)
        if (key == null && p == AiProvider.OPENAI) {
            modelsError = "Save the API key to load models"
            return
        }
        modelsLoading = true
        modelsError = null
        viewModelScope.launch {
            val r = runCatching { c.openAi.listModels(key, url).usableForMeals() }
            if (provider != p) return@launch // switched meanwhile
            r.onSuccess { models = it }.onFailure { modelsError = "Could not load models: ${it.message}" }
            modelsLoading = false
        }
    }

    /**
     * Runs a real estimate ("1 medium apple") with the current settings (a typed-but-unsaved key
     * included), so it also checks that the model accepts JSON-schema output.
     */
    fun testAi() {
        if (aiTesting) return
        val key = keyInput.trim().ifBlank { null } ?: c.secrets.get(provider.secretName)
        val url = baseUrl
        val m = model.trim()
        aiTestResult = when {
            key.isNullOrBlank() -> TestResult(false, "No API key set")
            url.isBlank() -> TestResult(false, "No base URL set")
            m.isBlank() -> TestResult(false, "No model selected")
            else -> null
        }
        if (aiTestResult != null) return
        aiTesting = true
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            aiTestResult = runCatching {
                c.openAi.estimate(key!!, url, m, listOf(ChatTurn.User("1 medium apple", emptyList()))).first
            }.fold(
                onSuccess = {
                    val secs = (System.currentTimeMillis() - started) / 1000.0
                    TestResult(
                        true,
                        "OK in ${String.format(Locale.US, "%.1f", secs)} s: ${it.title}, ${it.weightG.numStr()} g, " +
                            "${it.kcalPer100.kcalStr()} kcal/100 g",
                    )
                },
                onFailure = { TestResult(false, it.message ?: it.javaClass.simpleName) },
            )
            aiTesting = false
        }
    }

    // ---- Dropbox ----

    /** Returns the browser URL, or null if the app key is missing. */
    fun startDropbox(): String? {
        if (!DropboxClient.hasBuiltInKey && appKey.isBlank()) {
            message = "Enter the Dropbox app key first"
            return null
        }
        awaitingCode = !DropboxClient.hasBuiltInKey // built-in app returns via redirect, no code to paste
        return c.dropbox.startAuth(if (DropboxClient.hasBuiltInKey) "" else appKey)
    }

    fun finishDropbox() {
        viewModelScope.launch {
            runCatching { c.dropbox.finishAuth(authCode) }
                .onSuccess {
                    connected = true
                    awaitingCode = false
                    authCode = ""
                    syncNow()
                }
                .onFailure { message = it.message }
        }
    }

    fun disconnect() {
        c.dropbox.disconnect()
        connected = false
        dbxTestResult = null
    }

    fun syncNow() {
        if (syncing) return
        syncing = true
        viewModelScope.launch {
            val r = c.sync.sync()
            syncing = false
            message = if (r.isSuccess) "Synced" else "Sync failed: ${r.exceptionOrNull()?.message}"
        }
    }

    fun testDropbox() {
        if (dbxTesting) return
        dbxTesting = true
        dbxTestResult = null
        viewModelScope.launch {
            dbxTestResult = runCatching { c.dropbox.test() }.fold(
                onSuccess = { TestResult(true, it) },
                onFailure = { TestResult(false, it.message ?: it.javaClass.simpleName) },
            )
            dbxTesting = false
        }
    }

    // ---- Activity calories (Health Connect) ----
    var useActivity by mutableStateOf(false)
        private set
    var preferHealthSync by mutableStateOf(false)
        private set
    var hcStatus by mutableStateOf(c.activitySource.status())
        private set
    var hcGranted by mutableStateOf(false)
        private set
    /** Total and basal calories are granted too (used only by Test). */
    var hcAllGranted by mutableStateOf(false)
        private set
    private var testAfterPermission = false
    var hcTesting by mutableStateOf(false)
        private set
    var hcTestResult by mutableStateOf<TestResult?>(null)
        private set
    val hcPermissions get() = c.activitySource.allPermissions
    fun hcInstallIntent() = c.activitySource.installIntent()
    fun hcSettingsIntent() = c.activitySource.settingsIntent()

    init {
        viewModelScope.launch {
            useActivity = c.prefs.useActivity.first()
            preferHealthSync = c.prefs.preferHealthSync.first()
            refreshHealth()
        }
    }

    /** Re-checks availability and permission (e.g. after returning from Health Connect). */
    fun refreshHealth() {
        hcStatus = c.activitySource.status()
        viewModelScope.launch { checkPermissions() }
    }

    private suspend fun checkPermissions() {
        hcGranted = runCatching { c.activitySource.hasPermission() }.getOrDefault(false)
        hcAllGranted = runCatching { c.activitySource.hasAllPermissions() }.getOrDefault(false)
    }

    /** Returns true if the caller should launch the permission request. */
    fun onUseActivity(value: Boolean): Boolean {
        useActivity = value
        viewModelScope.launch {
            c.prefs.setUseActivity(value)
            if (value) c.activity.refresh()
        }
        return value && hcStatus == ActivitySource.Status.AVAILABLE && !hcGranted
    }

    fun onPreferHealthSync(value: Boolean) {
        preferHealthSync = value
        hcTestResult = null
        viewModelScope.launch {
            c.prefs.setPreferHealthSync(value)
            c.activity.refresh()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onPermissionResult(granted: Set<String>) {
        viewModelScope.launch {
            checkPermissions()
            if (!hcGranted) {
                testAfterPermission = false
                message = "Health Connect access not granted"
                return@launch
            }
            c.activity.refresh()
            if (testAfterPermission) {
                testAfterPermission = false
                testActivity()
            }
        }
    }

    /** Returns true if the caller should first request the extra permissions; the test then runs after the result. */
    fun onTestClick(): Boolean {
        if (!hcAllGranted) {
            testAfterPermission = true
            return true
        }
        testActivity()
        return false
    }

    private fun testActivity() {
        if (hcTesting) return
        hcTesting = true
        hcTestResult = null
        viewModelScope.launch {
            hcTestResult = runCatching {
                val today = LocalDate.now()
                val counted = c.activitySource.activeKcal(today, preferHealthSync)
                "Counted today: ${counted.kcalStr()} kcal active\n" + c.activitySource.diagnose(today)
            }.fold(
                onSuccess = { TestResult(true, it) },
                onFailure = { TestResult(false, it.message ?: it.javaClass.simpleName) },
            )
            if (useActivity) c.activity.refresh()
            hcTesting = false
        }
    }

    private fun mask(key: String?): String? =
        key?.let { if (it.length > 8) it.take(3) + "…" + it.takeLast(4) else "••••" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val status by vm.syncStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val hcPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { vm.onPermissionResult(it) }
    LifecycleResumeEffect(Unit) {
        vm.refreshHealth()
        onPauseOrDispose { }
    }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }

    Scaffold(
        topBar = { BackTopBar("Settings", onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("AI")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                AiProvider.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = vm.provider == p,
                        onClick = { vm.selectProvider(p) },
                        shape = SegmentedButtonDefaults.itemShape(i, AiProvider.entries.size),
                    ) { Text(p.label) }
                }
            }
            if (vm.provider == AiProvider.CUSTOM) {
                OutlinedTextField(
                    value = vm.customUrl,
                    onValueChange = vm::onCustomUrl,
                    label = { Text("Base URL") },
                    placeholder = { Text("https://host/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Hint(vm.provider.baseUrl.orEmpty())
            }

            Text(
                vm.keySaved?.let { "${vm.provider.label} key: $it" } ?: "No ${vm.provider.label} key set",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = vm.keyInput,
                onValueChange = { vm.keyInput = it },
                label = { Text("${vm.provider.label} API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::saveKey, enabled = vm.keyInput.isNotBlank()) { Text("Save key") }
                if (vm.keySaved != null) TextButton(onClick = vm::clearKey) { Text("Remove") }
            }

            ModelPicker(vm)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::testAi, enabled = !vm.aiTesting) { Text("Test AI") }
                if (vm.aiTesting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            vm.aiTestResult?.let { TestResultText(it) }
            Hint("Keys are stored encrypted on this device only, one per provider. They are never synced or backed up.")

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Section("Activity calories")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add activity calories to the daily goal", Modifier.weight(1f))
                Switch(
                    checked = vm.useActivity,
                    onCheckedChange = { if (vm.onUseActivity(it)) hcPermissionLauncher.launch(vm.hcPermissions) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = vm.preferHealthSync, onCheckedChange = vm::onPreferHealthSync)
                Text("Prefer Health Sync", Modifier.weight(1f))
            }
            Hint("When Health Sync has data for a day, count only its data and ignore the phone's own estimate.")
            when (vm.hcStatus) {
                ActivitySource.Status.UNAVAILABLE ->
                    Hint("Health Connect isn't available on this phone (it needs Android 9+ with Google Play).")
                ActivitySource.Status.NEEDS_INSTALL -> {
                    Hint("Health Connect needs to be installed or updated.")
                    OutlinedButton(onClick = { runCatching { context.startActivity(vm.hcInstallIntent()) } }) {
                        Text("Install Health Connect")
                    }
                }
                ActivitySource.Status.AVAILABLE -> {
                    if (!vm.hcGranted) {
                        Button(onClick = { hcPermissionLauncher.launch(vm.hcPermissions) }) { Text("Allow access") }
                    } else {
                        Text("Access granted", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (vm.onTestClick()) hcPermissionLauncher.launch(vm.hcPermissions) }, enabled = vm.hcGranted && !vm.hcTesting) { Text("Test") }
                        TextButton(onClick = { runCatching { context.startActivity(vm.hcSettingsIntent()) } }) {
                            Text("Open Health Connect")
                        }
                        if (vm.hcTesting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    vm.hcTestResult?.let { TestResultText(it) }
                }
            }
            Hint(
                "Reads active calories (not resting burn) from Health Connect when the app opens. " +
                    "Huawei Health: install Health Sync, connect Huawei Health as the source and Health Connect " +
                    "as the destination, and enable calories."
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Section("Dropbox sync")
            if (!vm.connected) {
                if (DropboxClient.hasBuiltInKey) {
                    Hint("Press Connect, sign in to Dropbox and allow access; you'll be brought back here. " +
                        "The app only sees its own folder (Apps/…).")
                } else {
                    Hint(
                        "1. Create an app at dropbox.com/developers/apps: Scoped access, App folder.\n" +
                            "2. Permissions tab: enable files.content.read and files.content.write, then Submit.\n" +
                            "3. Paste the App key below, press Connect, approve, and paste the code you get back."
                    )
                    OutlinedTextField(
                        value = vm.appKey,
                        onValueChange = { vm.appKey = it },
                        label = { Text("Dropbox app key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(onClick = { vm.startDropbox()?.let(uriHandler::openUri) }) { Text("Connect") }
                if (vm.awaitingCode) {
                    OutlinedTextField(
                        value = vm.authCode,
                        onValueChange = { vm.authCode = it },
                        label = { Text("Authorization code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = vm::finishDropbox, enabled = vm.authCode.isNotBlank()) { Text("Confirm code") }
                }
            } else {
                Text("Connected", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::syncNow, enabled = !vm.syncing) { Text("Sync now") }
                    OutlinedButton(onClick = vm::testDropbox, enabled = !vm.dbxTesting) { Text("Test connection") }
                    if (vm.syncing || vm.dbxTesting) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                vm.dbxTestResult?.let { TestResultText(it) }
                status.lastSuccessAt?.let {
                    Hint("Last sync: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)))
                }
                status.lastError?.let {
                    Text("Last error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Hint("Syncs meals.yaml and days.yaml in the app's Dropbox folder on start and after changes.")
                TextButton(onClick = vm::disconnect) { Text("Disconnect") }
            }
        }
    }
}

/** Editable dropdown: pick from the provider's model list (filtered as you type) or type any id. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(vm: SettingsViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val query = vm.model.trim()
    val exact = vm.models.any { it.id == query }
    val shown = remember(vm.models, query, exact) {
        val list = if (exact || query.isEmpty()) {
            vm.models
        } else {
            vm.models.filter { it.id.contains(query, true) || it.name?.contains(query, true) == true }
        }
        list.take(80)
    }
    val open = expanded && shown.isNotEmpty()

    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = vm.model,
            onValueChange = {
                vm.onModel(it)
                expanded = true
            },
            label = { Text("Model") },
            singleLine = true,
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (vm.modelsLoading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = vm::loadModels) { Icon(Icons.Default.Refresh, contentDescription = "Reload models") }
                    }
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = open)
                }
            },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { expanded = false }) {
            shown.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(m.id)
                            m.name?.takeIf { it != m.id }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    onClick = {
                        vm.onModel(m.id)
                        expanded = false
                    },
                )
            }
        }
    }
    when {
        vm.modelsLoading -> Hint("Loading models…")
        vm.modelsError != null -> Text(vm.modelsError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        vm.models.isNotEmpty() -> Hint(
            "${vm.models.size} models, newest first" +
                if (vm.provider == AiProvider.OPENROUTER) " (only ones with image input and JSON output)" else ""
        )
    }
}

@Composable
private fun TestResultText(r: TestResult) {
    Text(
        (if (r.ok) "✓ " else "✗ ") + r.text,
        style = MaterialTheme.typography.bodySmall,
        color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
