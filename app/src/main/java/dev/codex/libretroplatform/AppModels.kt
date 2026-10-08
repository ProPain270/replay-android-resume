package dev.codex.libretroplatform

import android.net.Uri
import dev.codex.libretroplatform.runtime.api.AnalogAxis
import dev.codex.libretroplatform.runtime.api.Capability
import dev.codex.libretroplatform.runtime.api.Button
import dev.codex.libretroplatform.runtime.api.SessionCommand
import dev.codex.libretroplatform.runtime.api.SessionHandle
import dev.codex.libretroplatform.runtime.api.SessionDiagnostics
import dev.codex.libretroplatform.runtime.content.ContentIdentity

enum class AppRoute {
    Welcome,
    ContentSource,
    Import,
    Library,
    Details,
    Player,
    Settings,
    Recovery,
}

enum class MetadataStatus { Verified, Partial, Unavailable }

enum class SupportStatus { Playable, Unsupported, NeedsCore, Unreadable }

enum class SaveSummary { NoSave, SavePresent, RecoveryAvailable }

enum class PauseReason { User, Lifecycle }

enum class LibraryFilter { All, Playable, Favorites }

enum class SourceVerificationStatus { Matches, Changed, Unavailable }

data class SourceVerification(
    val status: SourceVerificationStatus,
    val expected: ContentIdentity? = null,
    val actual: ContentIdentity? = null,
)

enum class LibrarySort { Recent, Title }

enum class DisplayPreset { Classic, Crisp, Night }

enum class PlayerPresentation(val label: String) { Adaptive("Edge to edge"), Classic("Classic shell") }

enum class ScreenTreatment(val label: String, val description: String) {
    Clean("Clean", "Unfiltered native frame"),
    Scanlines("Scanlines", "Subtle CRT-inspired line structure"),
    Mono("Mono", "Muted monochrome handheld finish"),
    Ghosting("Ghosting", "Soft phosphor-style finish where the Android surface supports it"),
}

enum class PixelScalingMode(val label: String, val description: String) {
    Fit("Fit", "Show the complete native frame without distortion"),
    Integer("Pixel", "Keep nearest-neighbor sampling for crisp pixels"),
    Fill("Fill", "Use the available display and crop only the outer edges"),
}

enum class TouchReachMode(val label: String, val description: String) {
    Full("Full width", "Use the whole player shell"),
    OneHandedLeft("Left reach", "Keep controls in the left-hand reach zone"),
    OneHandedRight("Right reach", "Keep controls in the right-hand reach zone"),
}

enum class PerformancePreset(val label: String, val description: String) {
    Balanced("Balanced", "Normal frame pacing and battery behavior"),
    BatterySaver("Battery saver", "Prefer conservative pacing and reduced visual work"),
    LowLatency("Low latency", "Prefer responsive input and audio at higher power use"),
}

/** One slot-independent staging location used by the active native session. */
const val RUNTIME_SAVE_STATE_STAGING_SLOT = "runtime"

enum class PlayerDisplaySize(val label: String, val widthMultiplier: Float) {
    // Keep every preset inside the fitted viewport. Scaling past 1.0f would
    // make the game surface eat into the touch deck on short Fold postures.
    Balanced("Balanced", 0.90f),
    Large("Large", 0.96f),
    Maximum("Maximum", 1f),
}

enum class TouchLayoutSize(val label: String, val multiplier: Float) {
    Compact("Compact", 0.88f),
    Comfort("Comfort", 1f),
    Large("Large", 1.12f),
}

/**
 * Normalized, reversible adjustments for the two primary touch-control groups.
 * Values are relative to the touch deck bounds so a layout survives density,
 * rotation, and Fold window changes without storing device-specific pixels.
 */
