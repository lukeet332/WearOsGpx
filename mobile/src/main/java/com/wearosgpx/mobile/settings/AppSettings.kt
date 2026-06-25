package com.wearosgpx.mobile.settings

import android.content.Context
import com.wearosgpx.mobile.BuildConfig

/**
 * User-configurable settings (API keys + AI provider) stored in SharedPreferences.
 *
 * - **OpenRouteService** key resolves to the user's own key if set, else the bundled
 *   BuildConfig default — so routing works out of the box.
 * - **AI provider** (key + model + OpenAI-compatible base URL) powers AI route
 *   generation. There is NO bundled default key, so it must be supplied to use that
 *   feature; that's the one [requiredKeysMissing] flags on launch.
 */
object AppSettings {

    private const val PREFS = "settings"
    private const val KEY_ORS = "ors_api_key"
    private const val KEY_AI = "ai_api_key"
    private const val KEY_AI_MODEL = "ai_model"
    private const val KEY_AI_BASE = "ai_base_url"

    // Defaults for the AI provider — OpenAI-compatible. Users can point these at any
    // OpenAI-compatible endpoint (OpenAI, Gemini, OpenRouter, Groq, Mistral, …).
    const val DEFAULT_AI_MODEL = "gpt-4o-mini"
    const val DEFAULT_AI_BASE = "https://api.openai.com/v1"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- OpenRouteService (routing) ----
    fun storedOrsKey(context: Context): String =
        prefs(context).getString(KEY_ORS, "").orEmpty()

    fun setOrsKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_ORS, key.trim()).apply()
    }

    /** Key actually used for routing: the user's if set, else the bundled default. */
    fun effectiveOrsKey(context: Context): String =
        storedOrsKey(context).ifBlank { BuildConfig.ORS_API_KEY }

    // ---- AI provider (AI route generation) ----
    fun aiKey(context: Context): String = prefs(context).getString(KEY_AI, "").orEmpty()

    fun setAiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_AI, key.trim()).apply()
    }

    fun aiModel(context: Context): String =
        prefs(context).getString(KEY_AI_MODEL, "").orEmpty().ifBlank { DEFAULT_AI_MODEL }

    fun setAiModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_AI_MODEL, model.trim()).apply()
    }

    fun aiBaseUrl(context: Context): String =
        prefs(context).getString(KEY_AI_BASE, "").orEmpty().ifBlank { DEFAULT_AI_BASE }

    fun setAiBaseUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_AI_BASE, url.trim()).apply()
    }

    fun hasAiKey(context: Context): Boolean = aiKey(context).isNotBlank()

    /**
     * Keys the app needs that aren't configured (and have no working default). Used to
     * decide whether to send the user to Settings on launch. The ORS key has a bundled
     * default, so only the AI key can be "missing" here.
     */
    fun requiredKeysMissing(context: Context): Boolean = !hasAiKey(context)
}
