package com.replayfy.android.internal.tracker

import android.view.View
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Tracks which Views we've already attached our [TouchListenerWrapper]
 * to in the current scan window. Prevents double-attachment when a
 * layout pass triggers the scan again while the same Views are still
 * onscreen.
 *
 * Uses [WeakHashMap] so detached views are auto-evicted by GC — no
 * leak risk from holding references to Views whose Activity has been
 * destroyed.
 *
 * Mirrors `ScreenActionViewsRepository` from UXCam's screenaction
 * module.
 */
internal class ViewRegistry {

    // WeakHashMap's keys are weakly held; entries vanish when the View
    // is no longer reachable elsewhere. Values are just placeholders
    // (we use the registry as a Set; WeakHashSet isn't in stdlib).
    private val tracked = WeakHashMap<View, Any>()
    private val lock = Any()

    fun contains(view: View): Boolean = synchronized(lock) {
        tracked.containsKey(view)
    }

    fun add(view: View) = synchronized(lock) {
        tracked[view] = PLACEHOLDER
    }

    /** Called when an Activity's window is destroyed — drop any
     *  tracked views from it so a recreated Activity with new view
     *  instances doesn't keep stale ghosts. */
    fun clear() = synchronized(lock) {
        tracked.clear()
    }

    private companion object {
        private val PLACEHOLDER = Any()
    }
}

/** Holds a weak reference to the currently-resumed Activity so the
 *  scan loop can find roots without leaking the Activity. */
internal class ActivityHolder {
    @Volatile
    private var ref: WeakReference<android.app.Activity>? = null

    fun set(activity: android.app.Activity?) {
        ref = if (activity == null) null else WeakReference(activity)
    }

    fun get(): android.app.Activity? = ref?.get()
}
