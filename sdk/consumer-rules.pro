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

# WorkManager (which schedules the background uploads) builds its Room
# database reflectively, from the generated WorkDatabase_Impl. The rule
# Room 2.5 ships (the version WorkManager 2.9 brings) keeps that class but not
# its constructor, and R8 full mode (the default from AGP 8) then removes the
# constructor: a minified release build crashed at startup with "Failed to
# create an instance of class androidx.work.impl.WorkDatabase". Room 2.6+
# ships this same rule.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
