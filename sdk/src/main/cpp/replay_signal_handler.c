// Native signal handler for the Replay Android SDK.
//
// What this catches: the fatal native signals that
// `Thread.setDefaultUncaughtExceptionHandler` (CrashHandler.kt's
// JVM-side) cannot see:
//
//   SIGSEGV   — invalid memory access (null deref, use-after-free)
//   SIGBUS    — misaligned access / nonexistent address
//   SIGABRT   — abort() (assertion failures, std::terminate)
//   SIGFPE    — div-by-zero, integer overflow with FE_OVERFLOW
//   SIGILL    — illegal instruction
//   SIGTRAP   — debugger trap, sometimes raised by ART CHECK macros
//
// These come from JNI / NDK code in the host app (game engines,
// Rust/C++ libs linked via cargo-ndk, OpenGL/Metal-equivalent
// frameworks). UXCam catches them via libunwindstack + a private
// fork of Google Breakpad; we go simpler — sigaction + libunwind
// (NDK-bundled) gives a usable stack at a fraction of the binary
// size.
//
// Async-signal-safety contract:
// =============================
// The handler runs in signal context where almost nothing is safe.
// Forbidden: malloc/free, printf, dlopen, Java JNI calls, locks
// that can be held by the interrupted thread. Allowed (per POSIX
// + Android docs): write, _exit, sigaction, fcntl with F_SETLK,
// _Unwind_Backtrace.
//
// File format (single line, pipe-delimited, terminated by \n):
//   v1|<signo>|<si_code>|<faulting_addr_hex>|<ts_ms>|<thread_name>|<stack_hex_comma_separated>
//
// Read on next launch by CrashHandler.kt#drainPreviousNativeCrash.

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

#define LOG_TAG "ReplaySdk-NDK"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// The signals we hook. Order doesn't matter — sigaction takes them
// one at a time.
static const int kHandledSignals[] = {
    SIGSEGV, SIGBUS, SIGABRT, SIGFPE, SIGILL, SIGTRAP,
};
static const int kNumHandledSignals =
    (int)(sizeof(kHandledSignals) / sizeof(kHandledSignals[0]));

// Prior actions for each handled signal. Chained on the way out so
// Crashlytics / Sentry / Android's default report still fires after
// us. Storing struct sigaction (not function pointers) so SA_SIGINFO
// vs simple SA_HANDLER chaining works.
static struct sigaction g_prior_actions[6];

// Absolute path of the crash record file. Set once via
// nativeInstall(filePath). Must be a stable string in process memory
// (Java handles UTF-8 -> heap copy). Bounded by PATH_MAX (4096 on
// Android) so a stack buffer is fine.
static char g_record_path[4096];
static int g_installed = 0;

// Re-entrancy guard: if a SECOND fatal signal fires while we're in
// the middle of writing the first record, just chain to the prior
// handler and exit. Double-faults are rare but we mustn't loop.
static volatile sig_atomic_t g_in_handler = 0;

// --- async-signal-safe number formatters ---------------------------
//
// snprintf is NOT async-signal-safe (it acquires the C locale lock).
// We need to emit decimal + hex into the record by hand.

// Write `n` as decimal into `buf` (max 24 chars). Returns count.
static size_t write_dec(char *buf, uint64_t n) {
    if (n == 0) { buf[0] = '0'; return 1; }
    char tmp[24];
    size_t i = 0;
    while (n > 0) {
        tmp[i++] = (char)('0' + (n % 10));
        n /= 10;
    }
    // Reverse into output.
    for (size_t j = 0; j < i; ++j) buf[j] = tmp[i - 1 - j];
    return i;
}

// Write `p` as 0-padded hex (16 chars, lower) into `buf`. Used for
// addresses + stack frames so the dashboard renders them as proper
// pointers.
static size_t write_hex(char *buf, uintptr_t p) {
    static const char hex[] = "0123456789abcdef";
    int width = sizeof(uintptr_t) * 2;
    for (int i = width - 1; i >= 0; --i) {
        buf[i] = hex[p & 0xF];
        p >>= 4;
    }
    return (size_t)width;
}

// --- stack unwind --------------------------------------------------

#define MAX_FRAMES 64
typedef struct {
    uintptr_t frames[MAX_FRAMES];
    int count;
} UnwindState;

static _Unwind_Reason_Code unwind_cb(struct _Unwind_Context *ctx, void *arg) {
    UnwindState *state = (UnwindState *)arg;
    if (state->count >= MAX_FRAMES) return _URC_END_OF_STACK;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc == 0) return _URC_END_OF_STACK;
    state->frames[state->count++] = pc;
    return _URC_NO_REASON;
}

// --- handler body --------------------------------------------------

