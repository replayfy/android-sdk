package com.replayfy.android.internal.tracker

import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView

/**
 * Extracts the `uiValue` field for a tapped view. The semantics differ
 * per widget type — see schema docs:
 *   - text     → the rendered text
 *   - button   → the button label
 *   - field    → the *hint*, NEVER the contents (privacy by default)
 *   - image    → contentDescription / drawable resource name
 *   - compound → current state ("on" | "off" | progress value)
 *   - container/unknown → empty
 *
 * Field-content occlusion (never extracting user input) is a non-
 * negotiable security floor — even when `isSensitive=false` on the
 * tap. Customers explicitly opt-in to field-content capture via
 * future privacy config (rarely useful for analytics, lots of risk).
 *
 * Mirrors the algorithm in
 * uxcam-flutter/lib/src/smart_events/uxcam_widget_extractor.dart
 * adapted from Flutter widgets to Android Views.
 */
internal object ValueExtractor {

    /** Max chars per uiValue. Twitter rule; long values are noise. */
    private const val MAX_LENGTH = 280

    fun extract(view: View, type: WidgetClassifier.UiType): String {
        val raw = when (type) {
            WidgetClassifier.UiType.TEXT,
            WidgetClassifier.UiType.BUTTON -> extractText(view)

            WidgetClassifier.UiType.FIELD -> extractFieldHint(view)

            WidgetClassifier.UiType.IMAGE -> extractImageRef(view)

            WidgetClassifier.UiType.COMPOUND -> extractCompoundState(view)

            WidgetClassifier.UiType.CONTAINER,
            WidgetClassifier.UiType.UNKNOWN -> ""
        }
        return raw.take(MAX_LENGTH)
    }

    private fun extractText(view: View): String {
        if (view is TextView) {
            // Prefer text over contentDescription so a labelled button
            // shows the label, not the a11y description.
            val text = view.text?.toString()
            if (!text.isNullOrEmpty()) return text
            // Fall through to a11y label if the view has no visible text
            // — e.g. an IconButton with only an icon + contentDescription.
            view.contentDescription?.toString()?.let { if (it.isNotEmpty()) return it }
        }
        return ""
    }

    /**
     * Field hint extraction. Critical: NEVER read `EditText.text`. That's
     * the user's input (password, email, message body). The tap event
     * should describe WHICH field was tapped, not its contents.
     */
    private fun extractFieldHint(view: View): String {
        if (view is EditText) {
            val hint = view.hint?.toString()
            if (!hint.isNullOrEmpty()) return hint
            view.contentDescription?.toString()?.let { if (it.isNotEmpty()) return it }
        }
        return ""
    }

    /**
     * Image reference. Prefer a11y description (semantic, stable
     * across resource renames) over the drawable resource name (only
     * extractable by reflection on private fields, fragile across
     * Android versions).
     */
    private fun extractImageRef(view: View): String {
        if (view is ImageButton || view is ImageView) {
            view.contentDescription?.toString()?.let { if (it.isNotEmpty()) return it }
            // Resource id → name lookup. Works when the drawable was
            // set declaratively (android:src="@drawable/..."); returns
            // empty when it was set programmatically with a Bitmap.
            val resId = try {
                val f = ImageView::class.java.getDeclaredField("mResource")
                f.isAccessible = true
                f.getInt(view)
            } catch (_: Throwable) { 0 }
            if (resId != 0) {
                return try {
                    view.resources.getResourceEntryName(resId)
                } catch (_: Throwable) { "" }
            }
        }
        return ""
    }

    /**
     * Compound state — for switches/checkboxes/radio: "on" | "off".
     * For sliders/SeekBar: the progress value as a string. Driven
     * by what reads cleanly in playback timeline.
     */
    private fun extractCompoundState(view: View): String {
        if (view is CompoundButton) {
            val label = view.text?.toString()?.takeIf { it.isNotEmpty() }
            val state = if (view.isChecked) "on" else "off"
            return if (label != null) "$label · $state" else state
        }
        if (view is SeekBar) {
            return view.progress.toString()
        }
        return ""
    }
}
