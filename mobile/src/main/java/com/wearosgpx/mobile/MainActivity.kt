package com.wearosgpx.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.wearosgpx.mobile.health.HealthConnectWriter
import com.wearosgpx.mobile.route.BaseMapService
import com.wearosgpx.mobile.route.GeocodingService
import com.wearosgpx.mobile.route.GpxMeta
import com.wearosgpx.mobile.route.PhoneRouteStore
import com.wearosgpx.mobile.route.RouteCreatorActivity
import com.wearosgpx.mobile.route.RouteDiscoveryService
import com.wearosgpx.mobile.settings.AppSettings
import com.wearosgpx.mobile.strava.StravaClient
import com.wearosgpx.mobile.sync.RunImporter
import com.wearosgpx.mobile.sync.WatchRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withContext

private val Neon = Color(0xFF39FF14)

/** A route shown in the list, with where it currently lives. */
data class RouteRow(
    val fileName: String?,   // null = bundled watch sample (not deletable / no phone copy)
    val name: String,
    val distanceMeters: Double,
    val ascentMeters: Double,
    val pointCount: Int,
    val onPhone: Boolean,
    val onWatch: Boolean,
)

/**
 * Phone companion (dark/neon to match the watch). Grants Health Connect, creates/
 * imports routes, and lists routes — merging the phone's local library with what's
 * on the watch so a saved route shows immediately ("syncing…") even if the watch
 * isn't reachable yet; pending routes are auto-pushed whenever the app opens.
 */
class MainActivity : ComponentActivity() {

    private val hcAvailable = mutableStateOf(true)
    private val hcGranted = mutableStateOf(false)
    private val stravaConnected = mutableStateOf(false)
    private val stravaAthlete = mutableStateOf<String?>(null)
    private val routes = mutableStateOf<List<RouteRow>>(emptyList())

    private val requestPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { refreshHealthConnect() }

    private val pickGpx = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { importGpx(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleStravaRedirect(intent)
        handleIncomingGpx(intent)
        setContent {
            val available by hcAvailable
            val granted by hcGranted
            val routeList by routes
            val stravaOn by stravaConnected
            val athlete by stravaAthlete
            CompanionApp(
                hcAvailable = available,
                hcGranted = granted,
                stravaConnected = stravaOn,
                stravaAthlete = athlete,
                routes = routeList,
                onGrant = ::onGrantClicked,
                onConnectStrava = ::onConnectStrava,
                onDisconnectStrava = ::onDisconnectStrava,
                onImport = { pickGpx.launch(arrayOf("application/gpx+xml", "application/xml", "text/xml", "application/octet-stream")) },
                onCreate = { startActivity(Intent(this, RouteCreatorActivity::class.java)) },
                onDelete = ::deleteRoute,
                onShare = ::shareRoute,
                onSendToWatch = ::sendToWatch,
                onRefresh = ::refreshRoutes,
                onResync = { syncQueuedRuns(auto = false) },
                onAddDiscovered = ::addDiscoveredRoute,
                lastLocation = ::lastKnownLocation,
            )
        }
    }

    /** Fetch a discovered OSM route's geometry → GPX → save locally + push to watch. */
    private fun addDiscoveredRoute(route: RouteDiscoveryService.DiscoveredRoute) {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Fetching “${route.name}”…", Toast.LENGTH_SHORT).show()
            val ok = runCatching {
                val gpx = withContext(Dispatchers.IO) { RouteDiscoveryService.buildGpx(route) }
                    ?: error("no geometry")
                val name = PhoneRouteStore.safeName(route.name)
                val bytes = gpx.toByteArray()
                PhoneRouteStore.save(this@MainActivity, name, bytes)
                runCatching { WatchRoutes.sendRoute(applicationContext, name, bytes) }
                true
            }.getOrDefault(false)
            Toast.makeText(
                this@MainActivity,
                if (ok) "Added “${route.name}”" else "Couldn't fetch that route",
                Toast.LENGTH_LONG,
            ).show()
            if (ok) attachBaseMap(route.name)
            delay(500); refreshRoutes()
        }
    }

    /** Best-effort last-known device location for "near me" search; null if unavailable. */
    private fun lastKnownLocation(): Pair<Double, Double>? {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val lm = getSystemService(LocationManager::class.java) ?: return null
            lm.getProviders(true).asReversed()
                .firstNotNullOfOrNull { p -> lm.getLastKnownLocation(p) }
                ?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStravaRedirect(intent)
        handleIncomingGpx(intent)
    }

