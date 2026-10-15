package com.replayfy.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
  * The keep rules every host app gets with the SDK. A minified host app is
  * shrunk by R8 in full mode (the default from AGP 8), which keeps nothing a
  * rule doesn't name, so these guard what the SDK and its dependencies create
  * by reflection.
  */
class ConsumerRulesTest {

    private val rules: String by lazy {
        // Unit tests run with the module directory as the working directory.
        File("consumer-rules.pro").readText()
    }

    // Each `-keep` rule, comments dropped and whitespace collapsed.
    private val keepRules: List<String> by lazy {
        rules.lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .split(Regex("(?=-keep)"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.startsWith("-keep") }
    }

    @Test
    fun keepsTheConstructorRoomCreatesWorkManagersDatabaseWith() {
        // Without it a minified release app crashes at startup in
        // WorkManagerInitializer: "Failed to create an instance of class
        // androidx.work.impl.WorkDatabase".
        assertTrue(
            "consumer-rules.pro must keep RoomDatabase subclasses' constructors; rules: $keepRules",
            keepRules.contains("-keep class * extends androidx.room.RoomDatabase { <init>(); }"),
        )
    }
}
