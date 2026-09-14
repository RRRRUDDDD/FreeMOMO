#include "secneo_patch.hpp"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

namespace momo_secneo {
namespace {

struct InstructionSignature {
    uintptr_t offset;
    uint32_t instruction;
};

constexpr InstructionSignature kSignatures[] = {
    {0x1c8c8, 0xd2800708},
    {0x1c8cc, 0xd4000001},
    {0x1cb48, 0xd28007e8},
    {0x1cb4c, 0xd4000001},
    {0x1cb50, 0xb13ffc1f},
    {0x1d740, 0x7100be1f},
    {kPatchOffset, kOriginalInstruction},
    {0x1db70, 0xd28015a8},
    {0x1db74, 0xd4000001},
};

uint32_t LoadInstruction(uintptr_t address) {
    return __atomic_load_n(reinterpret_cast<const uint32_t*>(address), __ATOMIC_ACQUIRE);
}

bool IsPayloadMapLine(char* line, uintptr_t* base) {
    char* dash = strchr(line, '-');
    char* permissions = dash == nullptr ? nullptr : strchr(dash + 1, ' ');
    if (permissions == nullptr || strncmp(permissions + 1, "rwxp ", 5) != 0) {
        return false;
    }

    char* end_of_start = nullptr;
    char* end_of_end = nullptr;
    const unsigned long long start = strtoull(line, &end_of_start, 16);
    const unsigned long long end = strtoull(dash + 1, &end_of_end, 16);
    if (end_of_start != dash || end_of_end != permissions ||
        end <= start || end - start != kPayloadSize) {
        return false;
    }

    unsigned long long offset = 0;
    unsigned int major = 0;
    unsigned int minor = 0;
    unsigned long inode = 0;
    const int fields = sscanf(permissions + 6, "%llx %x:%x %lu",
                              &offset, &major, &minor, &inode);
    if (fields != 4 || offset != 0 || major != 0 || minor != 0 || inode != 0) {
        return false;
    }

    *base = static_cast<uintptr_t>(start);
    return true;
}

bool CollectMapLine(char* line, ScanResult* scan) {
    uintptr_t base = 0;
    if (!IsPayloadMapLine(line, &base)) return true;
    if (scan->candidates.Add(base)) return true;
    scan->result = PatchResult::kCandidateOverflow;
    return false;
}

}  // namespace

bool CandidateSet::Add(uintptr_t base) {
    if (count > kMaxPayloadCandidates) return false;
    for (unsigned int index = 0; index < count; ++index) {
        if (bases[index] == base) return true;
    }
    if (count == kMaxPayloadCandidates) return false;
    bases[count++] = base;
    return true;
}

bool CandidateSet::Equals(const CandidateSet& other) const {
    if (count != other.count || count > kMaxPayloadCandidates) return false;
    // /proc maps is normally ordered, but identity must not depend on that order.
    for (unsigned int index = 0; index < count; ++index) {
        bool found = false;
        for (unsigned int other_index = 0; other_index < other.count; ++other_index) {
            if (bases[index] == other.bases[other_index]) {
                found = true;
                break;
            }
        }
        if (!found) return false;
    }
    return true;
}

long ForcedScanIntervalMicroseconds(long elapsed_microseconds) {
    if (elapsed_microseconds < 1000000L) return 8000L;
    if (elapsed_microseconds < 3000000L) return 50000L;
    return 250000L;
}

PatchResult PatchPayload(uintptr_t base, size_t size) {
    if (base == 0 || size != kPayloadSize) return PatchResult::kSignatureMismatch;

    constexpr unsigned char kElfMagic[] = {0x7f, 'E', 'L', 'F'};
    if (memcmp(reinterpret_cast<const void*>(base), kElfMagic, sizeof(kElfMagic)) != 0) {
        return PatchResult::kSignatureMismatch;
    }

    for (const InstructionSignature& signature : kSignatures) {
        const uint32_t actual = LoadInstruction(base + signature.offset);
        if (signature.offset == kPatchOffset) {
            if (actual != kOriginalInstruction && actual != kPatchedInstruction) {
                return PatchResult::kSignatureMismatch;
            }
        } else if (actual != signature.instruction) {
            return PatchResult::kSignatureMismatch;
        }
    }

    auto* instruction = reinterpret_cast<uint32_t*>(base + kPatchOffset);
    if (LoadInstruction(reinterpret_cast<uintptr_t>(instruction)) == kPatchedInstruction) {
        return PatchResult::kAlreadyPatched;
    }

    __atomic_store_n(instruction, kPatchedInstruction, __ATOMIC_RELEASE);
    __builtin___clear_cache(reinterpret_cast<char*>(instruction),
                            reinterpret_cast<char*>(instruction + 1));
    return LoadInstruction(reinterpret_cast<uintptr_t>(instruction)) == kPatchedInstruction
        ? PatchResult::kPatched
        : PatchResult::kWriteFailed;
}

ScanResult ScanAndPatchSelf() {
    ScanResult scan;
    const int maps = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (maps < 0) {
        scan.result = PatchResult::kScanFailed;
        return scan;
    }

    char buffer[32768];
    size_t used = 0;
    while (true) {
        const ssize_t bytes = read(maps, buffer + used, sizeof(buffer) - used - 1);
        if (bytes < 0 && errno == EINTR) continue;
        if (bytes < 0) {
            scan.result = PatchResult::kScanFailed;
            close(maps);
            return scan;
        }

        const size_t available = used + static_cast<size_t>(bytes);
        buffer[available] = '\0';
        char* cursor = buffer;
        char* end = buffer + available;
        while (cursor < end) {
            char* newline = static_cast<char*>(memchr(cursor, '\n', end - cursor));
            if (newline == nullptr) break;
            *newline = '\0';
            if (!CollectMapLine(cursor, &scan)) {
                close(maps);
                return scan;
            }
            cursor = newline + 1;
        }

        used = static_cast<size_t>(end - cursor);
        if (bytes == 0) {
            if (used > 0 && !CollectMapLine(cursor, &scan)) {
                close(maps);
                return scan;
            }
            break;
        }
        if (used == sizeof(buffer) - 1) {
            // A truncated line cannot establish a complete candidate set.
            scan.result = PatchResult::kScanFailed;
            close(maps);
            return scan;
        }
        if (used > 0 && cursor != buffer) {
            memmove(buffer, cursor, used);
        }
    }

    close(maps);
    // Collect the entire bounded set before any write, including on success.
    // This snapshot does not pin VMAs; concurrent unmap/protection changes remain unsafe.
    for (unsigned int index = 0; index < scan.candidates.count; ++index) {
        scan.base = scan.candidates.bases[index];
        scan.result = PatchPayload(scan.base, kPayloadSize);
        if (scan.result != PatchResult::kSignatureMismatch) break;
    }
    return scan;
}

}  // namespace momo_secneo
