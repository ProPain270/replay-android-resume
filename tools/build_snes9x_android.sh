#!/usr/bin/env bash
set -euo pipefail

# Build-time-only source fetch/build recipe. The Android app receives the
# generated .so files through the AAR; it never fetches source or native code.

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUTPUT_ROOT=${1:?usage: build_snes9x_android.sh OUTPUT_JNI_LIBS_DIR}
BUILD_ROOT=${SNES9X_BUILD_ROOT:-$PROJECT_ROOT/bundled-core-snes9x/build/snes9x}
ANDROID_API_LEVEL=${ANDROID_API_LEVEL:-26}
ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
ANDROID_NDK_VERSION=${ANDROID_NDK_VERSION:-28.2.13676358}

SNES9X_REPOSITORY="https://github.com/libretro/snes9x"
SNES9X_COMMIT="890b5d445538fe790aa3add3d5702c80f551e0ae"
SNES9X_VERSION="1.61"
SNES9X_ARCHIVE_SHA256="fcc32b536d3e7b1def0d15489917dd043d5e1d9636de8af7851c48e771276613"
SNES9X_ARCHIVE_URL="https://codeload.github.com/libretro/snes9x/tar.gz/${SNES9X_COMMIT}"

die() {
    echo "build_snes9x_android.sh: $*" >&2
    exit 1
}

if [[ -z "$ANDROID_SDK_ROOT" ]]; then
    die "ANDROID_SDK_ROOT or ANDROID_HOME is required"
fi

ANDROID_NDK_ROOT=${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION}
[[ -d "$ANDROID_NDK_ROOT" ]] || die "Android NDK not found: $ANDROID_NDK_ROOT"

