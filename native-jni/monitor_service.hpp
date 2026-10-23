#pragma once

#include "monitor_runner.hpp"

#include <pthread.h>
#include <sys/types.h>
#include <time.h>

namespace freemomo {

// Stable JNI wire values. STARTING/ARMED are not successful patch outcomes.
enum class ServiceState : int {
    kNotStarted = 0,
    kStarting = 1,
    kArmed = 2,
    kPatched = 3,
    kAlreadyPatched = 4,
    kNotTarget = 10,
    kUnsupportedArchitecture = 11,
    kUnsupportedPageSize = 12,
    kInitFailed = 13,
    kThreadFailed = 14,
    kReadyTimeout = 15,
    kForkRejected = 16,
    kSignatureMismatch = 20,
    kWriteFailed = 21,
    kMonitorTimeout = 22,
    kCandidateOverflow = 23,
    kScanFailed = 24,
    kClockFailed = 25,
    kCancelled = 26,
};

const char* StateName(ServiceState state);

struct ServiceSnapshot {
    ServiceState state = ServiceState::kNotStarted;
    ServiceState readiness = ServiceState::kNotStarted;
    pid_t owner_pid = 0;
    // Monitor completion published; NOT a pthread join/lifetime barrier.
    bool worker_finished = true;
    int error = 0;
};

struct ServicePlatform {
    void* context;
    pid_t (*get_pid)(void*);
    bool (*is_target_process)(void*);
    bool (*is_arm64)(void*);
    bool (*monotonic_time)(void*, timespec*);
    int (*create_thread)(void*, pthread_t*, const pthread_attr_t*, void* (*)(void*), void*);
};

struct ServiceObserver {
    void* context = nullptr;
    // Invoked under the state lock; must be bounded and must not reenter service.
    void (*on_state)(void*, const ServiceSnapshot&, int64_t monotonic_us) = nullptr;
    void (*on_runner_event)(void*, momo_secneo::RunnerEvent,
                            const momo_secneo::RunnerResult&) = nullptr;
};

const ServicePlatform& SystemServicePlatform();

// Production instance and all callback contexts live until process exit. The
// worker never accesses Java or JNIEnv. Tests may destroy only after every
// caller has returned AND their thread adapter has acknowledged Worker return.
// worker_finished is a state publication, not permission to free thread context.
class MonitorService {
public:
    explicit MonitorService(const ServicePlatform& platform = SystemServicePlatform(),
                            const momo_secneo::RunnerPlatform& runner = momo_secneo::SystemRunnerPlatform(),
                            const ServiceObserver& observer = {});
    ~MonitorService();
    MonitorService(const MonitorService&) = delete;
    MonitorService& operator=(const MonitorService&) = delete;

    // Returns the immutable readiness latch. Even an immediate terminal scan
    // cannot erase an earlier ARMED. GetSnapshot().state is the running outcome.
    // Concurrent/repeated callers share the first call's deadline; no retries.
    ServiceState Start(int ready_timeout_ms);
    ServiceSnapshot GetSnapshot();

private:
    bool OwnProcess();
    bool InitConditionLocked();
    void PublishLocked(ServiceState state, int error = 0);
    void RejectLocked(ServiceState state, int error = 0);
    bool PublishReady();
    bool IsCancelled();
    void Finish(const momo_secneo::RunnerResult& result);
    static void* Worker(void* argument);
    static bool OnReady(void* context, int64_t);
    static bool CheckCancelled(void* context);
    static void OnRunnerEvent(void*, momo_secneo::RunnerEvent, const momo_secneo::RunnerResult&);

    const ServicePlatform platform_;
    const momo_secneo::RunnerPlatform runner_;
    const ServiceObserver observer_;
    // Claimed using a lock-free PID CAS BEFORE touching any mutex/condition.
    // A child inheriting a claimed instance returns FORK_REJECTED, never tries
    // to repair or acquire a possibly locked parent mutex.
    pid_t owner_pid_ = 0;
    pthread_mutex_t mutex_ = PTHREAD_MUTEX_INITIALIZER;
    pthread_cond_t condition_;
    bool condition_initialized_ = false;
    bool cancelled_ = false;
    ServiceSnapshot snapshot_;
    timespec ready_deadline_ = {};
};

}  // namespace freemomo
