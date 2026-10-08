package dev.codex.libretroplatform

import java.util.Locale

/**
 * Product-level console metadata. A profile can be visible in the library
 * before its core is bundled, which keeps import recognition honest without
 * pretending that a future runtime lane is already playable.
 */
data class ConsoleProfile(
    val id: String,
    val displayName: String,
    val coreId: String,
    val extensions: Set<String>,
    val aspectRatio: Float,
    val inputFamily: InputFamily,
    val bundled: Boolean,
    val status: SupportStatus,
    val requiresHardwareVideo: Boolean = false,
    val requiresSystemFiles: Boolean = false,
) 

enum class InputFamily { Handheld, Snes, N64, GameCube }

object ConsoleCatalog {
    val profiles: List<ConsoleProfile> = listOf(
        ConsoleProfile(
            id = "gb",
            displayName = "Game Boy",
            coreId = "sameboy",
            extensions = setOf("gb"),
            aspectRatio = 10f / 9f,
            inputFamily = InputFamily.Handheld,
            bundled = true,
            status = SupportStatus.Playable,
        ),
        ConsoleProfile(
            id = "gbc",
            displayName = "Game Boy Color",
            coreId = "sameboy",
            extensions = setOf("gbc"),
            aspectRatio = 10f / 9f,
            inputFamily = InputFamily.Handheld,
            bundled = true,
            status = SupportStatus.Playable,
        ),
        ConsoleProfile(
            id = "gba",
            displayName = "Game Boy Advance",
            coreId = "mgba",
            extensions = setOf("gba"),
            aspectRatio = 3f / 2f,
            inputFamily = InputFamily.Handheld,
            bundled = true,
            status = SupportStatus.Playable,
        ),
        ConsoleProfile(
            id = "snes",
            displayName = "Super Nintendo",
            coreId = "snes9x",
            extensions = setOf("sfc", "smc", "fig", "swc", "bs", "st"),
            aspectRatio = 4f / 3f,
            inputFamily = InputFamily.Snes,
            bundled = true,
            status = SupportStatus.Playable,
        ),
        ConsoleProfile(
            id = "n64",
            displayName = "Nintendo 64",
            coreId = "mupen64plus-next",
            extensions = setOf("n64", "z64", "v64"),
            aspectRatio = 4f / 3f,
            inputFamily = InputFamily.N64,
            bundled = false,
            status = SupportStatus.NeedsCore,
            requiresHardwareVideo = true,
        ),
        ConsoleProfile(
            id = "gamecube",
            displayName = "Nintendo GameCube",
            coreId = "dolphin",
            extensions = setOf("gcm", "iso", "rvz", "gcz", "ciso", "wbfs"),
            aspectRatio = 4f / 3f,
            inputFamily = InputFamily.GameCube,
            bundled = false,
            status = SupportStatus.NeedsCore,
            requiresHardwareVideo = true,
            requiresSystemFiles = true,
        ),
    )

    fun forFileName(name: String): ConsoleProfile? {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return profiles.firstOrNull { extension in it.extensions }
    }

    fun forSystem(system: String): ConsoleProfile? {
        val exact = profiles.firstOrNull { profile ->
            profile.displayName.equals(system, ignoreCase = true) ||
                system.equals(profile.id, ignoreCase = true)
        }
        return exact ?: profiles
            .filter { profile -> system.contains(profile.displayName, ignoreCase = true) }
            .maxByOrNull { it.displayName.length }
    }
}
