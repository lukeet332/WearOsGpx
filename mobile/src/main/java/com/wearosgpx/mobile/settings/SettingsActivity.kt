package com.wearosgpx.mobile.settings

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wearosgpx.mobile.route.RecommendedModels
import com.wearosgpx.mobile.strava.StravaClient

private val Neon = Color(0xFF39FF14)

/**
 * Settings as its own page (was a popup): every API key the app uses lives here, each
 * with its status and a "Get a key" link. Launched with [EXTRA_PROMPT] true when a
 * required key is missing, so it shows a banner pointing the user at what to add.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prompt = intent.getBooleanExtra(EXTRA_PROMPT, false)
        setContent { SettingsScreen(prompt = prompt, onBack = { finish() }) }
    }

    companion object {
        const val EXTRA_PROMPT = "prompt_missing_keys"
    }
}

@Composable
private fun SettingsScreen(prompt: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    fun open(url: String) = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure { Toast.makeText(context, "Couldn't open link", Toast.LENGTH_SHORT).show() }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Neon, onPrimary = Color.Black,
            background = Color.Black, surface = Color(0xFF1A1A1A), onSurface = Color.White,
        )
    ) {
        var ors by remember { mutableStateOf(AppSettings.storedOrsKey(context)) }
        var aiKey by remember { mutableStateOf(AppSettings.aiKey(context)) }
        var modelChoice by remember { mutableStateOf(AppSettings.storedAiModel(context)) }  // "" = auto/recommended
        var stravaConnected by remember { mutableStateOf(StravaClient.isConnected(context)) }
        val athlete = remember { StravaClient.connectedAthlete(context) }

        fun saveAll() {
            AppSettings.setOrsKey(context, ors)
            AppSettings.setAiModel(context, modelChoice)   // before the key, so the key records the right provider
            AppSettings.setAiKey(context, aiKey)
            Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
        }

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
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Settings", color = Neon, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onBack) { Text("Done", color = Neon) }
            }

            if (prompt) {
                Spacer(Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF332A00)),
                ) {
                    Text(
                        "Add the API keys below to unlock everything — links to get each (free) " +
                            "key are included. You can skip and add them later.",
                        color = Color(0xFFF9D74A),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }

            // ---- AI route generation (diverse free model menu, kept fresh weekly) ----
            Spacer(Modifier.height(20.dp))
            val entries = remember { RecommendedModels.entries(context) }
            val active = entries.firstOrNull { it.model == modelChoice } ?: entries.firstOrNull() ?: RecommendedModels.FALLBACK
            KeyCard(
                title = "AI route generation",
                status = if (aiKey.isBlank()) "Not set — paste your ${active.provider} key"
                else "✓ ${active.provider} key set",
                statusOk = aiKey.isNotBlank(),
                blurb = "Describe a route in plain text (\"5k loop from here\") and the AI plots it on " +
                    "real roads. The recommended model is free; pick another below if you like — some use " +
                    "a different provider, so you'd paste that provider's key. Grab a free key and paste it.",
                links = listOf("Get a free ${active.provider} key →" to active.keyUrl),
                onOpen = ::open,
            ) {
                Text("Model", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                var expanded by remember { mutableStateOf(false) }
                val recLabel = entries.firstOrNull()?.label ?: "recommended"
                val current =
                    if (modelChoice.isBlank()) "Auto · $recLabel"
                    else entries.find { it.model == modelChoice }?.label ?: modelChoice
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(current, color = Neon)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Auto · keep the recommended model") },
                            onClick = { modelChoice = ""; expanded = false },
                        )
                        entries.forEach { e ->
                            DropdownMenuItem(
                                text = { Text(e.label) },
                                onClick = { modelChoice = e.model; expanded = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = aiKey, onValueChange = { aiKey = it }, singleLine = true,
                    label = { Text("Paste your ${active.provider} API key") }, modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- OpenRouteService (routing) ----
            Spacer(Modifier.height(16.dp))
            KeyCard(
                title = "OpenRouteService",
                status = if (ors.isBlank()) "Using built-in default key (fine to start)"
                else "✓ Your own key set",
                statusOk = true,
                blurb = "Road/path-following route creation. A built-in key is bundled; add " +
                    "your own free key for reliability (the shared one is rate-limited).",
                links = listOf("Get a free key →" to "https://openrouteservice.org/dev/#/signup"),
                onOpen = ::open,
            ) {
                OutlinedTextField(
                    value = ors, onValueChange = { ors = it }, singleLine = true,
                    label = { Text("ORS API key (optional)") }, modifier = Modifier.fillMaxWidth(),
                )
            }

            // ---- Strava (optional, OAuth — not a key field) ----
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Strava (optional)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (stravaConnected) "✓ Connected${athlete?.let { " · $it" } ?: ""}. Finished runs upload automatically, and you can import your own Strava routes & runs as routes (on the home screen). If Import doesn't appear, reconnect to grant read access."
                        else "Auto-upload finished runs to Strava, and import your own Strava routes & past runs as routes (e.g. reuse a parkrun you've done). Your primary sync is still Health Connect — Strava is an extra.",
                        color = if (stravaConnected) Color(0xFFFC4C02) else Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (stravaConnected) {
                        TextButton(onClick = {
                            StravaClient.disconnect(context); stravaConnected = false
                            Toast.makeText(context, "Disconnected from Strava", Toast.LENGTH_SHORT).show()
                        }) { Text("Disconnect Strava", color = Color(0xFFFF6B6B)) }
                    } else {
                        Button(
                            onClick = {
                                if (!StravaClient.isConfigured()) {
                                    Toast.makeText(context, "Strava API keys not set in this build.", Toast.LENGTH_LONG).show()
                                } else open(StravaClient.authorizeUrl())
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFC4C02), contentColor = Color.White),
                        ) { Text("Connect Strava", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { saveAll() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Neon, contentColor = Color.Black),
            ) { Text("Save", fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun KeyCard(
    title: String,
    status: String,
    statusOk: Boolean,
    blurb: String,
    links: List<Pair<String, String>>,
    onOpen: (String) -> Unit,
    fields: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(status, color = if (statusOk) Neon else Color(0xFFF9A825), fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(blurb, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            fields()
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                links.forEach { (label, url) ->
                    TextButton(onClick = { onOpen(url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(label, color = Neon, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