data class TouchLayoutAdjustments(
    val dpadX: Float = 0f,
    val dpadY: Float = 0f,
    val faceX: Float = 0f,
    val faceY: Float = 0f,
    val controls: Map<String, ControlPlacement> = emptyMap(),
) {
    fun sanitized(): TouchLayoutAdjustments = copy(
        dpadX = dpadX.finiteOrZero().coerceIn(MIN_OFFSET, MAX_OFFSET),
        dpadY = dpadY.finiteOrZero().coerceIn(MIN_OFFSET, MAX_OFFSET),
        faceX = faceX.finiteOrZero().coerceIn(MIN_OFFSET, MAX_OFFSET),
        faceY = faceY.finiteOrZero().coerceIn(MIN_OFFSET, MAX_OFFSET),
        controls = controls.filterKeys(::validControlKey).mapValues { it.value.sanitized() },
    )

    companion object {
        const val MIN_OFFSET = -0.16f
        const val MAX_OFFSET = 0.16f
    }
}

/** Per-device analog shaping that keeps inexpensive Bluetooth pads usable. */
data class AnalogCalibration(
    val deadZone: Float = 0.08f,
    val triggerThreshold: Float = 0.5f,
    val leftXInverted: Boolean = false,
    val leftYInverted: Boolean = false,
    val rightXInverted: Boolean = false,
    val rightYInverted: Boolean = false,
) {
    fun normalize(axis: AnalogAxis, value: Float): Float {
        val clamped = value.coerceIn(-1f, 1f)
        val magnitude = kotlin.math.abs(clamped)
        val safeDeadZone = deadZone.coerceIn(0f, 0.95f)
        val shaped = if (magnitude <= safeDeadZone) {
            0f
        } else {
            (kotlin.math.sign(clamped) * ((magnitude - safeDeadZone) / (1f - safeDeadZone))).coerceIn(-1f, 1f)
        }
        return if (isInverted(axis)) -shaped else shaped
    }

    fun isInverted(axis: AnalogAxis): Boolean = when (axis) {
        AnalogAxis.LeftX -> leftXInverted
        AnalogAxis.LeftY -> leftYInverted
        AnalogAxis.RightX -> rightXInverted
        AnalogAxis.RightY -> rightYInverted
    }

    companion object {
        fun fromPersistedValues(values: List<String>): AnalogCalibration? {
            if (values.size != 6) return null
            val floats = values.take(2).map { it.toFloatOrNull() ?: return null }
            val booleans = values.drop(2).map { it.toBooleanStrictOrNull() ?: return null }
            return AnalogCalibration(
                deadZone = floats[0].coerceIn(0f, 0.45f),
                triggerThreshold = floats[1].coerceIn(0.05f, 0.95f),
                leftXInverted = booleans[0],
                leftYInverted = booleans[1],
                rightXInverted = booleans[2],
                rightYInverted = booleans[3],
            )
        }

        fun default(): AnalogCalibration = AnalogCalibration()
    }
}

enum class HotkeyAction(val label: String, val description: String) {
    TogglePause("Pause / resume", "Hold the modifier and press the trigger."),
    QuickSave("Quick save state", "Commit a verified checkpoint to the selected slot."),
    QuickLoad("Quick load state", "Load the selected verified checkpoint."),
    ExitPlayer("Exit player", "Leave the session and return to the library."),
}

data class HotkeyBinding(
    val modifier: Button,
    val trigger: Button,
) {
    val label: String get() = "${modifier.name} + ${trigger.name}"
}

fun defaultHotkeyBindings(): Map<HotkeyAction, HotkeyBinding> = mapOf(
    HotkeyAction.TogglePause to HotkeyBinding(Button.Select, Button.Start),
    HotkeyAction.QuickSave to HotkeyBinding(Button.Select, Button.L),
    HotkeyAction.QuickLoad to HotkeyBinding(Button.Select, Button.R),
    HotkeyAction.ExitPlayer to HotkeyBinding(Button.Select, Button.B),
)

enum class HotkeyCapturePart { Modifier, Trigger }

