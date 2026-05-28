package com.replayfy.android.internal.tracker

import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.VisibleForTesting

/**
 * Maps an Android [View] subclass to one of the platform-neutral
 * widget types defined in our schema's `TapEventData.uiType`.
 *
 * Same taxonomy across web / iOS / Android / Flutter / RN so the
 * dashboard's heatmap + funnels can group by type without forking
 * per platform.
 *
 * Classification is by Java class identity / `instanceof` rather
 * than reflection on text/state, so a tap on an ImageButton that
 * happens to contain a label still classifies as `image` if the
 * underlying View is an ImageButton — matches what the user sees.
 *
 * Mirrors the algorithm in
 * uxcam-flutter/lib/src/smart_events/uxcam_widget_classifier.dart
 * adapted from Flutter Element types to Android Views.
 */
internal object WidgetClassifier {

    /** The seven buckets, one of which every classified view falls into. */
    enum class UiType(val wireName: String) {
        BUTTON("button"),
        FIELD("field"),
        COMPOUND("compound"),
        TEXT("text"),
        IMAGE("image"),
        CONTAINER("container"),
        UNKNOWN("unknown"),
    }

    /**
     * Classify a view. Cheap — only `is`-checks, no reflection.
     *
     * Order matters: most-specific classes are tested first because
     * Kotlin smart-cast `is` checks are evaluated top-to-bottom.
     * (e.g. ImageButton extends ImageView, so we check ImageButton
     * BEFORE ImageView. CompoundButton extends Button, so we check
     * CompoundButton BEFORE Button.)
     */
    fun classify(view: View): UiType = when {
        // Compound buttons (Switch, Checkbox, RadioButton, ToggleButton)
        // extend Button but render differently — own bucket so the
        // dashboard can render a switch widget in playback rather than
        // a generic button.
        view is CompoundButton -> UiType.COMPOUND

        // SeekBar = the only built-in slider primitive on Android.
        view is SeekBar -> UiType.COMPOUND

        // EditText extends TextView — but it's an input field, not
        // display text. Order-sensitive.
        view is EditText -> UiType.FIELD

        // ImageButton extends ImageView; check first.
        view is ImageButton -> UiType.IMAGE

        view is Button -> UiType.BUTTON

        view is ImageView -> UiType.IMAGE

        view is TextView -> UiType.TEXT

        // Scroll containers + lists — `container` bucket so the
        // dashboard knows they're tappable but don't have a label.
        view is ScrollView -> UiType.CONTAINER
        view is HorizontalScrollView -> UiType.CONTAINER
        view is AbsListView -> UiType.CONTAINER

        // Generic ViewGroups (LinearLayout, FrameLayout, ConstraintLayout,
        // etc.) — bucket as container only if they're clickable, else
        // unknown. A non-clickable container shouldn't surface taps; the
        // tracker filters those out before classification gets here, but
        // we still classify defensively.
        view is ViewGroup && view.isClickable -> UiType.CONTAINER

        // Anything tappable that doesn't match the above (e.g. custom
        // View subclasses with onClickListener) becomes UNKNOWN.
        else -> UiType.UNKNOWN
    }

    /**
     * Whether the view is *interactive* (worth attaching a touch
     * listener to). The tracker uses this to skip pure decoration
     * views during the tree walk — saves work + avoids listener
     * pollution on huge layouts.
     */
    @VisibleForTesting
    fun isInteractive(view: View): Boolean {
        if (!view.isShown || !view.isEnabled) return false
        return view.isClickable ||
            view.isLongClickable ||
            view.isFocusable ||
            view is EditText ||
            view is CompoundButton ||
            view is SeekBar
    }
}
