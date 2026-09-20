#pragma once

#include "monitor_policy.hpp"

namespace momo_secneo {

// Clock/cancellation failures are not MonitorPolicy outcomes.
enum class RunnerStatus { kCompleted, kCancelled, kClockFailed };
enum class RunnerEvent { kArmed, kFirstCandidate, kFinished };

struct RunnerResult {
    RunnerStatus status = RunnerStatus::kCompleted;
    MonitorStatus monitor_status = MonitorStatus::kRunning;
    long page_size = 0;
    int64_t started_at_us = 0;
    int64_t observed_at_us = 0;
    unsigned int attempts = 0;
    unsigned int scans = 0;
    unsigned int mismatch_observations = 0;
    ScanResult scan;
};

// The caller owns these contexts for the entire synchronous RunMonitor call.
// Callbacks must not throw. on_ready=false vetoes the FIRST scan.
struct RunnerObserver {
    void* context = nullptr;
    bool (*on_ready)(void*, int64_t) = nullptr;
    bool (*is_cancelled)(void*) = nullptr;
    void (*on_event)(void*, RunnerEvent, const RunnerResult&) = nullptr;
};

struct RunnerPlatform {
    void* context;
    long (*page_size)(void*);
    bool (*monotonic_us)(void*, int64_t*);
    int (*open_statm)(void*);
    bool (*read_pages)(void*, int, unsigned long long*);
    void (*close_statm)(void*, int);
    void (*sleep_us)(void*, long);
    ScanResult (*scan_self)(void*);
};

const RunnerPlatform& SystemRunnerPlatform();
RunnerResult RunMonitor(const RunnerPlatform& platform = SystemRunnerPlatform(),
                        const RunnerObserver& observer = {});
const char* RunnerReason(const RunnerResult& result);

}  // namespace momo_secneo
