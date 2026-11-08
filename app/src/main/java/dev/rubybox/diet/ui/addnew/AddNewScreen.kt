package dev.rubybox.diet.ui.addnew

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rubybox.diet.AppContainer
import dev.rubybox.diet.ai.ChatTurn
import dev.rubybox.diet.ai.Images
import dev.rubybox.diet.ai.MealEstimate
import dev.rubybox.diet.ai.PreparedImage
import dev.rubybox.diet.data.Per100
import dev.rubybox.diet.ui.BackTopBar
import dev.rubybox.diet.ui.appViewModel
import dev.rubybox.diet.ui.gramsStr
import dev.rubybox.diet.ui.kcalStr
import dev.rubybox.diet.ui.numStr
import dev.rubybox.diet.ui.toNum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

data class UiMessage(val fromUser: Boolean, val text: String, val images: List<ImageBitmap> = emptyList())

/** The editable estimate pane. Total kcal is always derived: weight × kcal/100 g. */
data class Form(
    val title: String,
    val weight: String,
    val kcal100: String,
    val fat100: Double,
    val carbs100: Double,
    val protein100: Double,
    val edited: Boolean = false,
) {
    val totalKcal: Double?
        get() {
            val w = weight.toNum() ?: return null
            val k = kcal100.toNum() ?: return null
            return w * k / 100
        }
    val valid: Boolean get() = title.isNotBlank() && (weight.toNum() ?: 0.0) > 0 && (kcal100.toNum() ?: -1.0) >= 0

    companion object {
        fun from(e: MealEstimate) = Form(
            title = e.title,
            weight = e.weightG.numStr(),
            kcal100 = e.kcalPer100.numStr(),
            fat100 = e.fatPer100,
            carbs100 = e.carbsPer100,
            protein100 = e.proteinPer100,
        )
    }
}

class AddNewViewModel(private val c: AppContainer) : ViewModel() {
    val messages = mutableStateListOf<UiMessage>()
    val pending = mutableStateListOf<PreparedImage>()
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var form by mutableStateOf<Form?>(null)
        private set
    private val turns = mutableListOf<ChatTurn>()
    private var adding = false

    fun attach(uri: Uri) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { Images.prepare(c.appContext, uri) } }
                .onSuccess { pending += it }
                .onFailure { error = "Could not load image: ${it.message}" }
        }
    }

    fun removePending(img: PreparedImage) {
        pending.remove(img)
    }

    fun editTitle(v: String) {
        form = form?.copy(title = v, edited = true)
    }

    fun editWeight(v: String) {
        form = form?.copy(weight = v, edited = true)
    }

    fun editKcal100(v: String) {
        form = form?.copy(kcal100 = v, edited = true)
    }

    fun send() {
        if (busy) return
        val text = input.trim()
        val images = pending.toList()
        if (text.isEmpty() && images.isEmpty()) return
        busy = true
        error = null

        viewModelScope.launch {
            val cfg = c.prefs.aiConfig.first()
            val key = c.secrets.get(cfg.provider.secretName)
            if (key.isNullOrBlank() || cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
                error = "Set up the AI provider, key and model in Settings first"
                busy = false
                return@launch
            }
            val f = form
            val note = if (f != null && f.edited) {
                "[I edited the values: title=\"${f.title}\", weight=${f.weight} g, kcal per 100 g=${f.kcal100}. " +
                    "Keep them unless I say otherwise.]\n"
            } else {
                ""
            }
            val turn = ChatTurn.User(note + text, images.map { it.dataUrl })
            val ui = UiMessage(true, text, images.map { it.thumb })
            turns += turn
            messages += ui
            input = ""
            pending.clear()

            runCatching { c.openAi.estimate(key, cfg.baseUrl, cfg.model, turns.toList()) }
                .onSuccess { (estimate, raw) ->
                    turns += ChatTurn.Assistant(raw)
                    messages += UiMessage(false, estimate.reply)
                    form = Form.from(estimate)
                }
                .onFailure {
                    // Roll back so the user can retry with the same input.
                    turns.remove(turn)
                    messages.remove(ui)
                    input = text
                    pending.addAll(images)
                    error = it.message ?: "Request failed"
                }
            busy = false
        }
    }

    fun add(save: Boolean, onDone: () -> Unit) {
        val f = form ?: return
        val w = f.weight.toNum() ?: return
        val k = f.kcal100.toNum() ?: return
        if (!f.valid || adding) return
        adding = true
        val per100 = Per100(k, f.fat100, f.carbs100, f.protein100)
        viewModelScope.launch {
            c.repo.logMeal(LocalDate.now(), f.title, w, per100)
            if (save) c.repo.saveMeal(f.title, w, per100)
            onDone()
        }
    }
}

