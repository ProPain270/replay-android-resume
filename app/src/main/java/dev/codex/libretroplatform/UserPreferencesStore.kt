package dev.codex.libretroplatform

import android.content.Context
import androidx.core.content.edit
import java.util.Properties

/** Small durable boundary for user-facing presentation and control preferences. */
interface UserPreferencesStore {
    fun load(): UserPreferences
    fun save(preferences: UserPreferences)
}

/** Stable, dependency-free encoding for normalized touch-layout offsets. */
object TouchLayoutAdjustmentsCodec {
    fun encode(value: TouchLayoutAdjustments): String {
        val safe = value.sanitized()
        val base = listOf(safe.dpadX, safe.dpadY, safe.faceX, safe.faceY).joinToString(",")
        return (listOf(base) + safe.controls.toSortedMap().map { (key, placement) ->
            listOf(key, placement.x, placement.y, placement.scale).joinToString(",")
        }).joinToString(";")
    }

    fun decode(encoded: String?): TouchLayoutAdjustments? {
        val records = encoded?.takeIf { it.length <= 16_384 }?.split(';') ?: return null
        val fields = records.first().split(',')
        if (fields.size != 4) return null
        val values = fields.map(String::toFloatOrNull)
        if (values.any { it == null || !it.isFinite() }) return null
        val controls = mutableMapOf<String, ControlPlacement>()
        for (record in records.drop(1)) {
            val parts = record.split(',')
            if (parts.size != 4 || !validControlKey(parts[0])) return null
            val numbers = parts.drop(1).map { it.toFloatOrNull() }
            if (numbers.any { it == null || !it.isFinite() }) return null
            controls[parts[0]] = ControlPlacement(numbers[0]!!, numbers[1]!!, numbers[2]!!).sanitized()
        }
        return TouchLayoutAdjustments(
            dpadX = values[0]!!,
            dpadY = values[1]!!,
            faceX = values[2]!!,
            faceY = values[3]!!,
            controls = controls,
        ).sanitized()
    }
}

class InMemoryUserPreferencesStore : UserPreferencesStore {
    private var value = UserPreferences()

    override fun load(): UserPreferences = value

    override fun save(preferences: UserPreferences) {
        value = preferences
    }
}

