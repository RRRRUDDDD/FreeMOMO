#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ -n "${CXX:-}" ]; then
    compiler=$CXX
elif command -v c++ >/dev/null 2>&1; then
    compiler=c++
elif command -v g++ >/dev/null 2>&1; then
    compiler=g++
elif command -v clang++ >/dev/null 2>&1; then
    compiler=clang++
else
    echo "No C++ compiler found (tried CXX, c++, g++, and clang++)." >&2
    exit 1
fi

build_dir=$(mktemp -d "${TMPDIR:-/tmp}/momo-secneo-host.XXXXXX")
trap 'rm -rf -- "$build_dir"' EXIT HUP INT TERM

common_sources="
    $project_dir/jni/secneo_patch.cpp
    $project_dir/jni/monitor_policy.cpp
    $project_dir/jni/monitor_runner.cpp
    $project_dir/jni/monitor_process_guard.cpp
"

for test_name in secneo_patch_host_test monitor_policy_host_test monitor_runner_host_test monitor_process_guard_host_test; do
    "$compiler" \
    -std=c++17 \
    -D_DEFAULT_SOURCE \
    -O2 \
    -Wall \
    -Wextra \
    -Wpedantic \
    -Werror \
    -fno-exceptions \
    -fno-rtti \
    -pthread \
    -I"$project_dir/jni" \
    "$project_dir/tests/$test_name.cpp" \
    $common_sources \
    -o "$build_dir/$test_name"

    "$build_dir/$test_name"
done