LLVM_ROOT=$(find "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d -print -quit)
[[ -n "$LLVM_ROOT" && -d "$LLVM_ROOT/bin" ]] || die "NDK LLVM toolchain not found"

MAKE_BIN=${MAKE_BIN:-$(command -v make || true)}
[[ -n "$MAKE_BIN" && -x "$MAKE_BIN" ]] || die "make is required for the pinned Libretro recipe"
STRIP_BIN="$LLVM_ROOT/bin/llvm-strip"
READELF_BIN="$LLVM_ROOT/bin/llvm-readelf"
[[ -x "$STRIP_BIN" && -x "$READELF_BIN" ]] || die "NDK LLVM tools not found"

SOURCE_CACHE="$BUILD_ROOT/source"
ARCHIVE_CACHE="$BUILD_ROOT/downloads/snes9x-${SNES9X_COMMIT}.tar.gz"
SOURCE_ROOT="$SOURCE_CACHE/snes9x-${SNES9X_COMMIT}"
mkdir -p "$SOURCE_CACHE" "$(dirname "$ARCHIVE_CACHE")" "$OUTPUT_ROOT"

SOURCE_ARCHIVE=${SNES9X_SOURCE_ARCHIVE:-$ARCHIVE_CACHE}
if [[ ! -f "$SOURCE_ARCHIVE" ]]; then
    mkdir -p "$(dirname "$SOURCE_ARCHIVE")"
    curl --fail --location --retry 2 "$SNES9X_ARCHIVE_URL" -o "$SOURCE_ARCHIVE"
fi

ARCHIVE_DIGEST=$(shasum -a 256 "$SOURCE_ARCHIVE" | awk '{print $1}')
[[ "$ARCHIVE_DIGEST" == "$SNES9X_ARCHIVE_SHA256" ]] || die "source archive SHA-256 mismatch: $ARCHIVE_DIGEST"

if [[ ! -f "$SOURCE_ROOT/libretro/Makefile" ]]; then
    tar -xzf "$SOURCE_ARCHIVE" -C "$SOURCE_CACHE"
fi
[[ -f "$SOURCE_ROOT/libretro/Makefile" && -f "$SOURCE_ROOT/LICENSE" ]] || die "extracted Snes9x source is incomplete"
[[ -f "$SOURCE_ROOT/libretro/libretro.cpp" && -f "$SOURCE_ROOT/libretro/link.T" ]] || die "pinned source has no Libretro target"

COMMON_CFLAGS=(
    -DANDROID
    -D__LIBRETRO__
    -DALLOW_CPU_OVERCLOCK
    -DHAVE_STRINGS_H
    -O2
    -DNDEBUG
    -fPIC
    -fno-strict-aliasing
    -fno-omit-frame-pointer
    -ffunction-sections
    -fdata-sections
)
COMMON_CXXFLAGS=(
    "${COMMON_CFLAGS[@]}"
    -std=c++14
    -fno-rtti
    -fno-exceptions
    -pedantic
)
COMMON_LDFLAGS=(
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
    -Wl,--gc-sections
)

build_abi() {
    local abi="$1"
    local triple="$2"
    local compiler="$LLVM_ROOT/bin/${triple}${ANDROID_API_LEVEL}-clang"
    local cxx_compiler="$LLVM_ROOT/bin/${triple}${ANDROID_API_LEVEL}-clang++"
    local abi_source_parent="$BUILD_ROOT/source-$abi"
    local abi_source_root="$abi_source_parent/snes9x-${SNES9X_COMMIT}"
    local abi_output="$OUTPUT_ROOT/$abi"
    local built_core="$abi_source_root/libretro/snes9x_libretro.so"
    local core_output="$abi_output/libsnes9x_libretro.so"
    local libcxx_shared="$LLVM_ROOT/sysroot/usr/lib/$triple/libc++_shared.so"
    local libcxx_output="$abi_output/libc++_shared.so"

    [[ -x "$compiler" && -x "$cxx_compiler" ]] || die "NDK compiler not found for $abi"
    [[ -f "$libcxx_shared" ]] || die "NDK libc++_shared.so not found for $abi: $libcxx_shared"
    if [[ ! -f "$abi_source_root/libretro/Makefile" ]]; then
        mkdir -p "$abi_source_parent"
        cp -a "$SOURCE_ROOT" "$abi_source_root"
    fi
    mkdir -p "$abi_output"

    make -C "$abi_source_root/libretro" -f Makefile -B \
        platform=unix \
        LTO= \
        HAVE_EXCEPTIONS=0 \
        GIT_VERSION="\\\" $SNES9X_COMMIT\\\"" \
        CC="$compiler" \
        CXX="$cxx_compiler" \
        CFLAGS="${COMMON_CFLAGS[*]}" \
        CXXFLAGS="${COMMON_CXXFLAGS[*]}" \
        LDFLAGS="${COMMON_LDFLAGS[*]}" \
        -j"${SNES9X_BUILD_JOBS:-4}"
    [[ -f "$built_core" ]] || die "$abi build did not produce snes9x_libretro.so"

    "$STRIP_BIN" --strip-unneeded "$built_core"
    cp "$built_core" "$core_output"
    # Snes9x is C++ and intentionally uses the NDK's shared libc++ runtime.
    # Package the matching ABI beside the core so Android's class-loader
    # namespace can resolve the dependency when the host calls dlopen().
    cp "$libcxx_shared" "$libcxx_output"
    "$READELF_BIN" -h "$core_output" > "$BUILD_ROOT/$abi.elf-header.txt"
    "$READELF_BIN" -lW "$core_output" > "$BUILD_ROOT/$abi.program-headers.txt"
    "$READELF_BIN" -Ws "$core_output" > "$BUILD_ROOT/$abi.dynamic-symbols.txt"
    awk '$1 == "LOAD" {found = 1; if ($NF != "0x4000") bad = 1} END {exit !(found && !bad)}' \
        "$BUILD_ROOT/$abi.program-headers.txt" \
        || die "$abi core is not 16 KiB PT_LOAD aligned"
    "$READELF_BIN" -lW "$libcxx_output" > "$BUILD_ROOT/$abi.libcxx.program-headers.txt"
    awk '$1 == "LOAD" {found = 1; if ($NF != "0x4000") bad = 1} END {exit !(found && !bad)}' \
        "$BUILD_ROOT/$abi.libcxx.program-headers.txt" \
        || die "$abi libc++_shared.so is not 16 KiB PT_LOAD aligned"
    rg -F 'retro_api_version' "$BUILD_ROOT/$abi.dynamic-symbols.txt" >/dev/null \
        || die "$abi core is missing retro_api_version"
    rg -F 'retro_run' "$BUILD_ROOT/$abi.dynamic-symbols.txt" >/dev/null \
        || die "$abi core is missing retro_run"
}

build_abi arm64-v8a aarch64-linux-android
build_abi x86_64 x86_64-linux-android

cat > "$BUILD_ROOT/build-info.txt" <<EOF
snes9x_version=$SNES9X_VERSION
snes9x_repository=$SNES9X_REPOSITORY
snes9x_commit=$SNES9X_COMMIT
snes9x_archive_url=$SNES9X_ARCHIVE_URL
snes9x_archive_sha256=$SNES9X_ARCHIVE_SHA256
android_api_level=$ANDROID_API_LEVEL
android_ndk_version=$ANDROID_NDK_VERSION
abis=arm64-v8a,x86_64
build_system=upstream-libretro-Makefile
runtime_network_loading=disabled; source fetch is build-time only
embedded_bios_policy=none; optional BS-X and Sufami Turbo firmware remain user-owned system files
runtime_dependency=libc++_shared.so copied from the pinned Android NDK per ABI
link_flags=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384
compile_flags=-DANDROID;-D__LIBRETRO__;-DALLOW_CPU_OVERCLOCK;-O2;-DNDEBUG;-fPIC;-fno-strict-aliasing;-fno-rtti;-fno-exceptions
EOF

echo "Built Snes9x $SNES9X_COMMIT for arm64-v8a and x86_64 in $OUTPUT_ROOT"