data class UserPreferences(
    val favorites: Set<String> = emptySet(),
    val displayPreset: DisplayPreset = DisplayPreset.Classic,
    val playerPresentation: PlayerPresentation = PlayerPresentation.Adaptive,
    val playerDisplaySize: PlayerDisplaySize = PlayerDisplaySize.Large,
    val screenTreatment: ScreenTreatment = ScreenTreatment.Clean,
    val pixelScalingMode: PixelScalingMode = PixelScalingMode.Fit,
    val touchLayoutSize: TouchLayoutSize = TouchLayoutSize.Comfort,
    val touchReachMode: TouchReachMode = TouchReachMode.Full,
    val touchLayout: TouchLayoutAdjustments = TouchLayoutAdjustments(),
    val performancePreset: PerformancePreset = PerformancePreset.Balanced,
    val audioVolume: Float = 1f,
    val audioMuted: Boolean = false,
    val controlsOpacity: Float = 1f,
    val hapticsEnabled: Boolean = true,
    val leftHanded: Boolean = false,
    val showButtonLabels: Boolean = true,
    val reduceMotion: Boolean = false,
    val immersivePlayer: Boolean = false,
    val keepScreenOn: Boolean = true,
    val autoSaveOnBackground: Boolean = true,
    val autoResumeFromLatestState: Boolean = true,
    val gamepadEnabled: Boolean = true,
    val gamepadMapping: GamepadMapping = GamepadMapping.default(),
    val gamepadProfiles: Map<String, GamepadMapping> = emptyMap(),
    val gamepadAnalogCalibration: AnalogCalibration = AnalogCalibration.default(),
    val gamepadAnalogProfiles: Map<String, AnalogCalibration> = emptyMap(),
    val hotkeys: Map<HotkeyAction, HotkeyBinding> = defaultHotkeyBindings(),
)

data class GamepadDeviceInfo(
    val deviceId: Int,
    val name: String,
    val descriptor: String,
    val sourceLabel: String,
) {
    val profileKey: String get() = descriptor.ifBlank { "name:$name" }
}

data class GamepadDiagnostics(
    val deviceId: Int,
    val analog: Map<AnalogAxis, Float> = emptyMap(),
    val pressedButtons: Set<Button> = emptySet(),
    val lastEventEpochMs: Long = System.currentTimeMillis(),
)

data class ImportCandidate(
    val uri: Uri,
    val displayName: String,
    val isDirectory: Boolean = false,
)

data class ImportFailure(
    val displayName: String,
    val reason: String,
)

data class ImportSummary(
    val imported: Int,
    val skipped: Int,
    val failures: List<ImportFailure>,
    val cancelled: Boolean = false,
)

sealed interface ImportState {
    data object NoSelection : ImportState

    data class Queued(val candidates: List<ImportCandidate>) : ImportState

    data class Importing(val current: Int, val total: Int) : ImportState

    data class Completed(val summary: ImportSummary) : ImportState

    data class Cancelled(val summary: ImportSummary) : ImportState
}

data class LibraryItem(
    val id: String,
    val contentId: String,
    val contentSizeBytes: Long,
    val title: String,
    val system: String,
    val sourceUri: Uri?,
    val sourceDisplayName: String,
    val privateContentRef: String?,
    val coreId: String?,
    val metadataStatus: MetadataStatus,
    val supportStatus: SupportStatus,
    val importedAt: Long,
    val lastPlayedAt: Long? = null,
    val saveSummary: SaveSummary = SaveSummary.NoSave,
    /** User-facing alias; the original filename-derived title remains in [title]. */
    val titleAlias: String? = null,
    /** Optional normalized sort key that does not change content identity. */
    val sortTitle: String? = null,
    /** Optional app-private user artwork; generated artwork remains the fallback. */
    val artworkPath: String? = null,
    /** Optional per-title presentation override; null means use global settings. */
    val titlePreferences: TitlePlayerPreferences? = null,
) {
    val displayTitle: String get() = titleAlias?.takeIf { it.isNotBlank() } ?: title
    val sortTitleKey: String get() = sortTitle?.takeIf { it.isNotBlank() } ?: displayTitle
}

