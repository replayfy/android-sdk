# Builds libreplay_crash.so — the native-signal-handler half of the
# Replay Android SDK's crash subsystem.
#
# Why a tiny .so rather than a vendored library:
#   - We need ONLY sigaction + libunwind (already in the NDK
#     toolchain). No external deps.
#   - Bundled binary footprint stays trivial — ~10 KB per ABI.
#   - Keeps the build hermetic; consumers don't need extra
#     repositories or downloads.
#
# Wired into AGP via build.gradle.kts > android.externalNativeBuild.
# AGP cross-compiles for every ABI in defaultConfig.ndk.abiFilters
# and packs the .so files into jniLibs/<abi>/.

cmake_minimum_required(VERSION 3.22.1)

project(replay_crash LANGUAGES C)

# Treat warnings as errors locally so a bad cast or implicit decl
# can't slip through. -fvisibility=hidden trims the symbol table to
# the one JNI export — strips internal helpers from the .so.
add_compile_options(
    -Wall
    -Wextra
    -Werror
    -O2
    -fvisibility=hidden
    -fno-stack-protector       # signal handler runs on alt stack
)

add_library(
    replay_crash SHARED
    replay_signal_handler.c
)

# Logging + unwind are part of the NDK system libs. log = liblog.so,
# unwind = compiler-rt unwinder shipped with the NDK toolchain.
find_library(log-lib log)
target_link_libraries(replay_crash ${log-lib})
