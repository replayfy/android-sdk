package com.replayfy.example

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.replayfy.android.Replay
import com.replayfy.android.ReplayConfig

/**
 * Launcher / menu screen for the Tic-Tac-Toe replay demo.
 *
 * Boots the SDK, then auto-navigates into [GameActivity] after a short
 * beat (simulating a user tapping "play"). The point of this example is
 * to produce a recording with real screen transitions + lots of visual
 * change — a menu, then a board that fills with moves across several
 * rounds — rather than a single static screen.
 */
class MenuActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SDK boot. apiHost = host loopback as seen from the emulator.
        Replay.init(
            this,
            ReplayConfig(
                apiKey = "rpl_pk_ef7e2fc8c7f952bcd0a69466e1a42a0625f9",
                apiHost = "http://10.0.2.2:4000",
                captureSnapshotPixels = true,
            ),
        )
        Replay.identify(
            distinctId = "tic-tac-toe-player",
            properties = mapOf("plan" to "free", "demo" to "tic-tac-toe"),
        )
        Replay.tagScreenName("Menu")
        Replay.track("app_opened", mapOf("game" to "tic-tac-toe"))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(64, 64, 64, 64)
        }
        root.addView(
            TextView(this).apply {
                text = "TIC-TAC-TOE"
                textSize = 42f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            },
        )
        root.addView(
            TextView(this).apply {
                text = "auto-play demo"
                textSize = 18f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 72)
            },
        )
        root.addView(
            Button(this).apply {
                text = "TAP TO PLAY"
                textSize = 20f
                setOnClickListener { goToGame() }
            },
        )
        setContentView(root)

        // Headless crash-test hook. Launch with:
        //   adb shell am start -n <pkg>/.MenuActivity --es REPLAY_AUTOCRASH jvm
        // to throw an uncaught JVM exception ~6s after start (enough for the
        // session to register), with NO auto-navigate. The crash is persisted
        // by the SDK's crash handler and surfaced on the NEXT launch.
        val autoCrash = intent.getStringExtra("REPLAY_AUTOCRASH")
        if (!autoCrash.isNullOrEmpty()) {
            handler.postDelayed({
                android.util.Log.w("ReplayExample", "auto-crash: $autoCrash")
                throw RuntimeException("Headless auto-crash: $autoCrash")
            }, 6000)
            return
        }

        // Simulate the user starting the game after a beat.
        handler.postDelayed({
            Replay.track("menu_play_clicked")
            goToGame()
        }, 2500)
    }

    private fun goToGame() {
        if (navigated) return
        navigated = true
        startActivity(Intent(this, GameActivity::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
