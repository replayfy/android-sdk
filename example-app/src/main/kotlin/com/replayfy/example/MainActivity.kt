package com.replayfy.example

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.replayfy.android.Replay
import com.replayfy.android.ReplayConfig

/**
 * End-to-end smoke-test screen for the Replay Android SDK.
 *
 * Boots the SDK in onCreate (zero-config bootstrap also auto-fires
 * via ReplayContentProvider, but explicit Replay.start ensures the
 * config we want — including ingestUrl pointing at the host machine
 * — actually lands).
 *
 * The screen exposes four validation surfaces:
 *
 *   1. "Tap me" buttons — fires TapEvents the SDK records.
 *      Logcat should show "tap recorded" entries.
 *
 *   2. SurfaceView gradient — proves PixelCopy is wired. With the
 *      Legacy capture path, the SurfaceView region would render as
 *      solid black in the captured PNG. With PixelCopy, the gradient
 *      shows up. The dashboard's session player renders both paths
 *      via the same imageRef, so this is a visual diff.
 *
 *   3. "Trigger ANR" button — blocks main with Thread.sleep(7s).
 *      The AnrWatchdog should detect it within ~5s (its default
 *      threshold) and emit an anr_ms perf event. Logcat shows the
 *      captured main-thread stack.
 *
 *   4. Privacy field — wrapped with Replay.addPrivacyView so its
 *      content never reaches the dashboard. Captured snapshot
 *      shows the diagonal-stripe occlusion overlay over the field;
 *      taps inside the field ship as isSensitive=true with
 *      metadata blanked.
 *
 * Watch the SDK via:
 *   adb logcat -s ReplaySdk:V *:S
 */
class MainActivity : AppCompatActivity() {

    private var tapCount = 0
    private lateinit var tapLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SDK boot. ingestUrl points at host machine's loopback as
        // seen from the emulator — `10.0.2.2` is the emulator-NAT
        // alias for 127.0.0.1 on the host. The api key is a literal
        // string; the host's ingest service should accept any key
        // in dev mode.
        Replay.init(
            this,
            ReplayConfig(
                apiKey = "smoke-test-key",
                apiHost = "http://10.0.2.2:3001",
                captureSnapshotPixels = true,
            ),
        )
        Log.i(TAG, "Replay.init dispatched — see ReplaySdk-tagged logs from here")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        // --- 1. tap probes ---------------------------------------
        tapLabel = TextView(this).apply {
            text = "Taps recorded: 0"
            textSize = 18f
        }
        root.addView(tapLabel)

        root.addView(
            Button(this).apply {
                text = "Tap me (normal button)"
                id = android.R.id.button1
                setOnClickListener {
                    tapCount++
                    tapLabel.text = "Taps recorded: $tapCount"
                }
            },
        )

        // --- 2. SurfaceView gradient (PixelCopy validator) -------
        root.addView(
            TextView(this).apply {
                text = "Gradient below is a SurfaceView — PixelCopy should capture it; Legacy would paint it black:"
                setPadding(0, 32, 0, 8)
            },
        )
        root.addView(
            GradientSurfaceView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    240,
                )
            },
        )

        // --- 3. ANR trigger --------------------------------------
        root.addView(
            Button(this).apply {
                text = "Trigger ANR (sleeps main 7s — watchdog should flag at ~5s)"
                setOnClickListener {
                    Log.w(TAG, "About to block main for 7s — expect anr_ms event from watchdog")
                    SystemClock.sleep(7_000)
                    Log.w(TAG, "Main unblocked")
                }
            },
        )

        // --- 4. Privacy field ------------------------------------
        root.addView(
            TextView(this).apply {
                text = "This field is marked sensitive — taps + snapshots should occlude it:"
                setPadding(0, 32, 0, 8)
            },
        )
        val secret = EditText(this).apply {
            hint = "Pretend this is a credit card #"
        }
        root.addView(secret)
        // Mark sensitive AFTER the view is in the tree so the
        // registry's ancestor-walk lookup actually reaches it. (The
        // PrivacyRegistry holds a WeakReference; the host LinearLayout
        // keeps the EditText alive for the activity's lifetime.)
        Replay.addPrivacyView(secret)

        setContentView(root)
    }

    /**
     * A SurfaceView painted with a diagonal gradient. Lives on the
     * hardware compositor — invisible to view.draw(Canvas), visible
     * to PixelCopy. The whole point of including this in the smoke
     * test is to prove the new capture pipeline actually sees it.
     */
    private class GradientSurfaceView(context: android.content.Context) :
        SurfaceView(context), SurfaceHolder.Callback {

        init { holder.addCallback(this) }

        override fun surfaceCreated(holder: SurfaceHolder) {
            val canvas = holder.lockCanvas() ?: return
            try {
                val paint = Paint().apply {
                    shader = LinearGradient(
                        0f, 0f,
                        canvas.width.toFloat(), canvas.height.toFloat(),
                        intArrayOf(Color.MAGENTA, Color.CYAN, Color.YELLOW),
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

    companion object {
        private const val TAG = "ReplayExample"
    }
}
