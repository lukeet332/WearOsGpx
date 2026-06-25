package com.wearosgpx.mobile.route

import android.content.Context
import com.wearosgpx.mobile.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The AI route generator's provider config: which OpenAI-compatible endpoint to use, where
 * to get a key, and the curated model menu (first = recommended default). Loaded from the
 * bundled `res/raw/recommended_models.json` (kept fresh by the weekly app-model-review CI
 * job), with a built-in fallback so it always works. Parsing is pure + unit-tested.
 *
 * We stick to ONE provider so the user's single key never breaks; the weekly refresh only
 * changes the models, never the base_url. The plumbing stays OpenAI-compatible, so changing
 * provider in future is a config edit, not a code change.
 */
object RecommendedModels {

    data class Option(val model: String, val label: String)
    data class Config(
        val provider: String,
        val baseUrl: String,
        val keyUrl: String,
        val models: List<Option>,
    )

    private val json = Json { ignoreUnknownKeys = true }

    val FALLBACK = Config(
        provider = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        keyUrl = "https://aistudio.google.com/app/apikey",
        models = listOf(Option("gemini-2.5-flash", "Gemini 2.5 Flash · recommended")),
    )

    @Volatile
    private var cache: Config? = null

    fun config(context: Context): Config {
        cache?.let { return it }
        val loaded = runCatching {
            val text = context.resources.openRawResource(R.raw.recommended_models)
                .bufferedReader().use { it.readText() }
            parse(text)
        }.getOrNull() ?: FALLBACK
        cache = loaded
        return loaded
    }

    /** The recommended (first) model id, or the fallback default. */
    fun recommended(context: Context): String =
        config(context).models.firstOrNull()?.model ?: FALLBACK.models.first().model

    // ---- pure parsing (unit-tested); returns null if invalid/empty ----
    fun parse(jsonStr: String): Config? = runCatching {
        val o = json.parseToJsonElement(jsonStr).jsonObject
        val base = o["base_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val models = (o["models"] as? JsonArray)?.mapNotNull { el ->
            val m = el.jsonObject
            val model = m["model"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Option(model, m["label"]?.jsonPrimitive?.contentOrNull ?: model)
        }.orEmpty()
        if (models.isEmpty()) return null
        Config(
            provider = o["provider"]?.jsonPrimitive?.contentOrNull ?: "AI provider",
            baseUrl = base,
            keyUrl = o["key_url"]?.jsonPrimitive?.contentOrNull ?: FALLBACK.keyUrl,
            models = models,
        )
    }.getOrNull()
}
