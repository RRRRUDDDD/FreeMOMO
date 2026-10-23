#include "monitor_android_log.hpp"
#include "monitor_process_guard.hpp"
#include "zygisk.hpp"

#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <string.h>
#include <unistd.h>

namespace {

constexpr char kTargetProcess[] = "com.maimemo.android.momo";
constexpr char kLogTag[] = "FreeMOMO.Zygisk";

bool IsTargetProcess(const char* process_name) {
    if (process_name == nullptr) return false;
    const size_t length = sizeof(kTargetProcess) - 1;
    if (strncmp(process_name, kTargetProcess, length) != 0) return false;
    return process_name[length] == '\0' || process_name[length] == ':';
}

void LogMonitor(void*, momo_secneo::RunnerEvent event,
                const momo_secneo::RunnerResult& result) {
    momo_secneo::LogRunnerEvent(kLogTag, event, result);
}

// AtForkChild runs inside the fork child's signal context; the logd client
// library is not async-signal-safe (malloc + mutexes), so the notification
// degrades to a raw write(2) on stderr with a hand-rolled formatter.
char* AppendText(char* cursor, char* end, const char* text) {
    while (*text != '\0' && cursor < end) *cursor++ = *text++;
    return cursor;
}

char* AppendNumber(char* cursor, char* end, long value) {
    char digits[20];
    int count = 0;
    do {
        digits[count++] = static_cast<char>('0' + value % 10);
        value /= 10;
    } while (value != 0);
    while (count > 0 && cursor < end) *cursor++ = digits[--count];
    return cursor;
}

void LogForkChild(void*, pid_t pid, pid_t ppid, momo_secneo::PatchResult result) {
    char buffer[128];
    char* cursor = buffer;
    char* const end = buffer + sizeof(buffer);
    cursor = AppendText(cursor, end, "FreeMOMO.Zygisk: SecNeo fork child pid=");
    cursor = AppendNumber(cursor, end, pid);
    cursor = AppendText(cursor, end, " ppid=");
    cursor = AppendNumber(cursor, end, ppid);
    cursor = AppendText(cursor, end, " patch_result=");
    cursor = AppendNumber(cursor, end, static_cast<int>(result));
    cursor = AppendText(cursor, end, "\n");
    ssize_t written;
    do {
        written = write(STDERR_FILENO, buffer, static_cast<size_t>(cursor - buffer));
    } while (written < 0 && errno == EINTR);
}

void* MonitorPayload(void*) {
    const momo_secneo::RunnerObserver observer{nullptr, nullptr, nullptr, LogMonitor};
    momo_secneo::RunMonitor(momo_secneo::SystemRunnerPlatform(), observer);
    return nullptr;
}

class MomoSecNeoModule final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs* args) override {
        target_process_ = false;
        if (args != nullptr && args->nice_name != nullptr) {
            const char* process_name = env_->GetStringUTFChars(args->nice_name, nullptr);
            if (process_name != nullptr) {
                target_process_ = IsTargetProcess(process_name);
                env_->ReleaseStringUTFChars(args->nice_name, process_name);
            }
        }
        if (!target_process_) api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs*) override {
        if (!target_process_) return;

        const momo_secneo::GuardInstallResult guard =
            momo_secneo::InstallForkChildGuard({nullptr, LogForkChild});
        if (guard != momo_secneo::GuardInstallResult::kInstalled) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                "SecNeo fork-child guard install failed pid=%d result=%d",
                                getpid(), static_cast<int>(guard));
        } else {
            __android_log_print(ANDROID_LOG_INFO, kLogTag,
                                "SecNeo fork-child guard installed pid=%d", getpid());
        }

        pthread_attr_t attributes;
        const int attribute_error = pthread_attr_init(&attributes);
        int error = attribute_error;
        if (error == 0) {
            error = pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
        }
        pthread_t thread;
        if (error == 0) error = pthread_create(&thread, &attributes, MonitorPayload, nullptr);
        if (attribute_error == 0) pthread_attr_destroy(&attributes);
        if (error != 0) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                "SecNeo monitor thread creation failed pid=%d error=%d",
                                getpid(), error);
        }
    }

    void preServerSpecialize(zygisk::ServerSpecializeArgs*) override {
        api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
    bool target_process_ = false;
};

}  // namespace

REGISTER_ZYGISK_MODULE(MomoSecNeoModule)
