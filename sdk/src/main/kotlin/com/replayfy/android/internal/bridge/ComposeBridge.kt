package com.replayfy.android.internal.bridge

import android.graphics.Rect
import android.view.View
import com.replayfy.android.internal.privacy.PrivacyRegistry

/**
 * Public surface the `:sdk-compose` module talks to. The core SDK's
 * [PrivacyRegistry] is `internal` to keep the customer-visible
 * surface tight — Compose customers should NOT be reaching into
 * privacy internals directly. But the Compose Modifier wrapper
 * lives in a separate Gradle module and Kotlin's `internal` visibility
 * doesn't cross module boundaries, so we expose a deliberately
 * minimal bridge here:
 *
 *   - [registerOccludedBounds] / [unregisterOccludedBounds] — the
 *     two calls `Modifier.replayOcclude` needs to keep PrivacyRegistry
 *     in sync with composable lifecycle.
 *
 * Note: marked `@PublishedApi internal` would NOT work here because
 * Kotlin module-level internal still applies. Plain public is the
 * right choice; the package name `.internal.bridge` signals to
 * customers that it isn't part of the supported API.
 */
object ComposeBridge {

    /** Register Compose-composable bounds (window-relative). Called
     *  from `Modifier.replayOcclude`'s `onGloballyPositioned` callback.
     *  Token identifies the composable so updates replace rather than
     *  grow the registry. Kept as they are: right while the window isn't
     *  panned for the keyboard. The overload taking the composable's
     *  [View] is right in every case. */
    fun registerOccludedBounds(token: Any, rectInWindow: Rect) {
        PrivacyRegistry.addComposeBounds(token, rectInWindow)
    }

    /** [registerOccludedBounds] for a composable shown by [view] (its
     *  AndroidComposeView). The window's adjustPan scroll at this moment is
     *  taken out, so the bounds are kept in the window content's own
     *  coordinates: the keyboard's pan moves boundsInWindow without a new
     *  layout, so window bounds registered before it changes would be off
     *  by the pan in later frames. */
    fun registerOccludedBounds(token: Any, rectInWindow: Rect, view: View) {
        val decor = IntArray(2).also { view.rootView.getLocationInWindow(it) }
        PrivacyRegistry.addComposeBounds(token, Rect(rectInWindow).apply { offset(-decor[0], -decor[1]) })
    }

    /** Unregister. Called from the modifier's
     *  `DisposableEffect.onDispose`. */
    fun unregisterOccludedBounds(token: Any) {
        PrivacyRegistry.removeComposeBounds(token)
    }
}
