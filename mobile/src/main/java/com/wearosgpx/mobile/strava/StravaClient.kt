package com.wearosgpx.mobile.strava

import android.content.Context
import android.util.Log
import com.wearosgpx.mobile.BuildConfig
import com.wearosgpx.mobile.route.GpxBuilder
import com.wearosgpx.mobile.sync.RunPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to Strava: OAuth2 (authorization-code + refresh) and activity upload.
 *
 * Flow: [authorizeUrl] opens Strava's consent screen in the browser/app; Strava
 * redirects to [REDIRECT_URI] with a `code`; [exchangeCode] swaps it for tokens
 * (stored in prefs). [uploadRun] builds an activity GPX and POSTs it to /uploads,
 * refreshing the access token first if it's expired. Uploads are de-duplicated by
 * run start time so a run is never posted twice.
 */
object StravaClient {

    private const val TAG = "Strava"
    private const val PREFS = "strava"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_EXPIRES = "expires_at"        // epoch seconds
    private const val KEY_ATHLETE = "athlete"
    private const val KEY_ATHLETE_ID = "athlete_id"
    private const val KEY_UPLOADED = "uploaded_starts"

    // Custom-scheme redirect. The Strava API app's "Authorization Callback Domain"
    // must be set to `localhost` (Strava matches on host only, ignoring the scheme).
    const val REDIRECT_URI = "wearosgpx://localhost"
    // write = upload finished runs; read_all + activity:read_all = read the user's own
    // (incl. private) routes and activities so we can import them as routes.
    private const val SCOPE = "activity:write,activity:read_all,read_all"

    private val json = Json { ignoreUnknownKeys = true }

    /** True once API credentials are baked in (local.properties / CI env). */
    fun isConfigured(): Boolean =
        BuildConfig.STRAVA_CLIENT_ID.isNotBlank() && BuildConfig.STRAVA_CLIENT_SECRET.isNotBlank()

    /** True once the user has authorized us (we hold a refresh token). */
    fun isConnected(context: Context): Boolean =
        prefs(context).getString(KEY_REFRESH, null) != null

    fun connectedAthlete(context: Context): String? =
        prefs(context).getString(KEY_ATHLETE, null)

    fun authorizeUrl(): String {
        val params = mapOf(
            "client_id" to BuildConfig.STRAVA_CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "approval_prompt" to "auto",
            "scope" to SCOPE,
        ).entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        // /oauth/mobile/authorize deep-links into the installed Strava app when present.
        return "https://www.strava.com/oauth/mobile/authorize?$params"
    }

    fun disconnect(context: Context) {
        prefs(context).edit().remove(KEY_ACCESS).remove(KEY_REFRESH)
            .remove(KEY_EXPIRES).remove(KEY_ATHLETE).remove(KEY_ATHLETE_ID).apply()
    }

