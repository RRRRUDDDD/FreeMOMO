#include "monitor_runner.hpp"

#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <time.h>
#include <unistd.h>

namespace momo_secneo {
namespace {

long PageSize(void*) { return sysconf(_SC_PAGESIZE); }

bool ReadMonotonic(void*, int64_t* microseconds) {
    timespec now = {};
    if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return false;
    *microseconds = static_cast<int64_t>(now.tv_sec) * 1000000 + now.tv_nsec / 1000;
    return true;
}

void Sleep(void*, long microseconds) {
    timespec duration = {microseconds / 1000000L, (microseconds % 1000000L) * 1000L};
    while (nanosleep(&duration, &duration) != 0 && errno == EINTR) {}
}

int OpenStatm(void*) { return open("/proc/self/statm", O_RDONLY | O_CLOEXEC); }
void CloseStatm(void*, int fd) { close(fd); }

bool ReadPages(void*, int fd, unsigned long long* pages) {
    if (fd < 0 || lseek(fd, 0, SEEK_SET) < 0) return false;
    char buffer[64];
    ssize_t bytes;
    do {
        bytes = read(fd, buffer, sizeof(buffer) - 1);
    } while (bytes < 0 && errno == EINTR);
    if (bytes <= 0) return false;
    buffer[bytes] = '\0';
    char* end = nullptr;
    const unsigned long long value = strtoull(buffer, &end, 10);
    if (end == buffer) return false;
    *pages = value;
    return true;
}

ScanResult Scan(void*) { return ScanAndPatchSelf(); }

const RunnerPlatform kSystemPlatform = {
    nullptr, PageSize, ReadMonotonic, OpenStatm, ReadPages, CloseStatm, Sleep, Scan,
};

bool Cancelled(const RunnerObserver& observer) {
    return observer.is_cancelled != nullptr && observer.is_cancelled(observer.context);
}

void Emit(const RunnerObserver& observer, RunnerEvent event, const RunnerResult& result) {
    if (observer.on_event != nullptr) observer.on_event(observer.context, event, result);
}

}  // namespace

const RunnerPlatform& SystemRunnerPlatform() { return kSystemPlatform; }

RunnerResult RunMonitor(const RunnerPlatform& platform, const RunnerObserver& observer) {
    RunnerResult result;
    result.page_size = platform.page_size(platform.context);
    if (!platform.monotonic_us(platform.context, &result.started_at_us)) {
        result.status = RunnerStatus::kClockFailed;
        Emit(observer, RunnerEvent::kFinished, result);
        return result;
    }
    result.observed_at_us = result.started_at_us;
    MonitorPolicy monitor(result.page_size, result.started_at_us);
    result.monitor_status = monitor.status();
    if (monitor.status() != MonitorStatus::kRunning) {
        Emit(observer, RunnerEvent::kFinished, result);
        return result;
    }

    // An unavailable statm only disables the size-change optimization. Forced
    // maps scans and the original ten-second policy deadline still apply.
    const int statm = platform.open_statm(platform.context);
    const auto finish = [&]() {
        result.monitor_status = monitor.status();
        result.scans = monitor.scans();
        result.mismatch_observations = monitor.mismatch_observations();
        if (statm >= 0) platform.close_statm(platform.context, statm);
        Emit(observer, RunnerEvent::kFinished, result);
        return result;
    };

    if (Cancelled(observer)) {
        result.status = RunnerStatus::kCancelled;
        return finish();
    }
    if (!platform.monotonic_us(platform.context, &result.observed_at_us)) {
        result.status = RunnerStatus::kClockFailed;
        return finish();
    }
    if (observer.on_ready != nullptr &&
        !observer.on_ready(observer.context, result.observed_at_us)) {
        result.status = RunnerStatus::kCancelled;
        return finish();
    }
    Emit(observer, RunnerEvent::kArmed, result);

    unsigned long long previous_pages = 0;
    bool have_previous_pages = false;
    bool saw_candidate = false;
    while (true) {
        if (Cancelled(observer)) {
            result.status = RunnerStatus::kCancelled;
            break;
        }
        ++result.attempts;
        unsigned long long pages = 0;
        const bool have_pages = platform.read_pages(platform.context, statm, &pages);
        const bool pages_changed = have_pages && have_previous_pages && pages != previous_pages;
        if (!platform.monotonic_us(platform.context, &result.observed_at_us)) {
            result.status = RunnerStatus::kClockFailed;
            break;
        }
        const bool should_scan = monitor.ShouldScan(result.observed_at_us, pages_changed);
        if (have_pages) {
            previous_pages = pages;
            have_previous_pages = true;
        }
        if (should_scan) {
            if (Cancelled(observer)) {
                result.status = RunnerStatus::kCancelled;
                break;
            }
            result.scan = platform.scan_self(platform.context);
            // Preserve the old scan-start timestamp semantics. Skipped polls
            // never become empty observations. A scan cannot pin its VMAs.
            monitor.ObserveScan(result.observed_at_us, result.scan);
            result.scans = monitor.scans();
            result.mismatch_observations = monitor.mismatch_observations();
            result.monitor_status = monitor.status();
            if (!saw_candidate && result.scan.candidates.count != 0) {
                saw_candidate = true;
                Emit(observer, RunnerEvent::kFirstCandidate, result);
            }
        }
        if (monitor.status() != MonitorStatus::kRunning) break;
        if (!platform.monotonic_us(platform.context, &result.observed_at_us)) {
            result.status = RunnerStatus::kClockFailed;
            break;
        }
        platform.sleep_us(platform.context, monitor.SleepMicroseconds(result.observed_at_us));
    }
    return finish();
}

const char* RunnerReason(const RunnerResult& result) {
    if (result.status == RunnerStatus::kCancelled) return "SecNeo monitor cancelled; no further scan";
    if (result.status == RunnerStatus::kClockFailed) return "SecNeo monotonic clock failed; monitor stopped";
    switch (result.monitor_status) {
        case MonitorStatus::kPatched: return "SecNeo maps branch patched";
        case MonitorStatus::kAlreadyPatched: return "SecNeo maps branch already patched";
        case MonitorStatus::kWriteFailed: return "SecNeo maps branch write verification failed";
        case MonitorStatus::kSignatureMismatch:
            return "SecNeo stable candidate set signature mismatch; no patch applied";
        case MonitorStatus::kTimedOut: return "SecNeo monitor timeout; no patch applied";
        case MonitorStatus::kCandidateOverflow: return "SecNeo candidate capacity exceeded; no patch applied";
        case MonitorStatus::kScanFailed: return "SecNeo maps scan incomplete; no patch applied";
        case MonitorStatus::kUnsupportedPageSize:
            return "SecNeo unsupported page size; no scan or patch attempted";
        case MonitorStatus::kRunning: return "SecNeo monitor started";
    }
    return "SecNeo monitor stopped";
}

}  // namespace momo_secneo
