package com.replayfy.android.internal.tracker

/**
 * djb2 string hash, 32-bit unsigned, hex-encoded.
 *
 * Used to compute a stable per-widget id from
 * `route + uiClass + uiValue` so heatmaps + funnels can aggregate
 * taps on the same logical button across millions of sessions
 * without storing the full triple per row.
 *
 * Matches the algorithm the reference mobile SDK ships (and the one we documented in
 * docs/native-snapshot-format.md). The same hash on the SDK side and
 * the dashboard side keeps tap-marker → snapshot-node linking
 * deterministic.
 */
internal object UiIdHasher {

    /**
     * Build a stable id: `<route>:<djb2hex>`.
     *
     * Route is the prefix so the dashboard can group-by-screen without
     * a join. The hash collapses on `uiClass#uiValue` keyed by route
     * — same button label on two screens stays distinct, same button
     * on one screen across sessions collapses.
     */
    fun uiId(route: String, uiClass: String, uiValue: String): String {
        val normalized = uiValue.replace(" ", "").lowercase()
        val input = "$route#$uiClass#$normalized"
        return "$route:${djb2Hex(input)}"
    }

    /**
     * djb2 — Daniel J. Bernstein's hash. Fast, low collision rate for
     * short strings, no allocations. Public-domain; reimplementation
     * here rather than depending on a library.
     */
    private fun djb2Hex(input: String): String {
        var hash = 5381L
        for (i in input.indices) {
            // ((hash << 5) + hash) is the canonical djb2 step (== hash * 33)
            hash = ((hash shl 5) + hash) + input[i].code
        }
        // Mask to 32-bit unsigned to match the JS implementation in
        // the reference mobile SDK-flutter's widget_extractor; matters because the
        // dashboard reads ids from both web (web-sdk) and native, and
        // an inconsistent width would diverge collisions.
        return (hash and 0xFFFFFFFFL).toString(16)
    }
}
