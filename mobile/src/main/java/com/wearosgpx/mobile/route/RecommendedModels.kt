package com.wearosgpx.mobile.route

import android.content.Context
import com.wearosgpx.mobile.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The AI route generator's model menu. Each entry is SELF-DESCRIBING — it carries its own
 * provider, OpenAI-compatible base URL, and where to get a key — so the list can scale to a
 * mix of providers/models without a separate top-level provider block. The first entry is
 * the recommended default; the selected entry decides which endpoint + key the app uses.
 *
 * Loaded from the bundled `res/raw/recommended_models.json` (kept fresh by the weekly
 * app-model-review CI job), with a built-in fallback so it always works. The plumbing stays
 * OpenAI-compatible, so switching provider is just data here, not a code change. Parsing is
 * pure + unit-tested. (Note: that JSON is a bundled data file — never shown to the user.)
 */
object RecommendedModels {

    data class Entry(
        val provider: String,
        val baseUrl: String,
        val keyUrl: String,
        val model: String,
        val label: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    val FALLBACK = Entry(
        provider = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        keyUrl = "https://aistudio.google.com/app/apikey",
        model = "gemini-2.5-flash",
        label = "Gemini 2.5 Flash · recommended",
    )

    @Volatile
    private var cache: List<Entry>? = null

    fun entries(context: Context): List<Entry> {
        cache?.let { return it }
        val loaded = runCatching {
            val text = context.resources.openRawResource(R.raw.recommended_models)
                .bufferedReader().use { it.readText() }
            parse(text)
        }.getOrNull().orEmpty().ifEmpty { listOf(FALLBACK) }
        cache = loaded
        return loaded
    }

    /** The entry for [selectedModel] (the user's pick), else the first (recommended), else fallback. */
    fun activeEntry(context: Context, selectedModel: String): Entry {
        val es = entries(context)
        return es.firstOrNull { it.model == selectedModel } ?: es.firstOrNull() ?: FALLBACK
    }

    // ---- pure parsing (unit-tested); skips entries missing base_url/model ----
    fun parse(jsonStr: String): List<Entry> = runCatching {
        val arr = json.parseToJsonElement(jsonStr).jsonObject["models"] as? JsonArray ?: return emptyList()
        arr.mapNotNull { el ->
            val o = el.jsonObject
            val base = o["base_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val model = o["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(
                provider = o["provider"]?.jsonPrimitive?.contentOrNull ?: "AI provider",
                baseUrl = base,
                keyUrl = o["key_url"]?.jsonPrimitive?.contentOrNull ?: FALLBACK.keyUrl,
                model = model,
                label = o["label"]?.jsonPrimitive?.contentOrNull ?: model,
            )
        }
    }.getOrDefault(emptyList())
}
