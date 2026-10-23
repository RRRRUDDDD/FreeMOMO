#include "monitor_service.hpp"

#include <atomic>
#include <errno.h>
#include <semaphore.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/wait.h>
#include <unistd.h>

namespace {
using freemomo::MonitorService;
using freemomo::ServiceState;
using momo_secneo::PatchResult;
int checks = 0;
int failures = 0;

void Expect(bool value, const char* name) {
    ++checks;
    if (!value) { ++failures; fprintf(stderr, "FAIL: %s\n", name); }
}

void WaitGate(sem_t* gate) {
    while (sem_wait(gate) != 0) {
        if (errno != EINTR) abort();
    }
}

template <typename Predicate> void Await(Predicate condition) {
    for (int i = 0; i < 3000; ++i) {
        if (condition()) return;
        usleep(1000);
    }
    fprintf(stderr, "FAIL: test synchronization exceeded three seconds\n");
    abort();
}

struct Fake {
    std::atomic<pid_t> pid_override{0};
    std::atomic<int> creates{0};
    std::atomic<int> scans{0};
    std::atomic<bool> worker_exited{true};
    std::atomic<int> states[27]{};
    bool target = true;
    bool arm64 = true;
    long page_size = 4096;
    bool fail_clock = false;
    bool fail_runner_clock = false;
    int runner_clock_calls = 0;
    int fail_runner_clock_at = 0;
    int thread_error = 0;
    int create_delay_us = 0;
    int detach_state = -1;
    bool hold_worker = false;
    bool hold_scan = false;
    bool hold_start = false;
    sem_t worker_gate;
    sem_t scan_gate;
    sem_t starting_gate;
    void* (*worker_entry)(void*) = nullptr;
    void* worker_argument = nullptr;
    int64_t runner_us = 987654321000;
    momo_secneo::ScanResult result;

