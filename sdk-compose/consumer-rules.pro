# Keep the public Modifier extensions so customers' minified apps
# can still resolve them. They're extension functions (top-level)
# so the names live in a synthetic Kt class — keep that class.
-keep class com.replayfy.android.compose.ReplayComposeKt { *; }
