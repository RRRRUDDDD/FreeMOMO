#include "monitor_policy.hpp"

namespace momo_secneo {

MonitorPolicy::MonitorPolicy(long page_size, int64_t started_at_us)
    : started_at_us_(started_at_us),
      status_(page_size == kSupportedPageSize ? MonitorStatus::kRunning
                                            : MonitorStatus::kUnsupportedPageSize) {}

bool MonitorPolicy::CheckDeadline(int64_t now_us) {
    if (status_ != MonitorStatus::kRunning) return false;
    if (now_us - started_at_us_ >= kMonitorTimeoutMicroseconds) {
        status_ = MonitorStatus::kTimedOut;
        return false;
    }
    return true;
}

bool MonitorPolicy::ShouldScan(int64_t now_us, bool pages_changed) {
    if (!CheckDeadline(now_us)) return false;
    const long elapsed_us = static_cast<long>(now_us - started_at_us_);
    return scans_ == 0 || pages_changed || has_pending_candidates() ||
        now_us - last_scan_at_us_ >= ForcedScanIntervalMicroseconds(elapsed_us);
}

void MonitorPolicy::ObserveScan(int64_t scan_started_at_us, const ScanResult& scan) {
    if (!CheckDeadline(scan_started_at_us)) return;
    ++scans_;
    last_scan_at_us_ = scan_started_at_us;

    if (scan.candidates.count > kMaxPayloadCandidates) {
        status_ = MonitorStatus::kCandidateOverflow;
        return;
    }
    switch (scan.result) {
        case PatchResult::kPatched:
            status_ = MonitorStatus::kPatched;
            return;
        case PatchResult::kAlreadyPatched:
            status_ = MonitorStatus::kAlreadyPatched;
            return;
        case PatchResult::kWriteFailed:
            status_ = MonitorStatus::kWriteFailed;
            return;
        case PatchResult::kCandidateOverflow:
            status_ = MonitorStatus::kCandidateOverflow;
            return;
        case PatchResult::kScanFailed:
            status_ = MonitorStatus::kScanFailed;
            return;
        case PatchResult::kNotFound:
            if (scan.candidates.count != 0) {
                status_ = MonitorStatus::kScanFailed;
                return;
            }
            pending_candidates_ = {};
            mismatch_started_at_us_ = 0;
            return;
        case PatchResult::kSignatureMismatch:
            if (scan.candidates.count == 0) {
                status_ = MonitorStatus::kScanFailed;
                return;
            }
            mismatch_observations_ += scan.candidates.count;
            if (!pending_candidates_.Equals(scan.candidates)) {
                pending_candidates_ = scan.candidates;
                mismatch_started_at_us_ = scan_started_at_us;
            } else if (scan_started_at_us - mismatch_started_at_us_ >=
                       kMismatchGraceMicroseconds) {
                status_ = MonitorStatus::kSignatureMismatch;
            }
            return;
    }
}

long MonitorPolicy::SleepMicroseconds(int64_t now_us) const {
    if (status_ != MonitorStatus::kRunning) return 0;
    const int64_t elapsed_us = now_us - started_at_us_;
    const int64_t remaining_us = kMonitorTimeoutMicroseconds - elapsed_us;
    if (remaining_us <= 0) return 0;
    const long interval_us = has_pending_candidates() ? 500L
        : (elapsed_us < 1000000 ? 500L : (elapsed_us < 3000000 ? 2000L : 10000L));
    return remaining_us < interval_us ? static_cast<long>(remaining_us) : interval_us;
}

}  // namespace momo_secneo
