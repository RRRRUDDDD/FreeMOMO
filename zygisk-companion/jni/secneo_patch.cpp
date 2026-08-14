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

bool CheckMapLine(char* line, ScanResult* scan) {
    uintptr_t base = 0;
    if (!IsPayloadMapLine(line, &base)) return false;

    ++scan->candidates;
    const PatchResult result = PatchPayload(base, kPayloadSize);
    scan->result = result;
    scan->base = base;
    return result == PatchResult::kPatched ||
        result == PatchResult::kAlreadyPatched ||
        result == PatchResult::kWriteFailed;
}

}  // namespace

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
    const int maps = open("/proc/self/maps", O_RDONLY | O_CLOEXEC);
    if (maps < 0) return {PatchResult::kNotFound, 0, 0};

    ScanResult scan = {PatchResult::kNotFound, 0, 0};
    char buffer[32768];
    size_t used = 0;
    bool finished = false;
    while (!finished) {
        ssize_t bytes = read(maps, buffer + used, sizeof(buffer) - used - 1);
        if (bytes < 0 && errno == EINTR) continue;
        if (bytes <= 0) finished = true;

        const size_t available = used + (bytes > 0 ? static_cast<size_t>(bytes) : 0);
        buffer[available] = '\0';
        char* cursor = buffer;
        char* end = buffer + available;
        while (cursor < end) {
            char* newline = static_cast<char*>(memchr(cursor, '\n', end - cursor));
            if (newline == nullptr) break;
            *newline = '\0';
            if (CheckMapLine(cursor, &scan)) {
                close(maps);
                return scan;
            }
            cursor = newline + 1;
        }

        used = static_cast<size_t>(end - cursor);
        if (used == sizeof(buffer) - 1) {
            used = 0;
        } else if (used > 0 && cursor != buffer) {
            memmove(buffer, cursor, used);
        }
    }

    if (used > 0) {
        buffer[used] = '\0';
        CheckMapLine(buffer, &scan);
    }

    close(maps);
    return scan;
}

}  // namespace momo_secneo
