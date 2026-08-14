#include "secneo_patch.hpp"

extern "C" [[gnu::visibility("default")]] int momo_secneo_test_patch(
    void* payload, size_t size) {
    return static_cast<int>(
        momo_secneo::PatchPayload(reinterpret_cast<uintptr_t>(payload), size));
}

extern "C" [[gnu::visibility("default")]] int momo_secneo_test_scan() {
    return static_cast<int>(momo_secneo::ScanAndPatchSelf().result);
}
