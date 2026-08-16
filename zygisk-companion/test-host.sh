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
    -I"$project_dir/jni" \
    "$project_dir/tests/secneo_patch_host_test.cpp" \
    "$project_dir/jni/secneo_patch.cpp" \
    -o "$build_dir/secneo_patch_host_test"

"$build_dir/secneo_patch_host_test"