    /** Exchange the OAuth `code` from the redirect for tokens. Returns success. */
    suspend fun exchangeCode(context: Context, code: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val body = "client_id=${enc(BuildConfig.STRAVA_CLIENT_ID)}" +
                "&client_secret=${enc(BuildConfig.STRAVA_CLIENT_SECRET)}" +
                "&code=${enc(code)}&grant_type=authorization_code"
            val resp = postForm("https://www.strava.com/oauth/token", body)
            val obj = json.parseToJsonElement(resp).jsonObject
            storeTokens(context, obj)
            val athlete = obj["athlete"]?.jsonObject
            val name = listOfNotNull(
                athlete?.get("firstname")?.jsonPrimitive?.contentOrNull,
                athlete?.get("lastname")?.jsonPrimitive?.contentOrNull,
            ).joinToString(" ").ifBlank { "Strava" }
            val athleteId = athlete?.get("id")?.jsonPrimitive?.longOrNull ?: 0L
            prefs(context).edit().putString(KEY_ATHLETE, name).putLong(KEY_ATHLETE_ID, athleteId).apply()
            Log.i(TAG, "Connected to Strava as $name")
            true
        }.getOrElse { Log.e(TAG, "Token exchange failed", it); false }
    }

    /** Upload one run as a Strava activity, unless it's already been uploaded. Returns the upload id, or null. */
    suspend fun uploadRun(context: Context, payload: RunPayload, trigger: String): Long? = withContext(Dispatchers.IO) {
        if (!isConnected(context)) return@withContext null
        val key = payload.startEpochMillis.toString()
        if (uploaded(context).contains(key)) {
            Log.i(TAG, "[$trigger] run $key already uploaded to Strava; skipping.")
            return@withContext null
        }
        val token = validAccessToken(context) ?: run {
            Log.w(TAG, "[$trigger] no valid Strava token"); return@withContext null
        }
        runCatching {
            val gpx = StravaActivityGpx.build(payload).toByteArray(Charsets.UTF_8)
            val uploadId = postUpload(token, gpx, payload)
            markUploaded(context, key)
            Log.i(TAG, "[$trigger] uploaded run $key to Strava (upload id=$uploadId) ✓")
            uploadId
        }.getOrElse { Log.e(TAG, "[$trigger] Strava upload failed for run $key", it); null }
    }

    // --- reading the user's own routes / activities (for import) ---

    /** The authenticated athlete's saved routes (newest first), or null if not reachable. */
    suspend fun listRoutes(context: Context): List<StravaParse.Item>? = withContext(Dispatchers.IO) {
        val token = validAccessToken(context) ?: return@withContext null
        val id = athleteId(context) ?: return@withContext null
        runCatching {
            StravaParse.routes(httpGet("https://www.strava.com/api/v3/athletes/$id/routes?per_page=50", token))
        }.getOrElse { Log.e(TAG, "listRoutes failed", it); null }
    }

    /** The athlete's recent activities that have GPS (newest first), or null if not reachable. */
    suspend fun listActivities(context: Context): List<StravaParse.Item>? = withContext(Dispatchers.IO) {
        val token = validAccessToken(context) ?: return@withContext null
        runCatching {
            StravaParse.activities(httpGet("https://www.strava.com/api/v3/athlete/activities?per_page=30", token))
        }.getOrElse { Log.e(TAG, "listActivities failed", it); null }
    }

    /** Export a saved route as GPX bytes (Strava builds the GPX for us). */
    suspend fun routeGpx(context: Context, routeId: Long): ByteArray? = withContext(Dispatchers.IO) {
        val token = validAccessToken(context) ?: return@withContext null
        runCatching {
            httpGetBytes("https://www.strava.com/api/v3/routes/$routeId/export_gpx", token)
        }.getOrElse { Log.e(TAG, "routeGpx failed", it); null }
    }

    /** Build a GPX from an activity's GPS stream; null if it has no usable track. */
    suspend fun activityGpx(context: Context, activityId: Long, name: String): String? = withContext(Dispatchers.IO) {
        val token = validAccessToken(context) ?: return@withContext null
        runCatching {
            val resp = httpGet(
                "https://www.strava.com/api/v3/activities/$activityId/streams?keys=latlng&key_by_type=true",
                token,
            )
            val pts = StravaParse.latLng(resp)
            if (pts.size < 2) null else GpxBuilder.build(name, pts)
        }.getOrElse { Log.e(TAG, "activityGpx failed", it); null }
    }

    private fun athleteId(context: Context): Long? =
        prefs(context).getLong(KEY_ATHLETE_ID, 0L).takeIf { it > 0 }

    // --- token management ---

    private suspend fun validAccessToken(context: Context): String? {
        val p = prefs(context)
        val nowSec = System.currentTimeMillis() / 1000
        val expires = p.getLong(KEY_EXPIRES, 0)
        val access = p.getString(KEY_ACCESS, null)
        if (access != null && expires - 60 > nowSec) return access
        // Refresh.
        val refresh = p.getString(KEY_REFRESH, null) ?: return null
        return runCatching {
            val body = "client_id=${enc(BuildConfig.STRAVA_CLIENT_ID)}" +
                "&client_secret=${enc(BuildConfig.STRAVA_CLIENT_SECRET)}" +
                "&grant_type=refresh_token&refresh_token=${enc(refresh)}"
            val resp = postForm("https://www.strava.com/oauth/token", body)
            storeTokens(context, json.parseToJsonElement(resp).jsonObject)
            prefs(context).getString(KEY_ACCESS, null)
        }.getOrElse { Log.e(TAG, "Token refresh failed", it); null }
    }

    private fun storeTokens(context: Context, obj: kotlinx.serialization.json.JsonObject) {
        val access = obj["access_token"]?.jsonPrimitive?.contentOrNull
        val refresh = obj["refresh_token"]?.jsonPrimitive?.contentOrNull
        val expires = obj["expires_at"]?.jsonPrimitive?.longOrNull
        prefs(context).edit().apply {
            if (access != null) putString(KEY_ACCESS, access)
            if (refresh != null) putString(KEY_REFRESH, refresh)
            if (expires != null) putLong(KEY_EXPIRES, expires)
        }.apply()
    }

    // --- HTTP ---

    private fun postForm(url: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return readResponse(conn)
    }

    private fun postUpload(token: String, gpx: ByteArray, payload: RunPayload): Long {
        val boundary = "----wearosgpx" + payload.startEpochMillis
        val conn = (URL("https://www.strava.com/api/v3/uploads").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(conn.outputStream).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                out.writeBytes("$value\r\n")
            }
            field("data_type", "gpx")
            field("name", payload.title)
            field("external_id", "wearosgpx-${payload.startEpochMillis}")
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"run.gpx\"\r\n")
            out.writeBytes("Content-Type: application/gpx+xml\r\n\r\n")
            out.write(gpx)
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        val resp = readResponse(conn)
        return json.parseToJsonElement(resp).jsonObject["id"]?.jsonPrimitive?.longOrNull ?: -1L
    }

    private fun httpGet(url: String, token: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return readResponse(conn)
    }

    private fun httpGetBytes(url: String, token: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        val code = conn.responseCode
        if (code !in 200..299) throw RuntimeException("HTTP $code: ${conn.errorStream?.bufferedReader()?.use { it.readText() }}")
        return conn.inputStream.use { it.readBytes() }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("HTTP $code: $text")
        return text
    }

    // --- prefs / dedupe ---

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun uploaded(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_UPLOADED, emptySet())?.toSet() ?: emptySet()

    private fun markUploaded(context: Context, key: String) {
        val updated = (prefs(context).getStringSet(KEY_UPLOADED, emptySet())?.toMutableSet() ?: mutableSetOf())
        updated.add(key)
        prefs(context).edit().putStringSet(KEY_UPLOADED, updated).apply()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
