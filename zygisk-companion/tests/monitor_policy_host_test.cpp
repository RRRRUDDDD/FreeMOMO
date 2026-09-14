#include "monitor_policy.hpp"

#include <stdio.h>

namespace {

using momo_secneo::MonitorStatus;
using momo_secneo::PatchResult;
using momo_secneo::ScanResult;

constexpr uintptr_t kCandidateA = 0x100000;
constexpr uintptr_t kCandidateB = 0x300000;
constexpr uintptr_t kCandidateC = 0x500000;
constexpr int64_t kClockOrigin = 1234567890123;
int g_checks = 0;
int g_failures = 0;

void Expect(bool condition, const char* name) {
    ++g_checks;
    if (condition) {
        printf("PASS: %s\n", name);
    } else {
        fprintf(stderr, "FAIL: %s\n", name);
        ++g_failures;
    }
}

ScanResult Mismatch(uintptr_t first, uintptr_t second = 0) {
    ScanResult scan;
    scan.result = PatchResult::kSignatureMismatch;
    scan.candidates.Add(first);
    if (second != 0) scan.candidates.Add(second);
    scan.base = second != 0 ? second : first;
    return scan;
}

struct FakeMonitor {
    explicit FakeMonitor(long page_size = momo_secneo::kSupportedPageSize)
        : policy(page_size, kClockOrigin) {}

    bool PollAt(int64_t elapsed_us, bool pages_changed = false) {
        now_us = kClockOrigin + elapsed_us;
        return policy.ShouldScan(now_us, pages_changed);
    }

    void ScanAt(int64_t elapsed_us, const ScanResult& scan) {
        now_us = kClockOrigin + elapsed_us;
        policy.ObserveScan(now_us, scan);
    }

    long SleepAt(int64_t elapsed_us) const {
        return policy.SleepMicroseconds(kClockOrigin + elapsed_us);
    }

