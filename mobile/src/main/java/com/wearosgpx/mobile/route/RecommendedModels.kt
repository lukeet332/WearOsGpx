package com.wearosgpx.mobile.route

import android.content.Context
import com.wearosgpx.mobile.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Curated, ordered model options per provider for the AI route generator. Loaded from the
 * bundled `res/raw/recommended_models.json` (which a weekly CI job keeps fresh), with a
 * built-in fallback so it always works. The FIRST model for a provider is the recommended
 * default. Parsing is pure + unit-tested.
 */
object RecommendedModels {

    data class Option(val model: String, val label: String)

    private val json = Json { ignoreUnknownKeys = true }

    private val FALLBACK: Map<String, List<Option>> = mapOf(
        "gemini" to listOf(Option("gemini-2.5-flash", "Gemini 2.5 Flash · recommended")),
        "openrouter" to listOf(Option("openai/gpt-4o-mini", "GPT-4o mini")),
        "groq" to listOf(Option("llama-3.3-70b-versatile", "Llama 3.3 70B")),
        "openai" to listOf(Option("gpt-4o-mini", "GPT-4o mini")),
    )

    @Volatile
    private var cache: Map<String, List<Option>>? = null

    fun all(context: Context): Map<String, List<Option>> {
        cache?.let { return it }
        val loaded = runCatching {
            val text = context.resources.openRawResource(R.raw.recommended_models)
                .bufferedReader().use { it.readText() }
            parse(text).ifEmpty { FALLBACK }
        }.getOrDefault(FALLBACK)
        cache = loaded
        return loaded
    }

    fun forProvider(context: Context, providerKey: String): List<Option> =
        all(context)[providerKey] ?: emptyList()

    /** The recommended (first) model id for a provider, or null if none configured. */
    fun recommended(context: Context, providerKey: String): String? =
        forProvider(context, providerKey).firstOrNull()?.model

    // ---- pure helpers (unit-tested) ----

    fun parse(jsonStr: String): Map<String, List<Option>> = runCatching {
        json.parseToJsonElement(jsonStr).jsonObject.mapValues { (_, v) ->
            (v as? JsonArray)?.mapNotNull { el ->
                val o = el.jsonObject
                val model = o["model"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val label = o["label"]?.jsonPrimitive?.contentOrNull ?: model
                Option(model, label)
            } ?: emptyList()
        }
    }.getOrDefault(emptyMap())

    fun firstModel(map: Map<String, List<Option>>, providerKey: String): String? =
        map[providerKey]?.firstOrNull()?.model
}
