#include "monitor_runner.hpp"

#include <stdio.h>
#include <initializer_list>

namespace {
using namespace momo_secneo;
int checks = 0;
int failures = 0;

void Expect(bool value, const char* name) {
    ++checks;
    if (!value) { ++failures; fprintf(stderr, "FAIL: %s\n", name); }
}

struct Fake {
    long page_size = 4096;
    int64_t now = 123456789000;
    int clock_calls = 0;
    int fail_clock_at = 0;
    int opens = 0;
    int closes = 0;
    int scans = 0;
    int ready = 0;
    int candidates = 0;
    int finished = 0;
    bool ready_allowed = true;
    bool cancelled = false;
    bool cancel_when_ready = false;
    int64_t ready_delay_us = 0;
    bool statm_available = true;
    ScanResult scan;

    static Fake& Get(void* p) { return *static_cast<Fake*>(p); }
    static long Page(void* p) { return Get(p).page_size; }
    static bool Clock(void* p, int64_t* now) {
        auto& f = Get(p);
        if (++f.clock_calls == f.fail_clock_at) return false;
        *now = f.now;
        return true;
    }
    static int Open(void* p) { auto& f = Get(p); ++f.opens; return f.statm_available ? 5 : -1; }
    static void Close(void* p, int) { ++Get(p).closes; }
    static bool Pages(void* p, int fd, unsigned long long* pages) {
        *pages = 100;
        return Get(p).statm_available && fd >= 0;
    }
    static void Sleep(void* p, long us) { Get(p).now += us; }
    static ScanResult Scan(void* p) { auto& f = Get(p); ++f.scans; return f.scan; }
    static bool Ready(void* p, int64_t) {
        auto& f = Get(p);
        ++f.ready;
        f.now += f.ready_delay_us;
        f.cancelled = f.cancel_when_ready;
        return f.ready_allowed;
    }
    static bool Cancelled(void* p) { return Get(p).cancelled; }
    static void Event(void* p, RunnerEvent event, const RunnerResult&) {
        auto& f = Get(p);
        if (event == RunnerEvent::kFirstCandidate) ++f.candidates;
        if (event == RunnerEvent::kFinished) ++f.finished;
    }
    RunnerResult Run() {
        return RunMonitor({this, Page, Clock, Open, Pages, Close, Sleep, Scan},
                          {this, Ready, Cancelled, Event});
    }
};

void TestReadinessAndCancellation() {
    Fake veto;
    veto.ready_allowed = false;
    const auto denied = veto.Run();
    Expect(denied.status == RunnerStatus::kCancelled && veto.scans == 0,
           "rejected readiness never enters a scan");
    Expect(veto.closes == 1 && veto.finished == 1, "readiness veto closes statm and finishes once");
    Fake cancelled;
    cancelled.cancelled = true;
    const auto before = cancelled.Run();
    Expect(before.status == RunnerStatus::kCancelled && cancelled.ready == 0 && cancelled.scans == 0,
           "pre-readiness cancellation never publishes readiness");
    Fake after;
    after.cancel_when_ready = true;
    Expect(after.Run().status == RunnerStatus::kCancelled && after.scans == 0,
           "cancellation after readiness is observed before the first scan");
}

void TestClockAndPageFailures() {
    for (int clock_at : {1, 2, 3, 4}) {
        Fake f;
        f.fail_clock_at = clock_at;
        const auto result = f.Run();
        Expect(result.status == RunnerStatus::kClockFailed, "clock failure is a separate runner outcome");
        Expect(f.finished == 1 && f.closes == (clock_at == 1 ? 0 : 1),
               "clock failure closes initialized resources and finishes once");
    }
    for (long page : {16384L, 65536L, 0L, -1L}) {
        Fake f;
        f.page_size = page;
        const auto result = f.Run();
        Expect(result.monitor_status == MonitorStatus::kUnsupportedPageSize &&
                   f.opens == 0 && f.ready == 0 && f.scans == 0,
               "unsupported page size never opens statm, arms or scans");
    }
}

void TestPolicyIntegration() {
    Fake timeout;
    const auto expired = timeout.Run();
    Expect(expired.monitor_status == MonitorStatus::kTimedOut &&
               expired.observed_at_us - expired.started_at_us == 10000000,
           "shared runner retains exactly the ten-second deadline");
    Expect(timeout.scans == static_cast<int>(expired.scans) && expired.attempts > expired.scans,
           "skipped statm polls are not observations");

    Fake delayed;
    delayed.ready_delay_us = 10000000;
    const auto delayed_result = delayed.Run();
    Expect(delayed_result.monitor_status == MonitorStatus::kTimedOut && delayed.scans == 0,
           "readiness callback cannot renew the total deadline");

    Fake mismatch;
    mismatch.scan.result = PatchResult::kSignatureMismatch;
    mismatch.scan.candidates.Add(0x100000);
    const auto rejected = mismatch.Run();
    Expect(rejected.monitor_status == MonitorStatus::kSignatureMismatch &&
               rejected.observed_at_us - rejected.started_at_us == 20000,
           "runner preserves the stable-candidate twenty-millisecond window");
    Expect(mismatch.candidates == 1 && mismatch.finished == 1,
           "first-candidate and terminal diagnostics are emitted once");

    Fake unavailable;
    unavailable.statm_available = false;
    unavailable.scan.result = PatchResult::kPatched;
    unavailable.scan.candidates.Add(0x100000);
    const auto patched = unavailable.Run();
    Expect(patched.monitor_status == MonitorStatus::kPatched && unavailable.scans == 1,
           "statm failure keeps maps scanning enabled");
    Expect(unavailable.closes == 0 && unavailable.ready == 1, "invalid statm fd is never closed");
}
}  // namespace

int main() {
    TestReadinessAndCancellation();
    TestClockAndPageFailures();
    TestPolicyIntegration();
    printf("Monitor runner: %d checks, %d failures.\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