class SharedPreferencesUserPreferencesStore(context: Context) : UserPreferencesStore {
    private val sharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun load(): UserPreferences = UserPreferences(
        playerPresentation = runCatching { PlayerPresentation.valueOf(sharedPreferences.getString("player-presentation", "Adaptive")!!) }.getOrDefault(PlayerPresentation.Adaptive),
        favorites = sharedPreferences.getStringSet(KEY_FAVORITES, emptySet()).orEmpty().toSet(),
        displayPreset = sharedPreferences.getString(KEY_DISPLAY_PRESET, DisplayPreset.Classic.name)
            ?.let { runCatching { DisplayPreset.valueOf(it) }.getOrDefault(DisplayPreset.Classic) }
            ?: DisplayPreset.Classic,
        playerDisplaySize = sharedPreferences.getString(KEY_PLAYER_DISPLAY_SIZE, PlayerDisplaySize.Large.name)
            ?.let { runCatching { PlayerDisplaySize.valueOf(it) }.getOrDefault(PlayerDisplaySize.Large) }
            ?: PlayerDisplaySize.Large,
        screenTreatment = sharedPreferences.getString(KEY_SCREEN_TREATMENT, ScreenTreatment.Clean.name)
            ?.let { runCatching { ScreenTreatment.valueOf(it) }.getOrDefault(ScreenTreatment.Clean) }
            ?: ScreenTreatment.Clean,
        pixelScalingMode = sharedPreferences.getString(KEY_PIXEL_SCALING_MODE, PixelScalingMode.Fit.name)
            ?.let { runCatching { PixelScalingMode.valueOf(it) }.getOrDefault(PixelScalingMode.Fit) }
            ?: PixelScalingMode.Fit,
        touchLayoutSize = sharedPreferences.getString(KEY_TOUCH_LAYOUT_SIZE, TouchLayoutSize.Comfort.name)
            ?.let { runCatching { TouchLayoutSize.valueOf(it) }.getOrDefault(TouchLayoutSize.Comfort) }
            ?: TouchLayoutSize.Comfort,
        touchReachMode = sharedPreferences.getString(KEY_TOUCH_REACH_MODE, TouchReachMode.Full.name)
            ?.let { runCatching { TouchReachMode.valueOf(it).takeIf { mode -> mode != TouchReachMode.Full } ?: TouchReachMode.Full }.getOrDefault(TouchReachMode.Full) }
            ?: TouchReachMode.Full,
        touchLayout = TouchLayoutAdjustmentsCodec.decode(sharedPreferences.getString(KEY_TOUCH_LAYOUT, null))
            ?: TouchLayoutAdjustments(),
        performancePreset = sharedPreferences.getString(KEY_PERFORMANCE_PRESET, PerformancePreset.Balanced.name)
            ?.let { runCatching { PerformancePreset.valueOf(it) }.getOrDefault(PerformancePreset.Balanced) }
            ?: PerformancePreset.Balanced,
        audioVolume = sharedPreferences.getFloat(KEY_AUDIO_VOLUME, 1f).coerceIn(0f, 1f),
        audioMuted = sharedPreferences.getBoolean(KEY_AUDIO_MUTED, false),
        controlsOpacity = sharedPreferences.getFloat(KEY_CONTROLS_OPACITY, 1f).coerceIn(0.35f, 1f),
        hapticsEnabled = sharedPreferences.getBoolean(KEY_HAPTICS, true),
        leftHanded = sharedPreferences.getBoolean(KEY_LEFT_HANDED, false),
        showButtonLabels = sharedPreferences.getBoolean(KEY_BUTTON_LABELS, true),
        reduceMotion = sharedPreferences.getBoolean(KEY_REDUCE_MOTION, false),
        immersivePlayer = sharedPreferences.getBoolean(KEY_IMMERSIVE, false),
        keepScreenOn = sharedPreferences.getBoolean(KEY_KEEP_SCREEN_ON, true),
        autoSaveOnBackground = sharedPreferences.getBoolean(KEY_AUTO_SAVE_ON_BACKGROUND, true),
        autoResumeFromLatestState = sharedPreferences.getBoolean(KEY_AUTO_RESUME_LATEST_STATE, true),
        gamepadEnabled = sharedPreferences.getBoolean(KEY_GAMEPAD_ENABLED, true),
        gamepadProfiles = sharedPreferences.getStringSet(KEY_GAMEPAD_PROFILES, emptySet())
            .orEmpty()
            .mapNotNull(::decodeGamepadProfile)
            .toMap(),
        gamepadAnalogProfiles = sharedPreferences.getStringSet(KEY_GAMEPAD_ANALOG_PROFILES, emptySet())
            .orEmpty()
            .mapNotNull(::decodeAnalogProfile)
            .toMap(),
        gamepadAnalogCalibration = decodeAnalogCalibration(
            sharedPreferences.getString(KEY_GAMEPAD_ANALOG_GLOBAL, null),
        ) ?: AnalogCalibration.default(),
        hotkeys = defaultHotkeyBindings() + sharedPreferences.getStringSet(KEY_HOTKEYS, emptySet())
            .orEmpty()
            .mapNotNull(::decodeHotkey)
            .toMap(),
        gamepadMapping = GamepadMapping(
            up = sharedPreferences.getInt(KEY_GAMEPAD_UP, GamepadMapping.default().up),
            down = sharedPreferences.getInt(KEY_GAMEPAD_DOWN, GamepadMapping.default().down),
            left = sharedPreferences.getInt(KEY_GAMEPAD_LEFT, GamepadMapping.default().left),
            right = sharedPreferences.getInt(KEY_GAMEPAD_RIGHT, GamepadMapping.default().right),
            a = sharedPreferences.getInt(KEY_GAMEPAD_A, GamepadMapping.default().a),
            b = sharedPreferences.getInt(KEY_GAMEPAD_B, GamepadMapping.default().b),
            x = sharedPreferences.getInt(KEY_GAMEPAD_X, GamepadMapping.default().x),
            y = sharedPreferences.getInt(KEY_GAMEPAD_Y, GamepadMapping.default().y),
            l = sharedPreferences.getInt(KEY_GAMEPAD_L, GamepadMapping.default().l),
            r = sharedPreferences.getInt(KEY_GAMEPAD_R, GamepadMapping.default().r),
            l2 = sharedPreferences.getInt(KEY_GAMEPAD_L2, GamepadMapping.default().l2),
            r2 = sharedPreferences.getInt(KEY_GAMEPAD_R2, GamepadMapping.default().r2),
            l3 = sharedPreferences.getInt(KEY_GAMEPAD_L3, GamepadMapping.default().l3),
            r3 = sharedPreferences.getInt(KEY_GAMEPAD_R3, GamepadMapping.default().r3),
            start = sharedPreferences.getInt(KEY_GAMEPAD_START, GamepadMapping.default().start),
            select = sharedPreferences.getInt(KEY_GAMEPAD_SELECT, GamepadMapping.default().select),
        ),
    )

