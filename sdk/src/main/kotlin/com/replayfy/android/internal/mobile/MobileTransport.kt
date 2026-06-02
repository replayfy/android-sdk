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
    // Dashboard-controlled capture toggles (server-authoritative).
    val captureConsole: Boolean = true,
    val captureNetwork: Boolean = true,
)

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
class MobileTransport(host: String, private val projectKey: String) {
    private val baseURL = host.trimEnd('/')
    @Volatile var token: String? = null
        private set

    fun start(params: JSONObject): MobileStartResponse? {
        val body = params.toString().toByteArray(Charsets.UTF_8)
        val resp = post("/v1/mobile/start", body, json = true, bearer = false) ?: return null
        return try {
            // Response envelope: { ok, data: {...}, meta }
            val data = JSONObject(String(resp, Charsets.UTF_8)).getJSONObject("data")
            val parsed = MobileStartResponse(
                token = data.getString("token"),
                sessionID = data.getString("sessionID"),
                fps = data.getInt("fps"),
                quality = data.getString("quality"),
                framesSupport = data.optBoolean("framesSupport", true),
                projectID = data.optString("projectID", ""),
                userUUID = data.optString("userUUID", ""),
                captureConsole = data.optBoolean("captureConsole", true),
                captureNetwork = data.optBoolean("captureNetwork", true),
            )
            token = parsed.token
            parsed
        } catch (e: Exception) {
            null
        }
    }

    fun sendMessages(gzippedBatch: ByteArray): Boolean =
        post("/v1/mobile/i", gzippedBatch, json = false, bearer = true) != null

    fun sendLateMessages(batch: ByteArray): Boolean =
        post("/v1/mobile/late", batch, json = false, bearer = true) != null

    fun sendImages(gzippedArchive: ByteArray): Boolean =
        post("/v1/mobile/images", gzippedArchive, json = false, bearer = true) != null

    private fun post(path: String, body: ByteArray, json: Boolean, bearer: Boolean): ByteArray? {
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
                if (bearer) token?.let { setRequestProperty("Authorization", "Bearer $it") }
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
