#pragma once

#include "monitor_runner.hpp"

#include <android/log.h>
#include <time.h>
#include <unistd.h>

namespace momo_secneo {

// Android adapters share diagnostics; the runner itself has no Android/JNI API.
inline void LogRunnerEvent(const char* tag, RunnerEvent event, const RunnerResult& result) {
    timespec event_time = {};
    const long long event_us = clock_gettime(CLOCK_MONOTONIC, &event_time) == 0
        ? static_cast<long long>(event_time.tv_sec) * 1000000 + event_time.tv_nsec / 1000 : -1;
    int priority = ANDROID_LOG_INFO;
    const char* reason = RunnerReason(result);
    if (event == RunnerEvent::kFirstCandidate) {
        reason = "SecNeo first candidate observed (scan result follows)";
    } else if (event == RunnerEvent::kFinished) {
        if (result.status != RunnerStatus::kCompleted ||
            result.monitor_status == MonitorStatus::kWriteFailed) {
            priority = ANDROID_LOG_ERROR;
        } else if (result.monitor_status != MonitorStatus::kPatched &&
                   result.monitor_status != MonitorStatus::kAlreadyPatched) {
            priority = ANDROID_LOG_WARN;
        }
    }
    __android_log_print(
        priority, tag,
        "%s pid=%d mono_us=%lld scan_at_us=%lld page_size=%ld required=%ld base=0x%lx offset=0x%lx "
        "candidates=%u capacity=%u observations=%u attempts=%u scans=%u elapsed_us=%lld result=%d timeout_ms=%lld",
        reason, getpid(), event_us, static_cast<long long>(result.observed_at_us), result.page_size,
        kSupportedPageSize, static_cast<unsigned long>(result.scan.base),
        static_cast<unsigned long>(kPatchOffset), result.scan.candidates.count, kMaxPayloadCandidates,
        result.mismatch_observations, result.attempts, result.scans,
        static_cast<long long>(result.observed_at_us - result.started_at_us),
        static_cast<int>(result.scan.result),
        static_cast<long long>(kMonitorTimeoutMicroseconds / 1000));
}

}  // namespace momo_secneo
