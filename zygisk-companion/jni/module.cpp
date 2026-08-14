#include "secneo_patch.hpp"
#include "zygisk.hpp"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

namespace {

constexpr char kTargetProcess[] = "com.maimemo.android.momo";
constexpr char kLogTag[] = "FreeMOMO.Zygisk";
constexpr long kTimeoutMilliseconds = 10000;
constexpr long kFallbackScanMicroseconds = 8000;
constexpr long kMismatchGraceMicroseconds = 20000;

long ElapsedMicroseconds(const timespec& start, const timespec& end) {
    return (end.tv_sec - start.tv_sec) * 1000000L +
        (end.tv_nsec - start.tv_nsec) / 1000L;
}

void SleepFor(long microseconds) {
    timespec duration = {microseconds / 1000000L,
                         (microseconds % 1000000L) * 1000L};
    while (nanosleep(&duration, &duration) != 0) {}
}

bool ReadVirtualPages(int statm, unsigned long long* pages) {
    if (statm < 0 || lseek(statm, 0, SEEK_SET) < 0) return false;
    char buffer[64];
    ssize_t bytes;
    do {
        bytes = read(statm, buffer, sizeof(buffer) - 1);
    } while (bytes < 0 && errno == EINTR);
    if (bytes <= 0) return false;
    buffer[bytes] = '\0';
    char* end = nullptr;
    const unsigned long long value = strtoull(buffer, &end, 10);
    if (end == buffer) return false;
    *pages = value;
    return true;
}

void* MonitorPayload(void*) {
    timespec started = {};
    clock_gettime(CLOCK_MONOTONIC, &started);
    bool saw_mismatch = false;
    bool pending_candidate = false;
    unsigned int mismatch_candidates = 0;
    unsigned int attempts = 0;
    unsigned int scans = 0;
    unsigned long long previous_pages = 0;
    bool have_previous_pages = false;
    long last_scan_us = -kFallbackScanMicroseconds;
    long first_mismatch_us = -1;
    const int statm = open("/proc/self/statm", O_RDONLY | O_CLOEXEC);

    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "SecNeo monitor started pid=%d timeout_ms=%ld",
                        getpid(), kTimeoutMilliseconds);

    while (true) {
        ++attempts;
        timespec now = {};
        clock_gettime(CLOCK_MONOTONIC, &now);
        const long elapsed_us = ElapsedMicroseconds(started, now);
        unsigned long long pages = 0;
        const bool have_pages = ReadVirtualPages(statm, &pages);
        const bool pages_changed = have_pages && have_previous_pages &&
            pages != previous_pages;
        const bool should_scan = attempts == 1 || !have_pages || pages_changed ||
            pending_candidate || elapsed_us - last_scan_us >= kFallbackScanMicroseconds;
        if (have_pages) {
            previous_pages = pages;
            have_previous_pages = true;
        }

        momo_secneo::ScanResult scan = {
            momo_secneo::PatchResult::kNotFound, 0, 0};
        if (should_scan) {
            scan = momo_secneo::ScanAndPatchSelf();
            ++scans;
            last_scan_us = elapsed_us;
        }

        if (scan.result == momo_secneo::PatchResult::kPatched) {
            __android_log_print(
                ANDROID_LOG_INFO, kLogTag,
                "SecNeo maps branch patched pid=%d base=0x%lx offset=0x%lx attempts=%u scans=%u elapsed_us=%ld",
                getpid(), static_cast<unsigned long>(scan.base),
                static_cast<unsigned long>(momo_secneo::kPatchOffset),
                attempts, scans, elapsed_us);
            close(statm);
            return nullptr;
        }
        if (scan.result == momo_secneo::PatchResult::kAlreadyPatched) {
            __android_log_print(
                ANDROID_LOG_INFO, kLogTag,
                "SecNeo maps branch already patched pid=%d base=0x%lx attempts=%u scans=%u elapsed_us=%ld",
                getpid(), static_cast<unsigned long>(scan.base), attempts, scans, elapsed_us);
            close(statm);
            return nullptr;
        }
        if (scan.result == momo_secneo::PatchResult::kWriteFailed) {
            __android_log_print(
                ANDROID_LOG_ERROR, kLogTag,
                "SecNeo maps branch write verification failed pid=%d base=0x%lx attempts=%u scans=%u elapsed_us=%ld",
                getpid(), static_cast<unsigned long>(scan.base), attempts, scans, elapsed_us);
            close(statm);
            return nullptr;
        }
        if (scan.result == momo_secneo::PatchResult::kSignatureMismatch) {
            saw_mismatch = true;
            pending_candidate = true;
            mismatch_candidates += scan.candidates;
            if (first_mismatch_us < 0) first_mismatch_us = elapsed_us;
            if (elapsed_us - first_mismatch_us >= kMismatchGraceMicroseconds) {
                __android_log_print(
                    ANDROID_LOG_WARN, kLogTag,
                    "SecNeo payload signature mismatch; no patch applied pid=%d observations=%u attempts=%u scans=%u elapsed_ms=%ld",
                    getpid(), mismatch_candidates, attempts, scans, elapsed_us / 1000L);
                close(statm);
                return nullptr;
            }
        } else if (scan.result == momo_secneo::PatchResult::kNotFound) {
            pending_candidate = false;
        }

        if (elapsed_us >= kTimeoutMilliseconds * 1000L) {
            if (saw_mismatch) {
                __android_log_print(
                    ANDROID_LOG_WARN, kLogTag,
                    "SecNeo payload signature mismatch; no patch applied pid=%d observations=%u attempts=%u scans=%u elapsed_ms=%ld",
                    getpid(), mismatch_candidates, attempts, scans, elapsed_us / 1000L);
            } else {
                __android_log_print(
                    ANDROID_LOG_WARN, kLogTag,
                    "SecNeo payload not found before timeout pid=%d attempts=%u scans=%u elapsed_ms=%ld",
                    getpid(), attempts, scans, elapsed_us / 1000L);
            }
            close(statm);
            return nullptr;
        }

        const long interval_us = elapsed_us < 1000000L ? 500L
            : (elapsed_us < 3000000L ? 2000L : 10000L);
        SleepFor(interval_us);
    }
}

class MomoSecNeoModule final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        target_process_ = false;
        if (args != nullptr && args->nice_name != nullptr) {
            const char* process_name = env_->GetStringUTFChars(args->nice_name, nullptr);
            if (process_name != nullptr) {
                target_process_ = strcmp(process_name, kTargetProcess) == 0;
                env_->ReleaseStringUTFChars(args->nice_name, process_name);
            }
        }
        if (!target_process_) api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs*) override {
        if (!target_process_) return;

        pthread_attr_t attributes;
        const int attribute_error = pthread_attr_init(&attributes);
        int error = attribute_error;
        if (error == 0) {
            error = pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
        }
        pthread_t thread;
        if (error == 0) error = pthread_create(&thread, &attributes, MonitorPayload, nullptr);
        if (attribute_error == 0) pthread_attr_destroy(&attributes);
        if (error != 0) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                "SecNeo monitor thread creation failed pid=%d error=%d",
                                getpid(), error);
        }
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs*) override {
        api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    bool target_process_ = false;
};

}  // namespace

REGISTER_ZYGISK_MODULE(MomoSecNeoModule)
