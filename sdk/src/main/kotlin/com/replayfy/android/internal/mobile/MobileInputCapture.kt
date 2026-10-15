package com.replayfy.android.internal.mobile

import android.graphics.Rect
import android.view.View
import android.widget.EditText
import com.replayfy.android.internal.privacy.PrivacyRegistry
import java.lang.ref.WeakReference

/**
 * Observes specific [EditText]s and reports their value when editing ends.
 *
 * Mirrors the reference: inputs are opt-in per field (no global auto-capture),
 * the value is read on focus-loss (not per keystroke), and a field masked in
 * the frames reports "***" — never the typed text — with the field's hint as
 * the label. The host's own focus listener is preserved (chained), never
 * clobbered, so input tracking can't break the app's focus handling.
 *
 * [hostMasks] are the masks a host SDK (React Native, Flutter) added to the
 * last frame, in that frame's coordinates: a field under one is masked too.
 */
internal class MobileInputCapture(
    private val onInput: (value: String, masked: Boolean, label: String) -> Unit,
    private val hostMasks: () -> List<Rect> = { emptyList() },
) {
    private val observed = mutableListOf<WeakReference<EditText>>()

    fun observe(editText: EditText) {
        if (observed.any { it.get() === editText }) return // already observing
        observed.add(WeakReference(editText))
        val previous = editText.onFocusChangeListener
        editText.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            previous?.onFocusChange(v, hasFocus) // preserve the host's listener
            if (!hasFocus && v is EditText) finish(v)
        }
    }

    private fun finish(view: EditText) {
        // Invariant: a field whose pixels are masked never ships its text.
        // If the check itself fails, the field is treated as masked.
        val masked = try { isMasked(view) } catch (e: Throwable) { true }
        val value = if (masked) "***" else (view.text?.toString() ?: "")
        onInput(value, masked, view.hint?.toString() ?: "")
    }

    /**
     * Whether the frames mask [view]: a privacy rule (a password / card /
     * one-time-code field, occludeAllTextFields — the app's or the
     * dashboard's — a marked view or class, or one around it, the whole
     * screen), a FLAG_SECURE window (recorded black), a Compose
     * `replayOcclude` region over it, or a host SDK's mask over it.
     */
    private fun isMasked(view: EditText): Boolean {
        if (PrivacyRegistry.isSensitive(view) || PrivacyRegistry.isSecureWindow(view)) return true
        val inDecor = PrivacyRegistry.decorRectOf(view)
        if (PrivacyRegistry.isSensitiveByBoundsIntersect(inDecor)) return true
        val host = hostMasks()
        if (host.isEmpty()) return false
        val o = PrivacyRegistry.frameOffset(view.rootView)
        val inFrame = Rect(inDecor).apply { offset(o[0], o[1]) }
        return host.any { Rect.intersects(it, inFrame) }
    }
}
