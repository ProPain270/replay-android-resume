#!/usr/bin/env bash
set -euo pipefail

# Build-time-only source fetch/build recipe. The Android app receives the
# generated .so files through the AAR; it never fetches source or native code.

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUTPUT_ROOT=${1:?usage: build_mgba_android.sh OUTPUT_JNI_LIBS_DIR}
BUILD_ROOT=${MGBA_BUILD_ROOT:-$PROJECT_ROOT/bundled-core-mgba/build/mgba}
ANDROID_API_LEVEL=${ANDROID_API_LEVEL:-26}
ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
ANDROID_NDK_VERSION=${ANDROID_NDK_VERSION:-28.2.13676358}

MGBA_REPOSITORY="https://github.com/libretro/mgba"
MGBA_COMMIT="e31759b24e7a4e3899285ff720d7b573ac328ae7"
MGBA_VERSION="0.11.0"
MGBA_ARCHIVE_SHA256="396d749cce8fe3358b29cbb1db479b1816a151bd688ee45b1d241503cbc40243"
MGBA_ARCHIVE_URL="https://codeload.github.com/libretro/mgba/tar.gz/${MGBA_COMMIT}"

die() {
    echo "build_mgba_android.sh: $*" >&2
    exit 1
}

if [[ -z "$ANDROID_SDK_ROOT" ]]; then
    die "ANDROID_SDK_ROOT or ANDROID_HOME is required"
fi

ANDROID_NDK_ROOT=${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION}
[[ -d "$ANDROID_NDK_ROOT" ]] || die "Android NDK not found: $ANDROID_NDK_ROOT"

