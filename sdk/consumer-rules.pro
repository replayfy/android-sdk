# Consumer ProGuard rules — applied to the host app's build whenever
# this AAR is on the classpath. Keeps the public API surface from being
# shrunk away or renamed.

-keep class com.replayfy.android.Replay { *; }
-keep class com.replayfy.android.ReplayConfig { *; }
-keep class com.replayfy.android.ReplayConfig$* { *; }

# The ContentProvider is auto-instantiated reflectively by the Android
# OS — must keep the class + zero-arg constructor.
-keep class com.replayfy.android.internal.ReplayContentProvider { *; }

# Internal classes referenced by reflection (lifecycle observer, etc.).
# Once we add the tap tracker that uses WindowManagerGlobal reflection,
# we'll add the corresponding -keep rules here.