    override fun save(preferences: UserPreferences) {
        sharedPreferences.edit {
            putString("player-presentation", preferences.playerPresentation.name)
            putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)
            putStringSet(KEY_FAVORITES, preferences.favorites.toSet())
            putString(KEY_DISPLAY_PRESET, preferences.displayPreset.name)
            putString(KEY_PLAYER_DISPLAY_SIZE, preferences.playerDisplaySize.name)
            putString(KEY_SCREEN_TREATMENT, preferences.screenTreatment.name)
            putString(KEY_PIXEL_SCALING_MODE, preferences.pixelScalingMode.name)
            putString(KEY_TOUCH_LAYOUT_SIZE, preferences.touchLayoutSize.name)
            putString(KEY_TOUCH_REACH_MODE, preferences.touchReachMode.name)
            putString(KEY_TOUCH_LAYOUT, TouchLayoutAdjustmentsCodec.encode(preferences.touchLayout.sanitized()))
            putString(KEY_PERFORMANCE_PRESET, preferences.performancePreset.name)
            putFloat(KEY_AUDIO_VOLUME, preferences.audioVolume.coerceIn(0f, 1f))
            putBoolean(KEY_AUDIO_MUTED, preferences.audioMuted)
            putFloat(KEY_CONTROLS_OPACITY, preferences.controlsOpacity.coerceIn(0.35f, 1f))
            putBoolean(KEY_HAPTICS, preferences.hapticsEnabled)
            putBoolean(KEY_LEFT_HANDED, preferences.leftHanded)
            putBoolean(KEY_BUTTON_LABELS, preferences.showButtonLabels)
            putBoolean(KEY_REDUCE_MOTION, preferences.reduceMotion)
            putBoolean(KEY_IMMERSIVE, preferences.immersivePlayer)
            putBoolean(KEY_KEEP_SCREEN_ON, preferences.keepScreenOn)
            putBoolean(KEY_AUTO_SAVE_ON_BACKGROUND, preferences.autoSaveOnBackground)
            putBoolean(KEY_AUTO_RESUME_LATEST_STATE, preferences.autoResumeFromLatestState)
            putBoolean(KEY_GAMEPAD_ENABLED, preferences.gamepadEnabled)
            putStringSet(
                KEY_GAMEPAD_PROFILES,
                preferences.gamepadProfiles.map { (profileKey, mapping) -> encodeGamepadProfile(profileKey, mapping) }.toSet(),
            )
            putStringSet(
                KEY_GAMEPAD_ANALOG_PROFILES,
                preferences.gamepadAnalogProfiles.map { (profileKey, calibration) -> encodeAnalogProfile(profileKey, calibration) }.toSet(),
            )
            putString(KEY_GAMEPAD_ANALOG_GLOBAL, encodeCalibration(preferences.gamepadAnalogCalibration))
            putStringSet(KEY_HOTKEYS, preferences.hotkeys.map { (action, binding) -> encodeHotkey(action, binding) }.toSet())
            putInt(KEY_GAMEPAD_UP, preferences.gamepadMapping.up)
            putInt(KEY_GAMEPAD_DOWN, preferences.gamepadMapping.down)
            putInt(KEY_GAMEPAD_LEFT, preferences.gamepadMapping.left)
            putInt(KEY_GAMEPAD_RIGHT, preferences.gamepadMapping.right)
            putInt(KEY_GAMEPAD_A, preferences.gamepadMapping.a)
            putInt(KEY_GAMEPAD_B, preferences.gamepadMapping.b)
            putInt(KEY_GAMEPAD_X, preferences.gamepadMapping.x)
            putInt(KEY_GAMEPAD_Y, preferences.gamepadMapping.y)
            putInt(KEY_GAMEPAD_L, preferences.gamepadMapping.l)
            putInt(KEY_GAMEPAD_R, preferences.gamepadMapping.r)
            putInt(KEY_GAMEPAD_L2, preferences.gamepadMapping.l2)
            putInt(KEY_GAMEPAD_R2, preferences.gamepadMapping.r2)
            putInt(KEY_GAMEPAD_L3, preferences.gamepadMapping.l3)
            putInt(KEY_GAMEPAD_R3, preferences.gamepadMapping.r3)
            putInt(KEY_GAMEPAD_START, preferences.gamepadMapping.start)
            putInt(KEY_GAMEPAD_SELECT, preferences.gamepadMapping.select)
        }
    }

    companion object {
        private const val FILE_NAME = "presentation-preferences"
        private const val CURRENT_SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "schema-version"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_DISPLAY_PRESET = "display-preset"
        private const val KEY_PLAYER_DISPLAY_SIZE = "player-display-size"
        private const val KEY_SCREEN_TREATMENT = "screen-treatment"
        private const val KEY_PIXEL_SCALING_MODE = "pixel-scaling-mode"
        private const val KEY_TOUCH_LAYOUT_SIZE = "touch-layout-size"
        private const val KEY_TOUCH_REACH_MODE = "touch-reach-mode"
        private const val KEY_TOUCH_LAYOUT = "touch-layout"
        private const val KEY_PERFORMANCE_PRESET = "performance-preset"
        private const val KEY_AUDIO_VOLUME = "audio-volume"
        private const val KEY_AUDIO_MUTED = "audio-muted"
        private const val KEY_CONTROLS_OPACITY = "controls-opacity"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_LEFT_HANDED = "left-handed"
        private const val KEY_BUTTON_LABELS = "button-labels"
        private const val KEY_REDUCE_MOTION = "reduce-motion"
        private const val KEY_IMMERSIVE = "immersive-player"
        private const val KEY_KEEP_SCREEN_ON = "keep-screen-on"
        private const val KEY_AUTO_SAVE_ON_BACKGROUND = "auto-save-on-background"
        private const val KEY_AUTO_RESUME_LATEST_STATE = "auto-resume-latest-state"
        private const val KEY_GAMEPAD_ENABLED = "gamepad-enabled"
        private const val KEY_GAMEPAD_PROFILES = "gamepad-profiles"
        private const val KEY_GAMEPAD_ANALOG_PROFILES = "gamepad-analog-profiles"
        private const val KEY_GAMEPAD_ANALOG_GLOBAL = "gamepad-analog-global"
        private const val KEY_HOTKEYS = "runtime-hotkeys"
        private const val KEY_GAMEPAD_UP = "gamepad-up"
        private const val KEY_GAMEPAD_DOWN = "gamepad-down"
        private const val KEY_GAMEPAD_LEFT = "gamepad-left"
        private const val KEY_GAMEPAD_RIGHT = "gamepad-right"
        private const val KEY_GAMEPAD_A = "gamepad-a"
        private const val KEY_GAMEPAD_B = "gamepad-b"
        private const val KEY_GAMEPAD_X = "gamepad-x"
        private const val KEY_GAMEPAD_Y = "gamepad-y"
        private const val KEY_GAMEPAD_L = "gamepad-l"
        private const val KEY_GAMEPAD_R = "gamepad-r"
        private const val KEY_GAMEPAD_L2 = "gamepad-l2"
        private const val KEY_GAMEPAD_R2 = "gamepad-r2"
        private const val KEY_GAMEPAD_L3 = "gamepad-l3"
        private const val KEY_GAMEPAD_R3 = "gamepad-r3"
        private const val KEY_GAMEPAD_START = "gamepad-start"
        private const val KEY_GAMEPAD_SELECT = "gamepad-select"

        private fun encodeGamepadProfile(profileKey: String, mapping: GamepadMapping): String =
            "${profileKey.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }}|${mapping.persistedKeyCodes().joinToString(",")}"

        private fun decodeGamepadProfile(encoded: String): Pair<String, GamepadMapping>? {
            val parts = encoded.split('|', limit = 2)
            if (parts.size != 2 || parts[0].length % 2 != 0) return null
            val keyBytes = ByteArray(parts[0].length / 2)
            for (index in keyBytes.indices) {
                val value = parts[0].substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
                keyBytes[index] = value.toByte()
            }
            val mapping = GamepadMapping.fromPersistedKeyCodes(
                parts[1].split(',').mapNotNull(String::toIntOrNull),
            ) ?: return null
            return keyBytes.toString(Charsets.UTF_8) to mapping
        }

        private fun encodeCalibration(calibration: AnalogCalibration): String = listOf(
            calibration.deadZone,
            calibration.triggerThreshold,
            calibration.leftXInverted,
            calibration.leftYInverted,
            calibration.rightXInverted,
            calibration.rightYInverted,
        ).joinToString(",")

        private fun decodeAnalogCalibration(encoded: String?): AnalogCalibration? = encoded
            ?.split(',')
            ?.let(AnalogCalibration::fromPersistedValues)

        private fun encodeAnalogProfile(profileKey: String, calibration: AnalogCalibration): String =
            "${profileKey.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }}|${encodeCalibration(calibration)}"

        private fun decodeAnalogProfile(encoded: String): Pair<String, AnalogCalibration>? {
            val parts = encoded.split('|', limit = 2)
            if (parts.size != 2 || parts[0].isEmpty() || parts[0].length % 2 != 0) return null
            val keyBytes = ByteArray(parts[0].length / 2)
            for (index in keyBytes.indices) {
                val value = parts[0].substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
                keyBytes[index] = value.toByte()
            }
            val calibration = decodeAnalogCalibration(parts[1]) ?: return null
            return keyBytes.toString(Charsets.UTF_8) to calibration
        }

        private fun encodeHotkey(action: HotkeyAction, binding: HotkeyBinding): String =
            "${action.name}|${binding.modifier.name}|${binding.trigger.name}"

        private fun decodeHotkey(encoded: String): Pair<HotkeyAction, HotkeyBinding>? {
            val parts = encoded.split('|')
            if (parts.size != 3) return null
            val action = runCatching { HotkeyAction.valueOf(parts[0]) }.getOrNull() ?: return null
            val modifier = runCatching { dev.codex.libretroplatform.runtime.api.Button.valueOf(parts[1]) }.getOrNull() ?: return null
            val trigger = runCatching { dev.codex.libretroplatform.runtime.api.Button.valueOf(parts[2]) }.getOrNull() ?: return null
            if (modifier == trigger) return null
            return action to HotkeyBinding(modifier, trigger)
        }
    }
}