@Composable
fun AddNewScreen(onBack: () -> Unit) {
    val vm = appViewModel { AddNewViewModel(it) }
    val ctx = LocalContext.current
    val listState = rememberLazyListState()

    var photoUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let(vm::attach)
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        uris.forEach(vm::attach)
    }
    val onCamera: () -> Unit = {
        val dir = File(ctx.cacheDir, "photos").apply { mkdirs() }
        val file = File(dir, "meal_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        photoUri = uri
        try {
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            vm.error = "No camera app found"
        }
        Unit
    }

    LaunchedEffect(vm.messages.size, vm.busy) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = { BackTopBar("New meal", onBack) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            vm.form?.let { f ->
                EstimatePane(
                    f = f,
                    onTitle = vm::editTitle,
                    onWeight = vm::editWeight,
                    onKcal100 = vm::editKcal100,
                    onAdd = { save -> vm.add(save, onBack) },
                )
            }
            vm.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (vm.messages.isEmpty()) {
                    item {
                        Text(
                            "Describe what you ate or attach a photo, e.g. “bowl of borscht with sour cream, ~400 g”. " +
                                "Refine with follow-up messages.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                itemsIndexed(vm.messages) { _, m -> Bubble(m) }
                if (vm.busy) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("Estimating…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Composer(
                vm = vm,
                onCamera = onCamera,
                onGallery = {
                    pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
        }
    }
}

@Composable
private fun EstimatePane(
    f: Form,
    onTitle: (String) -> Unit,
    onWeight: (String) -> Unit,
    onKcal100: (String) -> Unit,
    onAdd: (save: Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = f.title,
                onValueChange = onTitle,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = f.weight,
                    onValueChange = onWeight,
                    label = { Text("Weight, g") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = f.kcal100,
                    onValueChange = onKcal100,
                    label = { Text("kcal / 100 g") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${f.totalKcal?.kcalStr() ?: "—"} kcal",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "per 100 g: F ${f.fat100.gramsStr()} · P ${f.protein100.gramsStr()} · C ${f.carbs100.gramsStr()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onAdd(false) }, enabled = f.valid, modifier = Modifier.weight(1f)) {
                    Text("Add")
                }
                Button(onClick = { onAdd(true) }, enabled = f.valid, modifier = Modifier.weight(1f)) {
                    Text("Add & save")
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: UiMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (m.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.images.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        m.images.forEach {
                            Image(
                                it,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                            )
                        }
                    }
                }
                if (m.text.isNotBlank()) Text(m.text)
            }
        }
    }
}

@Composable
private fun Composer(vm: AddNewViewModel, onCamera: () -> Unit, onGallery: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            if (vm.pending.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    vm.pending.forEach { img ->
                        Box {
                            Image(
                                img.thumb,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            IconButton(
                                onClick = { vm.removePending(img) },
                                modifier = Modifier.size(24.dp).align(Alignment.TopEnd),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCamera) { Icon(Icons.Default.PhotoCamera, contentDescription = "Take photo") }
                IconButton(onClick = onGallery) { Icon(Icons.Default.Image, contentDescription = "Pick photo") }
                OutlinedTextField(
                    value = vm.input,
                    onValueChange = { vm.input = it },
                    placeholder = { Text("Describe the meal…") },
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = vm::send, enabled = !vm.busy) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}
