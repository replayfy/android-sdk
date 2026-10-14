package com.replayfy.android.internal.privacy

import android.os.Build
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import java.util.Locale

/**
 * The text fields masked with no setup at all: the same rules the reference
 * tracker applies to every EditText by default.
 *
 *  - password fields, by input type (class + variation bits, so extra flags
 *    such as NO_SUGGESTIONS don't hide them) or by a password transformation;
 *  - card fields, by their exact (case-sensitive) autofill hint;
 *  - one-time codes, by any autofill hint containing "otp".
 *
 * Deliberately NOT matched, as in the reference: username, email and phone
 * hints, the generic "password" hint and the card expiration day. Nor is
 * there a cap on how many fields one frame masks: past a cap, fields would
 * ship unmasked.
 */
internal object SensitiveInputRules {

    /** Card hints the reference tracker auto-masks (View.AUTOFILL_HINT_CREDIT_CARD_*). */
    private val CARD_HINTS = setOf(
        "creditCardNumber",
        "creditCardSecurityCode",
        "creditCardExpirationDate",
        "creditCardExpirationMonth",
        "creditCardExpirationYear",
    )

    /** Whether [v] is masked: always for the rules above, and every field
     *  when [occludeAllTextFields] (the app's switch or the dashboard's). */
    fun isSensitiveEditText(v: EditText, occludeAllTextFields: Boolean): Boolean =
        occludeAllTextFields || isPasswordEditText(v) || hasSensitiveAutofillHint(v)

    /** A password field. A field whose "show password" toggle is on (input
     *  type still a password variation, transformation removed) still is. */
    fun isPasswordEditText(v: EditText): Boolean {
        if (v.transformationMethod is PasswordTransformationMethod) return true
        val type = v.inputType
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** A card or one-time-code field, by autofill hint (API 26+; older
     *  devices have no autofill hints). */
    fun hasSensitiveAutofillHint(v: EditText): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val hints = v.autofillHints ?: return false
        for (h in hints) {
            if (h == null) continue // skipped, never a crash
            if (h in CARD_HINTS || h.lowercase(Locale.ROOT).contains("otp")) return true
        }
        return false
    }
}
