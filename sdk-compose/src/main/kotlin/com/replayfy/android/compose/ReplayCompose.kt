package com.replayfy.android.compose

import android.graphics.Rect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.replayfy.android.Replay
import com.replayfy.android.internal.bridge.ComposeBridge

/**
 * Jetpack Compose integration for the Replay Android SDK.
 *
 * Two Modifier extensions mirror the iOS SwiftUI wrappers
 * (`.replayOcclude()` + `.replayTagScreenName()`):
 *
 *   - `Modifier.replayOcclude(blockGestures: Boolean = true)` marks
 *     the composable's bounds sensitive. Taps inside it ship as
 *     `isSensitive: true` with metadata blanked; the captured PNG
 *     paints over the region with the diagonal-stripe overlay.
 *
 *   - `Modifier.replayTagScreenName(name: String)` fires
 *     `Replay.tagScreenName(name)` on first composition (and
 *     whenever `name` changes). Solves the auto-tag gap for
 *     Compose-first apps where every screen lives under one
 *     ComponentActivity.
 *
 * Why a separate module: Compose customers add a second dependency
 * (`com.replayfy:android-sdk-compose`). UIKit-only Android customers
 * skip this entirely so the core AAR stays tiny + free of Compose
 * transitive deps.
 *
 * Compose-occlude uses bounds-intersection rather than ancestor walk:
 * Compose composables aren't UIViews — the marker can't be inserted
 * into the View tree where the underlying capture pipeline walks.
 * Instead, the modifier registers the composable's window-relative
 * bounds via [ComposeBridge.registerOccludedBounds] and the
 * TapTracker + ViewTreeSerializer + BitmapCapture pipelines all
 * consult that table via PrivacyRegistry.
 */

/**
 * Mark the modified composable's bounds as privacy-sensitive.
 *
 * @param blockGestures When true (default), wraps the composable
 *   in a [pointerInput] that consumes all touches — useful when
 *   the sensitive region also responds to gestures you don't want
 *   correlated with the user (e.g. a "show CVC" toggle inside a
 *   credit-card row). Set false to let touches pass through to the
 *   underlying composable; the SDK still records them as sensitive
 *   but the composable's own gesture handlers still fire.
 */
fun Modifier.replayOcclude(blockGestures: Boolean = true): Modifier = composed {
    // Opaque token so PrivacyRegistry can update the same entry on
    // re-layout rather than growing the table. `remember` ensures a
    // fresh token per composable instance, not per recomposition.
    val token = remember { Any() }

    // Register on each onGloballyPositioned callback so scrolling /
    // animation moves keep the registered rect current.
    val positionMod = Modifier.onGloballyPositioned { coords ->
        val r = coords.boundsInWindow()
        ComposeBridge.registerOccludedBounds(
            token,
            Rect(
                r.left.toInt(),
                r.top.toInt(),
                r.right.toInt(),
                r.bottom.toInt(),
            ),
        )
    }

    // Unregister on dispose so dismissed composables don't leak entries.
    DisposableEffect(token) {
        onDispose { ComposeBridge.unregisterOccludedBounds(token) }
    }

    // Gesture swallower — depends only on compose-ui (not foundation).
    // Catches each pointer event in the Initial pass before children
    // get a chance, then marks every change as consumed so onClick /
    // detectTapGestures / scroll handlers underneath never fire.
    val gestureBlocker = if (blockGestures) {
        Modifier.pointerInput(token) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    for (change in event.changes) {
                        if (change.pressed || change.previousPressed) {
                            change.consume()
                        }
                    }
                }
            }
        }
    } else {
        Modifier
    }

    // Order matters: positionMod first to capture the composable's
    // actual rect; gestureBlocker over the top to swallow gestures.
    this.then(positionMod).then(gestureBlocker)
}

/**
 * Tag the current screen with a name for the SDK's session timeline.
 *
 * Fires `Replay.tagScreenName(name)` on first composition AND any
 * time `name` changes (different screen, conditionally rendered
 * subscreen, etc).
 *
 * Use ONCE per logical screen at the top of its composable, e.g.
 * `Box(modifier = Modifier.replayTagScreenName("Checkout")) { … }`.
 */
fun Modifier.replayTagScreenName(name: String): Modifier = composed {
    LaunchedEffect(name) { Replay.tagScreenName(name) }
    this
}
