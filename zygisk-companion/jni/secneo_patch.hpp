#pragma once

#include <stddef.h>
#include <stdint.h>

namespace momo_secneo {

constexpr size_t kPayloadSize = 0x117000;
constexpr uintptr_t kPatchOffset = 0x1d744;
constexpr uint32_t kOriginalInstruction = 0x54001741;
constexpr uint32_t kPatchedInstruction = 0x34ff9e90;
constexpr unsigned int kMaxPayloadCandidates = 16;

enum class PatchResult : int {
    kNotFound = 0,
    kPatched = 1,
    kAlreadyPatched = 2,
    kSignatureMismatch = 3,
    kWriteFailed = 4,
    kCandidateOverflow = 5,
    kScanFailed = 6,
};

struct CandidateSet {
    uintptr_t bases[kMaxPayloadCandidates] = {};
    unsigned int count = 0;

    bool Add(uintptr_t base);
    bool Equals(const CandidateSet& other) const;
};

struct ScanResult {
    PatchResult result = PatchResult::kNotFound;
    // The result address is for diagnostics; monitor identity uses the whole set.
    uintptr_t base = 0;
    CandidateSet candidates;
};

// Shared elapsed-time tier for the poll beat: the sleep ladder (monitor
// policy) and the forced-scan ladder key on the same 1s/3s breakpoints and
// must not drift apart.
inline int BeatTier(long elapsed_microseconds) {
    if (elapsed_microseconds < 1000000L) return 0;
    if (elapsed_microseconds < 3000000L) return 1;
    return 2;
}

long ForcedScanIntervalMicroseconds(long elapsed_microseconds);
PatchResult PatchPayload(uintptr_t base, size_t size);
ScanResult ScanAndPatchSelf();

}  // namespace momo_secneo
