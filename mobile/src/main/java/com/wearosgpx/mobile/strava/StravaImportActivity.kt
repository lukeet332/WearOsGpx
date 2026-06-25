package com.wearosgpx.mobile.strava

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearosgpx.mobile.route.PhoneRouteStore
import com.wearosgpx.mobile.sync.WatchRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Neon = Color(0xFF39FF14)
private val StravaOrange = Color(0xFFFC4C02)

/**
 * Lists the connected athlete's own Strava routes + recent GPS activities; tap one to
 * import it as a route (saved locally + pushed to the watch). This is the legitimate,
 * per-user path — e.g. turn "my last parkrun" into a reusable route. No global search:
 * Strava only exposes the authenticated user's own data.
 */
class StravaImportActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Screen() }
    }

    private suspend fun importRoute(item: StravaParse.Item): Boolean {
        val bytes = StravaClient.routeGpx(this, item.id) ?: return false
        return save(item.name, bytes)
    }

    private suspend fun importActivity(item: StravaParse.Item): Boolean {
        val gpx = StravaClient.activityGpx(this, item.id, item.name) ?: return false
        return save(item.name, gpx.toByteArray())
    }

    private suspend fun save(name: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val file = PhoneRouteStore.safeName(name)
            PhoneRouteStore.save(this@StravaImportActivity, file, bytes)
            runCatching { WatchRoutes.sendRoute(applicationContext, file, bytes) }
            true
        }.getOrDefault(false)
    }

    @Composable
    private fun Screen() {
        MaterialTheme(
            colorScheme = darkColorScheme(primary = Neon, background = Color.Black, surface = Color(0xFF1A1A1A), onSurface = Color.White),
        ) {
            val scope = rememberCoroutineScope()
            var loading by remember { mutableStateOf(true) }
            var routes by remember { mutableStateOf<List<StravaParse.Item>>(emptyList()) }
            var activities by remember { mutableStateOf<List<StravaParse.Item>>(emptyList()) }
            var status by remember { mutableStateOf<String?>(null) }
            var busy by remember { mutableStateOf(false) }
            val imported = remember { mutableStateListOf<Long>() }

            LaunchedEffect(Unit) {
                val r = StravaClient.listRoutes(this@StravaImportActivity)
                val a = StravaClient.listActivities(this@StravaImportActivity)
                routes = r.orEmpty()
                activities = a.orEmpty()
                loading = false
                status = if (r == null && a == null) "Couldn't reach Strava — check your connection in Settings."
                else if (routes.isEmpty() && activities.isEmpty()) "No routes or GPS activities found on your Strava."
                else null
            }

            fun pick(item: StravaParse.Item, isRoute: Boolean) {
                if (busy) return
                busy = true
                scope.launch {
                    val ok = if (isRoute) importRoute(item) else importActivity(item)
                    busy = false
                    if (ok) {
                        imported.add(item.id)
                        Toast.makeText(this@StravaImportActivity, "Imported “${item.name}”", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@StravaImportActivity, "Couldn't import that one", Toast.LENGTH_LONG).show()
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Import from Strava", color = StravaOrange, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    TextButton(onClick = ::finish) { Text("✕", color = Color.White, fontSize = 20.sp) }
                }
                Spacer(Modifier.height(8.dp))

                if (loading) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Neon)
                    }
                }
                status?.let { Text(it, color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp) }

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    if (routes.isNotEmpty()) {
                        item { SectionHeader("Your routes") }
                        items(routes) { ItemCard(it, imported.contains(it.id)) { pick(it, isRoute = true) } }
                    }
                    if (activities.isNotEmpty()) {
                        item { SectionHeader("Recent activities") }
                        items(activities) { ItemCard(it, imported.contains(it.id)) { pick(it, isRoute = false) } }
                    }
                }
            }
        }
    }

    @Composable
    private fun SectionHeader(text: String) {
        Spacer(Modifier.height(12.dp))
        Text(text, color = Neon, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
    }

    @Composable
    private fun ItemCard(item: StravaParse.Item, done: Boolean, onClick: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            onClick = onClick,
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${item.detail} · %.2f km".format(item.distanceMeters / 1000),
                        color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp,
                    )
                }
                Text(if (done) "✓ Added" else "Import", color = if (done) Color.White.copy(alpha = 0.5f) else Neon, fontSize = 14.sp)
            }
        }
    }
}