    int64_t now_us = kClockOrigin;
    momo_secneo::MonitorPolicy policy;
};

void TestStableMismatchBoundary() {
    FakeMonitor monitor;
    monitor.ScanAt(0, Mismatch(kCandidateA));
    Expect(monitor.policy.has_pending_candidates(), "first mismatch starts a pending set");
    Expect(monitor.PollAt(500), "pending candidates force a real scan at the next poll");
    monitor.ScanAt(19999, Mismatch(kCandidateA));
    Expect(monitor.policy.status() == MonitorStatus::kRunning,
           "unchanged mismatch remains eligible just before 20 ms");
    monitor.ScanAt(20000, Mismatch(kCandidateA));
    Expect(monitor.policy.status() == MonitorStatus::kSignatureMismatch,
           "unchanged mismatch stops at exactly 20 ms");
    Expect(!monitor.PollAt(20001) && monitor.SleepAt(20001) == 0,
           "stable mismatch is terminal");
}

void TestDisappearance() {
    FakeMonitor monitor;
    monitor.ScanAt(0, Mismatch(kCandidateA));
    monitor.ScanAt(500, {});
    Expect(!monitor.policy.has_pending_candidates(), "real empty scan clears pending candidates");
    monitor.ScanAt(25000, Mismatch(kCandidateB));
    Expect(monitor.policy.status() == MonitorStatus::kRunning,
           "candidate B gets a fresh window after A disappears");
    monitor.ScanAt(44999, Mismatch(kCandidateB));
    Expect(monitor.policy.status() == MonitorStatus::kRunning,
           "replacement remains eligible just before its own 20 ms");
    monitor.ScanAt(45000, Mismatch(kCandidateB));
    Expect(monitor.policy.status() == MonitorStatus::kSignatureMismatch,
           "replacement expires at its own 20 ms");

    FakeMonitor reappeared;
    reappeared.ScanAt(0, Mismatch(kCandidateA));
    reappeared.ScanAt(500, {});
    reappeared.ScanAt(25000, Mismatch(kCandidateA));
    Expect(reappeared.policy.status() == MonitorStatus::kRunning,
           "the same address also gets a fresh window after a real disappearance");
    reappeared.ScanAt(45000, Mismatch(kCandidateA));
    Expect(reappeared.policy.status() == MonitorStatus::kSignatureMismatch,
           "reappeared address is still bounded by its new 20 ms window");
}

void TestReplacementAndMembership() {
    FakeMonitor replaced;
    replaced.ScanAt(0, Mismatch(kCandidateA));
    replaced.ScanAt(25000, Mismatch(kCandidateB));
    Expect(replaced.policy.status() == MonitorStatus::kRunning,
           "direct address replacement resets the window without an empty scan");
    replaced.ScanAt(45000, Mismatch(kCandidateB));
    Expect(replaced.policy.status() == MonitorStatus::kSignatureMismatch,
           "direct replacement receives exactly its own 20 ms window");

    FakeMonitor multi;
    multi.ScanAt(0, Mismatch(kCandidateA, kCandidateB));
    multi.ScanAt(25000, Mismatch(kCandidateC, kCandidateB));
    Expect(multi.policy.status() == MonitorStatus::kRunning,
           "changing an earlier candidate resets time even when diagnostic base is unchanged");
    multi.ScanAt(44999, Mismatch(kCandidateB, kCandidateC));
    Expect(multi.policy.status() == MonitorStatus::kRunning,
           "reordered candidate set is eligible before the boundary");
    multi.ScanAt(45000, Mismatch(kCandidateC, kCandidateB));
    Expect(multi.policy.status() == MonitorStatus::kSignatureMismatch,
           "candidate reordering does not renew the mismatch window");

    FakeMonitor membership;
    membership.ScanAt(0, Mismatch(kCandidateA));
    membership.ScanAt(19000, Mismatch(kCandidateA, kCandidateB));
    membership.ScanAt(38000, Mismatch(kCandidateB));
    Expect(membership.policy.status() == MonitorStatus::kRunning,
           "candidate additions and removals both renew the window");
    membership.ScanAt(57999, Mismatch(kCandidateB));
    Expect(membership.policy.status() == MonitorStatus::kRunning,
           "remaining candidate keeps its full new window");
    membership.ScanAt(58000, Mismatch(kCandidateB));
    Expect(membership.policy.status() == MonitorStatus::kSignatureMismatch,
           "remaining candidate expires 20 ms after membership changed");
}

void TestSkippedPollsAndScheduling() {
    FakeMonitor monitor;
    Expect(monitor.PollAt(0), "first poll requests a scan");
    monitor.ScanAt(0, {});
    Expect(!monitor.PollAt(500) && !monitor.PollAt(7999),
           "unchanged statm polls skip maps before the forced interval");
    Expect(monitor.policy.scans() == 1, "skipped polls do not count as real scans");
    Expect(monitor.PollAt(8000), "skipped polls do not postpone the forced scan deadline");
    monitor.ScanAt(8000, {});
    Expect(monitor.PollAt(8001, true), "virtual-size change requests an immediate scan");
    monitor.ScanAt(1000000, {});
    Expect(!monitor.PollAt(1049999) && monitor.PollAt(1050000),
           "middle-phase forced interval is 50 ms");
    monitor.ScanAt(3000000, {});
    Expect(!monitor.PollAt(3249999) && monitor.PollAt(3250000),
           "late-phase forced interval is 250 ms");

    FakeMonitor pending;
    pending.ScanAt(0, Mismatch(kCandidateA));
    pending.PollAt(500);
    pending.PollAt(19999);
    Expect(pending.policy.has_pending_candidates() && pending.policy.scans() == 1 &&
               pending.policy.mismatch_observations() == 1,
           "polling without a completed scan preserves pending identity and counters");
    pending.ScanAt(20000, Mismatch(kCandidateA));
    Expect(pending.policy.status() == MonitorStatus::kSignatureMismatch,
           "a skipped observation cannot be mistaken for candidate disappearance");
}

void TestTotalDeadline() {
    FakeMonitor empty;
    empty.ScanAt(0, {});
    Expect(empty.PollAt(9999999), "a scan is permitted just before the total deadline");
    Expect(!empty.PollAt(10000000) && empty.policy.status() == MonitorStatus::kTimedOut,
           "no scan starts at exactly ten seconds");

    FakeMonitor churn;
    bool stayed_running = true;
    for (int64_t elapsed_us = 0; elapsed_us < 10000000; elapsed_us += 19000) {
        const uintptr_t base = (elapsed_us / 19000) % 2 == 0 ? kCandidateA : kCandidateB;
        churn.ScanAt(elapsed_us, Mismatch(base));
        stayed_running = stayed_running && churn.policy.status() == MonitorStatus::kRunning;
    }
    Expect(stayed_running, "candidate churn renews only the mismatch window");
    Expect(!churn.PollAt(10000000) && churn.policy.status() == MonitorStatus::kTimedOut,
           "candidate churn never extends the ten-second total deadline");

    FakeMonitor late;
    late.ScanAt(9999999, Mismatch(kCandidateA));
    Expect(late.policy.status() == MonitorStatus::kRunning,
           "a candidate just before the deadline may be observed");
    late.ScanAt(10000000, Mismatch(kCandidateB));
    Expect(late.policy.status() == MonitorStatus::kTimedOut && late.policy.scans() == 1,
           "late replacement cannot override or extend the total deadline");
    Expect(late.SleepAt(10000001) == 0, "expired monitor never sleeps again");
}

void TestSleepAndPageSize() {
    FakeMonitor monitor;
    Expect(monitor.SleepAt(0) == 500 && monitor.SleepAt(999999) == 500,
           "initial poll sleep is 0.5 ms");
    Expect(monitor.SleepAt(1000000) == 2000 && monitor.SleepAt(2999999) == 2000,
           "middle poll sleep is 2 ms");
    Expect(monitor.SleepAt(3000000) == 10000, "late poll sleep is 10 ms");
    Expect(monitor.SleepAt(9999999) == 1 && monitor.SleepAt(10000000) == 0,
           "sleep is capped by the remaining total deadline");
    monitor.ScanAt(4000000, Mismatch(kCandidateA));
    Expect(monitor.SleepAt(4000000) == 500, "pending candidates bypass late poll backoff");

    const long unsupported_sizes[] = {16384, 65536, 0, -1};
    for (long page_size : unsupported_sizes) {
        FakeMonitor unsupported(page_size);
        unsupported.ScanAt(0, Mismatch(kCandidateA));
        char name[96];
        snprintf(name, sizeof(name), "page size %ld stops before scanning or sleeping", page_size);
        Expect(unsupported.policy.status() == MonitorStatus::kUnsupportedPageSize &&
                   !unsupported.PollAt(0) && unsupported.SleepAt(0) == 0 &&
                   unsupported.policy.scans() == 0,
               name);
    }
    Expect(monitor.policy.status() == MonitorStatus::kRunning, "4096-byte pages enable monitoring");
}

void TestTerminalScanResults() {
    const PatchResult results[] = {
        PatchResult::kPatched, PatchResult::kAlreadyPatched, PatchResult::kWriteFailed,
        PatchResult::kCandidateOverflow, PatchResult::kScanFailed,
    };
    const MonitorStatus statuses[] = {
        MonitorStatus::kPatched, MonitorStatus::kAlreadyPatched, MonitorStatus::kWriteFailed,
        MonitorStatus::kCandidateOverflow, MonitorStatus::kScanFailed,
    };
    const char* names[] = {
        "successful fingerprint wins at the mismatch boundary",
        "already-patched fingerprint stops the monitor",
        "write failure stops the monitor",
        "candidate overflow stops the monitor",
        "incomplete scan stops the monitor instead of clearing history",
    };
    for (unsigned int index = 0; index < sizeof(results) / sizeof(results[0]); ++index) {
        FakeMonitor monitor;
        monitor.ScanAt(0, Mismatch(kCandidateA));
        ScanResult terminal = Mismatch(kCandidateA);
        terminal.result = results[index];
        monitor.ScanAt(20000, terminal);
        monitor.ScanAt(20001, Mismatch(kCandidateB));
        Expect(monitor.policy.status() == statuses[index] && monitor.policy.scans() == 2 &&
                   !monitor.PollAt(20002), names[index]);
    }

    FakeMonitor overflow;
    ScanResult too_many = Mismatch(kCandidateA);
    too_many.candidates.count = momo_secneo::kMaxPayloadCandidates + 1;
    overflow.ScanAt(0, too_many);
    Expect(overflow.policy.status() == MonitorStatus::kCandidateOverflow,
           "oversized injected candidate sets fail closed before comparison");

    FakeMonitor missing_identity;
    ScanResult invalid_mismatch;
    invalid_mismatch.result = PatchResult::kSignatureMismatch;
    missing_identity.ScanAt(0, invalid_mismatch);
    Expect(missing_identity.policy.status() == MonitorStatus::kScanFailed,
           "mismatch without candidate identity fails closed");

    FakeMonitor invalid_empty;
    ScanResult invalid_miss = Mismatch(kCandidateA);
    invalid_miss.result = PatchResult::kNotFound;
    invalid_empty.ScanAt(0, invalid_miss);
    Expect(invalid_empty.policy.status() == MonitorStatus::kScanFailed,
           "not-found result with candidates fails closed");
}

}  // namespace

int main() {
    TestStableMismatchBoundary();
    TestDisappearance();
    TestReplacementAndMembership();
    TestSkippedPollsAndScheduling();
    TestTotalDeadline();
    TestSleepAndPageSize();
    TestTerminalScanResults();
    printf("Monitor policy: %d checks, %d failures.\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
