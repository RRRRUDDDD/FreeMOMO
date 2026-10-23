#include "monitor_service.hpp"

#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

namespace freemomo {
namespace {

static_assert(__atomic_always_lock_free(sizeof(pid_t), nullptr), "PID guard must be lock-free");
constexpr char kTargetProcess[] = "com.maimemo.android.momo";

pid_t Pid(void*) { return getpid(); }

bool TargetProcess(void*) {
    const int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char name[sizeof(kTargetProcess)] = {};
    ssize_t count;
    do {
        count = read(fd, name, sizeof(name));
    } while (count < 0 && errno == EINTR);
    close(fd);
    // Require the complete NUL-terminated argv[0], not a matching prefix.
    return count == static_cast<ssize_t>(sizeof(name)) &&
        memcmp(name, kTargetProcess, sizeof(name)) == 0;
}

bool Arm64(void*) {
#if defined(__aarch64__)
    return true;
#else
    return false;
#endif
}

bool Clock(void*, timespec* now) { return clock_gettime(CLOCK_MONOTONIC, now) == 0; }

int CreateThread(void*, pthread_t* thread, const pthread_attr_t* attributes,
                 void* (*entry)(void*), void* argument) {
    return pthread_create(thread, attributes, entry, argument);
}

const ServicePlatform kSystemPlatform = {nullptr, Pid, TargetProcess, Arm64, Clock, CreateThread};

bool DeadlineReached(const timespec& now, const timespec& deadline) {
    return now.tv_sec > deadline.tv_sec ||
        (now.tv_sec == deadline.tv_sec && now.tv_nsec >= deadline.tv_nsec);
}

ServiceState ResultState(const momo_secneo::RunnerResult& result) {
    if (result.status == momo_secneo::RunnerStatus::kClockFailed) return ServiceState::kClockFailed;
    if (result.status == momo_secneo::RunnerStatus::kCancelled) return ServiceState::kCancelled;
    switch (result.monitor_status) {
        case momo_secneo::MonitorStatus::kPatched: return ServiceState::kPatched;
        case momo_secneo::MonitorStatus::kAlreadyPatched: return ServiceState::kAlreadyPatched;
        case momo_secneo::MonitorStatus::kSignatureMismatch: return ServiceState::kSignatureMismatch;
        case momo_secneo::MonitorStatus::kWriteFailed: return ServiceState::kWriteFailed;
        case momo_secneo::MonitorStatus::kTimedOut: return ServiceState::kMonitorTimeout;
        case momo_secneo::MonitorStatus::kCandidateOverflow: return ServiceState::kCandidateOverflow;
        case momo_secneo::MonitorStatus::kScanFailed: return ServiceState::kScanFailed;
        case momo_secneo::MonitorStatus::kUnsupportedPageSize: return ServiceState::kUnsupportedPageSize;
        case momo_secneo::MonitorStatus::kRunning: return ServiceState::kInitFailed;
    }
    return ServiceState::kInitFailed;
}

}  // namespace

const ServicePlatform& SystemServicePlatform() { return kSystemPlatform; }

const char* StateName(ServiceState state) {
    switch (state) {
        case ServiceState::kNotStarted: return "NOT_STARTED";
        case ServiceState::kStarting: return "STARTING";
        case ServiceState::kArmed: return "ARMED";
        case ServiceState::kPatched: return "PATCHED";
        case ServiceState::kAlreadyPatched: return "ALREADY_PATCHED";
        case ServiceState::kNotTarget: return "NOT_TARGET";
        case ServiceState::kUnsupportedArchitecture: return "UNSUPPORTED_ARCHITECTURE";
        case ServiceState::kUnsupportedPageSize: return "UNSUPPORTED_PAGE_SIZE";
        case ServiceState::kInitFailed: return "INIT_FAILED";
        case ServiceState::kThreadFailed: return "THREAD_FAILED";
        case ServiceState::kReadyTimeout: return "READY_TIMEOUT";
        case ServiceState::kForkRejected: return "FORK_REJECTED";
        case ServiceState::kSignatureMismatch: return "SIGNATURE_MISMATCH";
        case ServiceState::kWriteFailed: return "WRITE_FAILED";
        case ServiceState::kMonitorTimeout: return "MONITOR_TIMEOUT";
        case ServiceState::kCandidateOverflow: return "CANDIDATE_OVERFLOW";
        case ServiceState::kScanFailed: return "SCAN_FAILED";
        case ServiceState::kClockFailed: return "CLOCK_FAILED";
        case ServiceState::kCancelled: return "CANCELLED";
    }
    return "UNKNOWN";
}

MonitorService::MonitorService(const ServicePlatform& platform,
                               const momo_secneo::RunnerPlatform& runner,
                               const ServiceObserver& observer)
    : platform_(platform), runner_(runner), observer_(observer) {}

MonitorService::~MonitorService() {
    // The Android singleton is never destroyed while its pinned loader lives.
    // Read-only ownership check: OwnProcess() would CAS-claim ownership as a
    // side effect, which a destructor must not do.
    const pid_t owner = __atomic_load_n(&owner_pid_, __ATOMIC_ACQUIRE);
    if (owner != 0 && owner != platform_.get_pid(platform_.context)) return;
    if (condition_initialized_) pthread_cond_destroy(&condition_);
    pthread_mutex_destroy(&mutex_);
}

bool MonitorService::OwnProcess() {
    const pid_t pid = platform_.get_pid(platform_.context);
    if (pid <= 0) return false;
    pid_t owner = __atomic_load_n(&owner_pid_, __ATOMIC_ACQUIRE);
    if (owner == 0) {
        __atomic_compare_exchange_n(&owner_pid_, &owner, pid, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE);
        owner = __atomic_load_n(&owner_pid_, __ATOMIC_ACQUIRE);
    }
    return owner == pid;
}

bool MonitorService::InitConditionLocked() {
    pthread_condattr_t attributes;
    const int init_error = pthread_condattr_init(&attributes);
    int error = init_error;
    if (error == 0) error = pthread_condattr_setclock(&attributes, CLOCK_MONOTONIC);
    if (error == 0) error = pthread_cond_init(&condition_, &attributes);
    if (init_error == 0) pthread_condattr_destroy(&attributes);
    if (error != 0) {
        RejectLocked(ServiceState::kInitFailed, error);
        return false;
    }
    condition_initialized_ = true;
    return true;
}

void MonitorService::PublishLocked(ServiceState state, int error) {
    snapshot_.state = state;
    snapshot_.owner_pid = __atomic_load_n(&owner_pid_, __ATOMIC_RELAXED);
    snapshot_.error = error;
    if (observer_.on_state != nullptr) {
        timespec now = {};
        const bool clock_ok = platform_.monotonic_time(platform_.context, &now);
        observer_.on_state(observer_.context, snapshot_,
            clock_ok ? static_cast<int64_t>(now.tv_sec) * 1000000 + now.tv_nsec / 1000 : -1);
    }
    if (condition_initialized_) pthread_cond_broadcast(&condition_);
}

void MonitorService::RejectLocked(ServiceState state, int error) {
    cancelled_ = true;
    snapshot_.readiness = state;
    PublishLocked(state, error);
}

ServiceState MonitorService::Start(int ready_timeout_ms) {
    if (!OwnProcess()) return ServiceState::kForkRejected;
    if (pthread_mutex_lock(&mutex_) != 0) return ServiceState::kInitFailed;
    if (snapshot_.state == ServiceState::kNotStarted) {
        if (!platform_.is_target_process(platform_.context)) {
            RejectLocked(ServiceState::kNotTarget);
        } else if (!platform_.is_arm64(platform_.context)) {
            RejectLocked(ServiceState::kUnsupportedArchitecture);
        } else if (runner_.page_size(runner_.context) != momo_secneo::kSupportedPageSize) {
            RejectLocked(ServiceState::kUnsupportedPageSize);
        } else if (ready_timeout_ms < 1 || ready_timeout_ms > 10000 ||
                   !platform_.monotonic_time(platform_.context, &ready_deadline_)) {
            RejectLocked(ServiceState::kInitFailed);
        } else if (InitConditionLocked()) {
            ready_deadline_.tv_sec += ready_timeout_ms / 1000;
            ready_deadline_.tv_nsec += (ready_timeout_ms % 1000) * 1000000L;
            if (ready_deadline_.tv_nsec >= 1000000000L) {
                ++ready_deadline_.tv_sec;
                ready_deadline_.tv_nsec -= 1000000000L;
            }
            snapshot_.readiness = ServiceState::kStarting;
            PublishLocked(ServiceState::kStarting);
            pthread_attr_t attributes;
            const int init_error = pthread_attr_init(&attributes);
            int error = init_error;
            if (error == 0) error = pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
            pthread_t thread;
            if (error == 0) {
                snapshot_.worker_finished = false;
                error = platform_.create_thread(platform_.context, &thread, &attributes, Worker, this);
            }
            if (init_error == 0) pthread_attr_destroy(&attributes);
            if (error != 0) {
                snapshot_.worker_finished = true;
                RejectLocked(ServiceState::kThreadFailed, error);
            }
        }
    }
    while (snapshot_.readiness == ServiceState::kStarting) {
        // This releases mutex_. The worker uses that SAME lock to accept or
        // reject readiness at the absolute monotonic deadline.
        const int error = pthread_cond_timedwait(&condition_, &mutex_, &ready_deadline_);
        if (snapshot_.readiness != ServiceState::kStarting) break;
        if (error == ETIMEDOUT) RejectLocked(ServiceState::kReadyTimeout, error);
        else if (error != 0) RejectLocked(ServiceState::kInitFailed, error);
    }
    const ServiceState readiness = snapshot_.readiness;
    pthread_mutex_unlock(&mutex_);
    return readiness;
}

ServiceSnapshot MonitorService::GetSnapshot() {
    if (!OwnProcess()) {
        ServiceSnapshot rejected;
        rejected.state = rejected.readiness = ServiceState::kForkRejected;
        rejected.owner_pid = __atomic_load_n(&owner_pid_, __ATOMIC_ACQUIRE);
        return rejected;
    }
    if (pthread_mutex_lock(&mutex_) != 0) {
        ServiceSnapshot failed;
        failed.state = failed.readiness = ServiceState::kInitFailed;
        return failed;
    }
    ServiceSnapshot copy = snapshot_;
    copy.owner_pid = __atomic_load_n(&owner_pid_, __ATOMIC_RELAXED);
    pthread_mutex_unlock(&mutex_);
    return copy;
}

bool MonitorService::PublishReady() {
    if (!OwnProcess()) return false;
    pthread_mutex_lock(&mutex_);
    bool accepted = false;
    if (!cancelled_ && snapshot_.state == ServiceState::kStarting) {
        timespec now = {};
        if (!platform_.monotonic_time(platform_.context, &now)) {
            RejectLocked(ServiceState::kClockFailed);
        } else if (DeadlineReached(now, ready_deadline_)) {
            // The waiting caller may not have been scheduled at the deadline.
            // A late worker still has no authority to scan.
            RejectLocked(ServiceState::kReadyTimeout, ETIMEDOUT);
        } else {
            snapshot_.readiness = ServiceState::kArmed;
            PublishLocked(ServiceState::kArmed);
            accepted = true;
        }
    }
    pthread_mutex_unlock(&mutex_);
    return accepted;
}

bool MonitorService::IsCancelled() {
    if (!OwnProcess()) return true;
    pthread_mutex_lock(&mutex_);
    const bool cancelled = cancelled_;
    pthread_mutex_unlock(&mutex_);
    return cancelled;
}

void MonitorService::Finish(const momo_secneo::RunnerResult& result) {
    if (!OwnProcess()) return;
    pthread_mutex_lock(&mutex_);
    snapshot_.worker_finished = true;
    if (snapshot_.state == ServiceState::kStarting) {
        RejectLocked(ResultState(result));
    } else if (snapshot_.state == ServiceState::kArmed) {
        PublishLocked(ResultState(result));
    }
    // READY_TIMEOUT and other startup failures are immutable. The cancelled
    // worker's later return must not overwrite their diagnostic or readiness.
    if (condition_initialized_) pthread_cond_broadcast(&condition_);
    pthread_mutex_unlock(&mutex_);
}

bool MonitorService::OnReady(void* context, int64_t) {
    return static_cast<MonitorService*>(context)->PublishReady();
}

bool MonitorService::CheckCancelled(void* context) {
    return static_cast<MonitorService*>(context)->IsCancelled();
}

void MonitorService::OnRunnerEvent(void* context, momo_secneo::RunnerEvent event,
                                   const momo_secneo::RunnerResult& result) {
    auto* service = static_cast<MonitorService*>(context);
    if (service->observer_.on_runner_event != nullptr) {
        service->observer_.on_runner_event(service->observer_.context, event, result);
    }
}

void* MonitorService::Worker(void* argument) {
    auto* service = static_cast<MonitorService*>(argument);
    const momo_secneo::RunnerObserver observer{service, OnReady, CheckCancelled, OnRunnerEvent};
    const auto result = momo_secneo::RunMonitor(service->runner_, observer);
    service->Finish(result);
    return nullptr;
}

}  // namespace freemomo
