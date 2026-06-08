package com.replayfy.android.internal

import com.replayfy.android.ReplayConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * POSTs envelopes to /v1/replay/batch.
 *
 * Uses OkHttp because it's the de-facto Android HTTP client and we
 * already require it as a dep (we'll add the OkHttp Interceptor for
 * network capture in a follow-up). Plain `java.net.HttpURLConnection`
 * would have worked but OkHttp gives us connection pooling, gzip,
 * retry budget, and HTTP/2 for free.
 *
 * JSON is serialized via `org.json` (which is part of Android's stdlib)
 * to avoid adding kotlinx-serialization or Moshi to the dep graph just
 * for one POST endpoint. Hand-rolled, but the schema is small (~7
 * fields + a list of events) so the serializer is straightforward.
 *
 * Errors are swallowed — failed uploads will be retried via WorkManager
 * once that lands. For now, the in-memory batch is just lost on network
 * failure, which is fine for the foundation commit (the dashboard
 * shows session_start; if subsequent batches fail, the session is
 * still visible as "started but no events").
 */
internal class BatchSender(
    private val config: ReplayConfig,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** Currently-known identity, attached to every outgoing batch. */
    @Volatile
    var identity: IdentifyPayload? = null

    /**
     * Synchronous send. Caller (the upload pipeline) is expected to
     * invoke this from a coroutine on [Dispatchers.IO].
     *
     * Returns true if the server accepted the batch (HTTP 2xx),
     * false otherwise. The persistent-queue layer
     * ([com.replayfy.android.internal.upload.BatchUploader]) catches
     * the false return and stages the JSON to disk.
     */
    fun send(envelope: ReplayBatchEnvelope): Boolean {
        val json = serializeRequest(envelope, identity).toString()
        return sendJson(
            json = json,
            sdkName = envelope.sdk.name,
            sdkVersion = envelope.sdk.version,
        )
    }

    /**
     * Sends a pre-serialized JSON body. Used by the WorkManager
     * worker that drains the on-disk queue at next app launch — the
     * worker reads bytes verbatim from disk without re-deserializing
     * to a typed [ReplayBatchEnvelope].
     *
     * Identity is whatever was baked into the JSON at write time;
     * we don't re-attach the current process's identity (would
     * mis-attribute crash sessions to the next user).
     */
    fun sendJson(json: String, sdkName: String, sdkVersion: String): Boolean {
        return try {
            // gzip the body before sending. Typical event-mix batches
            // compress 5-10× — saves bandwidth on cellular + reduces
            // ingest billable bytes. Express's `json()` middleware
            // auto-inflates when Content-Encoding: gzip is set
            // (inflate:true default), so no backend change needed.
            // the reference mobile SDK bundles minizip on iOS for the same purpose;
            // Android they use OkHttp's gzip request interceptor
            // pattern.
            //
            // Tiny payloads (<256 bytes) we send uncompressed — the
            // gzip header alone is ~20 bytes + dictionary state, so
            // for very small bodies compression actually inflates.
            val raw = json.toByteArray(Charsets.UTF_8)
            val builder = Request.Builder()
                .url(joinUrl(config.apiHost, "/v1/replay/batch"))
                .header("x-replay-api-key", config.apiKey)
                .header("user-agent", "$sdkName/$sdkVersion")
            if (raw.size >= GZIP_THRESHOLD_BYTES) {
                val gzipped = gzip(raw)
                builder.header("content-encoding", "gzip")
                builder.post(gzipped.toRequestBody(JSON))
            } else {
                builder.post(raw.toRequestBody(JSON))
            }
            client.newCall(builder.build()).execute().use { response ->
                response.isSuccessful
            }
        } catch (t: Throwable) {
            android.util.Log.v(TAG, "batch send failed: ${t.message}")
            false
        }
    }

    /** Public for the queue layer — serialize without sending so the
     *  queue can write the body to disk before attempting the live
     *  POST. Identity attached at serialize time, frozen in the
     *  stored bytes. */
    fun serialize(envelope: ReplayBatchEnvelope): String {
        return serializeRequest(envelope, identity).toString()
    }

    private fun serializeRequest(
        envelope: ReplayBatchEnvelope,
        identity: IdentifyPayload?,
    ): JSONObject {
        val root = JSONObject()
        root.put("envelope", envelopeToJson(envelope))
        if (identity != null) {
            root.put("identify", identifyToJson(identity))
        }
        return root
    }

    private fun envelopeToJson(env: ReplayBatchEnvelope): JSONObject {
        val obj = JSONObject()
        obj.put("sessionId", env.sessionId)
        obj.put("segmentId", env.segmentId)
        obj.put("sequence", env.sequence)
        obj.put("sentAt", env.sentAt)
        obj.put("sdk", JSONObject().apply {
            put("name", env.sdk.name)
            put("version", env.sdk.version)
            put("platform", env.sdk.platform)
        })
        obj.put("page", JSONObject().apply {
            put("url", env.page.url)
            put("userAgent", env.page.userAgent)
            put("viewport", JSONObject().apply {
                put("width", env.page.viewport.width)
                put("height", env.page.viewport.height)
            })
            env.page.title?.let { put("title", it) }
            env.page.referrer?.let { put("referrer", it) }
            env.page.timezone?.let { put("timezone", it) }
            env.page.language?.let { put("language", it) }
        })
        env.projectId?.let { obj.put("projectId", it) }
        val events = JSONArray()
        for (ev in env.events) {
            events.put(eventToJson(ev))
        }
        obj.put("events", events)
        return obj
    }

    private fun eventToJson(ev: ReplayEvent): JSONObject {
        val obj = JSONObject()
        obj.put("id", ev.id)
        obj.put("ts", ev.ts)
        obj.put("offsetMs", ev.offsetMs)
        obj.put("type", ev.type)
        obj.put("source", ev.source)
        obj.put("data", toJsonValue(ev.data))
        return obj
    }

    private fun identifyToJson(p: IdentifyPayload): JSONObject {
        val obj = JSONObject()
        obj.put("distinctId", p.distinctId)
        p.email?.let { obj.put("email", it) }
        p.name?.let { obj.put("name", it) }
        p.plan?.let { obj.put("plan", it) }
        p.customProps?.let { obj.put("customProps", mapToJson(it)) }
        return obj
    }

    /**
     * Recursive JSON encoder for [ReplayEvent.data] payloads. Handles
     * data-class instances by reflection over their public properties,
     * Maps + Lists by recursive descent, primitives directly. JSONObject
     * handles null + Number + Boolean + String natively.
     */
    private fun toJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is JSONObject, is JSONArray -> value
            is Number, is Boolean, is String -> value
            is Map<*, *> -> mapToJson(value)
            is Iterable<*> -> {
                val arr = JSONArray()
                for (v in value) arr.put(toJsonValue(v))
                arr
            }
            else -> dataClassToJson(value)
        }
    }

    private fun mapToJson(m: Map<*, *>): JSONObject {
        val obj = JSONObject()
        for ((k, v) in m) {
            if (k == null) continue
            obj.put(k.toString(), toJsonValue(v))
        }
        return obj
    }

    /**
     * Encodes a Kotlin data class by enumerating its declared
     * properties via reflection. Slow per-event (microseconds) but
     * fine for the foundation; the snapshot pipeline lands later with
     * a hand-rolled serializer for the hot path.
     */
    private fun dataClassToJson(obj: Any): JSONObject {
        val out = JSONObject()
        for (field in obj.javaClass.declaredFields) {
            if (field.isSynthetic) continue
            field.isAccessible = true
            val name = field.name
            val v = field.get(obj)
            if (v != null) out.put(name, toJsonValue(v))
        }
        return out
    }

    private fun joinUrl(host: String, path: String): String {
        val h = host.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return h + p
    }

    /** gzip-compress the bytes via java.util.zip (no external dep).
     *  ByteArrayOutputStream + GZIPOutputStream is the canonical
     *  pattern + handles the gzip wrapper + checksum automatically. */
    private fun gzip(input: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream(input.size / 4)  // optimistic pre-size
        GZIPOutputStream(baos).use { it.write(input) }
        return baos.toByteArray()
    }

    companion object {
        private const val TAG = "ReplaySdk"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        /** Below this size, gzip overhead (20+ bytes header + dict
         *  state) outweighs the compression win — send raw. Most
         *  session_start-only batches are well above this. */
        private const val GZIP_THRESHOLD_BYTES = 256
    }
}
