package com.replayfy.android.internal.tracker

import android.app.Activity
import android.view.View
import android.view.WindowManager
import java.lang.reflect.Field

/**
 * Returns every active window root attached to the current process
 * — not just the foreground activity's content view, but also
 * dialogs, popups, dropdown menus, toasts, and overlay windows.
 *
 * Android doesn't expose a public API for this; we reflect into
 * `WindowManagerGlobal.mRoots` (private but stable across all API
 * levels we support, 21+). Same trick used by Stetho, LeakCanary,
 * Robolectric, UXCam, and Sentry's session replay.
 *
 * On Activity.getWindowManager() we find:
 *   - API ≥ 28: `mWindowManager` field pointing at a WindowManagerImpl
 *     whose `mGlobal` field is the WindowManagerGlobal singleton.
 *   - API < 28: `mGlobal` field directly on the activity's WM.
 *
 * Both cases land on the same WindowManagerGlobal which has:
 *   - `mRoots: List<ViewRootImpl>`  — one per attached window
 *   - `mParams: List<WindowManager.LayoutParams>` — params per root
 *
 * From a ViewRootImpl we pull `mView` to get the root [View].
 *
 * Mirrors `com.uxcam.internals.screenaction.bj#a` from UXCam's
 * screenaction module.
 */
internal object WindowRootDiscovery {

    /** A root view + the WindowManager params it was attached with. */
    data class WindowRoot(
        val view: View,
        val params: WindowManager.LayoutParams,
    )

    fun rootsFor(activity: Activity): List<WindowRoot> {
        val wm = activity.windowManager ?: return emptyList()

        // Find the WindowManagerGlobal. Two possible paths depending on
        // Android version + manufacturer customisation.
        val global = try {
            // API 28+: mWindowManager on WindowManagerImpl
            readField(wm, "mWindowManager") ?: readField(wm, "mGlobal")
        } catch (_: Throwable) {
            try { readField(wm, "mGlobal") } catch (_: Throwable) { null }
        } ?: return emptyList()

        val rootsList = readField(global, "mRoots") as? List<*> ?: return emptyList()
        val paramsList = readField(global, "mParams") as? List<*> ?: return emptyList()

        val out = ArrayList<WindowRoot>(rootsList.size)
        for (i in rootsList.indices) {
            val rootImpl = rootsList[i] ?: continue
            val view = readField(rootImpl, "mView") as? View ?: continue
            val params = paramsList.getOrNull(i) as? WindowManager.LayoutParams ?: continue
            if (!view.isShown) continue
            out.add(WindowRoot(view, params))
        }
        return out
    }

    /**
     * Reflect a field by name walking up the class hierarchy. Plain
     * `getDeclaredField` only sees the immediate class; OEMs often
     * subclass framework classes and bury fields deeper.
     */
    private fun readField(target: Any, name: String): Any? {
        var cls: Class<*>? = target.javaClass
        while (cls != null) {
            val field: Field? = try {
                cls.getDeclaredField(name)
            } catch (_: NoSuchFieldException) { null }
            if (field != null) {
                field.isAccessible = true
                return field.get(target)
            }
            cls = cls.superclass
        }
        return null
    }
}
