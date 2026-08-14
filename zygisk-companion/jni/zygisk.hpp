/*
 * Minimal Zygisk public API v4 declarations used by this module.
 * Derived from topjohnwu/zygisk-module-sample (0BSD).
 */
#pragma once

#include <jni.h>
#include <sys/types.h>

#define ZYGISK_API_VERSION 4

namespace zygisk {

namespace internal {
struct api_table;
template <class T>
void entry_impl(api_table*, JNIEnv*);
}  // namespace internal

struct Api;

struct AppSpecializeArgs {
    jint& uid;
    jint& gid;
    jintArray& gids;
    jint& runtime_flags;
    jobjectArray& rlimits;
    jint& mount_external;
    jstring& se_info;
    jstring& nice_name;
    jstring& instruction_set;
    jstring& app_data_dir;
    jintArray* const fds_to_ignore;
    jboolean* const is_child_zygote;
    jboolean* const is_top_app;
    jobjectArray* const pkg_data_info_list;
    jobjectArray* const whitelisted_data_info_list;
    jboolean* const mount_data_dirs;
    jboolean* const mount_storage_dirs;

    AppSpecializeArgs() = delete;
};

struct ServerSpecializeArgs {
    jint& uid;
    jint& gid;
    jintArray& gids;
    jint& runtime_flags;
    jlong& permitted_capabilities;
    jlong& effective_capabilities;

    ServerSpecializeArgs() = delete;
};

class ModuleBase {
public:
    virtual void onLoad([[maybe_unused]] Api* api, [[maybe_unused]] JNIEnv* env) {}
    virtual void preAppSpecialize([[maybe_unused]] AppSpecializeArgs* args) {}
    virtual void postAppSpecialize([[maybe_unused]] const AppSpecializeArgs* args) {}
    virtual void preServerSpecialize([[maybe_unused]] ServerSpecializeArgs* args) {}
    virtual void postServerSpecialize([[maybe_unused]] const ServerSpecializeArgs* args) {}
};

enum Option : int {
    FORCE_DENYLIST_UNMOUNT = 0,
    DLCLOSE_MODULE_LIBRARY = 1,
};

enum StateFlag : unsigned int {
    PROCESS_GRANTED_ROOT = (1u << 0),
    PROCESS_ON_DENYLIST = (1u << 1),
};

struct Api {
    int connectCompanion();
    int getModuleDir();
    void setOption(Option option);
    unsigned int getFlags();
    bool exemptFd(int fd);
    void hookJniNativeMethods(
        JNIEnv* env, const char* class_name, JNINativeMethod* methods, int method_count);
    void pltHookRegister(
        dev_t device, ino_t inode, const char* symbol, void* replacement, void** original);
    bool pltHookCommit();

private:
    internal::api_table* table_;
    template <class T>
    friend void internal::entry_impl(internal::api_table*, JNIEnv*);
};

namespace internal {

struct module_abi {
    long api_version;
    ModuleBase* implementation;
    void (*pre_app_specialize)(ModuleBase*, AppSpecializeArgs*);
    void (*post_app_specialize)(ModuleBase*, const AppSpecializeArgs*);
    void (*pre_server_specialize)(ModuleBase*, ServerSpecializeArgs*);
    void (*post_server_specialize)(ModuleBase*, const ServerSpecializeArgs*);

    explicit module_abi(ModuleBase* module)
        : api_version(ZYGISK_API_VERSION), implementation(module) {
        pre_app_specialize = [](ModuleBase* value, AppSpecializeArgs* args) {
            value->preAppSpecialize(args);
        };
        post_app_specialize = [](ModuleBase* value, const AppSpecializeArgs* args) {
            value->postAppSpecialize(args);
        };
        pre_server_specialize = [](ModuleBase* value, ServerSpecializeArgs* args) {
            value->preServerSpecialize(args);
        };
        post_server_specialize = [](ModuleBase* value, const ServerSpecializeArgs* args) {
            value->postServerSpecialize(args);
        };
    }
};

struct api_table {
    void* implementation;
    bool (*register_module)(api_table*, module_abi*);
    void (*hook_jni_native_methods)(JNIEnv*, const char*, JNINativeMethod*, int);
    void (*plt_hook_register)(dev_t, ino_t, const char*, void*, void**);
    bool (*exempt_fd)(int);
    bool (*plt_hook_commit)();
    int (*connect_companion)(void*);
    void (*set_option)(void*, Option);
    int (*get_module_dir)(void*);
    unsigned int (*get_flags)(void*);
};

template <class T>
void entry_impl(api_table* table, JNIEnv* env) {
    static Api api;
    api.table_ = table;
    static T module;
    static module_abi abi(&module);
    if (!table->register_module(table, &abi)) return;
    module.onLoad(&api, env);
}

}  // namespace internal

inline int Api::connectCompanion() {
    return table_->connect_companion ? table_->connect_companion(table_->implementation) : -1;
}

inline int Api::getModuleDir() {
    return table_->get_module_dir ? table_->get_module_dir(table_->implementation) : -1;
}

inline void Api::setOption(Option option) {
    if (table_->set_option) table_->set_option(table_->implementation, option);
}

inline unsigned int Api::getFlags() {
    return table_->get_flags ? table_->get_flags(table_->implementation) : 0;
}

inline bool Api::exemptFd(int fd) {
    return table_->exempt_fd != nullptr && table_->exempt_fd(fd);
}

inline void Api::hookJniNativeMethods(
    JNIEnv* env, const char* class_name, JNINativeMethod* methods, int method_count) {
    if (table_->hook_jni_native_methods) {
        table_->hook_jni_native_methods(env, class_name, methods, method_count);
    }
}

inline void Api::pltHookRegister(
    dev_t device, ino_t inode, const char* symbol, void* replacement, void** original) {
    if (table_->plt_hook_register) {
        table_->plt_hook_register(device, inode, symbol, replacement, original);
    }
}

inline bool Api::pltHookCommit() {
    return table_->plt_hook_commit != nullptr && table_->plt_hook_commit();
}

}  // namespace zygisk

extern "C" [[gnu::visibility("default")]] void zygisk_module_entry(
    zygisk::internal::api_table*, JNIEnv*);

#define REGISTER_ZYGISK_MODULE(module_class)                                      \
    void zygisk_module_entry(zygisk::internal::api_table* table, JNIEnv* env) {   \
        zygisk::internal::entry_impl<module_class>(table, env);                    \
    }