/**
 * Stable, dependency-free representation used by portable backups. The
 * on-device SharedPreferences encoding remains an implementation detail; this
 * format is versioned so a future migration can reject or transform it before
 * touching the user's current settings.
 */
object UserPreferencesBackupCodec {
    const val FORMAT_VERSION = 1

    private const val KEY_VERSION = "formatVersion"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_DISPLAY_PRESET = "displayPreset"
    private const val KEY_PLAYER_DISPLAY_SIZE = "playerDisplaySize"
    private const val KEY_SCREEN_TREATMENT = "screenTreatment"
    private const val KEY_PIXEL_SCALING_MODE = "pixelScalingMode"
    private const val KEY_TOUCH_LAYOUT_SIZE = "touchLayoutSize"
    private const val KEY_TOUCH_REACH_MODE = "touchReachMode"
    private const val KEY_TOUCH_LAYOUT = "touchLayout"
    private const val KEY_PERFORMANCE_PRESET = "performancePreset"
    private const val KEY_AUDIO_VOLUME = "audioVolume"
    private const val KEY_AUDIO_MUTED = "audioMuted"
    private const val KEY_CONTROLS_OPACITY = "controlsOpacity"
    private const val KEY_HAPTICS = "hapticsEnabled"
    private const val KEY_LEFT_HANDED = "leftHanded"
    private const val KEY_BUTTON_LABELS = "showButtonLabels"
    private const val KEY_REDUCE_MOTION = "reduceMotion"
    private const val KEY_IMMERSIVE = "immersivePlayer"
    private const val KEY_KEEP_SCREEN_ON = "keepScreenOn"
    private const val KEY_AUTO_SAVE_ON_BACKGROUND = "autoSaveOnBackground"
    private const val KEY_AUTO_RESUME_LATEST_STATE = "autoResumeFromLatestState"
    private const val KEY_GAMEPAD_ENABLED = "gamepadEnabled"
    private const val KEY_GAMEPAD_MAPPING = "gamepadMapping"
    private const val KEY_GAMEPAD_PROFILES = "gamepadProfiles"
    private const val KEY_GAMEPAD_ANALOG_PROFILES = "gamepadAnalogProfiles"
    private const val KEY_GAMEPAD_ANALOG_GLOBAL = "gamepadAnalogGlobal"
    private const val KEY_HOTKEYS = "hotkeys"

