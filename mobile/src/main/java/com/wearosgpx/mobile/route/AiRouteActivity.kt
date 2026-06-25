package com.wearosgpx.mobile.route

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.wearosgpx.mobile.settings.AppSettings
import com.wearosgpx.mobile.sync.WatchRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.min

private val Neon = Color(0xFF39FF14)

/**
 * AI route generation as a chat: the user describes a route, the [AiRouteAgent] asks
 * clarifying questions and/or calls real APIs to build it, then offers a preview
 * (map + distance + ascent) to confirm before saving (GPX → phone store → watch).
 */
class AiRouteActivity : ComponentActivity() {

    private lateinit var agent: AiRouteAgent

    /** Lets the agent list + delete the user's saved routes (deletes go to the recycle bin). */
    private val routeOps = object : RouteOps {
        override suspend fun list(): List<RouteOps.Info> = withContext(Dispatchers.IO) {
            PhoneRouteStore.list(this@AiRouteActivity).mapNotNull { f ->
                val meta = GpxMeta.read(f) ?: return@mapNotNull null
                RouteOps.Info(f.name, meta.name ?: f.nameWithoutExtension, meta.distanceMeters)
            }
        }

        override suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
            val exists = PhoneRouteStore.fileFor(this@AiRouteActivity, fileName) != null
            if (exists) {
                PhoneRouteStore.moveToTrash(this@AiRouteActivity, fileName)
                PhoneRouteStore.deleteBaseMap(this@AiRouteActivity, fileName)
                runCatching { WatchRoutes.delete(applicationContext, fileName) }
            }
            exists
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        agent = AiRouteAgent(
            apiKey = AppSettings.aiKey(this),
            model = AppSettings.aiModel(this),
            baseUrl = AppSettings.aiBaseUrl(this),
            orsKey = AppSettings.effectiveOrsKey(this),
            currentLocation = lastKnownLocation(),
            routeOps = routeOps,
        )
        setContent {
            AiRouteScreen(
                onSend = { agent.send(it) },
                onSave = ::saveRoute,
                onClose = ::finish,
            )
        }
    }

    private suspend fun saveRoute(turn: AiRouteAgent.Turn.RouteReady): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                // On an update, keep the same file name but version the old copy to the recycle
                // bin first (a safety buffer, since the AI can get it wrong); else create new.
                val fileName = turn.replaceFileName?.let {
                    PhoneRouteStore.moveToTrash(this@AiRouteActivity, it)
                    PhoneRouteStore.safeName(it)
                } ?: PhoneRouteStore.safeName(turn.name)
                val gpx = GpxBuilder.build(turn.name, turn.geometry.points, turn.geometry.elevations)
                val bytes = gpx.toByteArray()
                PhoneRouteStore.save(this@AiRouteActivity, fileName, bytes)
                runCatching { WatchRoutes.sendRoute(applicationContext, fileName, bytes) }
                true
            }.getOrDefault(false)
        }

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
}

private data class ChatMsg(val fromUser: Boolean, val text: String)

@Composable
private fun AiRouteScreen(
    onSend: suspend (String) -> AiRouteAgent.Turn,
    onSave: suspend (AiRouteAgent.Turn.RouteReady) -> Boolean,
    onClose: () -> Unit,
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon, onPrimary = Color.Black,
            background = Color.Black, surface = Color(0xFF1A1A1A), onSurface = Color.White,
        )
    ) {
        val scope = rememberCoroutineScope()
        val messages = remember {
            listOf(
                ChatMsg(
                    false,
                    "Describe the route you'd like — e.g. \"5k loop from here\" or " +
                        "\"10k out-and-back along the river from Hyde Park\". I'll ask if I need to know more.",
                )
            ).toMutableStateList()
        }
        var input by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var pending by remember { mutableStateOf<AiRouteAgent.Turn.RouteReady?>(null) }
        val listState = rememberLazyListState()

        fun submit() {
            val text = input.trim()
            if (text.isEmpty() || busy) return
            input = ""
            messages.add(ChatMsg(true, text))
            busy = true
            scope.launch {
                when (val turn = onSend(text)) {
                    is AiRouteAgent.Turn.Reply -> messages.add(ChatMsg(false, turn.text))
                    is AiRouteAgent.Turn.Failed -> messages.add(ChatMsg(false, "⚠ ${turn.text}"))
                    is AiRouteAgent.Turn.RouteReady -> {
                        messages.add(ChatMsg(false, "Here's a route — take a look and confirm."))
                        pending = turn
                    }
                }
                busy = false
            }
        }

        // Keep the latest message in view.
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .systemBarsPadding()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("AI route", color = Neon, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Describe it — I'll plot it on real roads", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                }
                TextButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 20.sp) }
            }

            Spacer(Modifier.height(8.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages.size) { i -> Bubble(messages[i]) }
                if (busy) item { Bubble(ChatMsg(false, "…")) }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    enabled = !busy,
                    maxLines = 3,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { submit() },
                    enabled = !busy && input.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Color.Black),
                ) { Text("Send") }
            }
        }

        pending?.let { ready ->
            RoutePreviewDialog(
                ready = ready,
                onConfirm = {
                    busy = true
                    scope.launch {
                        val ok = onSave(ready)
                        pending = null
                        busy = false
                        if (ok) onClose()
                    }
                },
                onDiscard = {
                    pending = null
                    messages.add(ChatMsg(false, "No problem — tell me what to change."))
                },
            )
        }
    }
}

@Composable
private fun Bubble(msg: ChatMsg) {
    val align = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    val bg = if (msg.fromUser) Neon else Color(0xFF1E1E1E)
    val fg = if (msg.fromUser) Color.Black else Color.White
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Text(
            msg.text,
            color = fg,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun RoutePreviewDialog(
    ready: AiRouteAgent.Turn.RouteReady,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDiscard,
        containerColor = Color(0xFF1A1A1A),
        title = { Text(ready.name, color = Color.White) },
        text = {
            Column {
                RoutePreviewMapView(
                    points = ready.geometry.points,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                        .clip(RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Distance", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                    Text("%.2f km".format(ready.geometry.distanceM / 1000), color = Neon, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Ascent", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                    Text("%.0f m".format(ready.geometry.ascentM), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Save to watch", color = Neon, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDiscard) { Text("Discard", color = Color(0xFFFF6B6B)) } },
    )
}
