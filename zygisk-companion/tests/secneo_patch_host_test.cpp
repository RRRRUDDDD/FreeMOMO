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

int g_checks = 0;
int g_failures = 0;

void Expect(bool condition, const char* test_name) {
    ++g_checks;
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
        const long page_size = sysconf(_SC_PAGESIZE);
        if (page_size <= 0) return;
        // Guard pages keep neighboring fixtures from merging into a larger VMA.
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
    void* allocation_ = MAP_FAILED;
    size_t allocation_size_ = 0;
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
               empty.candidates.count == 0,
           "scanner reports no candidate");

    {
        PayloadMapping payload;
        Expect(payload.IsValid(), "allocate scanner mismatch candidate");
        if (payload.IsValid()) {
            const momo_secneo::ScanResult mismatch = momo_secneo::ScanAndPatchSelf();
            Expect(mismatch.result == momo_secneo::PatchResult::kSignatureMismatch &&
                       mismatch.base == payload.Base() && mismatch.candidates.count == 1 &&
                       mismatch.candidates.bases[0] == payload.Base(),
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
                       patched.base == payload.Base() && patched.candidates.count == 1 &&
                       patched.candidates.bases[0] == payload.Base(),
                   "scanner finds and patches a valid candidate");
        }
    }
}

bool Contains(const momo_secneo::CandidateSet& candidates, uintptr_t base) {
    for (unsigned int index = 0; index < candidates.count; ++index) {
        if (candidates.bases[index] == base) return true;
    }
    return false;
}

void TestCompleteCandidateSet() {
    PayloadMapping first;
    PayloadMapping second;
    PayloadMapping third;
    Expect(first.IsValid() && second.IsValid() && third.IsValid(),
           "allocate three isolated scanner candidates");
    if (!first.IsValid() || !second.IsValid() || !third.IsValid()) return;

    const momo_secneo::ScanResult mismatch = momo_secneo::ScanAndPatchSelf();
    Expect(mismatch.result == momo_secneo::PatchResult::kSignatureMismatch &&
               mismatch.candidates.count == 3 && Contains(mismatch.candidates, first.Base()) &&
               Contains(mismatch.candidates, second.Base()) && Contains(mismatch.candidates, third.Base()),
           "mismatch scan includes every candidate identity");

    first.Initialize();
    second.Initialize();
    third.Initialize();
    const momo_secneo::ScanResult patched = momo_secneo::ScanAndPatchSelf();
    Expect(patched.result == momo_secneo::PatchResult::kPatched && patched.candidates.count == 3 &&
               Contains(patched.candidates, first.Base()) && Contains(patched.candidates, second.Base()) &&
               Contains(patched.candidates, third.Base()),
           "successful scan still includes all candidates after the first valid one");
    const unsigned int patched_count =
        (first.ReadInstruction(momo_secneo::kPatchOffset) == momo_secneo::kPatchedInstruction ? 1U : 0U) +
        (second.ReadInstruction(momo_secneo::kPatchOffset) == momo_secneo::kPatchedInstruction ? 1U : 0U) +
        (third.ReadInstruction(momo_secneo::kPatchOffset) == momo_secneo::kPatchedInstruction ? 1U : 0U);
    Expect(patched_count == 1, "one scan patches at most one validated payload");

    const momo_secneo::ScanResult already = momo_secneo::ScanAndPatchSelf();
    Expect(already.result == momo_secneo::PatchResult::kAlreadyPatched && already.candidates.count == 3,
           "already-patched scan also returns the complete candidate set");
}

void TestCandidateCapacity() {
    PayloadMapping payloads[momo_secneo::kMaxPayloadCandidates + 1];
    bool valid = true;
    for (PayloadMapping& payload : payloads) {
        valid = valid && payload.IsValid();
        if (payload.IsValid()) payload.Initialize();
    }
    Expect(valid, "allocate enough isolated mappings to exceed candidate capacity");
    if (!valid) return;

    const momo_secneo::ScanResult overflow = momo_secneo::ScanAndPatchSelf();
    Expect(overflow.result == momo_secneo::PatchResult::kCandidateOverflow &&
               overflow.candidates.count == momo_secneo::kMaxPayloadCandidates && overflow.base == 0,
           "capacity overflow returns a bounded diagnostic result");
    bool untouched = true;
    for (const PayloadMapping& payload : payloads) {
        untouched = untouched && payload.ReadInstruction(momo_secneo::kPatchOffset) ==
            momo_secneo::kOriginalInstruction;
    }
    Expect(untouched, "overflow is discovered before any candidate instruction is patched");

    Expect(mprotect(reinterpret_cast<void*>(payloads[0].Base()), momo_secneo::kPayloadSize,
                    PROT_READ | PROT_WRITE) == 0,
           "remove one executable mapping to reach the exact candidate limit");
    const momo_secneo::ScanResult at_limit = momo_secneo::ScanAndPatchSelf();
    Expect(at_limit.result == momo_secneo::PatchResult::kPatched &&
               at_limit.candidates.count == momo_secneo::kMaxPayloadCandidates &&
               !Contains(at_limit.candidates, payloads[0].Base()),
           "exact capacity succeeds and non-executable mappings remain excluded");
}

void TestCandidateIdentity() {
    momo_secneo::CandidateSet first;
    momo_secneo::CandidateSet reversed;
    first.Add(0x1000);
    first.Add(0x2000);
    reversed.Add(0x2000);
    reversed.Add(0x1000);
    Expect(first.Equals(reversed), "candidate identity ignores enumeration order");
    Expect(first.Add(0x1000) && first.count == 2, "duplicate map observations do not grow the set");
    reversed.bases[0] = 0x3000;
    Expect(!first.Equals(reversed), "candidate identity detects replacement at unchanged count");
}

}  // namespace

int main() {
    if (sysconf(_SC_PAGESIZE) != 4096) {
        fprintf(stderr, "Fingerprint mapping harness requires 4096-byte pages.\n");
        return 1;
    }
    TestForcedScanBackoff();
    TestPatchPayload();
    TestScanner();
    TestCompleteCandidateSet();
    TestCandidateCapacity();
    TestCandidateIdentity();
    printf("SecNeo fingerprints and scanner: %d checks, %d failures.\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
