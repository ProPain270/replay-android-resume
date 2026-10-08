#!/usr/bin/env bash
set -euo pipefail

# This is a build-time source fetch/build script. The app never fetches native
# code, and the generated .so files are only consumed by the Android build.

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUTPUT_ROOT=${1:?usage: build_sameboy_android.sh OUTPUT_JNI_LIBS_DIR}
BUILD_ROOT=${SAMEBOY_BUILD_ROOT:-$PROJECT_ROOT/bundled-core-sameboy/build/sameboy}
ANDROID_API_LEVEL=${ANDROID_API_LEVEL:-26}
ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
ANDROID_NDK_VERSION=${ANDROID_NDK_VERSION:-28.2.13676358}

SAMEBOY_REPOSITORY="https://github.com/LIJI32/SameBoy"
SAMEBOY_COMMIT="0fc08d93bed566c71f97b4498cda43f97cd3cd6f"
SAMEBOY_SHORT_COMMIT="${SAMEBOY_COMMIT:0:7}"
SAMEBOY_VERSION="1.0.1"
SAMEBOY_ARCHIVE_SHA256="5b3c9116ce2e3881f7ee5111b4077c5672fda159bc6458f442d284e543f081a6"
SAMEBOY_ARCHIVE_URL="https://codeload.github.com/LIJI32/SameBoy/tar.gz/${SAMEBOY_COMMIT}"

die() {
    echo "build_sameboy_android.sh: $*" >&2
    exit 1
}

if [[ -z "$ANDROID_SDK_ROOT" ]]; then
    die "ANDROID_SDK_ROOT or ANDROID_HOME is required"
fi

ANDROID_NDK_ROOT=${ANDROID_NDK_ROOT:-$ANDROID_SDK_ROOT/ndk/$ANDROID_NDK_VERSION}
[[ -d "$ANDROID_NDK_ROOT" ]] || die "Android NDK not found: $ANDROID_NDK_ROOT"

