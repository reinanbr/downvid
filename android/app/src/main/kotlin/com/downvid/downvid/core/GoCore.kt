package com.downvid.downvid.core

import android.content.Context
import android.util.Log
import com.downvid.downvid.YtDlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/** A failed Go call; [fields] carries extra response keys ("unsupported", "code"). */
class GoError(message: String, val fields: JsonObject = JsonObject(emptyMap())) : Exception(message) {
    constructor(message: String, code: String) : this(message, JsonObject(mapOf("code" to JsonPrimitive(code))))

    val unsupported get() = (fields["unsupported"] as? JsonPrimitive)?.booleanOrNull == true
    val code get() = (fields["code"] as? JsonPrimitive)?.contentOrNull
}

/** A candidate media URL for probe (scan.Candidate). */
@Serializable
data class Candidate(
    val url: String,
    // Always sent: DvJson leaves defaults out, and Go labels options by it.
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault
    val source: String = "webview",
    val hint: String? = null,
    val referer: String? = null,
    val cookie: String? = null,
)

@Serializable
private data class ProbeRequest(
    val pageUrl: String,
    val candidates: List<Candidate> = emptyList(),
    val headers: Headers = Headers(),
    val skipPage: Boolean = false,
)

@Serializable
private class Envelope<T>(val ok: Boolean = false, val data: T? = null, val error: String? = null)

/**
 * Typed wrappers over [DvCore.nativeCall]. Suspend functions run on
 * Dispatchers.IO; the core is initialized (queue loaded, interrupted
 * downloads resumed) by the first call that needs it.
 */
object GoCore {
    private const val TAG = "DownVid"
    private lateinit var app: Context
    @Volatile private var ready = false
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun attach(context: Context) {
        app = context.applicationContext
    }

    // ------------------------------------------------------------ plumbing