    fun encode(preferences: UserPreferences): Properties = Properties().apply {
        setProperty("playerPresentation", preferences.playerPresentation.name)
        setProperty(KEY_VERSION, FORMAT_VERSION.toString())
        setProperty(KEY_FAVORITES, preferences.favorites.sorted().joinToString("\n"))
        setProperty(KEY_DISPLAY_PRESET, preferences.displayPreset.name)
        setProperty(KEY_PLAYER_DISPLAY_SIZE, preferences.playerDisplaySize.name)
        setProperty(KEY_SCREEN_TREATMENT, preferences.screenTreatment.name)
        setProperty(KEY_PIXEL_SCALING_MODE, preferences.pixelScalingMode.name)
        setProperty(KEY_TOUCH_LAYOUT_SIZE, preferences.touchLayoutSize.name)
        setProperty(KEY_TOUCH_REACH_MODE, preferences.touchReachMode.name)
        setProperty(KEY_TOUCH_LAYOUT, TouchLayoutAdjustmentsCodec.encode(preferences.touchLayout.sanitized()))
        setProperty(KEY_PERFORMANCE_PRESET, preferences.performancePreset.name)
        setProperty(KEY_AUDIO_VOLUME, preferences.audioVolume.coerceIn(0f, 1f).toString())
        setProperty(KEY_AUDIO_MUTED, preferences.audioMuted.toString())
        setProperty(KEY_CONTROLS_OPACITY, preferences.controlsOpacity.coerceIn(0.35f, 1f).toString())
        setProperty(KEY_HAPTICS, preferences.hapticsEnabled.toString())
        setProperty(KEY_LEFT_HANDED, preferences.leftHanded.toString())
        setProperty(KEY_BUTTON_LABELS, preferences.showButtonLabels.toString())
        setProperty(KEY_REDUCE_MOTION, preferences.reduceMotion.toString())
        setProperty(KEY_IMMERSIVE, preferences.immersivePlayer.toString())
        setProperty(KEY_KEEP_SCREEN_ON, preferences.keepScreenOn.toString())
        setProperty(KEY_AUTO_SAVE_ON_BACKGROUND, preferences.autoSaveOnBackground.toString())
        setProperty(KEY_AUTO_RESUME_LATEST_STATE, preferences.autoResumeFromLatestState.toString())
        setProperty(KEY_GAMEPAD_ENABLED, preferences.gamepadEnabled.toString())
        setProperty(KEY_GAMEPAD_MAPPING, preferences.gamepadMapping.persistedKeyCodes().joinToString(","))
        setProperty(
            KEY_GAMEPAD_PROFILES,
            preferences.gamepadProfiles.toSortedMap().map { (key, mapping) -> encodeGamepadProfile(key, mapping) }.joinToString("\n"),
        )
        setProperty(
            KEY_GAMEPAD_ANALOG_PROFILES,
            preferences.gamepadAnalogProfiles.toSortedMap().map { (key, calibration) -> encodeAnalogProfile(key, calibration) }.joinToString("\n"),
        )
        setProperty(KEY_GAMEPAD_ANALOG_GLOBAL, encodeCalibration(preferences.gamepadAnalogCalibration))
        setProperty(
            KEY_HOTKEYS,
            preferences.hotkeys.toSortedMap(compareBy { it.name }).map { (action, binding) -> encodeHotkey(action, binding) }.joinToString("\n"),
        )
    }

