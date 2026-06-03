package com.replayfy.android.internal

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Listens to whole-app foreground/background transitions via
 * [androidx.lifecycle.ProcessLifecycleOwner] and asks [LegacyCore] to
 * rotate sessions accordingly.
 *
 * Why ProcessLifecycleOwner rather than ActivityLifecycleCallbacks:
 * - Activity callbacks fire per-activity. We'd have to count
 *   onStart/onStop ourselves to detect whole-app background.
 * - ProcessLifecycleOwner does that counting for us, with a 700ms
 *   debounce (so an Activity-to-Activity transition doesn't look like
 *   "app went to background then back").
 *
 * Same pattern UXCam uses (see ProcessLifecycleObserver / bi.b in their
 * decompiled source).
 */
internal class SessionLifecycleObserver(
    private val onAppForegrounded: () -> Unit,
    private val onAppBackgrounded: () -> Unit,
) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) {
        // App entered foreground — either fresh launch or returning
        // from background. LegacyCore decides whether this starts a
        // new session or just resumes the existing one (matters when
        // multi-session-record is enabled and the gap was short).
        onAppForegrounded()
    }

    override fun onStop(owner: LifecycleOwner) {
        // App entered background. Flush + end session. If the process
        // is killed in the background, the on-disk queue (WorkManager
        // commit) takes over.
        onAppBackgrounded()
    }
}
