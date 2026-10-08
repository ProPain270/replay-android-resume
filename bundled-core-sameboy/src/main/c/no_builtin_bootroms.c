/*
 * Build-only link stubs for the SameBoy Libretro target.
 *
 * The upstream target embeds generated boot-ROM arrays. This project does not
 * package ROMs or BIOS files, so the core is built with empty arrays instead.
 * SameBoy still attempts to load a user-provided boot ROM from the frontend
 * system directory before it uses these arrays.
 */

#include <stddef.h>

const unsigned char dmg_boot[] = {0};
const unsigned char mgb_boot[] = {0};
const unsigned char cgb0_boot[] = {0};
const unsigned char cgb_boot[] = {0};
const unsigned char agb_boot[] = {0};
const unsigned char sgb_boot[] = {0};
const unsigned char sgb2_boot[] = {0};

const unsigned dmg_boot_length = 0;
const unsigned mgb_boot_length = 0;
const unsigned cgb0_boot_length = 0;
const unsigned cgb_boot_length = 0;
const unsigned agb_boot_length = 0;
const unsigned sgb_boot_length = 0;
const unsigned sgb2_boot_length = 0;
