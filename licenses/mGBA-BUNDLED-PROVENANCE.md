# mGBA bundled-core provenance

Status: source-pinned Android build lane; not a legal clearance opinion.

- Upstream repository: https://github.com/libretro/mgba
- Upstream source URL: https://codeload.github.com/libretro/mgba/tar.gz/e31759b24e7a4e3899285ff720d7b573ac328ae7
- Exact source commit: `e31759b24e7a4e3899285ff720d7b573ac328ae7`
- Source archive SHA-256: `396d749cce8fe3358b29cbb1db479b1816a151bd688ee45b1d241503cbc40243`
- Upstream `LICENSE` SHA-256: `fab3dd6bdab226f1c08630b1dd917e11fcb4ec5e1e020e2c16f83a0a13863e85`
- mGBA license: Mozilla Public License 2.0, preserved verbatim in
  `mGBA-MPL-2.0.txt` and referenced by `mGBA-LICENSE.txt`.
- Bundled inih dependency: BSD-3-Clause, from the pinned source's
  `src/third-party/inih/LICENSE.txt`, preserved in
  `mGBA-inih-BSD-3-Clause.txt`.
- Libretro API header: MIT notice in the pinned source's
  `src/platform/libretro/libretro.h`, preserved in
  `mGBA-Libretro-API-MIT.txt`.

## Shipped source boundary

The recipe uses the pinned upstream CMake `BUILD_LIBRETRO` target with
`SKIP_LIBRARY=ON`, and disables desktop frontends, optional dependencies,
debuggers, scripting, and database/packaging features. The resulting Android
library is `libmgba_libretro.so` for `arm64-v8a` and `x86_64` only.

No ROM, BIOS, artwork, test cartridge, submodule, or remote runtime code path
is part of this module. mGBA includes an HLE BIOS implementation; optional
external user-owned BIOS files remain a frontend policy concern and are not
packaged here.

## Reproducible build boundary

`tools/build_mgba_android.sh` fetches source only at build time, verifies the
archive digest before extraction, and builds the two ABIs with Android API 26,
NDK `28.2.13676358`, and the Android SDK's CMake. It passes explicit 16 KiB
linker page-size flags and rejects any generated ELF whose `PT_LOAD` segments
are not `0x4000` aligned. The runtime module contains no downloader and the
app has no runtime network-loading dependency on this core.

Gradle packages the generated libraries through `bundled-core-mgba`; it does
not modify the native session host or the SameBoy module.

## Dependency notices

The complete inih BSD-3-Clause text is preserved in
`mGBA-inih-BSD-3-Clause.txt`. The complete Libretro API MIT notice is preserved
in `mGBA-Libretro-API-MIT.txt`.
