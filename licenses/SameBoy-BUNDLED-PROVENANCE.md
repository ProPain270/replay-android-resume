# SameBoy bundled-core provenance

Status: source-pinned Android build lane; not a legal clearance opinion.

- Upstream repository: https://github.com/LIJI32/SameBoy
- Upstream source URL: https://codeload.github.com/LIJI32/SameBoy/tar.gz/0fc08d93bed566c71f97b4498cda43f97cd3cd6f
- Release tag: `v1.0.1`
- Exact source commit: `0fc08d93bed566c71f97b4498cda43f97cd3cd6f`
- Source archive SHA-256: `5b3c9116ce2e3881f7ee5111b4077c5672fda159bc6458f442d284e543f081a6`
- Upstream `LICENSE` SHA-256: `0cd679b37b3a975fdb95c32783b8e8878bce2a2ec03aa9706318df96cfee475b`
- SameBoy license: Expat/MIT for the repository except the iOS and HexFiend directories. Neither exception directory is compiled by this lane.
- Libretro API header: MIT notice from The RetroArch team, preserved in `SameBoy-Libretro-API-MIT.txt`.
- `retro_inline.h`: separate MIT notice from The RetroArch team, preserved in `SameBoy-RetroArch-Inline-MIT.txt`.

## Shipped source boundary

The recipe compiles the pinned SameBoy `Core/*.c` files listed in
`tools/build_sameboy_android.sh`, the pinned `libretro/libretro.c`, and
generated C arrays produced from SameBoy's open-source `BootROMs/*.asm` files.
It does not compile SameBoy's Cocoa, iOS, HexFiend, SDL, or tester targets.

The upstream Libretro target normally generates embedded boot-ROM arrays. This
lane generates those arrays from the pinned SameBoy source at build time. The
boot ROMs are SameBoy's open-source reimplementations; Nintendo's original boot
ROMs and any other proprietary BIOS files are not packaged.

No submodules, ROMs, BIOS files, copyrighted test assets, artwork, or remote
runtime code-loading path are part of this lane.