    /** Import a GPX opened with / shared to the app (VIEW a .gpx, or SEND from another app). */
    private fun handleIncomingGpx(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return
        // Don't double-handle the OAuth redirect.
        if (uri.scheme == "wearosgpx") return

        lifecycleScope.launch {
            val name = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error("unreadable")
                val head = bytes.take(2048).toByteArray().decodeToString()
                require(head.contains("<gpx", ignoreCase = true)) { "not a GPX file" }
                val fileName = PhoneRouteStore.safeName(gpxName(uri, bytes))
                PhoneRouteStore.save(this@MainActivity, fileName, bytes)
                runCatching { WatchRoutes.sendRoute(applicationContext, fileName, bytes) }
                fileName
            }.getOrNull()
            Toast.makeText(
                this@MainActivity,
                name?.let { "Imported “$it”" } ?: "That file isn't a valid GPX route",
                Toast.LENGTH_LONG,
            ).show()
            if (name != null) { delay(500); refreshRoutes() }
        }
    }

    /** A route name from the URI's display name, else the GPX <name>, else a fallback. */
    private fun gpxName(uri: Uri, bytes: ByteArray): String {
        displayName(uri)?.takeIf { it.isNotBlank() }?.let { return it }
        val tag = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
            .find(bytes.decodeToString())?.groupValues?.get(1)?.trim()
        return tag?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "Imported route"
    }

    override fun onResume() {
        super.onResume()
        refreshHealthConnect()
        refreshStrava()
        refreshRoutes()
        syncQueuedRuns(auto = true)
    }

    private fun refreshStrava() {
        stravaConnected.value = StravaClient.isConnected(this)
        stravaAthlete.value = StravaClient.connectedAthlete(this)
    }

    private fun onConnectStrava() {
        if (!StravaClient.isConfigured()) {
            Toast.makeText(this, "Strava API keys not set in this build.", Toast.LENGTH_LONG).show(); return
        }
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(StravaClient.authorizeUrl()))) }
            .onFailure { Toast.makeText(this, "Couldn't open Strava", Toast.LENGTH_LONG).show() }
    }

    private fun onDisconnectStrava() {
        StravaClient.disconnect(this); refreshStrava()
        Toast.makeText(this, "Disconnected from Strava", Toast.LENGTH_SHORT).show()
    }

    /** Catch the OAuth redirect (wearosgpx://localhost?code=…) and exchange for tokens. */
    private fun handleStravaRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "wearosgpx" || data.host != "localhost") return
        val code = data.getQueryParameter("code")
        if (code == null) {
            Toast.makeText(this, "Strava authorization cancelled", Toast.LENGTH_LONG).show(); return
        }
        lifecycleScope.launch {
            val ok = StravaClient.exchangeCode(applicationContext, code)
            refreshStrava()
            Toast.makeText(
                this@MainActivity,
                if (ok) "Connected to Strava — runs will upload automatically" else "Strava connection failed",
                Toast.LENGTH_LONG,
            ).show()
            if (ok) syncQueuedRuns(auto = true)   // upload any already-recorded runs
        }
    }

    /** Catch up any runs still queued on the Data Layer (e.g. delivered while asleep). */
    private fun syncQueuedRuns(auto: Boolean) {
        lifecycleScope.launch {
            RunImporter.logRecentSessions(applicationContext)
            val written = withContext(Dispatchers.IO) {
                RunImporter.importQueued(applicationContext, if (auto) "resume" else "manual")
            }
            if (!auto) {
                val msg = if (written > 0) "Synced $written run(s) to Health Connect" else "No new runs to sync"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onGrantClicked() {
        if (!HealthConnectWriter(this).isAvailable()) { hcAvailable.value = false; return }
        requestPermissions.launch(HealthConnectWriter.PERMISSIONS)
    }

    private fun refreshHealthConnect() {
        val writer = HealthConnectWriter(this)
        hcAvailable.value = writer.isAvailable()
        if (!hcAvailable.value) return
        lifecycleScope.launch {
            hcGranted.value = runCatching { writer.hasAllPermissions() }.getOrDefault(false)
        }
    }

    private fun refreshRoutes() {
        lifecycleScope.launch {
            routes.value = withContext(Dispatchers.IO) { buildRows() }
            // Keep trying: push any phone route the watch doesn't have yet.
            val pending = routes.value.filter { it.onPhone && !it.onWatch && it.fileName != null }
            if (pending.isNotEmpty()) {
                pending.forEach { row ->
                    val bytes = PhoneRouteStore.fileFor(applicationContext, row.fileName!!)?.readBytes()
                    if (bytes != null) runCatching { WatchRoutes.sendRoute(applicationContext, row.fileName, bytes) }
                }
                delay(1200)
                routes.value = withContext(Dispatchers.IO) { buildRows() }
            }
        }
    }

    private suspend fun buildRows(): List<RouteRow> {
        val watch = runCatching { WatchRoutes.fetchIndex(applicationContext) }.getOrDefault(emptyList())
        val watchNames = watch.mapNotNull { it.fileName }.toSet()
        val phoneNames = HashSet<String>()
        val rows = ArrayList<RouteRow>()

        for (file in PhoneRouteStore.list(applicationContext)) {
            val meta = GpxMeta.read(file) ?: continue
            phoneNames.add(file.name)
            rows += RouteRow(
                fileName = file.name,
                name = meta.name ?: file.nameWithoutExtension,
                distanceMeters = meta.distanceMeters,
                ascentMeters = meta.ascentMeters,
                pointCount = meta.pointCount,
                onPhone = true,
                onWatch = watchNames.contains(file.name),
            )
        }
        for (w in watch) {
            if (w.fileName != null && phoneNames.contains(w.fileName)) continue
            rows += RouteRow(w.fileName, w.name, w.distanceMeters, w.ascentMeters, w.pointCount, onPhone = false, onWatch = true)
        }
        return rows.sortedBy { it.name.lowercase() }
    }

    private fun importGpx(uri: Uri) {
        val name = displayName(uri) ?: "route.gpx"
        if (!name.endsWith(".gpx", ignoreCase = true)) {
            Toast.makeText(this, "Please choose a .gpx file", Toast.LENGTH_LONG).show(); return
        }
        lifecycleScope.launch {
            val ok = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("read failed")
                PhoneRouteStore.save(this@MainActivity, name, bytes)            // always keep locally
                runCatching { WatchRoutes.sendRoute(applicationContext, name, bytes) }
            }.isSuccess
            Toast.makeText(this@MainActivity, if (ok) "Imported “$name”" else "Import failed", Toast.LENGTH_LONG).show()
            if (ok) attachBaseMap(name)
            delay(600); refreshRoutes()
        }
    }

    private fun sendToWatch(row: RouteRow) {
        val bytes = row.fileName?.let { PhoneRouteStore.fileFor(this, it)?.readBytes() } ?: return
        lifecycleScope.launch {
            val ok = runCatching { WatchRoutes.sendRoute(applicationContext, row.fileName!!, bytes) }.isSuccess
            Toast.makeText(this@MainActivity, if (ok) "Sent to watch" else "Watch not reachable", Toast.LENGTH_SHORT).show()
            if (ok) attachBaseMap(row.fileName)
            delay(800); refreshRoutes()
        }
    }

    /**
     * Build the surrounding vector basemap for a route (OSM via Overpass, on the
     * phone) and push it to the watch. Best-effort + fully async — never blocks the
     * GPX push, and silently no-ops if offline or the area has no data.
     */
    private fun attachBaseMap(rawName: String) {
        val fileName = PhoneRouteStore.safeName(rawName)
        lifecycleScope.launch {
            runCatching {
                val file = PhoneRouteStore.fileFor(applicationContext, fileName) ?: return@runCatching
                val points = withContext(Dispatchers.IO) { GpxMeta.readPoints(file) }
                if (points.size < 2) return@runCatching
                val mapBytes = withContext(Dispatchers.IO) { BaseMapService.build(points) } ?: return@runCatching
                WatchRoutes.sendBaseMap(applicationContext, fileName, mapBytes)
            }
        }
    }

    private fun deleteRoute(row: RouteRow) {
        val fileName = row.fileName ?: return
        lifecycleScope.launch {
            if (row.onWatch) runCatching { WatchRoutes.delete(applicationContext, fileName) }
            if (row.onPhone) PhoneRouteStore.delete(applicationContext, fileName)
            delay(600); refreshRoutes()
        }
    }

    private fun shareRoute(row: RouteRow) {
        val file = row.fileName?.let { PhoneRouteStore.fileFor(this, it) }
        if (file == null) {
            Toast.makeText(this, "No local copy on this phone to share.", Toast.LENGTH_LONG).show(); return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share route",
            ),
        )
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
}

@Composable
private fun CompanionApp(
    hcAvailable: Boolean,
    hcGranted: Boolean,
    stravaConnected: Boolean,
    stravaAthlete: String?,
    routes: List<RouteRow>,
    onGrant: () -> Unit,
    onConnectStrava: () -> Unit,
    onDisconnectStrava: () -> Unit,
    onImport: () -> Unit,
    onCreate: () -> Unit,
    onDelete: (RouteRow) -> Unit,
    onShare: (RouteRow) -> Unit,
    onSendToWatch: (RouteRow) -> Unit,
    onRefresh: () -> Unit,
    onResync: () -> Unit,
    onAddDiscovered: (RouteDiscoveryService.DiscoveredRoute) -> Unit,
    lastLocation: () -> Pair<Double, Double>?,
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon,
            onPrimary = Color.Black,
            background = Color.Black,
            surface = Color(0xFF1A1A1A),
            onSurface = Color.White,
        )
    ) {
        var detail by remember { mutableStateOf<RouteRow?>(null) }
        var showSettings by remember { mutableStateOf(false) }
        var showDiscover by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text("WearOsGpx", color = Neon, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("Companion", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                }
                TextButton(onClick = { showSettings = true }) { Text("Settings", color = Neon) }
            }

            Spacer(Modifier.height(20.dp))
            HealthConnectCard(hcAvailable, hcGranted, onGrant)

            if (hcGranted) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onResync,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A), contentColor = Neon),
                ) { Text("Re-sync watch runs") }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Color.Black),
            ) { Text("Create route on map", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { showDiscover = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A), contentColor = Neon),
            ) { Text("Discover routes (OpenStreetMap)") }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onImport,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A), contentColor = Color.White),
            ) { Text("Import GPX file") }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Routes", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onRefresh) { Text("Refresh", color = Neon) }
            }
            Spacer(Modifier.height(8.dp))

            if (routes.isEmpty()) {
                Text(
                    "No routes yet. Create one on the map or import a GPX file.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 14.sp,
                )
            } else {
                routes.forEach { route ->
                    RouteCard(route, onClick = { detail = route })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        detail?.let { row ->
            RouteDetailDialog(
                row = row,
                onShare = { onShare(row); detail = null },
                onSendToWatch = { onSendToWatch(row); detail = null },
                onDelete = { onDelete(row); detail = null },
                onDismiss = { detail = null },
            )
        }

        if (showSettings) {
            SettingsDialog(
                stravaConnected = stravaConnected,
                stravaAthlete = stravaAthlete,
                onConnectStrava = onConnectStrava,
                onDisconnectStrava = onDisconnectStrava,
                onDismiss = { showSettings = false },
            )
        }

        if (showDiscover) {
            DiscoverScreen(
                onAdd = onAddDiscovered,
                lastLocation = lastLocation,
                onClose = { showDiscover = false },
            )
        }
    }
}

