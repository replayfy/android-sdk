package com.replayfy.android.internal.privacy

import android.os.Build
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/**
 * The fields masked with no setup: the reference tracker's default rules,
 * no more and no less.
 */
@RunWith(RobolectricTestRunner::class)
class SensitiveInputRulesTest {

    private fun field(inputType: Int = InputType.TYPE_CLASS_TEXT): EditText =
        EditText(ApplicationProvider.getApplicationContext()).also { it.inputType = inputType }

    private fun masked(v: EditText, all: Boolean = false) = SensitiveInputRules.isSensitiveEditText(v, all)

    private val text = InputType.TYPE_CLASS_TEXT
    private val number = InputType.TYPE_CLASS_NUMBER

    @Test
    fun passwordInputTypesAreMaskedWhateverFlagsTheyCarry() {
        assertTrue(masked(field(text or InputType.TYPE_TEXT_VARIATION_PASSWORD)))
        assertTrue(masked(field(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
        assertTrue(masked(field(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)))
        assertTrue(masked(field(number or InputType.TYPE_NUMBER_VARIATION_PASSWORD)))
        // textPassword|textNoSuggestions (0x80081): the whole-value comparison
        // the typed-value path used before missed it.
        assertTrue(masked(field(0x80081)))
        assertTrue(masked(field(text or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)))
    }

    @Test
    fun aShowPasswordToggleStillLeavesTheFieldMasked() {
        val v = field(text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        v.transformationMethod = null // "show password" on
        assertTrue(masked(v))
    }

    @Test
    fun aPasswordTransformationAloneMasksAField() {
        val v = field()
        v.transformationMethod = PasswordTransformationMethod.getInstance()
        assertTrue(masked(v))
    }

    @Test
    fun cardAndOneTimeCodeHintsAreMasked() {
        for (hint in listOf(
            "creditCardNumber", "creditCardSecurityCode", "creditCardExpirationDate",
            "creditCardExpirationMonth", "creditCardExpirationYear", "smsOTPCode", "OTP",
        )) {
            val v = field().apply { setAutofillHints(hint) }
            assertTrue(hint, masked(v))
        }
    }

    @Test
    fun occludeAllTextFieldsMasksAPlainField() {
        assertTrue(masked(field(), all = true))
    }

    @Test
    fun everyOtherFieldIsLeftAlone() {
        assertFalse("number with a text-password bit", masked(field(number or 0x80)))
        assertFalse(masked(field(text or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)))
        // React Native's secureTextEntry + phone-pad: the reference doesn't match it either.
        assertFalse(masked(field(InputType.TYPE_CLASS_PHONE or 0x10)))
        assertFalse(masked(field()))
        assertFalse(masked(field().apply { transformationMethod = HideReturnsTransformationMethod.getInstance() }))
        for (hint in listOf("CREDITCARDNUMBER", "creditCardExpirationDay", "password", "emailAddress")) {
            assertFalse(hint, masked(field().apply { setAutofillHints(hint) }))
        }
    }

    @Test
    fun aNullHintIsSkippedWithoutACrash() {
        val v = field().apply { setAutofillHints(*arrayOf<String?>(null)) }
        assertFalse(masked(v))
    }

    @Test
    fun beforeAutofillOnlyTheInputTypeRulesApply() {
        // Android 7.1 has no autofill hints: the hint rule is skipped there,
        // without touching the API.
        val card = field().apply { setAutofillHints("creditCardNumber") }
        val password = field(text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val plain = field()
        val sdk = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.N_MR1)
        try {
            assertFalse(masked(card))
            assertFalse(masked(plain))
            assertTrue(masked(password))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdk)
        }
    }
}