    Fake() {
        sem_init(&worker_gate, 0, 0);
        sem_init(&scan_gate, 0, 0);
        sem_init(&starting_gate, 0, 0);
        result.result = PatchResult::kPatched;
        result.candidates.Add(0x100000);
    }
    ~Fake() {
        sem_destroy(&worker_gate);
        sem_destroy(&scan_gate);
        sem_destroy(&starting_gate);
    }
    static Fake& Get(void* p) { return *static_cast<Fake*>(p); }
    static pid_t Pid(void* p) {
        const pid_t fake = Get(p).pid_override.load();
        return fake == 0 ? getpid() : fake;
    }
    static bool Target(void* p) { return Get(p).target; }
    static bool Arm64(void* p) { return Get(p).arm64; }
    static bool Clock(void* p, timespec* now) {
        return !Get(p).fail_clock && clock_gettime(CLOCK_MONOTONIC, now) == 0;
    }
    static void* Trampoline(void* p) {
        auto& f = Get(p);
        if (f.hold_worker) WaitGate(&f.worker_gate);
        void* result = f.worker_entry(f.worker_argument);
        // The service's last unlock and member-function return have completed.
        // No service/context access follows this acknowledgement.
        f.worker_exited.store(true);
        return result;
    }
    static int Create(void* p, pthread_t* thread, const pthread_attr_t* attributes,
                      void* (*entry)(void*), void* argument) {
        auto& f = Get(p);
        ++f.creates;
        pthread_attr_getdetachstate(attributes, &f.detach_state);
        if (f.thread_error != 0) return f.thread_error;
        if (f.create_delay_us != 0) usleep(f.create_delay_us);
        f.worker_entry = entry;
        f.worker_argument = argument;
        f.worker_exited.store(false);
        const int error = pthread_create(thread, attributes, Trampoline, p);
        if (error != 0) f.worker_exited.store(true);
        return error;
    }
    static long Page(void* p) { return Get(p).page_size; }
    static bool RunnerClock(void* p, int64_t* now) {
        auto& f = Get(p);
        *now = f.runner_us;
        return !f.fail_runner_clock && ++f.runner_clock_calls != f.fail_runner_clock_at;
    }
    static int Open(void*) { return -1; }
    static bool Pages(void*, int, unsigned long long*) { return false; }
    static void Close(void*, int) { abort(); }
    static void Sleep(void* p, long us) { Get(p).runner_us += us; }
    static momo_secneo::ScanResult Scan(void* p) {
        auto& f = Get(p);
        ++f.scans;
        if (f.hold_scan) WaitGate(&f.scan_gate);
        return f.result;
    }
    static void State(void* p, const freemomo::ServiceSnapshot& snapshot, int64_t) {
        auto& f = Get(p);
        ++f.states[static_cast<int>(snapshot.state)];
        if (snapshot.state == ServiceState::kStarting && f.hold_start) WaitGate(&f.starting_gate);
    }
    freemomo::ServicePlatform Platform() { return {this, Pid, Target, Arm64, Clock, Create}; }
    momo_secneo::RunnerPlatform Runner() {
        return {this, Page, RunnerClock, Open, Pages, Close, Sleep, Scan};
    }
    freemomo::ServiceObserver Observer() { return {this, State, nullptr}; }
};

void Finished(MonitorService& service, Fake& fake) {
    Await([&] { return service.GetSnapshot().worker_finished && fake.worker_exited.load(); });
}

struct Caller {
    MonitorService* service;
    int timeout = 1000;
    ServiceState result = ServiceState::kNotStarted;
    static void* Start(void* p) {
        auto& c = *static_cast<Caller*>(p);
        c.result = c.service->Start(c.timeout);
        return nullptr;
    }
};

void TestConcurrentAndTerminal() {
    Fake f;
    f.hold_worker = true;
    f.hold_scan = true;
    MonitorService service(f.Platform(), f.Runner(), f.Observer());
    Expect(service.GetSnapshot().state == ServiceState::kNotStarted, "initial snapshot is NOT_STARTED");
    Caller callers[8];
    pthread_t threads[8];
    for (int i = 0; i < 8; ++i) {
        callers[i].service = &service;
        if (pthread_create(&threads[i], nullptr, Caller::Start, &callers[i]) != 0) abort();
    }
    Await([&] { return f.creates.load() != 0; });
    Expect(service.GetSnapshot().state == ServiceState::kStarting,
           "waiting Start releases its lock and exposes STARTING");
    sem_post(&f.worker_gate);
    for (int i = 0; i < 8; ++i) {
        pthread_join(threads[i], nullptr);
        Expect(callers[i].result == ServiceState::kArmed, "concurrent callers share ARMED latch");
    }
    Expect(f.creates == 1 && f.detach_state == PTHREAD_CREATE_DETACHED,
           "exactly one detached worker is created");
    Expect(service.GetSnapshot().state == ServiceState::kArmed,
           "ARMED is visible while the scan has no terminal result");
    sem_post(&f.scan_gate);
    Finished(service, f);
    const auto final = service.GetSnapshot();
    Expect(final.state == ServiceState::kPatched && final.readiness == ServiceState::kArmed,
           "terminal state and immutable readiness are independent");
    Expect(service.Start(1) == ServiceState::kArmed && f.creates == 1,
           "repeat start after terminal result never creates another worker");
    Expect(f.states[2] == 1 && f.states[3] == 1, "ARMED and PATCHED publish once");
}

void TestThreadFailureAndTimeout() {
    {
        Fake f;
        f.thread_error = EAGAIN;
        MonitorService service(f.Platform(), f.Runner(), f.Observer());
        Expect(service.Start(50) == ServiceState::kThreadFailed, "pthread_create failure is explicit");
        Expect(service.GetSnapshot().error == EAGAIN && service.GetSnapshot().worker_finished,
               "thread error is retained with no live worker");
        Expect(service.Start(500) == ServiceState::kThreadFailed && f.creates == 1 && f.scans == 0,
               "thread failure is not retried");
    }
    {
        Fake f;
        f.hold_worker = true;
        MonitorService service(f.Platform(), f.Runner(), f.Observer());
        Expect(service.Start(10) == ServiceState::kReadyTimeout, "monotonic readiness wait expires");
        Expect(service.GetSnapshot().state == ServiceState::kReadyTimeout && f.scans == 0,
               "timeout revokes authorization before a scan");
        sem_post(&f.worker_gate);
        Finished(service, f);
        Expect(f.scans == 0 && f.states[2] == 0, "late worker cannot arm or scan after timeout");
        Expect(service.Start(500) == ServiceState::kReadyTimeout &&
                   service.GetSnapshot().state == ServiceState::kReadyTimeout && f.creates == 1,
               "late worker completion cannot overwrite READY_TIMEOUT or retry");
    }
    {
        Fake f;
        f.create_delay_us = 30000;
        MonitorService service(f.Platform(), f.Runner(), f.Observer());
        Expect(service.Start(5) == ServiceState::kReadyTimeout,
               "worker cannot arm past deadline even if caller could not wait yet");
        Finished(service, f);
        Expect(f.states[2] == 0 && f.scans == 0, "deadline is checked by worker under the same lock");
    }
}

void TestPreflightAndClockFailures() {
    for (int mode = 0; mode < 6; ++mode) {
        Fake f;
        if (mode == 0) f.target = false;
        if (mode == 1) f.arm64 = false;
        if (mode == 2) f.page_size = 16384;
        if (mode == 3) f.fail_clock = true;
        if (mode == 4) f.fail_runner_clock = true;
        const ServiceState expected[] = {
            ServiceState::kNotTarget, ServiceState::kUnsupportedArchitecture,
            ServiceState::kUnsupportedPageSize, ServiceState::kInitFailed,
            ServiceState::kClockFailed, ServiceState::kInitFailed,
        };
        MonitorService service(f.Platform(), f.Runner(), f.Observer());
        Expect(service.Start(mode == 5 ? 0 : 1000) == expected[mode],
               "preflight/initialization failure has distinct readiness state");
        Finished(service, f);
        Expect(f.scans == 0 && f.states[2] == 0 && f.creates == (mode == 4 ? 1 : 0),
               "preflight/clock failures never authorize scanning");
    }
}

void TestAllTerminalResults() {
    const PatchResult results[] = {
        PatchResult::kPatched, PatchResult::kAlreadyPatched, PatchResult::kWriteFailed,
        PatchResult::kCandidateOverflow, PatchResult::kScanFailed,
        PatchResult::kSignatureMismatch, PatchResult::kNotFound,
    };
    const ServiceState expected[] = {
        ServiceState::kPatched, ServiceState::kAlreadyPatched, ServiceState::kWriteFailed,
        ServiceState::kCandidateOverflow, ServiceState::kScanFailed,
        ServiceState::kSignatureMismatch, ServiceState::kMonitorTimeout,
    };
    for (int i = 0; i < 7; ++i) {
        Fake f;
        f.result.result = results[i];
        if (results[i] == PatchResult::kNotFound) f.result.candidates = {};
        MonitorService service(f.Platform(), f.Runner(), f.Observer());
        Expect(service.Start(1000) == ServiceState::kArmed,
               "immediate terminal result never hides a successful readiness handshake");
        Finished(service, f);
        Expect(service.GetSnapshot().state == expected[i] &&
                   f.states[static_cast<int>(expected[i])] == 1,
               "runner terminal result is published once and synchronized");
        Expect(service.Start(1) == ServiceState::kArmed && f.creates == 1,
               "terminal failures also never restart the worker");
    }
}

void TestClockFailureAfterArming() {
    Fake f;
    f.result = {};
    f.fail_runner_clock_at = 4;
    MonitorService service(f.Platform(), f.Runner(), f.Observer());
    Expect(service.Start(1000) == ServiceState::kArmed, "clock failure after scan preserves readiness");
    Finished(service, f);
    Expect(service.GetSnapshot().state == ServiceState::kClockFailed && f.scans == 1,
           "running clock failure publishes its own terminal result");
    Expect(service.Start(10) == ServiceState::kArmed && f.creates == 1,
           "running clock failure does not authorize a retry");
}

void TestPidAndFork() {
    {
        Fake f;
        MonitorService service(f.Platform(), f.Runner(), f.Observer());
        service.Start(1000);
        Finished(service, f);
        f.pid_override = getpid() + 100;
        Expect(service.Start(50) == ServiceState::kForkRejected &&
                   service.GetSnapshot().state == ServiceState::kForkRejected && f.creates == 1,
               "changed PID never reuses an inherited running conclusion");
        f.pid_override = 0;
        Expect(service.GetSnapshot().state == ServiceState::kPatched,
               "PID rejection does not mutate parent state");
    }
    {
        Fake f;
        f.hold_start = true;
        MonitorService service(f.Platform(), f.Runner(), f.Observer());
        Caller caller{&service, 2500};
        pthread_t thread;
        if (pthread_create(&thread, nullptr, Caller::Start, &caller) != 0) abort();
        Await([&] { return f.states[1].load() == 1; });
        // on_state is deliberately paused WITH the state mutex held by a
        // different thread. The child must reject before any pthread lock.
        const pid_t child = fork();
        if (child == 0) {
            alarm(2);
            const bool rejected = service.GetSnapshot().state == ServiceState::kForkRejected &&
                service.Start(50) == ServiceState::kForkRejected;
            _exit(rejected ? 0 : 7);
        }
        int status = 0;
        const pid_t waited = child > 0 ? waitpid(child, &status, 0) : -1;
        sem_post(&f.starting_gate);
        pthread_join(thread, nullptr);
        Finished(service, f);
        Expect(waited == child && WIFEXITED(status) && WEXITSTATUS(status) == 0,
               "real fork child rejects inherited LOCKED mutex without deadlock");
        Expect(caller.result == ServiceState::kArmed &&
                   service.GetSnapshot().state == ServiceState::kPatched,
               "parent completes normally after fork rejection");
    }
}
}  // namespace

int main() {
    TestConcurrentAndTerminal();
    TestThreadFailureAndTimeout();
    TestPreflightAndClockFailures();
    TestAllTerminalResults();
    TestClockFailureAfterArming();
    TestPidAndFork();
    printf("Monitor service: %d checks, %d failures.\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