/** Full-screen route discovery: search OSM trails by name or near a place, add to watch. */
@Composable
private fun DiscoverScreen(
    onAdd: (RouteDiscoveryService.DiscoveredRoute) -> Unit,
    lastLocation: () -> Pair<Double, Double>?,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<RouteDiscoveryService.DiscoveredRoute>>(emptyList()) }
    var status by remember { mutableStateOf<String?>("Search a trail name, or a place to find nearby routes.") }

    fun search(byName: Boolean) {
        loading = true; status = null; results = emptyList()
        scope.launch {
            val found = runCatching {
                if (byName) {
                    RouteDiscoveryService.searchByName(query)
                } else {
                    val loc = if (query.isBlank()) lastLocation()
                        else GeocodingService.search(query)?.let { it.lat to it.lon }
                    if (loc == null) emptyList()
                    else RouteDiscoveryService.searchNearby(loc.first, loc.second)
                }
            }.getOrDefault(emptyList())
            results = found
            loading = false
            status = when {
                found.isNotEmpty() -> null
                byName -> "No trails found for that name."
                query.isBlank() -> "No location available — type a place name."
                else -> "No routes found near there."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding()
            .padding(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Discover routes", color = Neon, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 20.sp) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Trail name or place") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { search(byName = true) },
                enabled = query.isNotBlank(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Color.Black),
            ) { Text("By name") }
            Button(
                onClick = { search(byName = false) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A), contentColor = Neon),
            ) { Text(if (query.isBlank()) "Near me" else "Near place") }
        }
        Spacer(Modifier.height(16.dp))

        if (loading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Neon)
            }
        }
        status?.let { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp) }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(results) { route ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(route.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(route.activity, color = Neon, fontSize = 12.sp)
                        }
                        TextButton(onClick = { onAdd(route); onClose() }) { Text("Add", color = Neon) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    stravaConnected: Boolean,
    stravaAthlete: String?,
    onConnectStrava: () -> Unit,
    onDisconnectStrava: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var key by remember { mutableStateOf(AppSettings.storedOrsKey(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = { Text("Settings", color = Color.White) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("OpenRouteService API key", color = Color.White, fontSize = 15.sp)
                Text(
                    "Used for road-following route creation. Leave blank to use the built-in default. " +
                        "Get a free key at openrouteservice.org.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    label = { Text("API key") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(20.dp))
                Text("Strava (optional)", color = Color.White, fontSize = 15.sp)
                Text(
                    "Auto-upload finished runs to Strava as well. Your primary sync is Health " +
                        "Connect (read by Samsung Health) — Strava is an extra.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                StravaCard(stravaConnected, stravaAthlete, onConnectStrava, onDisconnectStrava)
            }
        },
        confirmButton = {
            TextButton(onClick = { AppSettings.setOrsKey(context, key); onDismiss() }) {
                Text("Save", color = Neon)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Neon) } },
    )
}

@Composable
private fun StravaCard(connected: Boolean, athlete: String?, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val stravaOrange = Color(0xFFFC4C02)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (connected) "✓ Strava connected${athlete?.let { " · $it" } ?: ""}. Runs upload automatically."
                else "Connect Strava to auto-upload each finished run as an activity.",
                color = if (connected) stravaOrange else Color.White,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            if (connected) {
                TextButton(onClick = onDisconnect) { Text("Disconnect Strava", color = Color(0xFFFF6B6B)) }
            } else {
                Button(
                    onClick = onConnect,
                    colors = ButtonDefaults.buttonColors(containerColor = stravaOrange, contentColor = Color.White),
                ) { Text("Connect Strava", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun HealthConnectCard(available: Boolean, granted: Boolean, onGrant: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
    ) {
        Column(Modifier.padding(16.dp)) {
            val status = when {
                !available -> "Health Connect unavailable — install/enable it to sync runs."
                granted -> "✓ Connected. Watch runs sync to Health Connect automatically (OHealth reads them)."
                else -> "Allow this app to write workouts to Health Connect so your watch runs sync."
            }
            Text(status, color = if (granted) Neon else Color.White, fontSize = 14.sp)
            if (available && !granted) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onGrant,
                    colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Color.Black),
                ) { Text("Grant Health Connect access", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun RouteCard(route: RouteRow, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(route.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "%.2f km · %.0f m ascent · %d pts".format(
                    route.distanceMeters / 1000, route.ascentMeters, route.pointCount,
                ),
                color = Neon,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(2.dp))
            Text(statusText(route), color = statusColor(route), fontSize = 12.sp)
        }
    }
}

private fun statusText(route: RouteRow): String = when {
    route.onWatch && route.onPhone -> "On watch"
    route.onWatch -> "On watch"
    route.onPhone -> "On phone · syncing to watch…"
    else -> ""
}

private fun statusColor(route: RouteRow): Color =
    if (route.onWatch) Neon else Color(0xFFF9A825)

@Composable
private fun RouteDetailDialog(
    row: RouteRow,
    onShare: () -> Unit,
    onSendToWatch: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A1A),
        title = { Text(row.name, color = Color.White) },
        text = {
            Column {
                DetailRow("Distance", "%.2f km".format(row.distanceMeters / 1000))
                DetailRow("Ascent", "%.0f m".format(row.ascentMeters))
                DetailRow("Track points", row.pointCount.toString())
                DetailRow("Status", statusText(row))
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (row.onPhone) TextButton(onClick = onShare) { Text("Share", color = Neon) }
                if (row.onPhone && !row.onWatch) {
                    TextButton(onClick = onSendToWatch) { Text("Send", color = Neon) }
                }
                if (row.fileName != null) {
                    TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFFF6B6B)) }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Neon) } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
