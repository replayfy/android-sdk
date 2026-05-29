package com.replayfy.example

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.replayfy.android.Replay
import com.replayfy.android.ReplayConfig

/**
 * End-to-end smoke-test screen for the Replay Android SDK.
 *
 * Validation surfaces:
 *
 *   1. "Tap me" button — fires TapEvents the SDK records.
 *
 *   2. SurfaceView #1 (MAGENTA→CYAN→YELLOW gradient) — proves the
 *      per-SurfaceView PixelCopy composite captures hardware-
 *      composited content. With the bare Window PixelCopy path, the
 *      region would render as transparent (hole-punched).
 *
 *   3. SurfaceView #2 (RED→GREEN→BLUE gradient) — marked with
 *      Replay.addPrivacyView. The captured PNG should show the
 *      diagonal-stripe occlusion overlay OVER the gradient region,
 *      proving the privacy overlay sits on top of the PixelCopy
 *      result.
 *
 *   4. TextureView (ORANGE→PURPLE→TEAL gradient) — TextureView
 *      renders into the View tree's main surface via the texture
 *      pipeline, so the bare Window PixelCopy SHOULD capture it
 *      without needing per-View extras. This validates that path.
 *
 *   5. "Trigger ANR" button — blocks main with SystemClock.sleep(7s).
 *      The AnrWatchdog should detect within ~5s and emit anr_ms.
 *
 *   6. EditText marked sensitive — taps + snapshot occlude content.
 *
 * Wrapped in a ScrollView so everything fits on Pixel 6-class screens
 * without losing the bottom widgets.
 *
 * Watch the SDK via:
 *   adb logcat -s ReplaySdk:V ReplayExample:V *:S
 */
class MainActivity : AppCompatActivity() {

    private var tapCount = 0
    private lateinit var tapLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SDK boot. apiHost points at host machine's loopback as seen
        // from the emulator — `10.0.2.2` is the emulator-NAT alias
        // for 127.0.0.1 on the host. Port 4000 is the local
        // ingest-api; the dashboard runs at :5180 and reads from the
        // same backing store.
        Replay.init(
            this,
            ReplayConfig(
                apiKey = "rpl_pk_97f6b1ce7235126b0cc017716e81fe12849e",
                apiHost = "http://10.0.2.2:4000",
                captureSnapshotPixels = true,
            ),
        )
        Log.i(TAG, "Replay.init dispatched — see ReplaySdk-tagged logs from here")

        // identify + track validation — exercise the public-API
        // surface in the example app so the smoke test proves both
        // round-trip to the dashboard. identify happens once at
        // launch; track fires on every tap (see button handler).
        Replay.identify(
            distinctId = "smoke-test-android-user",
            properties = mapOf(
                "plan" to "free",
                "device_class" to "test-emulator",
            ),
        )
        Replay.track(
            name = "example_app_launched",
            properties = mapOf("platform" to "android"),
        )
        // Console capture validation — println goes through STDOUT
        // (which ConsoleCapture intercepts). Log.i is also captured
        // when the customer uses Replay.log() bridge.
        println("[smoke-test] stdout println captured by ConsoleCapture")
        Replay.log("warn", "[smoke-test] explicit Replay.log(\"warn\") call")

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        // --- 1. tap probes ---------------------------------------
        tapLabel = TextView(this).apply {
            text = "Taps recorded: 0"
            textSize = 18f
        }
        column.addView(tapLabel)

        column.addView(
            Button(this).apply {
                text = "Tap me (normal button)"
                id = android.R.id.button1
                setOnClickListener {
                    tapCount++
                    tapLabel.text = "Taps recorded: $tapCount"
                    // Fire a track event per click so the dashboard's
                    // event timeline has something custom to filter
                    // on.
                    Replay.track(
                        name = "tap_me_clicked",
                        properties = mapOf("count" to tapCount),
                    )
                    Replay.log("info", "[smoke-test] tap_me_clicked count=$tapCount")
                }
            },
        )

