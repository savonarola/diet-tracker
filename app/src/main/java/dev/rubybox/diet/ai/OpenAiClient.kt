package dev.rubybox.diet.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable
data class MealEstimate(
    val reply: String,
    val title: String,
    @SerialName("weight_g") val weightG: Double,
    @SerialName("kcal_per_100g") val kcalPer100: Double,
    @SerialName("fat_per_100g") val fatPer100: Double,
    @SerialName("carbs_per_100g") val carbsPer100: Double,
    @SerialName("protein_per_100g") val proteinPer100: Double,
)

data class ModelInfo(
    val id: String,
    val name: String?,
    val created: Long,
    /** null = the endpoint does not say (OpenAI's /models has no capability info). */
    val vision: Boolean?,
    val structuredOutput: Boolean?,
)

private val NON_CHAT = Regex(
    "embedding|tts|whisper|dall-e|davinci|babbage|moderation|audio|realtime|transcribe|image|search|computer-use|sora",
    RegexOption.IGNORE_CASE,
)

/** Models that can plausibly do photo + JSON-schema meal estimates, newest first. */
fun List<ModelInfo>.usableForMeals(): List<ModelInfo> =
    filter {
        it.vision != false && it.structuredOutput != false &&
            !NON_CHAT.containsMatchIn(it.id) && !it.id.endsWith(":batch") // batch variants are async-only
    }
        .sortedWith(compareByDescending<ModelInfo> { it.created }.thenBy { it.id })

sealed interface ChatTurn {
    data class User(val text: String, val imageDataUrls: List<String>) : ChatTurn
    /** Raw JSON the model returned, replayed so it sees its previous estimate. */
    data class Assistant(val json: String) : ChatTurn
}

class OpenAiClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns the parsed estimate and the raw JSON content (for conversation history). */
    /** [baseUrl] is any OpenAI-compatible endpoint, e.g. https://openrouter.ai/api/v1 */
    suspend fun estimate(apiKey: String, baseUrl: String, model: String, turns: List<ChatTurn>): Pair<MealEstimate, String> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", model)
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    }
                    for (t in turns) when (t) {
                        is ChatTurn.User -> addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                if (t.text.isNotBlank()) addJsonObject {
                                    put("type", "text")
                                    put("text", t.text)
                                }
                                for (url in t.imageDataUrls) addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", url) }
                                }
                            }
                        }
                        is ChatTurn.Assistant -> addJsonObject {
                            put("role", "assistant")
                            put("content", t.json)
                        }
                    }
                }
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", "meal_estimate")
                        put("strict", true)
                        put("schema", SCHEMA)
                    }
                }
            }

            val request = Request.Builder()
                .url(baseUrl.trim().trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
                            ?.get("message")?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    throw IOException("HTTP ${resp.code}: " + (msg ?: text.take(200)))
                }
                val message = runCatching {
                    json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
                }.getOrElse { throw IOException("Unexpected response: ${text.take(200)}") }
                message["refusal"]?.jsonPrimitive?.contentOrNull?.let { throw IOException("Model refused: $it") }
                val content = message["content"]?.jsonPrimitive?.contentOrNull
                    ?: throw IOException("Empty response from model")
                json.decodeFromString(MealEstimate.serializer(), content) to content
            }
        }

    /** GET {baseUrl}/models. The key is optional (OpenRouter lists models without one). */
    suspend fun listModels(apiKey: String?, baseUrl: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trim().trimEnd('/') + "/models")
            .apply { if (!apiKey.isNullOrBlank()) header("Authorization", "Bearer $apiKey") }
            .get()
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching {
                    json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
                        ?.get("message")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                throw IOException("HTTP ${resp.code}: " + (msg ?: text.take(200)))
            }
            val data = runCatching { json.parseToJsonElement(text).jsonObject["data"]!!.jsonArray }
                .getOrElse { throw IOException("Unexpected response: ${text.take(200)}") }
            data.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val inputs = ((o["architecture"] as? JsonObject)?.get("input_modalities") as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                val params = (o["supported_parameters"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ModelInfo(
                    id = id,
                    name = o["name"]?.jsonPrimitive?.contentOrNull,
                    created = o["created"]?.jsonPrimitive?.longOrNull ?: 0L,
                    vision = inputs?.contains("image"),
                    structuredOutput = params?.let { "structured_outputs" in it || "response_format" in it },
                )
            }
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You are the nutrition estimator inside a personal calorie-tracking app.
            The user describes what they ate with text and/or photos, possibly over several messages.
            Each time, return your best current estimate for the WHOLE meal being described:
            - title: short meal name in the user's language (e.g. "Borscht with sour cream").
            - weight_g: total grams the user ate.
            - kcal_per_100g, fat_per_100g, carbs_per_100g, protein_per_100g: for the meal as a whole
              (weighted average if it is a plate of several components).
            Rules:
            - If a photo shows a nutrition label or packaging, use the label values.
            - If the user states a weight or a value, use it exactly.
            - When the user corrects something, update the affected values and keep the rest.
            - If a message says the user edited values, keep those values unless the user asks otherwise.
            - Use typical reference values (e.g. USDA) and realistic home portion sizes when unsure.
            - reply: 1-3 short sentences in the user's language: what you identified and key assumptions.
              Ask a question only if the answer would change calories substantially.
        """.trimIndent()

        private val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("reply") { put("type", "string") }
                putJsonObject("title") { put("type", "string") }
                putJsonObject("weight_g") { put("type", "number") }
                putJsonObject("kcal_per_100g") { put("type", "number") }
                putJsonObject("fat_per_100g") { put("type", "number") }
                putJsonObject("carbs_per_100g") { put("type", "number") }
                putJsonObject("protein_per_100g") { put("type", "number") }
            }
            putJsonArray("required") {
                listOf(
                    "reply", "title", "weight_g", "kcal_per_100g",
                    "fat_per_100g", "carbs_per_100g", "protein_per_100g",
                ).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            }
            put("additionalProperties", false)
        }
    }
}
