#include "monitor_policy.hpp"
#include "monitor_process_guard.hpp"
#include "secneo_patch.hpp"

#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <unistd.h>

namespace {

struct Signature {
    uintptr_t offset;
    uint32_t instruction;
};

constexpr Signature kSignatures[] = {
    {0x1c8c8, 0xd2800708},
    {0x1c8cc, 0xd4000001},
    {0x1cb48, 0xd28007e8},
    {0x1cb4c, 0xd4000001},
    {0x1cb50, 0xb13ffc1f},
    {0x1d740, 0x7100be1f},
    {momo_secneo::kPatchOffset, momo_secneo::kOriginalInstruction},
    {0x1db70, 0xd28015a8},
    {0x1db74, 0xd4000001},
};

int g_checks = 0;
int g_failures = 0;

void Expect(bool condition, const char* name) {
    ++g_checks;
    if (condition) {
        printf("PASS: %s\n", name);
        return;
    }
    fprintf(stderr, "FAIL: %s\n", name);
    ++g_failures;
}

class PayloadMapping {
public:
    PayloadMapping() {
        const long page_size = sysconf(_SC_PAGESIZE);
        if (page_size <= 0) return;
        allocation_size_ = momo_secneo::kPayloadSize + static_cast<size_t>(page_size) * 2;
        allocation_ = mmap(nullptr, allocation_size_, PROT_NONE,
                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (allocation_ == MAP_FAILED) return;
        address_ = static_cast<unsigned char*>(allocation_) + page_size;
        if (mprotect(address_, momo_secneo::kPayloadSize,
                     PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
            munmap(allocation_, allocation_size_);
            allocation_ = MAP_FAILED;
            address_ = MAP_FAILED;
        }
    }

    ~PayloadMapping() {
        if (allocation_ != MAP_FAILED) munmap(allocation_, allocation_size_);
    }

    PayloadMapping(const PayloadMapping&) = delete;
    PayloadMapping& operator=(const PayloadMapping&) = delete;

    bool IsValid() const { return address_ != MAP_FAILED; }
    uintptr_t Base() const { return reinterpret_cast<uintptr_t>(address_); }

    void Initialize() {
        memset(address_, 0, momo_secneo::kPayloadSize);
        constexpr unsigned char kElfMagic[] = {0x7f, 'E', 'L', 'F'};
        memcpy(address_, kElfMagic, sizeof(kElfMagic));
        for (const Signature& signature : kSignatures) {
            memcpy(static_cast<unsigned char*>(address_) + signature.offset,
                   &signature.instruction, sizeof(signature.instruction));
        }
    }

    uint32_t ReadInstruction(uintptr_t offset) const {
        uint32_t instruction = 0;
        memcpy(&instruction, static_cast<const unsigned char*>(address_) + offset,
               sizeof(instruction));
        return instruction;
    }

private:
    void* allocation_ = MAP_FAILED;
    size_t allocation_size_ = 0;
    void* address_ = MAP_FAILED;
};

bool WaitExited(pid_t pid, int expected_code, const char* name) {
    int status = 0;
    const pid_t waited = waitpid(pid, &status, 0);
    const bool ok = waited == pid && WIFEXITED(status) && WEXITSTATUS(status) == expected_code;
    Expect(ok, name);
    return ok;
}

bool WaitSignaled(pid_t pid, int expected_signal, const char* name) {
    int status = 0;
    const pid_t waited = waitpid(pid, &status, 0);
    const bool ok = waited == pid && WIFSIGNALED(status) && WTERMSIG(status) == expected_signal;
    Expect(ok, name);
    return ok;
}

void RaiseSegv() { raise(SIGSEGV); }

void TestOwnerStillFaults() {
    const pid_t pid = fork();
    if (pid == 0) {
        const auto installed = momo_secneo::InstallForkChildGuard();
        if (installed != momo_secneo::GuardInstallResult::kInstalled) _exit(2);
        RaiseSegv();
        _exit(3);
    }
    Expect(pid > 0, "fork owner-fault fixture");
    if (pid > 0) WaitSignaled(pid, SIGSEGV, "owner process still fatally faults");
}

void TestForkChildCrashExitsZero() {
    const pid_t pid = fork();
    if (pid == 0) {
        const auto installed = momo_secneo::InstallForkChildGuard();
        if (installed != momo_secneo::GuardInstallResult::kInstalled) _exit(2);
        const pid_t child = fork();
        if (child == 0) {
            RaiseSegv();
            _exit(3);
        }
        if (child < 0) _exit(4);
        int status = 0;
        const pid_t waited = waitpid(child, &status, 0);
        _exit(waited == child && WIFEXITED(status) && WEXITSTATUS(status) == 0 ? 0 : 5);
    }
    Expect(pid > 0, "fork child-crash fixture");
    if (pid > 0) WaitExited(pid, 0, "fork child SIGSEGV becomes exit 0");
}

void TestForkChildPatchesPayload() {
    if (sysconf(_SC_PAGESIZE) != momo_secneo::kSupportedPageSize) {
        Expect(true, "skip payload fork patch on unsupported host page size");
        return;
    }
    PayloadMapping payload;
    Expect(payload.IsValid(), "allocate fork-child payload");
    if (!payload.IsValid()) return;
    payload.Initialize();
    const auto installed = momo_secneo::InstallForkChildGuard();
    Expect(installed == momo_secneo::GuardInstallResult::kInstalled,
           "install guard before payload fork");
    const pid_t child = fork();
    if (child == 0) {
        const bool patched =
            payload.ReadInstruction(momo_secneo::kPatchOffset) == momo_secneo::kPatchedInstruction;
        const bool child_mark = momo_secneo::IsForkChildProcess();
        _exit(patched && child_mark ? 0 : 6);
    }
    Expect(child > 0, "fork payload child");
    if (child > 0) {
        WaitExited(child, 0, "atfork child patches inherited payload and sees child pid");
        Expect(payload.ReadInstruction(momo_secneo::kPatchOffset) ==
                   momo_secneo::kOriginalInstruction,
               "parent keeps its own copy of the payload instruction");
        Expect(!momo_secneo::IsForkChildProcess() &&
                   momo_secneo::ForkChildGuardOwnerPid() == getpid(),
               "parent remains the guard owner");
    }
}

}  // namespace

int main() {
    TestOwnerStillFaults();
    TestForkChildCrashExitsZero();
    TestForkChildPatchesPayload();
    printf("Monitor process guard: %d checks, %d failures.\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
