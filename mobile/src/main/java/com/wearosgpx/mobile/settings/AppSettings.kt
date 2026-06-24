package com.wearosgpx.mobile.settings

import android.content.Context
import com.wearosgpx.mobile.BuildConfig

/**
 * User-configurable settings. The OpenRouteService key resolves to the user's own
 * key if they've entered one, otherwise the built-in default (from BuildConfig).
 */
object AppSettings {

    private const val PREFS = "settings"
    private const val KEY_ORS = "ors_api_key"

    /** The user-entered key, or "" if they haven't set one. */
    fun storedOrsKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ORS, "").orEmpty()

    fun setOrsKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ORS, key.trim()).apply()
    }

    /** Key actually used for routing: the user's if set, else the bundled default. */
    fun effectiveOrsKey(context: Context): String =
        storedOrsKey(context).ifBlank { BuildConfig.ORS_API_KEY }
}
