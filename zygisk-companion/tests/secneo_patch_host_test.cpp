#include "secneo_patch.hpp"

#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
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

int g_failures = 0;

void Expect(bool condition, const char* test_name) {
    if (condition) {
        printf("PASS: %s\n", test_name);
        return;
    }
    fprintf(stderr, "FAIL: %s\n", test_name);
    ++g_failures;
}

class PayloadMapping {
public:
    PayloadMapping() {
        address_ = mmap(nullptr, momo_secneo::kPayloadSize,
                        PROT_READ | PROT_WRITE | PROT_EXEC,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    }

    ~PayloadMapping() {
        if (IsValid()) munmap(address_, momo_secneo::kPayloadSize);
    }

    PayloadMapping(const PayloadMapping&) = delete;
    PayloadMapping& operator=(const PayloadMapping&) = delete;

    bool IsValid() const { return address_ != MAP_FAILED; }

    uintptr_t Base() const { return reinterpret_cast<uintptr_t>(address_); }

    void Initialize(uint32_t patch_instruction = momo_secneo::kOriginalInstruction) {
        memset(address_, 0, momo_secneo::kPayloadSize);
        constexpr unsigned char kElfMagic[] = {0x7f, 'E', 'L', 'F'};
        memcpy(address_, kElfMagic, sizeof(kElfMagic));
        for (const Signature& signature : kSignatures) {
            const uint32_t instruction = signature.offset == momo_secneo::kPatchOffset
                ? patch_instruction
                : signature.instruction;
            memcpy(static_cast<unsigned char*>(address_) + signature.offset,
                   &instruction, sizeof(instruction));
        }
    }

    void WriteInstruction(uintptr_t offset, uint32_t instruction) {
        memcpy(static_cast<unsigned char*>(address_) + offset,
               &instruction, sizeof(instruction));
    }

    uint32_t ReadInstruction(uintptr_t offset) const {
        uint32_t instruction = 0;
        memcpy(&instruction, static_cast<const unsigned char*>(address_) + offset,
               sizeof(instruction));
        return instruction;
    }

private:
    void* address_ = MAP_FAILED;
};

void TestForcedScanBackoff() {
    Expect(momo_secneo::ForcedScanIntervalMicroseconds(0) == 8000,
           "forced scan starts at 8 ms");
    Expect(momo_secneo::ForcedScanIntervalMicroseconds(999999) == 8000,
           "forced scan remains 8 ms through the first second");
    Expect(momo_secneo::ForcedScanIntervalMicroseconds(1000000) == 50000,
           "forced scan backs off to 50 ms after one second");
    Expect(momo_secneo::ForcedScanIntervalMicroseconds(2999999) ==
               momo_secneo::ForcedScanIntervalMicroseconds(1000000),
           "middle forced scan phase is stable");
    Expect(momo_secneo::ForcedScanIntervalMicroseconds(3000000) == 250000,
           "forced scan backs off to 250 ms after three seconds");
    Expect(momo_secneo::ForcedScanIntervalMicroseconds(10000000) ==
               momo_secneo::ForcedScanIntervalMicroseconds(3000000),
           "late forced scan phase is stable through timeout");
}

void TestPatchPayload() {
    {
        PayloadMapping payload;
        Expect(payload.IsValid(), "allocate original fingerprint payload");
        if (payload.IsValid()) {
            payload.Initialize();
            Expect(momo_secneo::PatchPayload(payload.Base(), momo_secneo::kPayloadSize) ==
                       momo_secneo::PatchResult::kPatched,
                   "original fingerprint patches successfully");
            Expect(payload.ReadInstruction(momo_secneo::kPatchOffset) ==
                       momo_secneo::kPatchedInstruction,
                   "patch writes the replacement instruction");
        }
    }

    {
        PayloadMapping payload;
        Expect(payload.IsValid(), "allocate already-patched payload");
        if (payload.IsValid()) {
            payload.Initialize(momo_secneo::kPatchedInstruction);
            Expect(momo_secneo::PatchPayload(payload.Base(), momo_secneo::kPayloadSize) ==
                       momo_secneo::PatchResult::kAlreadyPatched,
                   "already-patched fingerprint is accepted");
        }
    }

    {
        PayloadMapping payload;
        Expect(payload.IsValid(), "allocate wrong-size payload fixture");
        if (payload.IsValid()) {
            payload.Initialize();
            Expect(momo_secneo::PatchPayload(payload.Base(),
                                              momo_secneo::kPayloadSize - 1) ==
                       momo_secneo::PatchResult::kSignatureMismatch,
                   "wrong payload size is rejected");
        }
    }

    {
        PayloadMapping payload;
        Expect(payload.IsValid(), "allocate wrong-ELF payload fixture");
        if (payload.IsValid()) {
            payload.Initialize();
            const unsigned char zero = 0;
            memcpy(reinterpret_cast<void*>(payload.Base()), &zero, sizeof(zero));
            Expect(momo_secneo::PatchPayload(payload.Base(), momo_secneo::kPayloadSize) ==
                       momo_secneo::PatchResult::kSignatureMismatch,
                   "wrong ELF magic is rejected");
        }
    }

    for (size_t index = 0; index < sizeof(kSignatures) / sizeof(kSignatures[0]); ++index) {
        PayloadMapping payload;
        char test_name[96];
        snprintf(test_name, sizeof(test_name), "signature mismatch at point %zu is rejected",
                 index + 1);
        if (!payload.IsValid()) {
            Expect(false, test_name);
            continue;
        }
        payload.Initialize();
        payload.WriteInstruction(kSignatures[index].offset,
                                 kSignatures[index].instruction ^ 0x1U);
        Expect(momo_secneo::PatchPayload(payload.Base(), momo_secneo::kPayloadSize) ==
                   momo_secneo::PatchResult::kSignatureMismatch,
               test_name);
    }
}

void TestScanner() {
    const momo_secneo::ScanResult empty = momo_secneo::ScanAndPatchSelf();
    Expect(empty.result == momo_secneo::PatchResult::kNotFound &&
               empty.candidates == 0,
           "scanner reports no candidate");

    {
        PayloadMapping payload;
        Expect(payload.IsValid(), "allocate scanner mismatch candidate");
        if (payload.IsValid()) {
            const momo_secneo::ScanResult mismatch = momo_secneo::ScanAndPatchSelf();
            Expect(mismatch.result == momo_secneo::PatchResult::kSignatureMismatch &&
                       mismatch.base == payload.Base() && mismatch.candidates == 1,
                   "scanner reports an invalid candidate");
        }
    }

    {
        PayloadMapping payload;
        Expect(payload.IsValid(), "allocate scanner success candidate");
        if (payload.IsValid()) {
            payload.Initialize();
            const momo_secneo::ScanResult patched = momo_secneo::ScanAndPatchSelf();
            Expect(patched.result == momo_secneo::PatchResult::kPatched &&
                       patched.base == payload.Base() && patched.candidates == 1,
                   "scanner finds and patches a valid candidate");
        }
    }
}

}  // namespace

int main() {
    TestForcedScanBackoff();
    TestPatchPayload();
    TestScanner();
    if (g_failures != 0) {
        fprintf(stderr, "%d host test(s) failed\n", g_failures);
        return 1;
    }
    printf("All SecNeo host tests passed.\n");
    return 0;
}
