# Core and dependency licensing

The first source-pinned bundled-core lane is SameBoy GB/GBC. Its exact source,
license notices, and build boundary are recorded in
`SameBoy-BUNDLED-PROVENANCE.md`, `SameBoy-LICENSE.txt`, and the two preserved
Libretro MIT notice files. The lane intentionally does not package ROMs, BIOS
files, or copyrighted artwork.

The second source-pinned bundled-core lane is mGBA GBA/GB/GBC. Its exact
source, dependency notices, MPL-2.0 text, and build boundary are recorded in
`mGBA-BUNDLED-PROVENANCE.md`, `mGBA-MPL-2.0.txt`, and the mGBA notice files.
The lane also intentionally does not package ROMs, BIOS files, or copyrighted
artwork.

The third source-pinned bundled-core lane is Snes9x SNES/SFC. Its exact source,
upstream license boundary, Libretro API notice, and build record are recorded
in `Snes9x-BUNDLED-PROVENANCE.md`, `Snes9x-LICENSE.txt`, and
`Snes9x-Libretro-API-MIT.txt`. The lane intentionally does not package ROMs,
firmware, or copyrighted artwork. The pinned Snes9x license is non-commercial;
the repository does not claim commercial distribution rights.

Every future core must have an exact commit, dependency inventory, SPDX/notice record, distribution status, ABI/build record, and source-offer record before it is bundled. Exact records for the bundled lanes are retained in this directory.
