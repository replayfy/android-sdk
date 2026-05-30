package com.replayfy.android.internal.tracker

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.replayfy.android.internal.TapBounds
import com.replayfy.android.internal.TapEventData
import com.replayfy.android.internal.TapPoint
import com.replayfy.android.internal.privacy.PrivacyRegistry

/**
 * Per-Activity tap capture.
 *
 * Hooks into the host app's [Application.ActivityLifecycleCallbacks].
 * On every onActivityResumed / onActivityStarted, we schedule a tree
 * walk via [Handler] (coalesced — multiple lifecycle events in the
 * same frame produce ONE scan, matching UXCam's `loopLayout` design).
 *
 * The scan:
 *   1. Get all window roots via [WindowRootDiscovery] (includes
 *      dialogs, popups, dropdowns — not just the activity content).
 *   2. Recursively walk each root's view tree.
 *   3. For each interactive View not already tracked, swap in a
 *      [TouchListenerWrapper] that preserves the original listener.
 *
 * Tap → emits a `TapEventData` via [emit] which the orchestrator
 * relays as a `tap` ReplayEvent.
 *
 * Mirrors `com.uxcam.screenaction.tracker.ScreenActionTracker` from
 * the UXCam decompiled source. We keep the same name shape so the
 * decompiled-to-Kotlin mapping is obvious in code review.
 */