NDK_PREBUILT_ROOT=$(find "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d -print -quit)
[[ -n "$NDK_PREBUILT_ROOT" && -d "$NDK_PREBUILT_ROOT/bin" ]] || die "NDK LLVM toolchain not found"

CMAKE_BIN=${CMAKE_BIN:-}
if [[ -z "$CMAKE_BIN" ]]; then
    CMAKE_BIN=$(find "$ANDROID_SDK_ROOT/cmake" -type f -name cmake -perm -111 -print 2>/dev/null | sort | tail -1)
fi
[[ -n "$CMAKE_BIN" && -x "$CMAKE_BIN" ]] || die "Android SDK CMake executable not found"
MAKE_BIN=${MAKE_BIN:-$(command -v make || true)}
[[ -n "$MAKE_BIN" && -x "$MAKE_BIN" ]] || die "make is required for the pinned CMake recipe"
READELF_BIN="$NDK_PREBUILT_ROOT/bin/llvm-readelf"
STRIP_BIN="$NDK_PREBUILT_ROOT/bin/llvm-strip"
[[ -x "$READELF_BIN" && -x "$STRIP_BIN" ]] || die "NDK LLVM tools not found"

SOURCE_CACHE="$BUILD_ROOT/source"
ARCHIVE_CACHE="$BUILD_ROOT/downloads/mGBA-${MGBA_COMMIT}.tar.gz"
SOURCE_ROOT="$SOURCE_CACHE/mgba-${MGBA_COMMIT}"
mkdir -p "$SOURCE_CACHE" "$(dirname "$ARCHIVE_CACHE")" "$OUTPUT_ROOT"

SOURCE_ARCHIVE=${MGBA_SOURCE_ARCHIVE:-$ARCHIVE_CACHE}
if [[ ! -f "$SOURCE_ARCHIVE" ]]; then
    mkdir -p "$(dirname "$SOURCE_ARCHIVE")"
    curl --fail --location --retry 2 "$MGBA_ARCHIVE_URL" -o "$SOURCE_ARCHIVE"
fi

ARCHIVE_DIGEST=$(shasum -a 256 "$SOURCE_ARCHIVE" | awk '{print $1}')
[[ "$ARCHIVE_DIGEST" == "$MGBA_ARCHIVE_SHA256" ]] || die "source archive SHA-256 mismatch: $ARCHIVE_DIGEST"

if [[ ! -f "$SOURCE_ROOT/CMakeLists.txt" ]]; then
    rm -rf "$SOURCE_ROOT"
    tar -xzf "$SOURCE_ARCHIVE" -C "$SOURCE_CACHE"
fi
[[ -f "$SOURCE_ROOT/CMakeLists.txt" && -f "$SOURCE_ROOT/LICENSE" ]] || die "extracted mGBA source is incomplete"
[[ -f "$SOURCE_ROOT/src/platform/libretro/libretro.c" ]] || die "pinned source has no Libretro target"
[[ -f "$SOURCE_ROOT/src/platform/libretro/link.T" ]] || die "pinned source has no Libretro export map"

COMMON_CMAKE_ARGS=(
    -G "Unix Makefiles"
    "-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake"
    "-DANDROID_PLATFORM=android-$ANDROID_API_LEVEL"
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY
    -DBINARY_NAME=mgba
    -DBUILD_LIBRETRO=ON
    -DLIBRETRO_LIBDIR=lib
    -DSKIP_LIBRARY=ON
    -DBUILD_SHARED=OFF
    -DBUILD_STATIC=OFF
    -DBUILD_SDL=OFF
    -DBUILD_QT=OFF
    -DBUILD_GL=OFF
    -DBUILD_GLES2=OFF
    -DBUILD_GLES3=OFF
    -DUSE_EPOXY=OFF
    -DUSE_FFMPEG=OFF
    -DUSE_ZLIB=OFF
    -DUSE_PNG=OFF
    -DUSE_LIBZIP=OFF
    -DUSE_MINIZIP=OFF
    -DUSE_LZMA=OFF
    -DUSE_SQLITE3=OFF
    -DUSE_ELF=OFF
    -DUSE_LUA=OFF
    -DUSE_JSON_C=OFF
    -DUSE_FREETYPE=OFF
    -DUSE_DISCORD_RPC=OFF
    -DENABLE_SCRIPTING=OFF
    -DENABLE_DEBUGGERS=OFF
    -DBUILD_TEST=OFF
    -DBUILD_SUITE=OFF
    -DBUILD_CINEMA=OFF
    -DBUILD_HEADLESS=OFF
    -DBUILD_EXAMPLE=OFF
    -DBUILD_PYTHON=OFF
    -DBUILD_PERF=OFF
    -DBUILD_DOCGEN=OFF
    -DBUILD_MAINTAINER_TOOLS=OFF
    -DDISABLE_DEPS=ON
    -DSKIP_GIT=ON
    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"
)

build_abi() {
    local abi="$1"
    local abi_build="$BUILD_ROOT/build-$abi"
    local abi_output="$OUTPUT_ROOT/$abi"
    local built_core="$abi_build/mgba_libretro.so"
    local core_output="$abi_output/libmgba_libretro.so"

    rm -rf "$abi_build"
    mkdir -p "$abi_output"
    "$CMAKE_BIN" -S "$SOURCE_ROOT" -B "$abi_build" "${COMMON_CMAKE_ARGS[@]}" "-DANDROID_ABI=$abi" "-DCMAKE_MAKE_PROGRAM=$MAKE_BIN"
    "$CMAKE_BIN" --build "$abi_build" --target mgba_libretro -- -j"${MGBA_BUILD_JOBS:-4}"
    [[ -f "$built_core" ]] || die "$abi build did not produce mgba_libretro.so"

    "$STRIP_BIN" --strip-unneeded "$built_core"
    cp "$built_core" "$core_output"
    "$READELF_BIN" -h "$core_output" > "$BUILD_ROOT/$abi.elf-header.txt"
    "$READELF_BIN" -lW "$core_output" > "$BUILD_ROOT/$abi.program-headers.txt"
    awk '$1 == "LOAD" {found = 1; if ($NF != "0x4000") bad = 1; print} END {exit !(found && !bad)}' \
        "$BUILD_ROOT/$abi.program-headers.txt" \
        || die "$abi core is not 16 KiB PT_LOAD aligned"
    "$READELF_BIN" -Ws "$core_output" | grep 'retro_api_version' >/dev/null \
        || die "$abi core is missing retro_api_version"
    "$READELF_BIN" -Ws "$core_output" | grep 'retro_run' >/dev/null \
        || die "$abi core is missing retro_run"
}

build_abi arm64-v8a
build_abi x86_64

cat > "$BUILD_ROOT/build-info.txt" <<EOF
mgba_version=$MGBA_VERSION
mgba_repository=$MGBA_REPOSITORY
mgba_commit=$MGBA_COMMIT
mgba_archive_url=$MGBA_ARCHIVE_URL
mgba_archive_sha256=$MGBA_ARCHIVE_SHA256
android_api_level=$ANDROID_API_LEVEL
android_ndk_version=$ANDROID_NDK_VERSION
abis=arm64-v8a,x86_64
build_system=upstream-CMake-BUILD_LIBRETRO
runtime_network_loading=disabled; source fetch is build-time only
embedded_bios_policy=none; mGBA's built-in BIOS implementation is used; user-owned external BIOS remains optional
link_flags=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
feature_flags=BUILD_LIBRETRO=ON;SKIP_LIBRARY=ON;DISABLE_DEPS=ON;USE_ZLIB=OFF;USE_PNG=OFF;USE_LIBZIP=OFF;USE_LZMA=OFF;USE_SQLITE3=OFF;USE_FFMPEG=OFF;USE_EPOXY=OFF;ENABLE_SCRIPTING=OFF;ENABLE_DEBUGGERS=OFF
EOF

echo "Built mGBA $MGBA_COMMIT for arm64-v8a and x86_64 in $OUTPUT_ROOT"
