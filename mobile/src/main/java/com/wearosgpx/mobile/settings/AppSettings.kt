package com.wearosgpx.mobile.settings

import android.content.Context
import com.wearosgpx.mobile.BuildConfig
import com.wearosgpx.mobile.route.RecommendedModels

/**
 * User-configurable settings (API keys) stored in SharedPreferences.
 *
 * - **OpenRouteService** key resolves to the user's own key if set, else the bundled
 *   BuildConfig default — so routing works out of the box.
 * - **AI** route generation uses the active model from the bundled menu (default Google
 *   Gemini — free, fast). Each model entry self-describes its provider + endpoint, so the
 *   endpoint, key and model all come from the selected entry. The user pastes one key; if
 *   the weekly review ever switches provider, [requiredKeysMissing] flags it and the app
 *   prompts for a new key rather than silently breaking.
 */
object AppSettings {

    private const val PREFS = "settings"
    private const val KEY_ORS = "ors_api_key"
    private const val KEY_AI = "ai_api_key"
    private const val KEY_AI_MODEL = "ai_model"
    private const val KEY_AI_KEY_PROVIDER = "ai_key_provider"  // provider the key was entered for

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

    // ---- AI route generation ----
    /** The active model entry: the user's pick if set, else the recommended (first). */
    private fun activeEntry(context: Context): RecommendedModels.Entry =
        RecommendedModels.activeEntry(context, storedAiModel(context))

    fun aiKey(context: Context): String = prefs(context).getString(KEY_AI, "").orEmpty()

    /** Store the key and remember which provider it was entered for (the active provider). */
    fun setAiKey(context: Context, key: String) {
        val k = key.trim()
        prefs(context).edit().apply {
            putString(KEY_AI, k)
            if (k.isBlank()) remove(KEY_AI_KEY_PROVIDER)
            else putString(KEY_AI_KEY_PROVIDER, activeProvider(context))
        }.apply()
    }

    fun hasAiKey(context: Context): Boolean = aiKey(context).isNotBlank()

    /** The provider the saved key was entered for ("" if no key). */
    fun aiKeyProvider(context: Context): String =
        prefs(context).getString(KEY_AI_KEY_PROVIDER, "").orEmpty()

    /** Provider/endpoint/model of the active entry. */
    fun activeProvider(context: Context): String = activeEntry(context).provider
    fun aiBaseUrl(context: Context): String = activeEntry(context).baseUrl
    fun aiModel(context: Context): String = activeEntry(context).model

    /** True if a key is set but for a DIFFERENT provider than the active one — i.e. the
     *  weekly review switched providers and the user needs a key for the new one. */
    fun aiKeyProviderMismatch(context: Context): Boolean =
        hasAiKey(context) && aiKeyProvider(context) != activeProvider(context)

    /** The user's explicit model override, or "" if they're on auto/recommended. */
    fun storedAiModel(context: Context): String =
        prefs(context).getString(KEY_AI_MODEL, "").orEmpty()

    fun setAiModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_AI_MODEL, model.trim()).apply()
    }

    /** The AI key is required for route generation: missing, or set for the wrong provider. */
    fun requiredKeysMissing(context: Context): Boolean =
        !hasAiKey(context) || aiKeyProviderMismatch(context)
}
