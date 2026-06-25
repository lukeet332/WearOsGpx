package com.wearosgpx.settings

import android.content.Context

/**
 * How the screen behaves during a run. The right choice is watch-dependent, so it's
 * user-selectable in the watch Settings screen.
 *
 * Background: the GPS/HR sensing always runs on the low-power co-processor (BES2700)
 * regardless of mode — this only controls the *display*, i.e. whether the W5
 * application processor stays awake to keep the screen up.
 */
enum class RunDisplayMode(val title: String, val blurb: String) {
    /** Screen held on the whole run, self-dimming after idle. Most readable; W5 stays awake. */
    ALWAYS_ON("Always on", "Screen stays on, dims when idle. Most readable — uses the most battery."),

    /** System always-on ambient: dim screen, W5 can sleep — on watches that allow third-party
     *  ambient. (On the OnePlus Watch 2R the system reclaims it to the watch face.) */
    TRUE_AMBIENT("True ambient", "Dim always-on display that lets the processor sleep. Best on watches that support it; some (e.g. OnePlus 2R) fall back to the watch face."),

    /** Screen sleeps during the run so the W5 sleeps too — most efficient. A watch-face chip
     *  (ongoing activity) taps back to live stats. */
    POWER_SAVER("Power saver", "Screen sleeps to save the most battery (processor sleeps too). Tap the run chip on the watch face to see stats.");
}

/** Watch-side user preferences (SharedPreferences). */
object WatchSettings {

    private const val PREFS = "watch_settings"
    private const val KEY_MODE = "run_display_mode"

    fun runDisplayMode(context: Context): RunDisplayMode =
        runCatching {
            RunDisplayMode.valueOf(
                prefs(context).getString(KEY_MODE, RunDisplayMode.ALWAYS_ON.name)!!,
            )
        }.getOrDefault(RunDisplayMode.ALWAYS_ON)

    fun setRunDisplayMode(context: Context, mode: RunDisplayMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