data class LibraryCollection(
    val id: String,
    val name: String,
    val itemIds: Set<String> = emptySet(),
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

data class SystemFileRecord(
    val id: String,
    val displayName: String,
    val privatePath: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class TitlePlayerPreferences(
    val displayPreset: DisplayPreset? = null,
    val playerPresentation: PlayerPresentation? = null,
    val playerDisplaySize: PlayerDisplaySize? = null,
    val screenTreatment: ScreenTreatment? = null,
    val pixelScalingMode: PixelScalingMode? = null,
    val touchLayoutSize: TouchLayoutSize? = null,
    val touchReachMode: TouchReachMode? = null,
    val touchLayout: TouchLayoutAdjustments? = null,
    val audioVolume: Float? = null,
    val audioMuted: Boolean? = null,
    val controlsOpacity: Float? = null,
    val leftHanded: Boolean? = null,
    val showButtonLabels: Boolean? = null,
    val hapticsEnabled: Boolean? = null,
)

data class CoreCapabilitySummary(
    val profile: ConsoleProfile,
    val state: String,
    val detail: String,
)

enum class SaveStateSlotAvailability { Empty, Verified, Corrupt, IdentityMismatch }

data class SaveStateSlotStatus(
    val slot: Int,
    val availability: SaveStateSlotAvailability = SaveStateSlotAvailability.Empty,
    val createdAtEpochMs: Long? = null,
    val payloadSizeBytes: Long? = null,
    val thumbnailPath: String? = null,
) {
    val available: Boolean get() = availability == SaveStateSlotAvailability.Verified
}

sealed interface PlayerState {
    data object Ready : PlayerState

    data object Running : PlayerState

    data class Paused(val reason: PauseReason) : PlayerState
}

data class RecoveryState(
    val title: String,
    val message: String,
    val safeData: String,
    val primaryAction: String,
)

data class FrontendUiState(
    val route: AppRoute = AppRoute.Welcome,
    val library: List<LibraryItem> = emptyList(),
    val selectedItemId: String? = null,
    val pendingCandidates: List<ImportCandidate> = emptyList(),
    val folderScanInProgress: Boolean = false,
    val importState: ImportState = ImportState.NoSelection,
    val playerState: PlayerState = PlayerState.Ready,
    val playerSession: SessionHandle? = null,
    val runtimeCapabilities: Set<Capability> = emptySet(),
    val runtimeDiagnostics: SessionDiagnostics? = null,
    val saveStateSlot: Int = 1,
    val saveStateSlots: List<SaveStateSlotStatus> = emptyList(),
    val saveStateSlotsReady: Boolean = false,
    val statusMessage: String? = null,
    val recovery: RecoveryState? = null,
    val libraryQuery: String = "",
    val libraryFilter: LibraryFilter = LibraryFilter.All,
    val librarySystem: String? = null,
    val libraryCollectionId: String? = null,
    val collections: List<LibraryCollection> = emptyList(),
    val systemFiles: List<SystemFileRecord> = emptyList(),
    val librarySort: LibrarySort = LibrarySort.Recent,
    val preferences: UserPreferences = UserPreferences(),
    val connectedGamepads: List<GamepadDeviceInfo> = emptyList(),
    val selectedGamepadProfileKey: String? = null,
    val gamepadDiagnostics: GamepadDiagnostics? = null,
    val gamepadCaptureTarget: Button? = null,
    val hotkeyCaptureTarget: HotkeyAction? = null,
    val hotkeyCapturePart: HotkeyCapturePart? = null,
    val hotkeyCaptureModifier: Button? = null,
    val settingsReturnRoute: AppRoute = AppRoute.Library,
)

/** Small app-side status adapter for the runtime command boundary. */
data class RuntimeCommandPreview(
    val command: SessionCommand,
    val accepted: Boolean,
    val message: String,
    val unsupportedCapability: Capability? = null,
)