static void write_record(int signo, siginfo_t *info) {
    int fd = open(g_record_path,
                  O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;

    // Format: "v1|<signo>|<si_code>|<addr>|<ts_ms>|<thread_name>|<frame0,frame1,...>\n"
    char buf[64];
    // Header — version + delimiter.
    write(fd, "v1|", 3);

    size_t n = write_dec(buf, (uint64_t)signo);
    write(fd, buf, n);
    write(fd, "|", 1);

    n = write_dec(buf, (uint64_t)(info ? info->si_code : 0));
    write(fd, buf, n);
    write(fd, "|", 1);

    // Faulting address. For SIGSEGV/SIGBUS this is the bad pointer;
    // for SIGABRT/SIGILL it's the PC where the trap fired.
    uintptr_t addr = info ? (uintptr_t)info->si_addr : 0;
    n = write_hex(buf, addr);
    write(fd, "0x", 2);
    write(fd, buf, n);
    write(fd, "|", 1);

    // Timestamp in millis since epoch — clock_gettime is signal-safe.
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    uint64_t ms = (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)(ts.tv_nsec / 1000000);
    n = write_dec(buf, ms);
    write(fd, buf, n);
    write(fd, "|", 1);

    // Thread name (best effort). pthread_getname_np is NOT documented
    // as async-signal-safe on Android but in practice the bionic impl
    // just reads /proc/self/task/<tid>/comm which is safe.
    // Read the thread name straight from /proc/self/comm. This is
    // async-signal-safe (open/read/close are) and, unlike
    // pthread_getname_np, isn't gated behind API 26 in bionic.
    char thread_name[16] = {0};
    int tn_fd = open("/proc/self/comm", O_RDONLY);
    if (tn_fd >= 0) {
        ssize_t tn_r = read(tn_fd, thread_name, sizeof(thread_name) - 1);
        close(tn_fd);
        for (ssize_t i = 0; i < tn_r; ++i) {
            if (thread_name[i] == '\n') { thread_name[i] = 0; break; }
        }
    }
    // Strip any pipes that could confuse the parser (paranoia).
    for (size_t i = 0; i < sizeof(thread_name) && thread_name[i]; ++i) {
        if (thread_name[i] == '|') thread_name[i] = '_';
    }
    size_t tn_len = strnlen(thread_name, sizeof(thread_name));
    write(fd, thread_name, tn_len);
    write(fd, "|", 1);

    // Stack unwind. _Unwind_Backtrace is signal-safe per ABI doc.
    UnwindState state = {.count = 0};
    _Unwind_Backtrace(unwind_cb, &state);
    for (int i = 0; i < state.count; ++i) {
        char hexbuf[24];
        write(fd, "0x", 2);
        size_t h = write_hex(hexbuf, state.frames[i]);
        write(fd, hexbuf, h);
        if (i + 1 < state.count) write(fd, ",", 1);
    }
    write(fd, "\n", 1);
    close(fd);
}

static void handle_signal(int signo, siginfo_t *info, void *ctx) {
    // Re-entrancy guard. If we're already handling a signal, just
    // chain — don't try to record again. The host app is in an
    // undefined state at this point.
    if (g_in_handler) {
        goto chain;
    }
    g_in_handler = 1;

    if (g_installed) {
        write_record(signo, info);
    }

chain:
    // Restore the prior action + re-raise so Crashlytics / Sentry
    // hooks downstream still fire AND the OS still produces its
    // tombstone in /data/tombstones/. This is the recommended
    // chained-handler pattern from Google Breakpad.
    for (int i = 0; i < kNumHandledSignals; ++i) {
        if (kHandledSignals[i] == signo) {
            // Re-install the prior action (could be SIG_DFL).
            sigaction(signo, &g_prior_actions[i], NULL);
            break;
        }
    }
    // Unblock the signal + raise it again. Tail-call to the prior
    // handler / default disposition.
    sigset_t set;
    sigemptyset(&set);
    sigaddset(&set, signo);
    pthread_sigmask(SIG_UNBLOCK, &set, NULL);
    raise(signo);
    (void)ctx;
}

// --- public ABI ----------------------------------------------------

// Called from CrashHandler.kt via JNI. `path` is the UTF-8 absolute
// file path where the next fatal signal will write its record.
int replay_install_signal_handler(const char *path) {
    if (g_installed) return 0; // idempotent
    size_t plen = strnlen(path, sizeof(g_record_path) - 1);
    memcpy(g_record_path, path, plen);
    g_record_path[plen] = '\0';

    // Use an alternate signal stack — the interrupted thread's
    // stack may be the very thing that's corrupt (stack overflow
    // SIGSEGVs). Without this, the handler would re-fault on entry.
    static stack_t alt_stack;
    static char alt_stack_buf[SIGSTKSZ];
    alt_stack.ss_sp = alt_stack_buf;
    alt_stack.ss_size = sizeof(alt_stack_buf);
    alt_stack.ss_flags = 0;
    if (sigaltstack(&alt_stack, NULL) != 0) {
        LOGW("sigaltstack failed: errno=%d", errno);
        // Continue without alt stack — better to handle most signals
        // than no signals.
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = handle_signal;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    // Block all other handled signals during the handler. Prevents
    // SIGABRT-in-SIGSEGV-handler kind of re-entry.
    for (int i = 0; i < kNumHandledSignals; ++i) {
        sigaddset(&sa.sa_mask, kHandledSignals[i]);
    }

    for (int i = 0; i < kNumHandledSignals; ++i) {
        if (sigaction(kHandledSignals[i], &sa, &g_prior_actions[i]) != 0) {
            LOGW("sigaction signo=%d failed: errno=%d",
                 kHandledSignals[i], errno);
        }
    }
    g_installed = 1;
    return 1;
}

// JNI entry point — kept thin so all the logic stays in plain C.
#include <jni.h>

JNIEXPORT jboolean JNICALL
Java_com_replayfy_android_internal_crash_NativeCrashHandler_nativeInstall(
    JNIEnv *env, jclass clazz, jstring jpath) {
    (void)clazz;
    if (!jpath) return JNI_FALSE;
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    if (!path) return JNI_FALSE;
    int ok = replay_install_signal_handler(path);
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}
