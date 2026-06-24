package com.wearosgpx.mobile.route

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.wearosgpx.mobile.settings.AppSettings
import com.wearosgpx.mobile.sync.WatchRoutes
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Garmin-style route builder. Two steps: (1) name the route, then (2) the map —
 * search a start (type-ahead suggestions), tap to plot, live distance, Save
 * (fetches elevation, keeps a local copy, pushes to the watch).
 */
class RouteCreatorActivity : ComponentActivity() {

    private lateinit var map: MapView
    private lateinit var polyline: Polyline
    private lateinit var distanceText: TextView
    private lateinit var suggestionsView: LinearLayout
    private lateinit var progressBar: ProgressBar
    private val points = mutableListOf<GeoPoint>()   // user taps (control markers)
    private var routed: List<GeoPoint> = emptyList()  // road-snapped geometry drawn + saved
    private var routeName = "My Route"
    private var searchJob: Job? = null
    private var routingJob: Job? = null
    private var warnedNoKey = false

    private val neon = Color.parseColor("#FF39FF14")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
        }
        setContentView(nameStep())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // --- Step 1: name -------------------------------------------------------

    private fun nameStep(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(28), dp(28), dp(28), dp(28))
            fitsSystemWindows = true
        }
        root.addView(TextView(this).apply {
            text = "Create route"
            setTextColor(neon)
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Name your route, then plot it on the map."
            setTextColor(Color.GRAY)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(24))
        })
        val field = EditText(this).apply {
            hint = "Route name"
            setText("My Route")
            setSelectAllOnFocus(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 17f
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#1F1F1F"))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        root.addView(field)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
            addView(pill("Cancel", filled = false) { finish() })
            addView(pill("Continue", filled = true) {
                routeName = field.text?.toString()?.trim().orEmpty().ifEmpty { "My Route" }
                showMapStep()
            })
        })
        return root
    }

    // --- Step 2: map --------------------------------------------------------

    private fun showMapStep() {
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)
            controller.setZoom(14.0)
            controller.setCenter(GeoPoint(51.5074, -0.1278))
        }
        polyline = Polyline(map).apply {
            outlinePaint.color = neon
            outlinePaint.strokeWidth = 10f
        }
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { addPoint(p); return true }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        map.overlays.add(polyline)

        setContentView(mapStep())
        map.onResume()
        updateDistance()
    }

    private fun mapStep(): FrameLayout {
        val root = FrameLayout(this)
        root.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))

        // Top bar: [ title  ·  X ] then [ search ] then [ suggestions ].
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6000000"))
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = routeName
            setTextColor(Color.WHITE)
            textSize = 18f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply {
            text = "✕"
            isAllCaps = false
            textSize = 16f
            setTextColor(neon)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1F1F1F"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { finish() }
        })
        topBar.addView(header)

        val searchField = EditText(this).apply {
            hint = "Search start location"
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 16f
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#1F1F1F"))
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEARCH) { runSearch(text.toString()); true } else false
            }
            addTextChangedListener(afterTextChanged = { scheduleSuggestions(it?.toString().orEmpty()) })
        }
        topBar.addView(searchField)

        suggestionsView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#101010"))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) }
        }
        topBar.addView(suggestionsView)

        root.addView(
            topBar,
            FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.TOP },
        )

        // Bottom bar: distance + Undo / Clear / Save.
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E6000000"))
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }
        distanceText = TextView(this).apply {
            setTextColor(neon)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        }
        bottomBar.addView(distanceText)
        bottomBar.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pill("Undo", filled = false) { undo() })
            addView(pill("Loop", filled = false) { closeLoop() })
            addView(pill("Clear", filled = false) { clearPoints() })
            addView(pill("Save", filled = true) { save() })
        })
        root.addView(
            bottomBar,
            FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.BOTTOM },
        )

        // Centered spinner shown while OpenRouteService snaps the route to roads.
        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            indeterminateTintList = ColorStateList.valueOf(neon)
        }
        root.addView(
            progressBar,
            FrameLayout.LayoutParams(dp(48), dp(48)).apply { gravity = Gravity.CENTER },
        )

        val baseTop = topBar.paddingTop
        val baseBottom = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.setPadding(topBar.paddingLeft, baseTop + bars.top, topBar.paddingRight, topBar.paddingBottom)
            bottomBar.setPadding(
                bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, baseBottom + bars.bottom,
            )
            insets
        }
        return root
    }

    private val MATCH get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP get() = ViewGroup.LayoutParams.WRAP_CONTENT

    /** Rounded pill button matching the app: neon-filled for primary, dark for secondary. */
    private fun pill(label: String, filled: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (filled) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (filled) neon else Color.parseColor("#2A2A2A"))
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        }

    // --- Search / suggestions ----------------------------------------------

    private fun scheduleSuggestions(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.length < 3) { suggestionsView.visibility = View.GONE; return }
        searchJob = lifecycleScope.launch {
            delay(350)  // debounce
            val results = GeocodingService.searchMany(q, 5)
            showSuggestions(results)
        }
    }

    private fun showSuggestions(places: List<GeocodingService.Place>) {
        suggestionsView.removeAllViews()
        if (places.isEmpty()) { suggestionsView.visibility = View.GONE; return }
        places.forEach { place ->
            suggestionsView.addView(TextView(this).apply {
                text = place.label
                setTextColor(Color.WHITE)
                textSize = 14f
                maxLines = 2
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                setOnClickListener {
                    map.controller.animateTo(GeoPoint(place.lat, place.lon))
                    map.controller.setZoom(15.0)
                    suggestionsView.visibility = View.GONE
                }
            })
        }
        suggestionsView.visibility = View.VISIBLE
    }

    private fun runSearch(query: String) {
        if (query.isBlank()) return
        lifecycleScope.launch {
            val place = GeocodingService.search(query)
            if (place != null) {
                map.controller.animateTo(GeoPoint(place.lat, place.lon))
                map.controller.setZoom(15.0)
                suggestionsView.visibility = View.GONE
            } else {
                toast("Location not found")
            }
        }
    }

    // --- Plotting -----------------------------------------------------------

    private fun addPoint(p: GeoPoint) {
        // Tapping back near the start snaps to it, closing the loop.
        if (points.size >= 2 && isNearStart(p)) {
            points.add(points.first().let { GeoPoint(it.latitude, it.longitude) })
        } else {
            points.add(p)
        }
        onWaypointsChanged()
    }

    private fun isNearStart(p: GeoPoint): Boolean {
        val start = map.projection.toPixels(points.first(), null)
        val tap = map.projection.toPixels(p, null)
        val dx = (start.x - tap.x).toDouble()
        val dy = (start.y - tap.y).toDouble()
        val r = dp(22).toDouble()
        return dx * dx + dy * dy < r * r
    }

    /** Connects the last point back to the start to form a full loop. */
    private fun closeLoop() {
        if (points.size < 2) { toast("Plot at least 2 points first"); return }
        val first = points.first()
        val last = points.last()
        if (first.latitude != last.latitude || first.longitude != last.longitude) {
            points.add(GeoPoint(first.latitude, first.longitude))
            onWaypointsChanged()
        }
    }

    private fun undo() { if (points.isNotEmpty()) points.removeAt(points.lastIndex); onWaypointsChanged() }
    private fun clearPoints() { points.clear(); onWaypointsChanged() }

    /**
     * Waypoints changed: show straight lines immediately for feedback, then ask
     * OpenRouteService (debounced) to snap them to roads and redraw with that.
     */
    private fun onWaypointsChanged() {
        redraw()           // markers update instantly; the line waits for routing
        scheduleRouting()
    }

    private fun scheduleRouting() {
        routingJob?.cancel()
        val key = AppSettings.effectiveOrsKey(this)
        // Can't route → straight lines (no spinner).
        if (points.size < 2 || key.isBlank()) {
            routed = points.map { GeoPoint(it.latitude, it.longitude) }
            setLoading(false)
            redraw()
            if (key.isBlank() && points.size >= 2 && !warnedNoKey) {
                warnedNoKey = true
                toast("Add an OpenRouteService key in Settings for road routing — using straight lines.")
            }
            return
        }
        val snapshot = points.map { it.latitude to it.longitude }
        setLoading(true)
        routingJob = lifecycleScope.launch {
            delay(450)  // debounce rapid taps
            val result = RoutingService.route(snapshot, key)
            routed = (result?.takeIf { it.size >= 2 })?.map { GeoPoint(it.first, it.second) }
                ?: snapshot.map { GeoPoint(it.first, it.second) }  // fallback if routing failed
            setLoading(false)
            redraw()
        }
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) distanceText.text = "Routing…"
    }

    private fun redraw() {
        polyline.setPoints(routed)
        map.overlays.removeAll { it is Marker }
        points.forEachIndexed { i, p ->
            map.overlays.add(Marker(map).apply {
                position = p
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = "Point ${i + 1}"
            })
        }
        map.invalidate()
        updateDistance()
    }

    private fun updateDistance() {
        var meters = 0.0
        for (i in 1 until routed.size) meters += routed[i - 1].distanceToAsDouble(routed[i])
        distanceText.text = if (meters < 1000) "%.0f m  ·  %d pts".format(meters, points.size)
        else "%.2f km  ·  %d pts".format(meters / 1000, points.size)
    }

    // --- Save ---------------------------------------------------------------

    private fun save() {
        if (points.size < 2) { toast("Tap the map to plot at least 2 points"); return }
        val fileName = PhoneRouteStore.safeName(routeName)
        distanceText.text = "Fetching elevation…"
        // Save the road-snapped geometry (or the straight waypoints if no routing).
        val geometry = routed.ifEmpty { points }.map { it.latitude to it.longitude }
        lifecycleScope.launch {
            val coords = geometry
            val elevations = ElevationService.lookup(coords)
            val bytes = GpxBuilder.build(routeName, coords, elevations).toByteArray(Charsets.UTF_8)
            PhoneRouteStore.save(this@RouteCreatorActivity, fileName, bytes)
            val ok = runCatching { WatchRoutes.sendRoute(applicationContext, fileName, bytes) }.isSuccess
            toast(if (ok) "Saved & sent “$routeName” to watch" else "Saved — it'll sync when the watch connects")
            finish()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onResume() { super.onResume(); if (::map.isInitialized) map.onResume() }
    override fun onPause() { super.onPause(); if (::map.isInitialized) map.onPause() }
}
