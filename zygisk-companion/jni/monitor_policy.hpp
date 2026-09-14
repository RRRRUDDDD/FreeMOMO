#pragma once

#include "secneo_patch.hpp"

namespace momo_secneo {

constexpr long kSupportedPageSize = 4096;
constexpr int64_t kMonitorTimeoutMicroseconds = 10000000;
constexpr int64_t kMismatchGraceMicroseconds = 20000;

enum class MonitorStatus {
    kRunning,
    kPatched,
    kAlreadyPatched,
    kSignatureMismatch,
    kWriteFailed,
    kTimedOut,
    kCandidateOverflow,
    kScanFailed,
    kUnsupportedPageSize,
};

// Callers inject monotonic timestamps and results of completed, real maps scans.
// A poll with no scan must not be reported as an empty ScanResult.
class MonitorPolicy {
public:
    MonitorPolicy(long page_size, int64_t started_at_us);

    bool ShouldScan(int64_t now_us, bool pages_changed);
    void ObserveScan(int64_t scan_started_at_us, const ScanResult& scan);
    long SleepMicroseconds(int64_t now_us) const;

    MonitorStatus status() const { return status_; }
    unsigned int scans() const { return scans_; }
    unsigned int mismatch_observations() const { return mismatch_observations_; }
    bool has_pending_candidates() const { return pending_candidates_.count != 0; }

private:
    bool CheckDeadline(int64_t now_us);

    const int64_t started_at_us_;
    MonitorStatus status_;
    int64_t last_scan_at_us_ = 0;
    int64_t mismatch_started_at_us_ = 0;
    CandidateSet pending_candidates_;
    unsigned int scans_ = 0;
    unsigned int mismatch_observations_ = 0;
};

}  // namespace momo_secneo
