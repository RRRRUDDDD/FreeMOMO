#!/bin/sh
set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ "$#" -ne 1 ] || [ -e "$1" ]; then
    echo "Usage: test-host.sh /absolute/new/output-directory" >&2
    exit 1
fi
mkdir -p "$1"
output=$(CDPATH= cd -- "$1" && pwd)
if [ -z "${JAVA_HOME:-}" ]; then
    JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
    export JAVA_HOME
fi

cmake -S "$project_dir" -B "$output" -G Ninja \
    -DFREEMOMO_HOST_TESTS=ON -DCMAKE_BUILD_TYPE=Debug
cmake --build "$output"
ctest --test-dir "$output" -V --timeout 20 --output-log "$output/ctest.log"

mkdir -p "$output/jni-good" "$output/jni-bad" "$output/jni-driver"
javac -d "$output/jni-good" "$project_dir/tests/java/org/lsposed/lspatch/metaloader/NativeBridge.java"
javac -d "$output/jni-bad" "$project_dir/tests/java-bad/org/lsposed/lspatch/metaloader/NativeBridge.java"
javac -d "$output/jni-driver" "$project_dir/tests/java/HostJniTest.java" "$project_dir/tests/java/JniLoadFailure.java"
java -Xcheck:jni -cp "$output/jni-driver" HostJniTest "$output/libfreemomo_native.so" "$output/jni-good"
java -Xcheck:jni -cp "$output/jni-driver" JniLoadFailure "$output/libfreemomo_native.so"
java -Xcheck:jni -cp "$output/jni-driver:$output/jni-bad" JniLoadFailure "$output/libfreemomo_native.so" partial
