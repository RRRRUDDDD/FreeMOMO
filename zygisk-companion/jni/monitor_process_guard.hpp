#pragma once

#include "secneo_patch.hpp"

#include <sys/types.h>

namespace momo_secneo {

enum class GuardInstallResult : int {
    kInstalled = 0,
    kSignalFailed = 1,
    kAtforkFailed = 2,
};

struct ForkChildObserver {
    void* context = nullptr;
    void (*on_child)(void* context, pid_t pid, pid_t ppid, PatchResult result) = nullptr;
};

// Owner-pid crash handlers are inherited across fork/clone. A later child that
// hits a fatal fault (GitHub #1: SIGSEGV at 0x79c) _exit(0) instead of
// signaling the parent. pthread_atfork also re-applies the self maps patch.
// CLONE_THREAD workers keep the owner pid and still chain to the previous
// handler. Not async-signal-safe to log from the crash handler.
GuardInstallResult InstallForkChildGuard(const ForkChildObserver& observer = {});
pid_t ForkChildGuardOwnerPid();
bool IsForkChildProcess();
PatchResult PatchForkChildSelf();

}  // namespace momo_secneo