    fun decode(properties: Properties): UserPreferences {
        require(properties.getProperty(KEY_VERSION)?.toIntOrNull() == FORMAT_VERSION) {
            "unsupported preferences backup format"
        }
        val displayPreset = properties.getProperty(KEY_DISPLAY_PRESET)
            ?.let { runCatching { DisplayPreset.valueOf(it) }.getOrDefault(DisplayPreset.Classic) }
            ?: DisplayPreset.Classic
        val playerDisplaySize = properties.getProperty(KEY_PLAYER_DISPLAY_SIZE)
            ?.let { runCatching { PlayerDisplaySize.valueOf(it) }.getOrDefault(PlayerDisplaySize.Large) }
            ?: PlayerDisplaySize.Large
        val mapping = properties.getProperty(KEY_GAMEPAD_MAPPING)
            ?.split(',')
            ?.mapNotNull(String::toIntOrNull)
            ?.let(GamepadMapping::fromPersistedKeyCodes)
            ?: GamepadMapping.default()
        return UserPreferences(
            playerPresentation = properties.getProperty("playerPresentation")?.let { raw -> PlayerPresentation.values().firstOrNull { it.name == raw } } ?: PlayerPresentation.Adaptive,
            favorites = properties.getProperty(KEY_FAVORITES).linesOrEmpty().toSet(),
            displayPreset = displayPreset,
            playerDisplaySize = playerDisplaySize,
            screenTreatment = properties.getProperty(KEY_SCREEN_TREATMENT)
                ?.let { runCatching { ScreenTreatment.valueOf(it) }.getOrDefault(ScreenTreatment.Clean) }
                ?: ScreenTreatment.Clean,
            pixelScalingMode = properties.getProperty(KEY_PIXEL_SCALING_MODE)
                ?.let { runCatching { PixelScalingMode.valueOf(it) }.getOrDefault(PixelScalingMode.Fit) }
                ?: PixelScalingMode.Fit,
            touchLayoutSize = properties.getProperty(KEY_TOUCH_LAYOUT_SIZE)
                ?.let { runCatching { TouchLayoutSize.valueOf(it) }.getOrDefault(TouchLayoutSize.Comfort) }
                ?: TouchLayoutSize.Comfort,
            touchReachMode = properties.getProperty(KEY_TOUCH_REACH_MODE)
                ?.let { runCatching { TouchReachMode.valueOf(it) }.getOrDefault(TouchReachMode.Full) }
                ?: TouchReachMode.Full,
            touchLayout = TouchLayoutAdjustmentsCodec.decode(properties.getProperty(KEY_TOUCH_LAYOUT))
                ?: TouchLayoutAdjustments(),
            performancePreset = properties.getProperty(KEY_PERFORMANCE_PRESET)
                ?.let { runCatching { PerformancePreset.valueOf(it) }.getOrDefault(PerformancePreset.Balanced) }
                ?: PerformancePreset.Balanced,
            audioVolume = properties.float(KEY_AUDIO_VOLUME, 1f).coerceIn(0f, 1f),
            audioMuted = properties.boolean(KEY_AUDIO_MUTED, false),
            controlsOpacity = properties.float(KEY_CONTROLS_OPACITY, 1f).coerceIn(0.35f, 1f),
            hapticsEnabled = properties.boolean(KEY_HAPTICS, true),
            leftHanded = properties.boolean(KEY_LEFT_HANDED, false),
            showButtonLabels = properties.boolean(KEY_BUTTON_LABELS, true),
            reduceMotion = properties.boolean(KEY_REDUCE_MOTION, false),
            immersivePlayer = properties.boolean(KEY_IMMERSIVE, false),
            keepScreenOn = properties.boolean(KEY_KEEP_SCREEN_ON, true),
            autoSaveOnBackground = properties.boolean(KEY_AUTO_SAVE_ON_BACKGROUND, true),
            autoResumeFromLatestState = properties.boolean(KEY_AUTO_RESUME_LATEST_STATE, true),
            gamepadEnabled = properties.boolean(KEY_GAMEPAD_ENABLED, true),
            gamepadMapping = mapping,
            gamepadProfiles = properties.getProperty(KEY_GAMEPAD_PROFILES).profiles(::decodeGamepadProfile),
            gamepadAnalogCalibration = decodeAnalogCalibration(properties.getProperty(KEY_GAMEPAD_ANALOG_GLOBAL))
                ?: AnalogCalibration.default(),
            gamepadAnalogProfiles = properties.getProperty(KEY_GAMEPAD_ANALOG_PROFILES).profiles(::decodeAnalogProfile),
            hotkeys = properties.getProperty(KEY_HOTKEYS)
                ?.hotkeys()
                ?.takeIf { it.isNotEmpty() }
                ?: defaultHotkeyBindings(),
        )
    }

