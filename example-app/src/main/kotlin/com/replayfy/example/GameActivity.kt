package com.replayfy.example

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.replayfy.android.Replay

/**
 * The board screen. An auto-player drives a few full rounds of
 * Tic-Tac-Toe (with a tiny win/block AI so games actually resolve),
 * pausing between moves so the recording captures the progression. Each
 * move + game-over + round is reported through the SDK, so the dashboard
 * timeline + Screens tab fill out alongside the video.
 */
class GameActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val cells = arrayOfNulls<Button>(9)
    private var board = IntArray(9) // 0 empty, 1 = X, 2 = O
    private var current = 1
    private var round = 1
    private var moves = 0
    private var startedAt = 0L
    private lateinit var status: TextView

    private val maxRounds = 3
    private val moveDelayMs = 1100L
    private val resultPauseMs = 2800L

    private val lines = arrayOf(
        intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
        intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
        intArrayOf(0, 4, 8), intArrayOf(2, 4, 6),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startedAt = System.currentTimeMillis()
        Replay.tagScreenName("Game")
        Replay.track("game_started", mapOf("round" to round))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(48, 110, 48, 48)
        }
        root.addView(
            TextView(this).apply {
                text = "TIC-TAC-TOE"
                textSize = 30f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, 28)
            },
        )
        status = TextView(this).apply {
            text = "X to move"
            textSize = 24f
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, 40)
        }
        root.addView(status)

        val grid = GridLayout(this).apply {
            rowCount = 3
            columnCount = 3
        }
        for (i in 0 until 9) {
            val b = Button(this).apply {
                text = ""
                textSize = 48f
                setTypeface(typeface, Typeface.BOLD)
                setBackgroundColor(Color.parseColor("#1E293B"))
                setTextColor(Color.WHITE)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 280
                    height = 280
                    setMargins(8, 8, 8, 8)
                    rowSpec = GridLayout.spec(i / 3)
                    columnSpec = GridLayout.spec(i % 3)
                }
            }
            cells[i] = b
            grid.addView(b)
        }
        root.addView(grid)
        setContentView(root)

        handler.postDelayed({ autoMove() }, 1200)
    }

    private fun autoMove() {
        if (isFinishing) return
        val move = chooseMove(board, current)
        if (move >= 0) {
            board[move] = current
            moves++
            cells[move]?.apply {
                text = if (current == 1) "X" else "O"
                setTextColor(
                    if (current == 1) Color.parseColor("#38BDF8")
                    else Color.parseColor("#FB7185"),
                )
            }
            Replay.track(
                "move",
                mapOf(
                    "player" to if (current == 1) "X" else "O",
                    "cell" to move,
                    "round" to round,
                ),
            )
        }

        val w = winner(board)
        if (w != 0 || moves == 9) {
            endRound(w)
            return
        }
        current = if (current == 1) 2 else 1
        setStatus(
            "${if (current == 1) "X" else "O"} to move",
            if (current == 1) "#38BDF8" else "#FB7185",
        )
        handler.postDelayed({ autoMove() }, moveDelayMs)
    }

    private fun endRound(winner: Int) {
        val msg = when (winner) {
            1 -> "X wins!"
            2 -> "O wins!"
            else -> "It's a draw"
        }
        setStatus(msg, "#FBBF24")
        if (winner != 0) highlightWin(winner)
        Replay.track("game_over", mapOf("result" to msg, "round" to round))

        val elapsed = System.currentTimeMillis() - startedAt
        if (round < maxRounds && elapsed < 60_000) {
            handler.postDelayed({ resetBoard() }, resultPauseMs)
        } else {
            handler.postDelayed({
                setStatus("Thanks for playing!", "#34D399")
                Replay.track("session_complete", mapOf("rounds" to round))
            }, resultPauseMs)
            // Demo crash: a few seconds after the games finish, throw an
            // uncaught exception so the SDK's crash handler + the dashboard
            // Crashes tab are exercised end-to-end.
            handler.postDelayed({
                setStatus("Simulating crash…", "#FB7185")
            }, resultPauseMs + 2200)
            handler.postDelayed({
                throw RuntimeException("Simulated crash from the Tic-Tac-Toe demo")
            }, resultPauseMs + 3500)
        }
    }

    private fun resetBoard() {
        round++
        board = IntArray(9)
        current = 1
        moves = 0
        for (b in cells) {
            b?.apply { text = ""; setBackgroundColor(Color.parseColor("#1E293B")) }
        }
        setStatus("Round $round  ·  X to move", "#38BDF8")
        Replay.tagScreenName("Game-Round$round")
        Replay.track("game_started", mapOf("round" to round))
        handler.postDelayed({ autoMove() }, 1000)
    }

    private fun setStatus(text: String, color: String) {
        status.text = text
        status.setTextColor(Color.parseColor(color))
    }

    private fun highlightWin(winner: Int) {
        for (l in lines) {
            if (board[l[0]] == winner && board[l[1]] == winner && board[l[2]] == winner) {
                for (idx in l) cells[idx]?.setBackgroundColor(Color.parseColor("#166534"))
            }
        }
    }

    // Tiny AI: take a winning move, else block, else center, else a
    // deterministic-but-varied empty cell (no RNG needed).
    private fun chooseMove(b: IntArray, p: Int): Int {
        val o = if (p == 1) 2 else 1
        winningMove(b, p)?.let { return it }
        winningMove(b, o)?.let { return it }
        if (b[4] == 0) return 4
        val empties = (0 until 9).filter { b[it] == 0 }
        return if (empties.isEmpty()) -1 else empties[(moves * 7 + round * 3) % empties.size]
    }

    private fun winningMove(b: IntArray, p: Int): Int? {
        for (i in 0 until 9) {
            if (b[i] == 0) {
                b[i] = p
                val win = winner(b) == p
                b[i] = 0
                if (win) return i
            }
        }
        return null
    }

    private fun winner(b: IntArray): Int {
        for (l in lines) {
            if (b[l[0]] != 0 && b[l[0]] == b[l[1]] && b[l[1]] == b[l[2]]) return b[l[0]]
        }
        return 0
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