internal class TapTracker(
    /** Called per tap with the metadata payload that becomes a
     *  `tap` ReplayEvent. Implementation lives in ReplayCore so
     *  we don't depend on its singleton from this package. */
    private val emit: (TapEventData) -> Unit,
) {

    /** Current screen name — defaults to Activity class name, can
     *  be overridden by `Replay.tagScreenName(name)`. */
    @Volatile
    var currentRoute: String = "/"
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private val registry = ViewRegistry()

    // Coalesce repeated scan triggers — multiple lifecycle callbacks
    // in the same frame collapse to one tree walk.
    private val scanRunnable = Runnable { runScanNow() }
    @Volatile private var pendingActivity: Activity? = null

    /** Currently-resumed activity, weakly held so a destroyed
     *  activity can be GC'd. Read by SnapshotCapture for window
     *  discovery. */
    private val activityHolder = ActivityHolder()

    /** Public accessor for the orchestrator + snapshot pipeline. */
    fun currentActivity(): Activity? = activityHolder.get()

    /** Set when a scan is currently in flight to prevent re-entry
     *  (e.g. attaching a listener mutates the view → triggers a
     *  layout pass → ActivityLifecycleCallbacks re-fires → infinite). */
    @Volatile private var scanning = false

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

        override fun onActivityStarted(activity: Activity) {
            scheduleScan(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            activityHolder.set(activity)
            updateRoute(activity)
            scheduleScan(activity)
            // Notify any listeners (snapshot pipeline) that a screen
            // change happened. Done after route update so the snapshot
            // payload includes the right route.
            onScreenResumed?.invoke(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (activityHolder.get() === activity) activityHolder.set(null)
        }

        override fun onActivityStopped(activity: Activity) {
            // Drop tracked views for this activity. Recreated activities
            // (e.g. configuration change) get a fresh attachment pass.
            if (pendingActivity === activity) pendingActivity = null
            // Don't clear() the whole registry — other activities may
            // still be in the back stack with attached listeners. The
            // WeakHashMap GCs the entries when their Views are gone.
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** Hook for the orchestrator: fires on activity resume after
     *  route + holder are updated. Used by SnapshotCapture to
     *  schedule a `screen_appeared` snapshot. */
    @Volatile
    var onScreenResumed: ((Activity) -> Unit)? = null

    fun attach(application: Application) {
        application.registerActivityLifecycleCallbacks(activityCallbacks)
    }

    fun detach(application: Application) {
        application.unregisterActivityLifecycleCallbacks(activityCallbacks)
        mainHandler.removeCallbacks(scanRunnable)
        registry.clear()
    }

    /** Called by the orchestrator when tagScreenName() is invoked. */
    fun setRoute(route: String) {
        currentRoute = route
    }

    // -----------------------------------------------------------------
    //  Scan loop — coalesced via Handler.post.
    // -----------------------------------------------------------------

    private fun scheduleScan(activity: Activity) {
        pendingActivity = activity
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.post(scanRunnable)
    }

    private fun runScanNow() {
        if (scanning) return
        val activity = pendingActivity ?: return
        pendingActivity = null
        scanning = true
        try {
            val roots = WindowRootDiscovery.rootsFor(activity)
            for (root in roots) {
                if (root.view is ViewGroup) {
                    walkSubtree(root.view)
                } else {
                    maybeAttachListener(root.view)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "tap scan failed: ${t.message}")
        } finally {
            scanning = false
        }
    }

    /** Depth-first walk. Skips subtrees rooted at invisible views. */
    private fun walkSubtree(group: ViewGroup) {
        if (!group.isShown) return
        // Visit the group itself first — some apps use clickable
        // ViewGroups as their primary button container.
        if (WidgetClassifier.isInteractive(group)) {
            maybeAttachListener(group)
        }
        val count = group.childCount
        for (i in 0 until count) {
            val child = group.getChildAt(i) ?: continue
            if (child is ViewGroup) {
                walkSubtree(child)
            } else if (WidgetClassifier.isInteractive(child)) {
                maybeAttachListener(child)
            }
        }
    }

    private fun maybeAttachListener(view: View) {
        if (registry.contains(view)) return

        // Skip Google's AdMob views — they install internal touch
        // handlers that don't play nicely with reflection-based
        // wrappers + customers don't want ad taps in their analytics
        // anyway.
        if (view.javaClass.name.startsWith("com.google.android.gms.ads")) {
            return
        }

        val existing = TouchListenerWrapper.readExistingListener(view)
        // If our wrapper is already installed (e.g. from a prior scan
        // that skipped registry update due to a failure), don't
        // double-wrap. The wrapper instance check is enough.
        if (existing is TouchListenerWrapper) {
            registry.add(view) // sync the registry to match reality
            return
        }

        val positionInParent = (view.parent as? ViewGroup)?.indexOfChild(view) ?: -1
        val wrapper = TouchListenerWrapper(
            original = existing,
            positionInParent = positionInParent,
            onTap = ::onTapCaptured,
        )
        try {
            view.setOnTouchListener(wrapper)
            registry.add(view)
        } catch (t: Throwable) {
            android.util.Log.v(TAG, "attach failed for ${view.javaClass.simpleName}: ${t.message}")
        }
    }

    // -----------------------------------------------------------------
    //  Tap → TapEventData
    // -----------------------------------------------------------------

    private fun onTapCaptured(
        view: View,
        x: Float,
        y: Float,
        gesture: String?,
        pinchScale: Double?,
    ) {
        val type = WidgetClassifier.classify(view)
        if (type == WidgetClassifier.UiType.UNKNOWN) {
            // Don't emit taps on unclassified views — too noisy, no
            // useful semantic. The dashboard skips these for heatmaps
            // anyway.
            return
        }

        // Privacy check — two paths:
        //   (a) View-ancestor walk: catches the UIKit-equivalent
        //       Replay.addPrivacyView(view) case.
        //   (b) Compose point-intersection: catches the Compose
        //       Modifier.replayOcclude case where the marker isn't
        //       in the view tree at all.
        // Tap coords get converted to window-relative before the
        // point-intersection check because Compose registers
        // bounds in window coords.
        val winLoc = IntArray(2).also { view.getLocationInWindow(it) }
        val xInWindow = winLoc[0] + x.toInt()
        val yInWindow = winLoc[1] + y.toInt()
        val isSensitive = PrivacyRegistry.isSensitive(view) ||
            PrivacyRegistry.isSensitiveAtPoint(xInWindow, yInWindow)
        val uiClass = if (isSensitive) "" else view.javaClass.simpleName
        val uiValue = if (isSensitive) "" else ValueExtractor.extract(view, type)
        val uiId = if (isSensitive) "$currentRoute:sensitive"
            else UiIdHasher.uiId(currentRoute, uiClass, uiValue)

        // Bounds in screen coordinates so the player can position
        // tap markers without knowing the activity's window position.
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val bounds = TapBounds(
            x = loc[0], y = loc[1],
            w = view.width, h = view.height,
        )
        val point = TapPoint(
            x = (loc[0] + x).toInt(),
            y = (loc[1] + y).toInt(),
        )

        emit(
            TapEventData(
                bounds = bounds,
                point = point,
                route = currentRoute,
                uiClass = uiClass,
                // Sensitive taps flip uiType to "unknown" so funnels
                // don't accidentally group them with regular
                // interactions of the same underlying widget type.
                uiType = if (isSensitive) "unknown" else type.wireName,
                uiValue = uiValue,
                uiId = uiId,
                isSensitive = isSensitive,
                gesture = gesture,
                pinchScale = pinchScale,
            ),
        )
        // Snapshot pipeline subscribes via this hook to debounce an
        // `idle` snapshot 500ms after the last tap. Catches
        // post-interaction state (modal opened, list scrolled).
        onTapEmitted?.invoke()
    }

    /** Hook for the snapshot pipeline. Invoked after each tap. */
    @Volatile
    var onTapEmitted: (() -> Unit)? = null

    /** When false, [onActivityResumed] skips the auto route-update
     *  step — only explicit [Replay.tagScreenName] calls move the
     *  route. Wired from [ReplayConfig.autoScreenName]. Defaults to
     *  true so the default-config flow continues to auto-tag. */
    @Volatile
    var autoScreenNameEnabled: Boolean = true

    private fun updateRoute(activity: Activity) {
        // Default route = activity class simple name. tagScreenName()
        // overrides via setRoute(). Matches UXCam's auto-tagging
        // behaviour. Opt-out: customers who tag manually set
        // ReplayConfig(autoScreenName=false) — then this method is
        // a no-op and the route stays whatever the last
        // tagScreenName() set it to.
        if (!autoScreenNameEnabled) return
        currentRoute = activity.javaClass.simpleName
    }

    private companion object {
        private const val TAG = "ReplaySdk"
    }
}