    private fun Properties.float(key: String, fallback: Float): Float =
        getProperty(key)?.toFloatOrNull() ?: fallback

    private fun Properties.boolean(key: String, fallback: Boolean): Boolean =
        getProperty(key)?.toBooleanStrictOrNull() ?: fallback

    private fun String?.linesOrEmpty(): List<String> =
        orEmpty().split('\n').map(String::trim).filter(String::isNotEmpty)

    private fun <T> String?.profiles(decoder: (String) -> Pair<String, T>?): Map<String, T> =
        linesOrEmpty().associate { encoded -> requireNotNull(decoder(encoded)) { "invalid controller profile" } }

    private fun String?.hotkeys(): Map<HotkeyAction, HotkeyBinding> =
        linesOrEmpty().associate { encoded -> requireNotNull(decodeHotkey(encoded)) { "invalid hotkey binding" } }

    private fun encodeGamepadProfile(profileKey: String, mapping: GamepadMapping): String =
        "${encodeKey(profileKey)}|${mapping.persistedKeyCodes().joinToString(",")}"

    private fun decodeGamepadProfile(encoded: String): Pair<String, GamepadMapping>? {
        val parts = encoded.split('|', limit = 2)
        if (parts.size != 2) return null
        val mapping = parts[1].split(',').mapNotNull(String::toIntOrNull).let(GamepadMapping::fromPersistedKeyCodes) ?: return null
        return decodeKey(parts[0]) to mapping
    }

    private fun encodeCalibration(calibration: AnalogCalibration): String = listOf(
        calibration.deadZone,
        calibration.triggerThreshold,
        calibration.leftXInverted,
        calibration.leftYInverted,
        calibration.rightXInverted,
        calibration.rightYInverted,
    ).joinToString(",")

    private fun decodeAnalogCalibration(encoded: String?): AnalogCalibration? = encoded
        ?.split(',')
        ?.let(AnalogCalibration::fromPersistedValues)

    private fun encodeAnalogProfile(profileKey: String, calibration: AnalogCalibration): String =
        "${encodeKey(profileKey)}|${encodeCalibration(calibration)}"

    private fun decodeAnalogProfile(encoded: String): Pair<String, AnalogCalibration>? {
        val parts = encoded.split('|', limit = 2)
        if (parts.size != 2) return null
        return decodeAnalogCalibration(parts[1])?.let { calibration -> decodeKey(parts[0]) to calibration }
    }

