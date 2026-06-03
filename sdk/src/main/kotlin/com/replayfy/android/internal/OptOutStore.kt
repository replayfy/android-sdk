package com.replayfy.android.internal

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent flag store for the SDK's two GDPR-style kill switches:
 *
 *   - **Overall opt-out** — full SDK halt. No events buffered, no
 *     snapshots taken, no uploads attempted. Customer-side calls
 *     to `track()` / `identify()` etc. become no-ops. This is the
 *     hammer customers reach for in "Do not sell my data" /
 *     "Withdraw consent" flows.
 *
 *   - **Schematic opt-out** — partial halt. Events keep flowing
 *     (taps, network, console, lifecycle) but pixel capture stops.
 *     The dashboard still shows a session timeline but the player
 *     stage is empty. Useful for screens where the pixel content
 *     is regulated (HIPAA-covered health data) but the interaction
 *     analytics are still wanted.
 *
 * Stored in SharedPreferences (sync API — durable across launches
 * + survives uninstall only if backup is configured). Defaults are
 * **opted in** for both flags — customers MUST take action to
 * opt out, matching UXCam's semantics + most analytics SDKs.
 *
 * Mirrors UXCam's `optOutOverall(boolean)` + `optOutSchematicRecordings(boolean)`
 * APIs. Read by [LegacyCore] at the top of every emit / capture
 * path so flips take effect on the next event without waiting for
 * a process restart.
 */
internal class OptOutStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whole-SDK kill switch. Read on every emit + every snapshot
     *  trigger. Defaults to false (opted in). */
    var overallOptOut: Boolean
        get() = prefs.getBoolean(KEY_OVERALL, false)
        set(value) {
            // commit() not apply() — opt-out is a privacy boundary;
            // we want the on-disk state to match the in-memory
            // state by the time the call returns. The synchronous
            // write fits naturally on the customer's UI thread
            // (one prefs commit is <1ms on modern devices).
            prefs.edit().putBoolean(KEY_OVERALL, value).commit()
        }

    /** Schematic (snapshot-only) kill switch. Read inside the
     *  snapshot trigger before BitmapCapture runs. Defaults to false. */
    var schematicOptOut: Boolean
        get() = prefs.getBoolean(KEY_SCHEMATIC, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SCHEMATIC, value).commit()
        }

    private companion object {
        const val PREFS_NAME = "replay_optout_v1"
        const val KEY_OVERALL = "overall_optout"
        const val KEY_SCHEMATIC = "schematic_optout"
    }
}