LLVM_ROOT=$(find "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d -print -quit)
[[ -n "$LLVM_ROOT" && -d "$LLVM_ROOT/bin" ]] || die "NDK LLVM toolchain not found"

SOURCE_CACHE="$BUILD_ROOT/source"
ARCHIVE_CACHE="$BUILD_ROOT/downloads/SameBoy-${SAMEBOY_COMMIT}.tar.gz"
SOURCE_ROOT="$SOURCE_CACHE/SameBoy-${SAMEBOY_COMMIT}"
mkdir -p "$SOURCE_CACHE" "$(dirname "$ARCHIVE_CACHE")" "$OUTPUT_ROOT"

SOURCE_ARCHIVE=${SAMEBOY_SOURCE_ARCHIVE:-$ARCHIVE_CACHE}
if [[ ! -f "$SOURCE_ARCHIVE" ]]; then
    mkdir -p "$(dirname "$SOURCE_ARCHIVE")"
    curl --fail --location --retry 2 "$SAMEBOY_ARCHIVE_URL" -o "$SOURCE_ARCHIVE"
fi

ARCHIVE_DIGEST=$(shasum -a 256 "$SOURCE_ARCHIVE" | awk '{print $1}')
[[ "$ARCHIVE_DIGEST" == "$SAMEBOY_ARCHIVE_SHA256" ]] || die "source archive SHA-256 mismatch: $ARCHIVE_DIGEST"

if [[ ! -f "$SOURCE_ROOT/LICENSE" ]]; then
    tar -xzf "$SOURCE_ARCHIVE" -C "$SOURCE_CACHE"
fi
[[ -f "$SOURCE_ROOT/LICENSE" ]] || die "extracted SameBoy source is incomplete"

for required_tool in make rgbasm rgblink rgbgfx hexdump; do
    command -v "$required_tool" >/dev/null 2>&1 || die "$required_tool is required to build SameBoy's open-source boot ROMs"
done

# SameBoy's boot ROMs are open-source reimplementations shipped in the pinned
# source archive. Build them from source and convert them into generated C so
# the Android core can boot without a system-directory dependency.
make -C "$SOURCE_ROOT" RGBDS="${RGBDS_PREFIX:-}" bootroms
BOOTROM_DIR="$SOURCE_ROOT/build/bin/BootROMs"
GENERATED_BOOTROMS="$BUILD_ROOT/generated_bootroms.c"
{
    echo '/* Generated from SameBoy BootROMs in the pinned source archive. */'
    for boot_name in dmg mgb cgb0 cgb agb sgb sgb2; do
        boot_file="$BOOTROM_DIR/${boot_name}_boot.bin"
        [[ -f "$boot_file" ]] || die "missing generated boot ROM: $boot_file"
        echo "const unsigned char ${boot_name}_boot[] = {"
        hexdump -v -e '/1 "0x%02x, "' "$boot_file"
        echo '};'
        echo "const unsigned ${boot_name}_boot_length = sizeof(${boot_name}_boot);"
    done
} > "$GENERATED_BOOTROMS"

COMMON_CFLAGS=(
    -Wall
    -O2
    -DNDEBUG
    -DANDROID
    -D__LIBRETRO__
    -DGB_INTERNAL
    -DGB_DISABLE_TIMEKEEPING
    -DGB_DISABLE_REWIND
    -DGB_DISABLE_DEBUGGER
    -DGB_DISABLE_CHEATS
    "-DGB_VERSION=\"$SAMEBOY_VERSION\""
    "-DGIT_VERSION=\" $SAMEBOY_SHORT_COMMIT\""
    -std=gnu11
    -D_GNU_SOURCE
    -D_USE_MATH_DEFINES
    -fPIC
    -fvisibility=hidden
    -fno-omit-frame-pointer
    -ffunction-sections
    -fdata-sections
    -I"$SOURCE_ROOT"
    -I"$SOURCE_ROOT/Core"
    -I"$SOURCE_ROOT/libretro"
)

SOURCE_FILES=(
    "$SOURCE_ROOT/Core/gb.c"
    "$SOURCE_ROOT/Core/sgb.c"
    "$SOURCE_ROOT/Core/apu.c"
    "$SOURCE_ROOT/Core/memory.c"
    "$SOURCE_ROOT/Core/mbc.c"
    "$SOURCE_ROOT/Core/timing.c"
    "$SOURCE_ROOT/Core/display.c"
    "$SOURCE_ROOT/Core/camera.c"
    "$SOURCE_ROOT/Core/sm83_cpu.c"
    "$SOURCE_ROOT/Core/joypad.c"
    "$SOURCE_ROOT/Core/save_state.c"
    "$SOURCE_ROOT/Core/random.c"
    "$SOURCE_ROOT/Core/rumble.c"
    "$SOURCE_ROOT/libretro/libretro.c"
    "$GENERATED_BOOTROMS"
)

for source_file in "${SOURCE_FILES[@]}"; do
    [[ -f "$source_file" ]] || die "missing pinned source file: $source_file"
done

build_abi() {
    local abi="$1"
    local triple="$2"
    local compiler="$LLVM_ROOT/bin/${triple}${ANDROID_API_LEVEL}-clang"
    local strip_tool="$LLVM_ROOT/bin/llvm-strip"
    local readelf_tool="$LLVM_ROOT/bin/llvm-readelf"
    local object_root="$BUILD_ROOT/obj/$abi"
    local abi_output="$OUTPUT_ROOT/$abi"
    local core_output="$abi_output/libsameboy_libretro.so"

    [[ -x "$compiler" ]] || die "NDK compiler not found: $compiler"
    [[ -x "$strip_tool" && -x "$readelf_tool" ]] || die "NDK LLVM tools not found"
    mkdir -p "$object_root" "$abi_output"

    local object_files=()
    local source_file
    local source_name
    local object_file
    for source_file in "${SOURCE_FILES[@]}"; do
        source_name=$(basename "$source_file")
        object_file="$object_root/${source_name%.c}.o"
        "$compiler" "${COMMON_CFLAGS[@]}" -c "$source_file" -o "$object_file"
        object_files+=("$object_file")
    done

    "$compiler" -shared \
        -Wl,--version-script="$SOURCE_ROOT/libretro/link.T" \
        -Wl,--no-undefined \
        -Wl,--gc-sections \
        -Wl,-z,max-page-size=16384 \
        -Wl,-z,common-page-size=16384 \
        -Wl,-soname,libsameboy_libretro.so \
        -o "$core_output" \
        "${object_files[@]}" \
        -lm
    "$strip_tool" --strip-unneeded "$core_output"

    "$readelf_tool" -h "$core_output" > "$BUILD_ROOT/${abi}.elf-header.txt"
    "$readelf_tool" -lW "$core_output" > "$BUILD_ROOT/${abi}.program-headers.txt"
    awk '$1 == "LOAD" {found = 1; if ($NF != "0x4000") bad = 1} END {exit !(found && !bad)}' \
        "$BUILD_ROOT/${abi}.program-headers.txt" \
        || die "$abi core is not 16 KiB PT_LOAD aligned"
    "$readelf_tool" -Ws "$core_output" | grep -q 'retro_api_version' \
        || die "$abi core is missing the Libretro entry points"
}

build_abi arm64-v8a aarch64-linux-android
build_abi x86_64 x86_64-linux-android

cat > "$BUILD_ROOT/build-info.txt" <<EOF
sameboy_version=$SAMEBOY_VERSION
sameboy_repository=$SAMEBOY_REPOSITORY
sameboy_commit=$SAMEBOY_COMMIT
sameboy_archive_url=$SAMEBOY_ARCHIVE_URL
sameboy_archive_sha256=$SAMEBOY_ARCHIVE_SHA256
android_api_level=$ANDROID_API_LEVEL
android_ndk_version=$ANDROID_NDK_VERSION
abis=arm64-v8a,x86_64
boot_rom_policy=open-source-sameboy-bootroms-built-from-pinned-source; no-proprietary-bios-packaged
link_flags=--version-script=libretro/link.T --no-undefined --gc-sections -z max-page-size=16384 -z common-page-size=16384
compile_flags=-Wall -O2 -DNDEBUG -DANDROID -D__LIBRETRO__ -DGB_INTERNAL -DGB_DISABLE_TIMEKEEPING -DGB_DISABLE_REWIND -DGB_DISABLE_DEBUGGER -DGB_DISABLE_CHEATS -DGB_VERSION=1.0.1 -DGIT_VERSION=0fc08d9 -std=gnu11 -fPIC -fvisibility=hidden -fno-omit-frame-pointer -ffunction-sections -fdata-sections
EOF

echo "Built SameBoy $SAMEBOY_COMMIT for arm64-v8a and x86_64 in $OUTPUT_ROOT"