    private fun encodeHotkey(action: HotkeyAction, binding: HotkeyBinding): String =
        "${action.name}|${binding.modifier.name}|${binding.trigger.name}"

    private fun decodeHotkey(encoded: String): Pair<HotkeyAction, HotkeyBinding>? {
        val parts = encoded.split('|')
        if (parts.size != 3) return null
        val action = runCatching { HotkeyAction.valueOf(parts[0]) }.getOrNull() ?: return null
        val modifier = runCatching { dev.codex.libretroplatform.runtime.api.Button.valueOf(parts[1]) }.getOrNull() ?: return null
        val trigger = runCatching { dev.codex.libretroplatform.runtime.api.Button.valueOf(parts[2]) }.getOrNull() ?: return null
        if (modifier == trigger) return null
        return action to HotkeyBinding(modifier, trigger)
    }

    private fun encodeKey(value: String): String = value.toByteArray(Charsets.UTF_8)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun decodeKey(value: String): String {
        require(value.isNotEmpty() && value.length % 2 == 0) { "invalid profile key" }
        val bytes = ByteArray(value.length / 2)
        bytes.indices.forEach { index ->
            bytes[index] = value.substring(index * 2, index * 2 + 2).toIntOrNull(16)
                ?.toByte() ?: throw IllegalArgumentException("invalid profile key")
        }
        return bytes.toString(Charsets.UTF_8)
    }
}

/** Compact versioned record for optional per-title presentation overrides. */
object TitlePlayerPreferencesCodec {
    private const val LEGACY_FIELD_COUNT = 12
    private const val FIELD_COUNT = 14

    fun encode(value: TitlePlayerPreferences?): String = value?.let {
        listOf(
            it.displayPreset?.name,
            it.playerDisplaySize?.name,
            it.screenTreatment?.name,
            it.pixelScalingMode?.name,
            it.touchLayoutSize?.name,
            it.touchReachMode?.name,
            it.touchLayout?.let(TouchLayoutAdjustmentsCodec::encode),
            it.audioVolume?.coerceIn(0f, 1f)?.toString(),
            it.audioMuted?.toString(),
            it.controlsOpacity?.coerceIn(0.35f, 1f)?.toString(),
            it.leftHanded?.toString(),
            it.showButtonLabels?.toString(),
            it.hapticsEnabled?.toString(),
            it.playerPresentation?.name,
        ).joinToString("|") { field -> field.orEmpty() }
    }.orEmpty()

    fun decode(encoded: String?): TitlePlayerPreferences? {
        if (encoded.isNullOrBlank()) return null
        val fields = encoded.split('|')
        if (fields.size !in listOf(LEGACY_FIELD_COUNT, 13, FIELD_COUNT)) return null
        val hasLayout = fields.size >= 13
        val audioIndex = if (hasLayout) 7 else 6
        fun <T : Enum<T>> enumOrNull(index: Int, values: Array<T>): T? = fields[index]
            .takeIf { it.isNotBlank() }
            ?.let { raw -> values.firstOrNull { value -> value.name == raw } }
        fun booleanOrNull(index: Int): Boolean? = fields[index].takeIf { it.isNotBlank() }?.toBooleanStrictOrNull()
        return TitlePlayerPreferences(
            playerPresentation = if (fields.size >= 14) enumOrNull(13, PlayerPresentation.values()) else null,
            displayPreset = enumOrNull(0, DisplayPreset.values()),
            playerDisplaySize = enumOrNull(1, PlayerDisplaySize.values()),
            screenTreatment = enumOrNull(2, ScreenTreatment.values()),
            pixelScalingMode = enumOrNull(3, PixelScalingMode.values()),
            touchLayoutSize = enumOrNull(4, TouchLayoutSize.values()),
            touchReachMode = enumOrNull(5, TouchReachMode.values()),
            touchLayout = if (hasLayout) TouchLayoutAdjustmentsCodec.decode(fields[6]) else null,
            audioVolume = fields[audioIndex].takeIf { it.isNotBlank() }?.toFloatOrNull()?.coerceIn(0f, 1f),
            audioMuted = booleanOrNull(audioIndex + 1),
            controlsOpacity = fields[audioIndex + 2].takeIf { it.isNotBlank() }?.toFloatOrNull()?.coerceIn(0.35f, 1f),
            leftHanded = booleanOrNull(audioIndex + 3),
            showButtonLabels = booleanOrNull(audioIndex + 4),
            hapticsEnabled = booleanOrNull(audioIndex + 5),
        ).takeIf { it != TitlePlayerPreferences() }
    }
}
