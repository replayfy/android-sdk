package com.replayfy.android.internal.network

import com.replayfy.android.ReplayConfig
import com.replayfy.android.internal.NetworkEventData
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.util.UUID

/**
 * OkHttp interceptor that captures every request flowing through
 * the client it's installed on. Customer wires via:
 *
 * ```
 * OkHttpClient.Builder()
 *     .addInterceptor(Replay.networkInterceptor())
 *     .build()
 * ```
 *
 * Unlike iOS where URLProtocol.registerClass auto-installs on
 * URLSession.shared, Android has no equivalent auto-install path
 * for OkHttp (it's a userland library, not a system framework).
 * Customer's explicit install is the standard pattern — every
 * popular Android library that wraps OkHttp (logging interceptor,
 * Stetho, Sentry, Chucker) does the same.
 *
 * Two emit gates:
 *   1. [emit] closure — set by LegacyCore on install. Null → drop
 *      events silently (SDK not initialised yet, or stopped).
 *   2. [enabled] — reflects ReplayConfig.captureNetwork. Customer
 *      can flip mid-session via remote config; we re-read on every
 *      request.
 *
 * Captured fields match the web SDK + iOS shapes so the dashboard's
 * Network panel renders identically across platforms.
 */
class ReplayInterceptor internal constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        // Fast path — if we're disabled or not wired, do no work.
        val emit = currentEmit
        if (emit == null || !enabled) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val requestId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()

        // Body redacted on URL match — drop the whole event. The
        // URL itself may carry tokens in path/query, so we don't
        // ship a sanitised URL either; matches the web SDK
        // semantics.
        val url = request.url.toString()
        if (isRedacted(url)) {
            return chain.proceed(request)
        }

        val response: Response = try {
            chain.proceed(request)
        } catch (t: Throwable) {
            // Network failure — still emit so the dashboard shows
            // the failed request alongside the successful ones.
            val endedAt = System.currentTimeMillis()
            emit(NetworkEventData(
                requestId = requestId,
                transport = "okhttp",
                method = request.method,
                url = url,
                statusCode = null,
                startedAt = startedAt,
                endedAt = endedAt,
                durationMs = endedAt - startedAt,
                ok = false,
                requestHeaders = if (captureHeaders) headersToMap(request.headers) else null,
                responseHeaders = null,
                requestBody = bodyString(request),
                responseBody = null,
                error = t.message ?: t.javaClass.simpleName,
            ))
            throw t
        }

        val endedAt = System.currentTimeMillis()
        val statusCode = response.code
        val ok = statusCode in 200..299

        // Response body capture — OkHttp's Response.body is a
        // ResponseBody backed by a streaming source. peekBody() reads
        // up to N bytes WITHOUT consuming the body, so the customer's
        // downstream code still gets full body.
        val responseBody: String? = if (response.body == null) null
        else try {
            val peeked = response.peekBody(maxBodyBytes.toLong())
            peeked.string().takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }

        emit(NetworkEventData(
            requestId = requestId,
            transport = "okhttp",
            method = request.method,
            url = url,
            statusCode = statusCode,
            startedAt = startedAt,
            endedAt = endedAt,
            durationMs = endedAt - startedAt,
            ok = ok,
            requestHeaders = if (captureHeaders) headersToMap(request.headers) else null,
            responseHeaders = if (captureHeaders) headersToMap(response.headers) else null,
            requestBody = bodyString(request),
            responseBody = responseBody,
            error = null,
        ))

        return response
    }

    /** Extract request body to a string. OkHttp's RequestBody is
     *  one-shot streamed; we buffer it into an okio.Buffer, then
     *  let OkHttp re-read because Buffer is re-readable. Caller
     *  doesn't see any difference. */
    private fun bodyString(request: okhttp3.Request): String? {
        val body = request.body ?: return null
        if (body.contentLength() == 0L) return null
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val cap = maxBodyBytes.toLong()
            val truncated = buffer.size > cap
            val str = buffer.readString(
                byteCount = minOf(buffer.size, cap),
                charset = Charsets.UTF_8,
            )
            if (truncated) "$str…[truncated]" else str.takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun headersToMap(headers: okhttp3.Headers): Map<String, String>? {
        if (headers.size == 0) return null
        val out = LinkedHashMap<String, String>(headers.size)
        for (i in 0 until headers.size) {
            out[headers.name(i)] = headers.value(i)
        }
        return out
    }

    private fun isRedacted(url: String): Boolean {
        for (pattern in redactPatterns) {
            if (pattern.containsMatchIn(url)) return true
        }
        return false
    }

    internal companion object {
        // Wired by LegacyCore on init. Volatile reads on every
        // request keep mid-session config changes effective.
        @Volatile internal var currentEmit: ((NetworkEventData) -> Unit)? = null
        @Volatile internal var enabled: Boolean = false
        @Volatile internal var captureHeaders: Boolean = false
        @Volatile internal var maxBodyBytes: Int = 4_096
        @Volatile internal var redactPatterns: List<Regex> = emptyList()

        /** Install settings from ReplayConfig. Called from
         *  LegacyCore.init when an interceptor instance exists in
         *  the customer's OkHttpClient. */
        internal fun configure(config: ReplayConfig) {
            enabled = config.captureNetwork
            captureHeaders = config.captureHeaders
            maxBodyBytes = config.maxBodyBytes
            // redactPatterns sourced from ReplayConfig (future
            // field, mirroring the web SDK's redactUrls). Empty
            // for now.
            redactPatterns = emptyList()
        }
    }
}
