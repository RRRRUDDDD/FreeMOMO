APP_ABI := arm64-v8a
# Registration runs once; avoid a libstdc++ dependency for guarded local statics.
APP_CPPFLAGS := -std=c++17 -fno-exceptions -fno-rtti -fno-threadsafe-statics
APP_STL := none
APP_PLATFORM := android-28
