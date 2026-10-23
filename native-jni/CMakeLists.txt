cmake_minimum_required(VERSION 3.16)
project(freemomo_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
option(FREEMOMO_HOST_TESTS "Build Linux runner/service tests and a host-only JNI fixture" OFF)

if(NOT ANDROID AND NOT FREEMOMO_HOST_TESTS)
    message(FATAL_ERROR "Product library requires the Android NDK; host builds are test-only")
endif()
if(ANDROID AND NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "The product patch is validated for arm64-v8a only")
endif()

add_library(freemomo_core STATIC
    ../zygisk-companion/jni/secneo_patch.cpp
    ../zygisk-companion/jni/monitor_policy.cpp
    ../zygisk-companion/jni/monitor_runner.cpp
    monitor_service.cpp)
target_include_directories(freemomo_core PUBLIC . ../zygisk-companion/jni)
target_compile_options(freemomo_core PUBLIC
    -Wall -Wextra -Wpedantic -Werror -fno-exceptions -fno-rtti
    -fno-threadsafe-statics -fvisibility=hidden -fvisibility-inlines-hidden)

add_library(freemomo_native SHARED jni_entry.cpp)
target_link_libraries(freemomo_native PRIVATE freemomo_core)
target_link_options(freemomo_native PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map"
    -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-z,relro -Wl,-z,now)
set_property(TARGET freemomo_native APPEND PROPERTY LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/exports.map")

# Diagnostic GOT tracer for the shell-context study; built only on demand
# (cmake --build ... --target dexcore) and packaged only in probe candidates.
# Renamed from libfmprobe to libdexcore to avoid detection by the shell's
# anti-injection checks; the own mapping is renamed via prctl at runtime.
add_library(dexcore SHARED fmprobe.c)
target_compile_options(dexcore PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter -fvisibility=hidden)
target_link_options(dexcore PRIVATE
    -Wl,--no-undefined -Wl,-z,max-page-size=16384 -Wl,-z,relro -Wl,-z,now)

if(ANDROID)
    find_library(android_log log REQUIRED)
    target_link_libraries(freemomo_native PRIVATE ${android_log})
    target_link_libraries(dexcore PRIVATE ${android_log})
    target_link_options(freemomo_native PRIVATE -nostdlib++)
else()
    find_package(Threads REQUIRED)
    find_package(JNI REQUIRED)
    target_include_directories(freemomo_native PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(freemomo_core PUBLIC Threads::Threads)
    enable_testing()
    foreach(test_name secneo_patch_host_test monitor_policy_host_test monitor_runner_host_test)
        add_executable(${test_name} ../zygisk-companion/tests/${test_name}.cpp)
        target_link_libraries(${test_name} PRIVATE freemomo_core)
        add_test(NAME ${test_name} COMMAND ${test_name})
    endforeach()
    add_executable(monitor_service_test tests/monitor_service_test.cpp)
    target_link_libraries(monitor_service_test PRIVATE freemomo_core)
    add_test(NAME monitor_service_test COMMAND monitor_service_test)
endif()
