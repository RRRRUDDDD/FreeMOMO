LOCAL_PATH := $(call my-dir)

MOMO_COMMON_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror -fno-exceptions -fno-rtti \
    -fvisibility=hidden -fvisibility-inlines-hidden

include $(CLEAR_VARS)
LOCAL_MODULE := momo_zygisk
LOCAL_SRC_FILES := module.cpp secneo_patch.cpp
LOCAL_CPPFLAGS := $(MOMO_COMMON_CPPFLAGS)
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
