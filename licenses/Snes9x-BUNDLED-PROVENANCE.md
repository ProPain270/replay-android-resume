# Snes9x bundled-core provenance

Status: source-pinned Android build lane; not a legal clearance opinion.

- Upstream repository: https://github.com/libretro/snes9x
- Exact source commit: `890b5d445538fe790aa3add3d5702c80f551e0ae`
- Source archive: `https://codeload.github.com/libretro/snes9x/tar.gz/890b5d445538fe790aa3add3d5702c80f551e0ae`
- Source archive SHA-256: `fcc32b536d3e7b1def0d15489917dd043d5e1d9636de8af7851c48e771276613`
- Upstream `LICENSE` SHA-256: `70efeee282d82a6e9d26aeed5466d08c632369858371dc6a4644c8dcedc2be78`
- Libretro API header SHA-256: `83b26122cc65d5b1db7f4ddb5b10cc69799dadabb4f6e95c461e83e66d877252`
- Upstream core version: `1.61`

The source license is preserved as a repository record in `Snes9x-LICENSE.txt`;
the exact upstream text is also available at the immutable raw URL above.
The Libretro API header's MIT notice is preserved in
`Snes9x-Libretro-API-MIT.txt`. The core source also carries the LGPL notice for
the `filter/snes_ntsc` component; it is included in the pinned build input and
must remain part of any source/notice offer.

## Shipped source boundary

The recipe builds only the upstream `libretro/Makefile` target. It does not
package ROMs, BIOS files, artwork, desktop frontends, or runtime downloaders.
Optional BS-X and Sufami Turbo firmware is treated as user-owned system data;
no firmware is bundled. The resulting Android libraries are
`libsnes9x_libretro.so` for `arm64-v8a` and `x86_64`.
Because the upstream core is C++, the matching NDK `libc++_shared.so` is
copied into the same ABI-specific library set; it is a runtime dependency,
not a second emulator core.

## Reproducible build boundary

`tools/build_snes9x_android.sh` fetches source only at build time, verifies the
archive digest before extraction, and builds each ABI with Android API 26,
NDK `28.2.13676358`, and the pinned Libretro Makefile. It passes explicit
16 KiB linker page-size flags and rejects any generated ELF whose `PT_LOAD`
segments are not `0x4000` aligned. For offline builds,
`SNES9X_SOURCE_ARCHIVE` may point to a local archive, but the digest is still
required to match.

The app receives only the generated `.so` files through the isolated
`bundled-core-snes9x` Android library. No core source or network loading path
is present at runtime.

## Known distribution boundary

The upstream Snes9x license in this pinned source grants non-commercial use
and includes additional component notices. This lane is technically bundled
and certified for the local/open development product path; commercial
distribution remains outside this repository's claim and requires a separate
distribution decision.
