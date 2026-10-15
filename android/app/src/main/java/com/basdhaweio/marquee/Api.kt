package com.basdhaweio.marquee

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Where the app talks to and who it is. Lives in app-private SharedPreferences. */
class Session(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("marquee", Context.MODE_PRIVATE)
    var server: String
        get() = prefs.getString("server", null) ?: BuildConfig.DEFAULT_SERVER
        set(v) = prefs.edit().putString("server", v.trimEnd('/')).apply()
    var token: String?
        get() = prefs.getString("token", null)
        set(v) = prefs.edit().putString("token", v).apply()
    var user: String?
        get() = prefs.getString("user", null)
        set(v) = prefs.edit().putString("user", v).apply()
    val signedIn get() = token != null
}

class ApiException(val code: Int, message: String) : Exception(message)

val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

class Api(private val session: Session) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)   // assistant turns can take a while
        .build()
    var onSignedOut: () -> Unit = {}

    private suspend fun raw(method: String, path: String, body: JsonElement? = null, query: Map<String, Any?> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val url = (session.server + path).toHttpUrl().newBuilder().apply {
                query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v.toString()) }
            }.build()
            val req = Request.Builder().url(url).apply {
                session.token?.let { header("Authorization", "Bearer $it") }
                val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
                when (method) {
                    "GET" -> get()
                    "DELETE" -> delete()
                    else -> method(method, rb ?: "{}".toRequestBody("application/json".toMediaType()))
                }
            }.build()
            http.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 401 && !path.startsWith("/auth/pin")) {
                    session.token = null
                    withContext(Dispatchers.Main) { onSignedOut() }
                }
                if (!r.isSuccessful) {
                    val detail = runCatching { json.parseToJsonElement(text).let { (it as JsonObject)["detail"] ?: it["error"] } }
                        .getOrNull()?.toString()?.trim('"')
                    throw ApiException(r.code, detail ?: "HTTP ${r.code}")
                }
                text
            }
        }

    private suspend inline fun <reified T> get(path: String, query: Map<String, Any?> = emptyMap()): T =
        json.decodeFromString(raw("GET", path, query = query))

    private suspend inline fun <reified T> post(path: String, body: JsonElement = JsonObject(emptyMap()), query: Map<String, Any?> = emptyMap()): T =
        json.decodeFromString(raw("POST", path, body, query))

    // ---- auth
    suspend fun newPin(): Pin = post("/auth/pin", query = mapOf("client" to "android"))
    suspend fun checkPin(id: Long): PinStatus = try {
        get("/auth/pin/$id", mapOf("client" to "android"))
    } catch (e: ApiException) {
        if (e.code == 403) PinStatus("denied") else throw e
    }

    // ---- dashboard
    suspend fun overview(): Overview = get("/api/overview")
    suspend fun queue(): Map<String, List<QueueItem>> {
        val obj = json.parseToJsonElement(raw("GET", "/api/queue")) as JsonObject
        return obj.mapNotNull { (k, v) -> (v as? JsonArray)?.let { k to json.decodeFromJsonElement<List<QueueItem>>(it) } }.toMap()
    }
    suspend fun removeFromQueue(app: String, id: Int, blocklist: Boolean, search: Boolean) {
        raw("POST", "/api/queue/$app/$id/remove", query = mapOf("blocklist" to blocklist, "search" to search))
    }
    suspend fun calendar(days: Int = 14): Items<CalendarItem> = get("/api/calendar", mapOf("days" to days))

    // ---- library
    suspend fun shows(q: String = ""): Items<Show> = get("/api/shows", mapOf("q" to q))
    suspend fun show(id: Int): Show = get("/api/shows/$id")
    suspend fun movies(app: String, q: String = ""): Items<Movie> = get("/api/movies/$app", mapOf("q" to q))
    suspend fun movie(app: String, id: Int): Movie = get("/api/movies/$app/$id")
    suspend fun lookup(kind: String, term: String): Items<LookupItem> = get("/api/lookup", mapOf("kind" to kind, "term" to term))
    suspend fun addShow(tvdbId: Int, monitor: String = "all", search: Boolean = true) {
        raw("POST", "/api/shows", buildJsonObject { put("tvdbId", tvdbId); put("monitor", monitor); put("search", search) })
    }
    suspend fun addMovie(app: String, tmdbId: Int, search: Boolean = true) {
        raw("POST", "/api/movies/$app", buildJsonObject { put("tmdbId", tmdbId); put("search", search) })
    }
    suspend fun setMonitored(app: String, ids: List<Int>, monitored: Boolean, level: String = "item") {
        raw("POST", "/api/monitor/$app", buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }; put("monitored", monitored); put("level", level)
        })
    }
    suspend fun searchNow(app: String, ids: List<Int>) {
        raw("POST", "/api/searchnow/$app", buildJsonObject { putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } } })
    }
    suspend fun releases(app: String, episodeId: Int? = null, movieId: Int? = null): Items<Release> =
        get("/api/releases/$app", mapOf("episodeId" to episodeId, "movieId" to movieId))
    suspend fun grab(app: String, r: Release) {
        raw("POST", "/api/releases/$app/grab", buildJsonObject { put("guid", r.guid); put("indexerId", r.indexerId) })
    }
    suspend fun history(app: String): Items<HistoryItem> = get("/api/history/$app")

    // ---- requests / activity / indexers
    suspend fun requests(filter: String = "all"): Items<RequestItem> = get("/api/requests", mapOf("filter" to filter))
    suspend fun decideRequest(id: Int, approve: Boolean) {
        raw("POST", "/api/requests/$id/${if (approve) "approve" else "decline"}")
    }
    suspend fun activity(): Activity = get("/api/activity")
    suspend fun stats(days: Int = 30): Map<String, List<StatRow>> = get("/api/stats", mapOf("days" to days))
    suspend fun indexers(): Items<Indexer> = get("/api/indexers")
    suspend fun indexerSearch(q: String): Items<IndexerResult> = get("/api/indexers/search", mapOf("q" to q))
    suspend fun testIndexer(id: Int): Boolean =
        (json.parseToJsonElement(raw("POST", "/api/indexers/$id/test")) as JsonObject)["ok"].toString() == "true"

    // ---- assistant
    suspend fun conversations(): ConvList = get("/api/assistant")
    suspend fun conversation(id: String): Conversation = get("/api/assistant/$id")
    suspend fun chat(id: String?, message: String): Conversation =
        post("/api/assistant/chat", buildJsonObject { if (id != null) put("conversationId", id); put("message", message) })
    suspend fun decide(id: String, approve: Boolean): Conversation =
        post("/api/assistant/$id/decide", buildJsonObject { put("approve", approve) })
    suspend fun deleteConversation(id: String) { raw("DELETE", "/api/assistant/$id") }

    // ---- self-update
    suspend fun latest(): AppLatest = get("/app/latest")
    suspend fun download(path: String, dest: File) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(session.server.substringBefore("/hub") + path)
            .header("Authorization", "Bearer ${session.token}").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "download failed")
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
        dest
    }
}