    private fun <T> invoke(method: String, request: String, deserializer: KSerializer<T>): T? {
        val out = DvCore.nativeCall(method, request.encodeToByteArray()).decodeToString()
        val env = DvJson.decodeFromString(Envelope.serializer(deserializer), out)
        if (env.ok) return env.data
        val fields = runCatching { DvJson.parseToJsonElement(out).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        throw GoError(env.error ?: "$method failed", fields)
    }

    private fun <T> call(method: String, request: String, deserializer: KSerializer<T>): T =
        invoke(method, request, deserializer) ?: throw GoError("$method: empty response")

    private fun run(method: String, request: String) {
        invoke(method, request, JsonElement.serializer())
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun jobsCall(method: String, request: String) {
        ensureInit()
        run(method, request)
    }

    private fun idReq(id: Long) = """{"id":$id}"""

    // ------------------------------------------------------------ jobs

    /**
     * Once per process: loads files/jobs/jobs.json and resumes what was
     * pending. Returns the initial job list (null if it already ran).
     */
    @Synchronized
    fun ensureInit(): List<Job>? {
        if (ready) return null
        val t0 = System.nanoTime()
        val s = Settings.current
        val dir = File(app.filesDir, "jobs").apply { mkdirs() }
        val ff = YtDlp.ffmpegPaths(app)
        val req = buildJsonObject {
            put("dir", dir.absolutePath)
            put("ffmpeg", buildJsonObject { put("path", ff["path"]); put("libraryPath", ff["libraryPath"]) })
            put("maxConcurrent", s.maxConcurrent)
            put("connections", s.connections)
        }
        val jobs = call("init", req.toString(), ListSerializer(Job.serializer()))
        ready = true
        Log.i(TAG, "core: ${jobs.size} jobs, ${jobs.count { it.needsService }} active (init ${(System.nanoTime() - t0) / 1_000_000} ms)")
        return jobs
    }

    fun settingsAsync(maxConcurrent: Int, connections: Int) {
        if (!ready) return // init reads the current settings
        bg.launch { run("settings", """{"maxConcurrent":$maxConcurrent,"connections":$connections}""") }
    }

    /** Blocks (up to [timeoutMs]) until a job changes after [since]. Call off the main thread. */
    fun awaitBlocking(since: Long, timeoutMs: Long): JobChanges {
        ensureInit()
        return call("await", """{"since":$since,"timeoutMs":$timeoutMs}""", JobChanges.serializer())
    }

    fun statusBlocking(): List<Job> {
        ensureInit()
        return call("status", "{}", ListSerializer(Job.serializer()))
    }

    suspend fun download(req: DownloadRequest): Long = io {
        ensureInit()
        val r = call("download", DvJson.encodeToString(DownloadRequest.serializer(), req), JsonObject.serializer())
        (r["id"] as JsonPrimitive).content.toLong()
    }

    fun pause(id: Long) = jobsCall("pause", idReq(id))
    fun resume(id: Long) = jobsCall("resume", idReq(id))
    fun cancel(id: Long) = jobsCall("cancel", idReq(id))
    fun remove(id: Long) = jobsCall("remove", idReq(id))

    fun replace(id: Long, url: String, audioUrl: String?, headers: Headers, chunkSize: Long) {
        val req = buildJsonObject {
            put("id", id)
            put("url", url)
            audioUrl?.let { put("audioUrl", it) }
            put("headers", DvJson.encodeToJsonElement(headers))
            put("chunkSize", chunkSize)
        }
        jobsCall("replace", req.toString())
    }

    fun markSaved(id: Long, saved: SavedFile) =
        jobsCall("markSaved", buildJsonObject { put("id", id); put("saved", DvJson.encodeToJsonElement(saved)) }.toString())

    fun saveFailed(id: Long, message: String) =
        jobsCall("saveFailed", buildJsonObject { put("id", id); put("message", message) }.toString())

    // ------------------------------------------------------------ extraction

    suspend fun probe(pageUrl: String, candidates: List<Candidate>, headers: Headers, skipPage: Boolean): ExtractResult = io {
        val req = ProbeRequest(pageUrl, candidates, headers, skipPage)
        call("probe", DvJson.encodeToString(ProbeRequest.serializer(), req), ExtractResult.serializer())
    }

    suspend fun ytdlp(json: String, stderr: String, exitCode: Int, platform: String): ExtractResult = io {
        val req = buildJsonObject {
            put("json", json); put("stderr", stderr); put("exitCode", exitCode); put("platform", platform)
        }
        call("ytdlp", req.toString(), ExtractResult.serializer())
    }

    suspend fun musicMeta(basic: MusicBasic, override: MusicMeta? = null): MusicMeta = io {
        val req = buildJsonObject {
            put("basic", DvJson.encodeToJsonElement(basic))
            override?.let { put("override", DvJson.encodeToJsonElement(it)) }
        }
        call("musicMeta", req.toString(), MusicMeta.serializer())
    }

    suspend fun musicLink(url: String): MusicLinkResult = io {
        call("musicLink", buildJsonObject { put("url", url) }.toString(), MusicLinkResult.serializer())
    }

    suspend fun musicPick(searchJson: String, meta: MusicMeta, max: Int = 3): List<String> = io {
        val req = buildJsonObject {
            put("searchJson", searchJson); put("meta", DvJson.encodeToJsonElement(meta)); put("max", max)
        }
        call("musicPick", req.toString(), MusicPickResult.serializer()).candidates
    }

    /** The query InstagramQuery runs in its WebView (JSON object). */
    suspend fun instaQuery(url: String, threads: Boolean): String = io {
        call(if (threads) "threadsQuery" else "instaQuery", buildJsonObject { put("url", url) }.toString(), JsonObject.serializer()).toString()
    }

    suspend fun instaParse(body: String, storyPk: String?): ExtractResult = io {
        val req = buildJsonObject { put("body", body); storyPk?.let { put("storyPk", it) } }
        call("instaParse", req.toString(), ExtractResult.serializer())
    }

    suspend fun threadsParse(body: String, shortcode: String?): ExtractResult = io {
        val req = buildJsonObject { put("body", body); shortcode?.let { put("shortcode", it) } }
        call("threadsParse", req.toString(), ExtractResult.serializer())
    }

    fun version(): String = runCatching { call("version", "{}", JsonPrimitive.serializer()).content }.getOrDefault("?")
}
