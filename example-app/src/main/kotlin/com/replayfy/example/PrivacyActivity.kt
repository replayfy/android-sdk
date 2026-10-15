package com.replayfy.example

import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.replayfy.android.Replay
import com.replayfy.android.ReplayConfig
import com.replayfy.android.ReplayMaskStyle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Privacy-masking screen for scripts/e2e-privacy.sh: every kind of field the
 * SDK masks with no setup, a plain control, a privacy view scaled 1.5x, a
 * WebView page with a password input, and a card field at the bottom that
 * pans the window (adjustPan) when the keyboard opens for it.
 *
 * Every 250ms it logs where each of them shows in a recorded frame (the
 * window's surface, in px) and on screen (for adb taps):
 *
 *   ReplayE2E: REPLAY_E2E_RECTS {"at":<epoch ms>,"pan":<px>,"frame":{name:[l,t,r,b]},"screen":{...}}
 *
 * Test extras (adb shell am start ... ):
 *   --ez REPLAY_MASK_OVERLAY true     → solid masks (the default)
 *   --ez REPLAY_MASK_BLUR true        → global BLUR: auto-masked fields must stay solid
 *   --ei REPLAY_SECURE_AFTER_SECS 20  → set FLAG_SECURE after N s (frames go black)
 *   --es REPLAY_AUTOCRASH jvm         → crash after REPLAY_CRASH_AFTER_SECS (default 30) s,
 *                                       which flushes the session through /late
 */
class PrivacyActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val tracked = LinkedHashMap<String, View>()
    private lateinit var web: WebView
    /** The page's password and text inputs, in CSS px, from the page itself. */
    @Volatile private var webBoxes: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Replay.init(
            this,
            ReplayConfig(
                apiKey = "rpl_pk_ef7e2fc8c7f952bcd0a69466e1a42a0625f9",
                apiHost = "http://10.0.2.2:4000",
                captureSnapshotPixels = true,
            ),
        )
        if (intent.getBooleanExtra("REPLAY_MASK_BLUR", false)) Replay.setMaskStyle(ReplayMaskStyle.BLUR)
        if (intent.getBooleanExtra("REPLAY_MASK_OVERLAY", false)) Replay.setMaskStyle(ReplayMaskStyle.OVERLAY)
        Replay.tagScreenName("Privacy")

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        fun add(name: String?, v: View, h: Int = 44) {
            column.addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)).also { it.topMargin = dp(6) })
            if (name != null) tracked[name] = v
        }
        fun field(name: String, setup: EditText.() -> Unit) = EditText(this).apply {
            setBackgroundColor(Color.YELLOW)
            setTextColor(Color.BLACK)
            hint = name
            setup()
            add(name, this)
        }
        add(null, TextView(this).apply { text = "PRIVACY E2E"; setTextColor(Color.BLACK) }, 28)
        field("password") { inputType = 0x80081 } // textPassword|textNoSuggestions
        field("numberPassword") { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD }
        field("transformation") {
            inputType = InputType.TYPE_CLASS_TEXT
            transformationMethod = PasswordTransformationMethod.getInstance()
            setText("hidden-by-transformation")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            field("card") { inputType = InputType.TYPE_CLASS_NUMBER; setAutofillHints("creditCardNumber"); setText("4111111111111111") }
            field("otp") { inputType = InputType.TYPE_CLASS_NUMBER; setAutofillHints("smsOTPCode"); setText("482913") }
        }
        field("control") { inputType = InputType.TYPE_CLASS_TEXT; setText("public text") }
        val scaled = TextView(this).apply {
            text = "SCALED 4111 1111"
            setBackgroundColor(Color.YELLOW)
            setTextColor(Color.BLACK)
            scaleX = 1.5f
            scaleY = 1.5f
        }
        column.addView(scaled, LinearLayout.LayoutParams(dp(160), dp(32)).also { it.topMargin = dp(14); it.leftMargin = dp(60) })
        tracked["scaled"] = scaled
        Replay.addPrivacyView(scaled)

        web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {}
        }
        column.addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).also { it.topMargin = dp(10) })
        tracked["web"] = web
        web.loadUrl("file:///android_asset/privacy.html")

        // Low on the screen: the keyboard opening for it pans the window.
        field("bottomCard") {
            inputType = InputType.TYPE_CLASS_NUMBER
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setAutofillHints("creditCardNumber")
        }
        setContentView(column)

        handler.post(object : Runnable {
            override fun run() {
                logRects()
                handler.postDelayed(this, 250)
            }
        })

        val secureAfter = intent.getIntExtra("REPLAY_SECURE_AFTER_SECS", 0)
        if (secureAfter > 0) {
            handler.postDelayed({
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                android.util.Log.i(TAG, "REPLAY_E2E_SECURE ${System.currentTimeMillis()}")
            }, secureAfter * 1000L)
        }
        val crash = intent.getStringExtra("REPLAY_AUTOCRASH")
        if (!crash.isNullOrEmpty()) {
            handler.postDelayed({
                android.util.Log.w(TAG, "REPLAY_E2E_END ${System.currentTimeMillis()}")
                throw RuntimeException("Privacy e2e auto-crash: $crash")
            }, intent.getIntExtra("REPLAY_CRASH_AFTER_SECS", 30) * 1000L)
        }
    }

    /** Log where every tracked view shows: in the frame (the window's
     *  surface, which a pan moves) and on screen. */
    private fun logRects() {
        val decor = window.decorView
        val pan = IntArray(2).also { decor.getLocationInWindow(it) }
        val frame = JSONObject()
        val screen = JSONObject()
        for ((name, v) in tracked) {
            val r = Rect()
            if (!v.isShown || !v.getGlobalVisibleRect(r)) continue
            frame.put(name, box(Rect(r).apply { offset(pan[0], pan[1]) }))
            val s = IntArray(2).also { v.getLocationOnScreen(it) }
            screen.put(name, box(Rect(s[0], s[1], s[0] + v.width, s[1] + v.height)))
        }
        // The page's own inputs: CSS px scaled to device px, from where the
        // WebView shows in the frame. The page doesn't zoom.
        webBoxes?.let { boxes ->
            val at = IntArray(2).also { web.getLocationInWindow(it) }
            val k = resources.displayMetrics.density
            for (key in boxes.keys()) {
                val b = boxes.getJSONArray(key)
                frame.put(key, JSONArray(listOf(
                    (at[0] + b.getDouble(0) * k).toInt(), (at[1] + b.getDouble(1) * k).toInt(),
                    (at[0] + b.getDouble(2) * k).toInt(), (at[1] + b.getDouble(3) * k).toInt(),
                )))
            }
        }
        web.evaluateJavascript(PAGE_BOXES) { raw ->
            webBoxes = try { JSONObject(org.json.JSONTokener(raw).nextValue() as String) } catch (e: Throwable) { null }
        }
        val line = JSONObject()
            .put("at", System.currentTimeMillis())
            .put("pan", pan[1])
            .put("frame", frame)
            .put("screen", screen)
        android.util.Log.i(TAG, "REPLAY_E2E_RECTS $line")
    }

    private fun box(r: Rect) = JSONArray(listOf(r.left, r.top, r.right, r.bottom))

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        const val TAG = "ReplayE2E"

        /** The page's password and text inputs, as CSS-px boxes relative to
         *  the WebView. Read-only, like the SDK's own scan. */
        const val PAGE_BOXES = """(function () {
  function b(id) { var r = document.getElementById(id).getBoundingClientRect(); return [r.left, r.top, r.right, r.bottom]; }
  return JSON.stringify({ webPassword: b('pw'), webText: b('tx') });
})()"""
    }
}
