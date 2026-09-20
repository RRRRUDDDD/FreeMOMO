#include "monitor_process_guard.hpp"

#include <pthread.h>
#include <signal.h>
#include <unistd.h>

namespace momo_secneo {
namespace {

constexpr int kHandledSignals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGTRAP, SIGSYS};

struct SavedAction {
    int sig = 0;
    struct sigaction previous = {};
    bool installed = false;
};

pid_t g_owner_pid = 0;
bool g_installed = false;
ForkChildObserver g_observer;
SavedAction g_saved[sizeof(kHandledSignals) / sizeof(kHandledSignals[0])];

SavedAction* FindSaved(int sig) {
    for (unsigned int index = 0; index < sizeof(g_saved) / sizeof(g_saved[0]); ++index) {
        if (g_saved[index].installed && g_saved[index].sig == sig) return &g_saved[index];
    }
    return nullptr;
}

void ChainPrevious(int sig, siginfo_t* info, void* context) {
    SavedAction* saved = FindSaved(sig);
    if (saved == nullptr) {
        signal(sig, SIG_DFL);
        raise(sig);
        _exit(128 + sig);
    }
    const struct sigaction& previous = saved->previous;
    if ((previous.sa_flags & SA_SIGINFO) != 0) {
        if (previous.sa_sigaction != nullptr) previous.sa_sigaction(sig, info, context);
        return;
    }
    if (previous.sa_handler == SIG_IGN) return;
    if (previous.sa_handler == SIG_DFL || previous.sa_handler == nullptr) {
        sigaction(sig, &previous, nullptr);
        raise(sig);
        return;
    }
    previous.sa_handler(sig);
}

void ForkChildCrashHandler(int sig, siginfo_t* info, void* context) {
    if (g_owner_pid != 0 && getpid() != g_owner_pid) _exit(0);
    ChainPrevious(sig, info, context);
}

void AtForkChild() {
    const PatchResult result = PatchForkChildSelf();
    if (g_observer.on_child != nullptr) {
        g_observer.on_child(g_observer.context, getpid(), getppid(), result);
    }
}

bool InstallOneSignal(unsigned int index, int sig) {
    struct sigaction action = {};
    action.sa_sigaction = ForkChildCrashHandler;
    action.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&action.sa_mask);
    SavedAction& saved = g_saved[index];
    if (sigaction(sig, &action, &saved.previous) != 0) return false;
    if ((saved.previous.sa_flags & SA_ONSTACK) != 0) {
        action.sa_flags |= SA_ONSTACK;
        if (sigaction(sig, &action, nullptr) != 0) return false;
    }
    saved.sig = sig;
    saved.installed = true;
    return true;
}

}  // namespace

PatchResult PatchForkChildSelf() {
    ScanResult scan = ScanAndPatchSelf();
    if (scan.result != PatchResult::kNotFound) return scan.result;
    scan = ScanAndPatchSelf();
    return scan.result;
}

pid_t ForkChildGuardOwnerPid() { return g_owner_pid; }

bool IsForkChildProcess() { return g_owner_pid != 0 && getpid() != g_owner_pid; }

GuardInstallResult InstallForkChildGuard(const ForkChildObserver& observer) {
    g_observer = observer;
    g_owner_pid = getpid();
    if (!g_installed) {
        bool signals_ok = true;
        for (unsigned int index = 0;
             index < sizeof(kHandledSignals) / sizeof(kHandledSignals[0]); ++index) {
            if (!InstallOneSignal(index, kHandledSignals[index])) signals_ok = false;
        }
        const int atfork_error = pthread_atfork(nullptr, nullptr, AtForkChild);
        g_installed = true;
        if (atfork_error != 0) return GuardInstallResult::kAtforkFailed;
        if (!signals_ok) return GuardInstallResult::kSignalFailed;
    }
    return GuardInstallResult::kInstalled;
}

}  // namespace momo_secneo