        // --- 2. SurfaceView #1: should be captured ---------------
        column.addView(
            TextView(this).apply {
                text = "SurfaceView #1 (MAGENTA→CYAN→YELLOW) — should appear in the captured PNG"
                setPadding(0, 32, 0, 8)
            },
        )
        column.addView(
            GradientSurfaceView(
                this,
                intArrayOf(Color.MAGENTA, Color.CYAN, Color.YELLOW),
            ).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 180)
            },
        )

        // --- 3. SurfaceView #2: should be occluded ---------------
        column.addView(
            TextView(this).apply {
                text = "SurfaceView #2 (RED→GREEN→BLUE) — marked sensitive, should show diagonal-stripe overlay"
                setPadding(0, 32, 0, 8)
            },
        )
        val occludedSurface = GradientSurfaceView(
            this,
            intArrayOf(Color.RED, Color.GREEN, Color.BLUE),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 180)
        }
        column.addView(occludedSurface)
        // Mark sensitive AFTER the view is in the tree so the
        // registry's ancestor-walk lookup actually reaches it.
        Replay.addPrivacyView(occludedSurface)

        // --- 4. TextureView (separate render path) ---------------
        column.addView(
            TextView(this).apply {
                text = "TextureView (ORANGE→PURPLE→TEAL) — bare Window PixelCopy should already see this"
                setPadding(0, 32, 0, 8)
            },
        )
        column.addView(
            GradientTextureView(
                this,
                intArrayOf(
                    Color.argb(255, 255, 165, 0),    // orange
                    Color.argb(255, 138, 43, 226),   // blue-violet (acts as purple)
                    Color.argb(255, 0, 128, 128),    // teal
                ),
            ).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 180)
            },
        )

        // --- 5. ANR trigger --------------------------------------
        column.addView(
            Button(this).apply {
                text = "Trigger ANR (sleeps main 7s — watchdog should flag at ~5s)"
                setOnClickListener {
                    Log.w(TAG, "About to block main for 7s — expect anr_ms event from watchdog")
                    SystemClock.sleep(7_000)
                    Log.w(TAG, "Main unblocked")
                }
            },
        )

        // --- 6. Privacy text field -------------------------------
        column.addView(
            TextView(this).apply {
                text = "This field is marked sensitive — taps + snapshots should occlude it:"
                setPadding(0, 32, 0, 8)
            },
        )
        val secret = EditText(this).apply {
            hint = "Pretend this is a credit card #"
        }
        column.addView(secret)
        Replay.addPrivacyView(secret)

        // ScrollView root so multi-SurfaceView + TextureView all fit
        // even on shorter screens / when Pixel 6 emulator has system
        // bars eating into the visible area.
        val scroll = ScrollView(this).apply { addView(column) }
        setContentView(scroll)
    }

    /**
     * SurfaceView painted with a diagonal gradient. Lives on the
     * hardware compositor — invisible to view.draw(Canvas), invisible
     * to bare Window PixelCopy (hole-punched), visible only via
     * PixelCopy.request(SurfaceView, ...).
     */
    private class GradientSurfaceView(
        context: android.content.Context,
        private val colors: IntArray,
    ) : SurfaceView(context), SurfaceHolder.Callback {

        init { holder.addCallback(this) }

        override fun surfaceCreated(holder: SurfaceHolder) {
            val canvas = holder.lockCanvas() ?: return
            try {
                val paint = Paint().apply {
                    shader = LinearGradient(
                        0f, 0f,
                        canvas.width.toFloat(), canvas.height.toFloat(),
                        colors,
                        null,
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawPaint(paint)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
        override fun surfaceDestroyed(holder: SurfaceHolder) {}
    }

    /**
     * TextureView painted with a diagonal gradient. TextureView
     * differs from SurfaceView: its content renders into a hardware
     * texture that gets composited into the parent View's surface,
     * so the bare Window PixelCopy SHOULD capture it without any
     * per-view fallback. This widget exists to PROVE that.
     */
    private class GradientTextureView(
        context: android.content.Context,
        private val colors: IntArray,
    ) : TextureView(context), TextureView.SurfaceTextureListener {

        init { surfaceTextureListener = this }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
            val sfc = Surface(surface)
            try {
                val canvas = sfc.lockCanvas(null) ?: return
                try {
                    val paint = Paint().apply {
                        shader = LinearGradient(
                            0f, 0f, w.toFloat(), h.toFloat(),
                            colors, null, Shader.TileMode.CLAMP,
                        )
                    }
                    canvas.drawPaint(paint)
                } finally {
                    sfc.unlockCanvasAndPost(canvas)
                }
            } finally {
                sfc.release()
            }
        }
        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
    }

    companion object {
        private const val TAG = "ReplayExample"
    }
}
