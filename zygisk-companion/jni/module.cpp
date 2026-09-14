#include "monitor_policy.hpp"
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

bool ReadMonotonicMicroseconds(int64_t* microseconds) {
    timespec now = {};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return false;
    *microseconds = static_cast<int64_t>(now.tv_sec) * 1000000 + now.tv_nsec / 1000;
    return true;
}

void SleepFor(long microseconds) {
    timespec duration = {microseconds / 1000000L,
                         (microseconds % 1000000L) * 1000L};
    while (nanosleep(&duration, &duration) != 0 && errno == EINTR) {}
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
    const long page_size = sysconf(_SC_PAGESIZE);
    int64_t started_at_us = 0;
    if (!ReadMonotonicMicroseconds(&started_at_us)) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "SecNeo monotonic clock unavailable pid=%d", getpid());
        return nullptr;
    }
    momo_secneo::MonitorPolicy monitor(page_size, started_at_us);
    if (monitor.status() == momo_secneo::MonitorStatus::kUnsupportedPageSize) {
        __android_log_print(
            ANDROID_LOG_WARN, kLogTag,
            "SecNeo unsupported page size; no scan or patch attempted pid=%d page_size=%ld required=%ld",
            getpid(), page_size, momo_secneo::kSupportedPageSize);
        return nullptr;
    }

    unsigned int attempts = 0;
    unsigned long long previous_pages = 0;
    bool have_previous_pages = false;
    momo_secneo::ScanResult scan;
    const int statm = open("/proc/self/statm", O_RDONLY | O_CLOEXEC);
    int64_t now_us = started_at_us;

    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "SecNeo monitor started pid=%d timeout_ms=%ld page_size=%ld capacity=%u",
                        getpid(), static_cast<long>(momo_secneo::kMonitorTimeoutMicroseconds / 1000),
                        page_size, momo_secneo::kMaxPayloadCandidates);

    while (true) {
        ++attempts;
        unsigned long long pages = 0;
        const bool have_pages = ReadVirtualPages(statm, &pages);
        const bool pages_changed = have_pages && have_previous_pages &&
            pages != previous_pages;
        if (!ReadMonotonicMicroseconds(&now_us)) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                "SecNeo monotonic clock failed; monitor stopped pid=%d", getpid());
            if (statm >= 0) close(statm);
            return nullptr;
        }
        const bool should_scan = monitor.ShouldScan(now_us, pages_changed);
        if (have_pages) {
            previous_pages = pages;
            have_previous_pages = true;
        }

        if (should_scan) {
            scan = momo_secneo::ScanAndPatchSelf();
            // The deadline is checked before scanning. A scan's start time is
            // the observation timestamp; skipped polls never supply a scan.
            monitor.ObserveScan(now_us, scan);
        }

        if (monitor.status() != momo_secneo::MonitorStatus::kRunning) break;
        if (!ReadMonotonicMicroseconds(&now_us)) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                "SecNeo monotonic clock failed; monitor stopped pid=%d", getpid());
            if (statm >= 0) close(statm);
            return nullptr;
        }
        SleepFor(monitor.SleepMicroseconds(now_us));
    }

    int priority = ANDROID_LOG_WARN;
    const char* reason = "SecNeo monitor stopped";
    switch (monitor.status()) {
        case momo_secneo::MonitorStatus::kPatched:
            priority = ANDROID_LOG_INFO;
            reason = "SecNeo maps branch patched";
            break;
        case momo_secneo::MonitorStatus::kAlreadyPatched:
            priority = ANDROID_LOG_INFO;
            reason = "SecNeo maps branch already patched";
            break;
        case momo_secneo::MonitorStatus::kWriteFailed:
            priority = ANDROID_LOG_ERROR;
            reason = "SecNeo maps branch write verification failed";
            break;
        case momo_secneo::MonitorStatus::kSignatureMismatch:
            reason = "SecNeo stable candidate set signature mismatch; no patch applied";
            break;
        case momo_secneo::MonitorStatus::kTimedOut:
            reason = "SecNeo monitor timeout; no patch applied";
            break;
        case momo_secneo::MonitorStatus::kCandidateOverflow:
            reason = "SecNeo candidate capacity exceeded; no patch applied";
            break;
        case momo_secneo::MonitorStatus::kScanFailed:
            reason = "SecNeo maps scan incomplete; no patch applied";
            break;
        case momo_secneo::MonitorStatus::kRunning:
        case momo_secneo::MonitorStatus::kUnsupportedPageSize:
            break;
    }
    __android_log_print(
        priority, kLogTag,
        "%s pid=%d base=0x%lx offset=0x%lx candidates=%u observations=%u attempts=%u scans=%u elapsed_us=%ld",
        reason, getpid(), static_cast<unsigned long>(scan.base),
        static_cast<unsigned long>(momo_secneo::kPatchOffset), scan.candidates.count,
        monitor.mismatch_observations(), attempts, monitor.scans(),
        static_cast<long>(now_us - started_at_us));
    if (statm >= 0) close(statm);
    return nullptr;
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
