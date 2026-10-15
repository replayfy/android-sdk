package com.replayfy.android.internal.mobile

import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Parsed /v1/mobile/start response. */
data class MobileStartResponse(
    val token: String,
    val sessionID: String,
    val fps: Int,
    val quality: String,
    val framesSupport: Boolean,
    val projectID: String,
    val userUUID: String,
    // Dashboard-controlled capture toggles. Null ⇒ the server didn't
    // specify, so the SDK's ReplayConfig default applies.
    val captureConsole: Boolean? = null,
    val captureNetwork: Boolean? = null,
    // Granular network toggles — strip headers/bodies on-device when disabled.
    val captureNetworkHeaders: Boolean? = null,
    val captureNetworkBodies: Boolean? = null,
    // false ⇒ the server's sampling gate dropped this session; the SDK
    // must not record. Distinct from a null response (network failure).
    val record: Boolean = true,
    // The dashboard's "always mask input values" (`privacy.occludeAllTextFields`).
    // Null ⇒ the server didn't send it, which counts as off.
    val occludeAllTextFields: Boolean? = null,
)

/**
 * The upload half of [MobileTransport] that the frame and message buffers
 * drain into. An interface so the drain/retry logic can be exercised against a
 * fake in tests. Calls are synchronous (the buffers call them from their own
 * sender thread); `true` means the server accepted the batch.
 */
interface MobileUploading {
    /** The session bearer these uploads carry. Saved next to a session's final
     *  batch so a later launch can still deliver it to the right session. */
    val token: String?
    fun sendMessages(gzippedBatch: ByteArray): Boolean
    fun sendLateMessages(batch: ByteArray): Boolean
    fun sendImages(gzippedArchive: ByteArray): Boolean
}

/**
 * HTTP transport for the mobile ingest endpoints (mirrors iOS).
 *
 *   POST {host}/v1/mobile/start   → JSON device params, returns token
 *   POST {host}/v1/mobile/i       → gzipped binary message batch
 *   POST {host}/v1/mobile/late    → un-gzipped binary message batch
 *   POST {host}/v1/mobile/images  → gzipped frames archive
 *
 * Built on HttpURLConnection to stay dependency-light. All calls are
 * synchronous; callers invoke them off the main thread.
 */
class MobileTransport(host: String, private val projectKey: String) : MobileUploading {
    private val baseURL = host.trimEnd('/')
    @Volatile override var token: String? = null
        private set

    fun start(params: JSONObject): MobileStartResponse? {
        val body = params.toString().toByteArray(Charsets.UTF_8)
        val resp = post("/v1/mobile/start", body, json = true, bearer = false) ?: return null
        return try {
            // Response envelope: { ok, data: {...}, meta }
            val data = JSONObject(String(resp, Charsets.UTF_8)).getJSONObject("data")
            val parsed = MobileStartResponse(
                token = data.optString("token", ""),
                sessionID = data.optString("sessionID", ""),
                fps = data.optInt("fps", 0),
                quality = data.optString("quality", ""),
                framesSupport = data.optBoolean("framesSupport", true),
                projectID = data.optString("projectID", ""),
                userUUID = data.optString("userUUID", ""),
                captureConsole = if (data.has("captureConsole")) data.getBoolean("captureConsole") else null,
                captureNetwork = if (data.has("captureNetwork")) data.getBoolean("captureNetwork") else null,
                captureNetworkHeaders = if (data.has("captureNetworkHeaders")) data.getBoolean("captureNetworkHeaders") else null,
                captureNetworkBodies = if (data.has("captureNetworkBodies")) data.getBoolean("captureNetworkBodies") else null,
                // false ⇒ server sampling gate dropped this launch.
                record = data.optBoolean("record", true),
                occludeAllTextFields = data.optJSONObject("privacy")?.let {
                    if (it.has("occludeAllTextFields")) it.optBoolean("occludeAllTextFields") else null
                },
            )
            token = parsed.token
            parsed
        } catch (e: Exception) {
            null
        }
    }

    override fun sendMessages(gzippedBatch: ByteArray): Boolean =
        post("/v1/mobile/i", gzippedBatch, json = false, bearer = true) != null

    override fun sendLateMessages(batch: ByteArray): Boolean =
        post("/v1/mobile/late", batch, json = false, bearer = true) != null

    /** A raw batch an earlier launch left on disk, under the token of the
     *  session it belongs to (not this transport's session). Sent to /i, not
     *  /late: the server has normally ended that session on its idle timer by
     *  now, and /late would end it again, rebuilding its frames archive from a
     *  stream the server no longer keeps (an empty replay over the real one). */
    fun sendSavedMessages(batch: ByteArray, token: String): Boolean =
        post("/v1/mobile/i", MobileFrames.gzip(batch), json = false, bearer = true, bearerToken = token) != null

    override fun sendImages(gzippedArchive: ByteArray): Boolean =
        post("/v1/mobile/images", gzippedArchive, json = false, bearer = true) != null

    /** Best-effort "drop this session" signal — POST /v1/mobile/cancel with the
     *  session bearer token and a JSON `{ sessionId }`. Fire-and-forget:
     *  cancelSession tears down locally regardless of the outcome. */
    fun cancelSession(sessionId: String): Boolean =
        post(
            "/v1/mobile/cancel",
            JSONObject().put("sessionId", sessionId).toString().toByteArray(),
            json = true,
            bearer = true,
        ) != null

    private fun post(
        path: String,
        body: ByteArray,
        json: Boolean,
        bearer: Boolean,
        bearerToken: String? = token,
    ): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(baseURL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty(
                    "Content-Type",
                    if (json) "application/json" else "application/octet-stream",
                )
                if (bearer) bearerToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            }
            DataOutputStream(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..299) conn.inputStream.readBytes() else null
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
