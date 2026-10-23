#include "monitor_service.hpp"

#include <jni.h>
#include <unistd.h>

#if defined(__ANDROID__)
#include "monitor_android_log.hpp"
#else
// Used only by Linux JVM binding tests; never an Android product build.
#include <stdio.h>
#endif

namespace {

constexpr char kLogTag[] = "FreeMOMO.Native";
constexpr char kBridgeClass[] = "org/lsposed/lspatch/metaloader/NativeBridge";

void LogState(void*, const freemomo::ServiceSnapshot& state, int64_t now_us) {
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
        "state=%s readiness=%s pid=%d mono_us=%lld error=%d worker_finished=%d",
        freemomo::StateName(state.state), freemomo::StateName(state.readiness),
        getpid(), static_cast<long long>(now_us), state.error, state.worker_finished);
#else
    fprintf(stderr, "%s state=%s readiness=%s pid=%d mono_us=%lld error=%d\n", kLogTag,
        freemomo::StateName(state.state), freemomo::StateName(state.readiness),
        getpid(), static_cast<long long>(now_us), state.error);
#endif
}

void LogRunner(void*, momo_secneo::RunnerEvent event, const momo_secneo::RunnerResult& result) {
#if defined(__ANDROID__)
    momo_secneo::LogRunnerEvent(kLogTag, event, result);
#else
    (void) event;
    fprintf(stderr, "%s %s\n", kLogTag, momo_secneo::RunnerReason(result));
#endif
}

void BindingLog(const char* stage) {
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "JNI binding %s pid=%d", stage, getpid());
#else
    fprintf(stderr, "%s JNI binding %s pid=%d\n", kLogTag, stage, getpid());
#endif
}

const freemomo::ServiceObserver kObserver{nullptr, LogState, LogRunner};
// Never destroy a process-long mutex/condition in an exit handler while a
// detached worker may still run. The pinned loader prevents ordinary unload.
#if defined(__clang__)
__attribute__((no_destroy))
#endif
freemomo::MonitorService g_service(freemomo::SystemServicePlatform(),
                                  momo_secneo::SystemRunnerPlatform(), kObserver);

// Intentionally retained for process lifetime. A jclass global reference keeps
// its defining loader (and this library) alive after LSPatch switches loaders.
// No JNIEnv pointer is cached or used by the worker.
jclass g_bridge = nullptr;

jint NativeStart(JNIEnv*, jclass, jint timeout_ms) {
    return static_cast<jint>(g_service.Start(timeout_ms));
}

jint NativeGetState(JNIEnv*, jclass) {
    return static_cast<jint>(g_service.GetSnapshot().state);
}

JNINativeMethod kMethods[] = {
    {const_cast<char*>("nativeStart"), const_cast<char*>("(I)I"), reinterpret_cast<void*>(NativeStart)},
    {const_cast<char*>("nativeGetState"), const_cast<char*>("()I"), reinterpret_cast<void*>(NativeGetState)},
};

void ClearBindingException(JNIEnv* env) {
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm == nullptr || vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr || g_bridge != nullptr) {
        BindingLog("GetEnv/repeated load rejected");
        return JNI_ERR;
    }
    jclass local = env->FindClass(kBridgeClass);
    if (local == nullptr) {
        BindingLog("FindClass failed");
        ClearBindingException(env);
        return JNI_ERR;
    }
    auto retained = static_cast<jclass>(env->NewGlobalRef(local));
    if (retained == nullptr) {
        BindingLog("NewGlobalRef failed");
        ClearBindingException(env);
        env->DeleteLocalRef(local);
        return JNI_ERR;
    }
    if (env->RegisterNatives(local, kMethods, sizeof(kMethods) / sizeof(kMethods[0])) != JNI_OK) {
        BindingLog("RegisterNatives failed");
        ClearBindingException(env);
        // Registration may have partially bound the class before failing.
        env->UnregisterNatives(local);
        ClearBindingException(env);
        env->DeleteGlobalRef(retained);
        env->DeleteLocalRef(local);
        return JNI_ERR;
    }
    g_bridge = retained;
    env->DeleteLocalRef(local);
    BindingLog("registered; monitor NOT_STARTED");
    return JNI_VERSION_1_6;
}
