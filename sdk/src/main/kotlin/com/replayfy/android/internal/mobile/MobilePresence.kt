package com.replayfy.android.internal.mobile

import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject

/**
 * Live-presence socket — a faithful port of the web SDK's `presence.ts`.
 *
 * Opens a Socket.IO connection authenticated as `role="sdk"` with the same
 * project key + session id the HTTP transport uses. The backend `LiveGateway`
 * marks the session's EndUser online on connect and offline ~10s after the
 * socket drops (app background / terminate / network loss), which is how the
 * dashboard's "active users" list stays accurate for mobile.
 *
 * Entirely best-effort: any failure (missing lib, bad URI, offline) is
 * swallowed so presence can never affect capture or crash the host app.
 */
internal class MobilePresence {
    private var socket: Socket? = null
    // Held by reference so updateDistinctId() mutates the same map the
    // Manager re-sends on each reconnect (matches presence.ts).
    private val auth = HashMap<String, String>()

    fun start(host: String?, projectKey: String?, sessionId: String, distinctId: String?) {
        if (host.isNullOrBlank() || projectKey.isNullOrBlank()) return
        stop()
        auth["role"] = "sdk"
        auth["apiKey"] = projectKey
        auth["sessionId"] = sessionId
        if (!distinctId.isNullOrBlank()) auth["distinctId"] = distinctId
        try {
            val opts = IO.Options()
            opts.path = "/socket.io/"
            // Default transports (polling → upgrade to websocket) match the web
            // SDK and negotiate up where a direct websocket would be blocked.
            opts.reconnection = true
            opts.reconnectionDelay = 1_000
            opts.reconnectionDelayMax = 8_000
            opts.auth = auth
            val s = IO.socket(host, opts)
            socket = s
            s.connect()
        } catch (_: Throwable) {
            // Best-effort — presence never surfaces to the host app.
        }
    }

    /** identify() after the socket is up: refresh the auth used on the next
     *  reconnect and nudge the server. */
    fun updateDistinctId(id: String) {
        if (id.isBlank()) return
        auth["distinctId"] = id
        try {
            socket?.emit("sdk:identify", JSONObject().put("distinctId", id))
        } catch (_: Throwable) {
            /* ignore */
        }
    }

    fun stop() {
        socket?.let {
            try { it.disconnect() } catch (_: Throwable) {}
            try { it.off() } catch (_: Throwable) {}
        }
        socket = null
    }
}
