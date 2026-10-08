package dev.codex.libretroplatform

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.view.Surface
import android.view.InputDevice
import dev.codex.libretroplatform.runtime.api.AnalogAxis
import dev.codex.libretroplatform.runtime.api.Button
import dev.codex.libretroplatform.runtime.api.SessionCommand
import dev.codex.libretroplatform.runtime.api.SessionEvent
import dev.codex.libretroplatform.runtime.api.SessionState
import dev.codex.libretroplatform.runtime.content.ContentImportCancelled
import dev.codex.libretroplatform.runtime.content.ImportCancellationToken
import dev.codex.libretroplatform.runtime.saves.SafSaveExportTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.graphics.scale
import kotlin.coroutines.coroutineContext

class FrontendController(
    private val repository: LibraryRepository,
    private val contentResolver: ContentResolver,
    private val scope: CoroutineScope,
    private val preferencesStore: UserPreferencesStore = InMemoryUserPreferencesStore(),
    private val backupStore: PortableBackupStore? = null,
    private val collectionStore: LibraryCollectionStore = InMemoryLibraryCollectionStore(),
    private val systemFileStore: SystemFileStore = InMemorySystemFileStore(),
    private val sessionCoordinator: EmulationSessionCoordinator = EmulationSessionCoordinator(
        engine = null,
        scope = scope,
    ),
) {
    var uiState by mutableStateOf(
        FrontendUiState(
            library = emptyList(),
            preferences = preferencesStore.load(),
            collections = runCatching { collectionStore.load() }.getOrDefault(emptyList()),
            systemFiles = runCatching { systemFileStore.load() }.getOrDefault(emptyList()),
        ),
    )
        private set

    private var libraryRefreshJob: Job? = null
    private var libraryRefreshGeneration = 0L
    private var importJob: Job? = null
    private var importCancellationSignal: CancellationSignal? = null
    private var importCancellationRequested = false
    private var folderScanJob: Job? = null
    private var folderScanCancellationSignal: CancellationSignal? = null
    private var closed = false
    private var exitSavePending = false
    var exitSaveFailure by mutableStateOf<String?>(null)
        private set
    private var nativeSaveOperationInFlight = false
    private var nativeSaveOperationGeneration = 0L
    private val nativeSaveWaiters = mutableListOf<(Boolean) -> Unit>()
    var automaticCheckpoints by mutableStateOf<List<AutomaticCheckpoint>>(emptyList())
        private set
    var automaticCheckpointsReady by mutableStateOf(false)
        private set
    /** Includes manual save/load, auto-resume and automatic capture: all share one staging file. */
    var checkpointBusy by mutableStateOf(false)
        private set
    val saveStateBusy: Boolean get() = checkpointBusy
    private val stateTransfers = AutomaticCheckpointTransfers { checkpointBusy = it }
    private var checkpointJob: Job? = null
    private var checkpointRefreshGeneration = 0L
    private var pauseInFlight = false
    private val pauseWaiters = mutableListOf<(Boolean) -> Unit>()
    private var videoThumbnailProvider: (() -> Bitmap?)? = null
    private val pressedInputs = mutableSetOf<Button>()
    private val analogInputs = mutableMapOf<AnalogAxis, Float>()

    init {
        refreshLibraryInBackground()
    }

    fun chooseGames() {
        uiState = uiState.copy(route = AppRoute.ContentSource, statusMessage = null)
    }

    fun updateLibraryQuery(query: String) {
        uiState = uiState.copy(libraryQuery = query)
    }

    fun updateLibraryMetadata(itemId: String, titleAlias: String?, sortTitle: String?) {
        if (uiState.library.none { it.id == itemId }) return
        scope.launch(Dispatchers.IO) {
            val result = runCatching { repository.updateMetadata(itemId, titleAlias, sortTitle) }
            withContext(Dispatchers.Main.immediate) {
                if (closed) return@withContext
                result.fold(
                    onSuccess = { updated ->
                        uiState = uiState.copy(
                            library = uiState.library.map { item -> if (item.id == updated.id) updated else item },
                            statusMessage = "Library title updated.",
                        )
                    },
                    onFailure = { failure ->
                        uiState = uiState.copy(statusMessage = "Could not update title: ${failure.message ?: "storage is unavailable"}")
                    },
                )
            }
        }
    }

    fun importArtwork(uri: Uri) {
        val item = selectedItem() ?: return
        scope.launch(Dispatchers.IO) {
            val result = runCatching { repository.importArtwork(contentResolver, item.id, uri) }
            withContext(Dispatchers.Main.immediate) {
                if (closed) return@withContext
                result.fold(
                    onSuccess = { updated ->
                        uiState = uiState.copy(
                            library = uiState.library.map { current -> if (current.id == updated.id) updated else current },
                            statusMessage = "Custom cover art saved in app-private storage.",
                        )
                    },
                    onFailure = { failure ->
                        uiState = uiState.copy(statusMessage = "Could not use that image: ${failure.message ?: "the image is unreadable"}")
                    },
                )
            }
        }
    }

    fun removeArtwork() {
        val item = selectedItem() ?: return
        scope.launch(Dispatchers.IO) {
            val result = runCatching { repository.removeArtwork(item.id) }
            withContext(Dispatchers.Main.immediate) {
                if (closed) return@withContext
                result.fold(
                    onSuccess = { updated ->
                        uiState = uiState.copy(
                            library = uiState.library.map { current -> if (current.id == updated.id) updated else current },
                            statusMessage = "Custom cover art removed; generated artwork restored.",
                        )
                    },
                    onFailure = { failure ->
                        uiState = uiState.copy(statusMessage = "Could not remove cover art: ${failure.message ?: "storage is unavailable"}")
                    },
                )
            }
        }
    }

    fun importSystemFile(uri: Uri, displayName: String) {
        scope.launch(Dispatchers.IO) {
            val result = runCatching { systemFileStore.import(contentResolver, uri, displayName) }
            withContext(Dispatchers.Main.immediate) {
                if (closed) return@withContext
                result.fold(
                    onSuccess = { record ->
                        uiState = uiState.copy(
                            systemFiles = (uiState.systemFiles.filterNot { it.id == record.id } + record),
                            statusMessage = "Runtime data imported privately. It will only be used by a compatible core.",
                        )
                    },
                    onFailure = { failure ->
                        uiState = uiState.copy(statusMessage = "Could not import runtime data: ${failure.message ?: "the file is unreadable"}")
                    },
                )
            }
        }
    }

    fun removeSystemFile(id: String) {
        val record = uiState.systemFiles.firstOrNull { it.id == id } ?: return
        runCatching { systemFileStore.delete(id) }
            .onSuccess {
                uiState = uiState.copy(
                    systemFiles = uiState.systemFiles.filterNot { it.id == id },
                    statusMessage = "Removed ${record.displayName} from private runtime data.",
                )
            }
            .onFailure { failure ->
                uiState = uiState.copy(statusMessage = "Could not remove runtime data: ${failure.message ?: "storage is unavailable"}")
            }
    }

    fun setLibraryFilter(filter: LibraryFilter) {
        uiState = uiState.copy(libraryFilter = filter)
    }

    fun setLibrarySystem(system: String?) {
        uiState = uiState.copy(librarySystem = system)
    }

    fun setLibraryCollection(collectionId: String?) {
        if (collectionId != null && uiState.collections.none { it.id == collectionId }) return
        uiState = uiState.copy(libraryCollectionId = collectionId)
    }

    fun createCollection(name: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) {
            uiState = uiState.copy(statusMessage = "Give the collection a name first.")
            return
        }
        if (cleanName.length > 80) {
            uiState = uiState.copy(statusMessage = "Collection names are limited to 80 characters.")
            return
        }
        if (uiState.collections.any { it.name.equals(cleanName, ignoreCase = true) }) {
            uiState = uiState.copy(statusMessage = "A collection with that name already exists.")
            return
        }
        val now = System.currentTimeMillis()
        val collection = LibraryCollection(
            id = "collection-${java.util.UUID.randomUUID()}",
            name = cleanName,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        runCatching { collectionStore.save(collection) }
            .onSuccess {
                uiState = uiState.copy(
                    collections = (uiState.collections + collection).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
                    libraryCollectionId = collection.id,
                    statusMessage = "Collection created. Add titles from their details screen.",
                )
            }
            .onFailure { failure ->
                uiState = uiState.copy(statusMessage = "Could not create collection: ${failure.message ?: "storage is unavailable"}")
            }
    }

    fun toggleItemInCollection(collectionId: String, itemId: String) {
        val collection = uiState.collections.firstOrNull { it.id == collectionId } ?: return
        if (uiState.library.none { it.id == itemId }) return
        val nextIds = collection.itemIds.toMutableSet().also { ids ->
            if (!ids.add(itemId)) ids.remove(itemId)
        }
        val updated = collection.copy(itemIds = nextIds, updatedAtEpochMs = System.currentTimeMillis())
        runCatching { collectionStore.save(updated) }
            .onSuccess {
                uiState = uiState.copy(
                    collections = uiState.collections.map { current -> if (current.id == updated.id) updated else current },
                    statusMessage = if (itemId in nextIds) "Added to ${updated.name}." else "Removed from ${updated.name}.",
                )
            }
            .onFailure { failure ->
                uiState = uiState.copy(statusMessage = "Could not update collection: ${failure.message ?: "storage is unavailable"}")
            }
    }

    fun deleteCollection(collectionId: String) {
        val collection = uiState.collections.firstOrNull { it.id == collectionId } ?: return
        runCatching { collectionStore.delete(collectionId) }
            .onSuccess {
                uiState = uiState.copy(
                    collections = uiState.collections.filterNot { it.id == collectionId },
                    libraryCollectionId = uiState.libraryCollectionId.takeUnless { it == collectionId },
                    statusMessage = "Deleted ${collection.name}.",
                )
            }
            .onFailure { failure ->
                uiState = uiState.copy(statusMessage = "Could not delete collection: ${failure.message ?: "storage is unavailable"}")
            }
    }

    fun setLibrarySort(sort: LibrarySort) {
        uiState = uiState.copy(librarySort = sort)
    }

    fun toggleFavorite(itemId: String) {
        if (uiState.library.none { it.id == itemId }) return
        updatePreferences { current ->
            val next = current.favorites.toMutableSet()
            if (!next.add(itemId)) next.remove(itemId)
            current.copy(favorites = next)
        }
    }

    fun openSettings(from: AppRoute = uiState.route) {
        uiState = uiState.copy(route = AppRoute.Settings, settingsReturnRoute = from)
    }

    fun closeSettings() {
        val returnRoute = uiState.settingsReturnRoute
        uiState = uiState.copy(route = returnRoute)
    }

    fun updatePreferences(transform: (UserPreferences) -> UserPreferences) {
        val previous = uiState.preferences
        val next = transform(previous)
        preferencesStore.save(next)
        uiState = uiState.copy(preferences = next)
        if (previous.audioVolume != next.audioVolume || previous.audioMuted != next.audioMuted) {
            sessionCoordinator.setAudioVolume(if (next.audioMuted) 0f else next.audioVolume) { result ->
                if (!result.accepted) uiState = uiState.copy(statusMessage = result.message)
            }
        }
    }

    fun resetPreferences() {
        preferencesStore.save(UserPreferences())
        uiState = uiState.copy(preferences = UserPreferences())
    }

    fun setConnectedGamepads(devices: List<GamepadDeviceInfo>) {
        val connected = devices.distinctBy { it.deviceId }
        val selected = uiState.selectedGamepadProfileKey
            ?.takeIf { profileKey -> connected.any { it.profileKey == profileKey } }
            ?: connected.firstOrNull()?.profileKey
        uiState = uiState.copy(
            connectedGamepads = connected,
            selectedGamepadProfileKey = selected,
            gamepadDiagnostics = uiState.gamepadDiagnostics
                ?.takeIf { diagnostic -> connected.any { it.deviceId == diagnostic.deviceId } },
        )
    }

    fun selectGamepadProfile(profileKey: String) {
        if (uiState.connectedGamepads.none { it.profileKey == profileKey }) return
        uiState = uiState.copy(
            selectedGamepadProfileKey = profileKey,
            gamepadDiagnostics = null,
            statusMessage = null,
        )
    }

    fun reportGamepadAnalog(deviceId: Int, values: Map<AnalogAxis, Float>) {
        if (uiState.route != AppRoute.Settings) return
        val selectedDevice = uiState.connectedGamepads.firstOrNull { it.deviceId == deviceId }
        if (uiState.selectedGamepadProfileKey != null && selectedDevice?.profileKey != uiState.selectedGamepadProfileKey) return
        val current = uiState.gamepadDiagnostics
        uiState = uiState.copy(
            gamepadDiagnostics = GamepadDiagnostics(
                deviceId = deviceId,
                analog = values.mapValues { (_, value) -> value.coerceIn(-1f, 1f) },
                pressedButtons = current?.takeIf { it.deviceId == deviceId }?.pressedButtons.orEmpty(),
            ),
        )
    }

    fun reportGamepadButton(deviceId: Int, button: Button, pressed: Boolean) {
        if (uiState.route != AppRoute.Settings) return
        val selectedDevice = uiState.connectedGamepads.firstOrNull { it.deviceId == deviceId }
        if (uiState.selectedGamepadProfileKey != null && selectedDevice?.profileKey != uiState.selectedGamepadProfileKey) return
        val current = uiState.gamepadDiagnostics?.takeIf { it.deviceId == deviceId }
            ?: GamepadDiagnostics(deviceId = deviceId)
        val buttons = current.pressedButtons.toMutableSet()
        if (pressed) buttons += button else buttons -= button
        uiState = uiState.copy(
            gamepadDiagnostics = current.copy(
                pressedButtons = buttons,
                lastEventEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    fun clearGamepadDiagnostics(deviceId: Int? = null) {
        if (deviceId == null || uiState.gamepadDiagnostics?.deviceId == deviceId) {
            uiState = uiState.copy(gamepadDiagnostics = null)
        }
    }

    fun selectedGamepadMapping(): GamepadMapping = uiState.selectedGamepadProfileKey
        ?.let { uiState.preferences.gamepadProfiles[it] }
        ?: uiState.preferences.gamepadMapping

    fun gamepadMappingFor(deviceId: Int): GamepadMapping {
        val device = InputDevice.getDevice(deviceId) ?: return uiState.preferences.gamepadMapping
        return uiState.preferences.gamepadProfiles[gamepadProfileKey(device)]
            ?: uiState.preferences.gamepadMapping
    }

    fun beginGamepadMapping(button: Button) {
        uiState = uiState.copy(gamepadCaptureTarget = button, statusMessage = null)
    }

    fun cancelGamepadMapping() {
        uiState = uiState.copy(gamepadCaptureTarget = null)
    }

    fun captureGamepadKey(keyCode: Int, deviceId: Int? = null) {
        val target = uiState.gamepadCaptureTarget ?: return
        val profileKey = deviceId
            ?.let(InputDevice::getDevice)
            ?.let(::gamepadProfileKey)
            ?: uiState.selectedGamepadProfileKey
        updatePreferences { current ->
            if (profileKey == null) {
                current.copy(gamepadMapping = current.gamepadMapping.withKey(target, keyCode))
            } else {
                val base = current.gamepadProfiles[profileKey] ?: current.gamepadMapping
                current.copy(
                    gamepadProfiles = current.gamepadProfiles + (profileKey to base.withKey(target, keyCode)),
                )
            }
        }
        uiState = uiState.copy(
            gamepadCaptureTarget = null,
            statusMessage = "${gamepadButtonLabel(target)} mapped to ${gamepadKeyLabel(keyCode)} for the selected controller.",
        )
    }

    fun resetGamepadMapping() {
        val profileKey = uiState.selectedGamepadProfileKey
        updatePreferences { current ->
            if (profileKey == null) {
                current.copy(gamepadMapping = GamepadMapping.default())
            } else {
                current.copy(gamepadProfiles = current.gamepadProfiles + (profileKey to GamepadMapping.default()))
            }
        }
        uiState = uiState.copy(gamepadCaptureTarget = null, statusMessage = "Controller mapping restored to the standard layout.")
    }

    fun applyRecommendedGamepadMapping() {
        val profileKey = uiState.selectedGamepadProfileKey
        updatePreferences { current ->
            if (profileKey == null) {
                current.copy(gamepadMapping = GamepadMapping.recommended())
            } else {
                current.copy(gamepadProfiles = current.gamepadProfiles + (profileKey to GamepadMapping.recommended()))
            }
        }
        uiState = uiState.copy(gamepadCaptureTarget = null, statusMessage = "Recommended Android HID layout applied. Verify it with the live test pad.")
    }

    fun selectedAnalogCalibration(): AnalogCalibration = uiState.selectedGamepadProfileKey
        ?.let { uiState.preferences.gamepadAnalogProfiles[it] }
        ?: uiState.preferences.gamepadAnalogCalibration

    fun analogCalibrationFor(deviceId: Int): AnalogCalibration {
        val device = InputDevice.getDevice(deviceId) ?: return uiState.preferences.gamepadAnalogCalibration
        return uiState.preferences.gamepadAnalogProfiles[gamepadProfileKey(device)]
            ?: uiState.preferences.gamepadAnalogCalibration
    }

    fun updateSelectedAnalogCalibration(transform: (AnalogCalibration) -> AnalogCalibration) {
        val profileKey = uiState.selectedGamepadProfileKey
        updatePreferences { current ->
            if (profileKey == null) {
                current.copy(gamepadAnalogCalibration = transform(current.gamepadAnalogCalibration))
            } else {
                val base = current.gamepadAnalogProfiles[profileKey] ?: current.gamepadAnalogCalibration
                current.copy(
                    gamepadAnalogProfiles = current.gamepadAnalogProfiles + (profileKey to transform(base)),
                )
            }
        }
    }

    fun resetSelectedAnalogCalibration() {
        val profileKey = uiState.selectedGamepadProfileKey
        updatePreferences { current ->
            if (profileKey == null) {
                current.copy(gamepadAnalogCalibration = AnalogCalibration.default())
            } else {
                current.copy(
                    gamepadAnalogProfiles = current.gamepadAnalogProfiles + (profileKey to AnalogCalibration.default()),
                )
            }
        }
        uiState = uiState.copy(statusMessage = "Analog calibration restored for the selected controller.")
    }

    fun beginHotkeyMapping(action: HotkeyAction) {
        uiState = uiState.copy(
            gamepadCaptureTarget = null,
            hotkeyCaptureTarget = action,
            hotkeyCapturePart = HotkeyCapturePart.Modifier,
            hotkeyCaptureModifier = null,
            statusMessage = null,
        )
    }

    fun cancelHotkeyMapping() {
        uiState = uiState.copy(
            hotkeyCaptureTarget = null,
            hotkeyCapturePart = null,
            hotkeyCaptureModifier = null,
        )
    }

    fun captureGamepadHotkeyButton(button: Button) {
        val action = uiState.hotkeyCaptureTarget ?: return
        when (uiState.hotkeyCapturePart) {
            HotkeyCapturePart.Modifier -> uiState = uiState.copy(
                hotkeyCapturePart = HotkeyCapturePart.Trigger,
                hotkeyCaptureModifier = button,
                statusMessage = "Modifier ${button.name} captured. Press the trigger button now.",
            )

            HotkeyCapturePart.Trigger -> {
                val modifier = uiState.hotkeyCaptureModifier ?: return
                if (modifier == button) {
                    uiState = uiState.copy(statusMessage = "Choose a different trigger so this hotkey always requires two buttons.")
                    return
                }
                val binding = HotkeyBinding(modifier, button)
                updatePreferences { current ->
                    current.copy(hotkeys = current.hotkeys.filterValues { it != binding } + (action to binding))
                }
                uiState = uiState.copy(
                    hotkeyCaptureTarget = null,
                    hotkeyCapturePart = null,
                    hotkeyCaptureModifier = null,
                    statusMessage = "${action.label} set to ${binding.label}.",
                )
            }

            null -> Unit
        }
    }

    fun hotkeyBinding(action: HotkeyAction): HotkeyBinding =
        uiState.preferences.hotkeys[action] ?: defaultHotkeyBindings().getValue(action)

    fun resetHotkeys() {
        updatePreferences { it.copy(hotkeys = defaultHotkeyBindings()) }
        cancelHotkeyMapping()
        uiState = uiState.copy(statusMessage = "Runtime hotkeys restored to the safe two-button defaults.")
    }

    fun hotkeyActionFor(activeButtons: Set<Button>, trigger: Button): HotkeyAction? =
        HotkeyAction.values().firstOrNull { action ->
            val binding = hotkeyBinding(action)
            binding.trigger == trigger && binding.modifier != trigger && binding.modifier in activeButtons
        }

    fun triggerHotkey(action: HotkeyAction): Boolean {
        if (uiState.route != AppRoute.Player || uiState.playerSession == null) return false
        when (action) {
            HotkeyAction.TogglePause -> if (uiState.playerState is PlayerState.Paused) resume() else pause()
            HotkeyAction.QuickSave -> saveState()
            HotkeyAction.QuickLoad -> loadState()
            HotkeyAction.ExitPlayer -> exitPlayer()
        }
        return true
    }

    fun setSaveStateSlot(slot: Int) {
        if (slot !in MIN_SAVE_STATE_SLOT..MAX_SAVE_STATE_SLOT) return
        uiState = uiState.copy(saveStateSlot = slot, statusMessage = "Save-state slot $slot selected.")
    }

    fun receiveFiles(candidates: List<ImportCandidate>) {
        queueCandidates(candidates)
    }

    fun removePendingCandidate(uri: Uri) {
        if (importJob?.isActive == true) return
        val remaining = uiState.pendingCandidates.filterNot { it.uri == uri }
        uiState = uiState.copy(
            pendingCandidates = remaining,
            importState = if (remaining.isEmpty()) ImportState.NoSelection else ImportState.Queued(remaining),
            statusMessage = if (remaining.isEmpty()) "No sources remain selected." else "Removed source from this import.",
        )
    }

    fun receiveFolder(uri: Uri, displayName: String) {
        if (closed || folderScanJob?.isActive == true) return
        val cancellationSignal = CancellationSignal()
        folderScanCancellationSignal = cancellationSignal
        uiState = uiState.copy(
            route = AppRoute.ContentSource,
            folderScanInProgress = true,
            statusMessage = "Scanning $displayName…",
        )
        folderScanJob = scope.launch {
            try {
                val scan = withContext(Dispatchers.IO) { scanFolder(uri, cancellationSignal) }
                withContext(Dispatchers.Main.immediate) {
                    if (closed) return@withContext
                    if (scan.candidates.isEmpty()) {
                        uiState = uiState.copy(statusMessage = "No supported game files were found in $displayName.")
                    } else {
                        queueCandidates(scan.candidates)
                        if (scan.truncated) {
                            uiState = uiState.copy(
                                statusMessage = "This folder contains more than $MAX_FOLDER_FILES supported files. The first $MAX_FOLDER_FILES are queued; scan a smaller folder to add the rest.",
                            )
                        }
                    }
                }
            } catch (_: android.os.OperationCanceledException) {
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) uiState = uiState.copy(statusMessage = "Folder scan cancelled.")
                }
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) uiState = uiState.copy(statusMessage = "Folder scan failed: ${failure.message ?: "the provider could not be read"}")
                }
            } finally {
                folderScanCancellationSignal = null
                folderScanJob = null
                if (!closed) {
                    scope.launch(Dispatchers.Main.immediate) {
                        uiState = uiState.copy(folderScanInProgress = false)
                    }
                }
            }
        }
    }

    fun cancelFolderScan() {
        if (folderScanJob?.isActive != true) return
        folderScanCancellationSignal?.cancel()
        folderScanJob?.cancel()
        uiState = uiState.copy(
            folderScanInProgress = false,
            statusMessage = "Folder scan cancelled.",
        )
    }

    private fun queueCandidates(candidates: List<ImportCandidate>) {
        val uniqueCandidates = candidates.distinctBy { it.uri.toString() }
        if (uniqueCandidates.isEmpty()) {
            uiState = uiState.copy(
                route = AppRoute.ContentSource,
                statusMessage = "No content was selected.",
            )
            return
        }

        uiState = uiState.copy(
            route = AppRoute.Import,
            pendingCandidates = uniqueCandidates,
            importState = ImportState.Queued(uniqueCandidates),
            statusMessage = null,
        )
    }

    private data class FolderScanResult(
        val candidates: List<ImportCandidate>,
        val truncated: Boolean,
    )

    private fun scanFolder(treeUri: Uri, cancellationSignal: CancellationSignal): FolderScanResult {
        val candidates = mutableListOf<ImportCandidate>()
        val visited = mutableSetOf<String>()
        var truncated = false
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)

        fun visit(documentId: String) {
            if (cancellationSignal.isCanceled) throw android.os.OperationCanceledException()
            if (candidates.size >= MAX_FOLDER_FILES) {
                truncated = true
                return
            }
            if (!visited.add(documentId)) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            contentResolver.query(childrenUri, projection, null, null, null, cancellationSignal)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    if (cancellationSignal.isCanceled) throw android.os.OperationCanceledException()
                    if (candidates.size >= MAX_FOLDER_FILES) {
                        truncated = true
                        break
                    }
                    val childId = cursor.getString(idIndex)
                    val name = cursor.getString(nameIndex).orEmpty().ifBlank { "Unnamed content" }
                    val mime = cursor.getString(mimeIndex)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        visit(childId)
                    } else if (ConsoleCatalog.forFileName(name) != null) {
                        candidates += ImportCandidate(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                            displayName = name,
                        )
                    }
                }
            }
        }

        visit(rootId)
        return FolderScanResult(candidates, truncated)
    }

    /** Starts one sequential import operation so completed items survive cancellation. */
    fun startImport() {
        if (closed || importJob?.isActive == true) return
        val candidates = uiState.pendingCandidates
        if (candidates.isEmpty()) return

        importCancellationRequested = false
        val cancellationSignal = CancellationSignal()
        importCancellationSignal = cancellationSignal
        uiState = uiState.copy(
            importState = ImportState.Importing(current = 0, total = candidates.size),
            statusMessage = "Importing verified private copies…",
        )

        val job = scope.launch {
            val failures = mutableListOf<ImportFailure>()
            var imported = 0
            var processed = 0
            var cancelled = false

            try {
                withContext(Dispatchers.IO) {
                    for (candidate in candidates) {
                        if (importCancellationRequested || !coroutineContext.isActive) {
                            cancelled = true
                            break
                        }

                        if (candidate.isDirectory) {
                            failures += ImportFailure(
                                candidate.displayName,
                                "Folder scanning is not connected yet; select files instead.",
                            )
                        } else {
                            try {
                                val result = repository.importSaf(
                                    resolver = contentResolver,
                                    uri = candidate.uri,
                                    displayName = candidate.displayName,
                                    cancellationSignal = cancellationSignal,
                                    cancellationToken = ImportCancellationToken {
                                        importCancellationRequested || !coroutineContext.isActive
                                    },
                                )
                                if (result.created) {
                                    imported++
                                } else {
                                    failures += ImportFailure(
                                        candidate.displayName,
                                        "Already imported; verified content identity was preserved.",
                                    )
                                }
                            } catch (_: ContentImportCancelled) {
                                cancelled = true
                                break
                            } catch (failure: Exception) {
                                failures += ImportFailure(
                                    candidate.displayName,
                                    failure.message ?: "The source could not be imported.",
                                )
                            }
                        }

                        processed++
                        withContext(Dispatchers.Main.immediate) {
                            if (!closed) {
                                uiState = uiState.copy(
                                    importState = ImportState.Importing(
                                        current = processed,
                                        total = candidates.size,
                                    ),
                                )
                            }
                        }
                    }
                }

                val refreshedLibrary = withContext(Dispatchers.IO) { repository.load() }

                if (!closed) {
                    val summary = ImportSummary(
                        imported = imported,
                        skipped = failures.size,
                        failures = failures.toList(),
                        cancelled = cancelled,
                    )
                    uiState = uiState.copy(
                        route = AppRoute.Import,
                        library = refreshedLibrary,
                        importState = if (cancelled) {
                            ImportState.Cancelled(summary)
                        } else {
                            ImportState.Completed(summary)
                        },
                        statusMessage = if (cancelled) {
                            "Import cancelled. Completed private copies remain in your library."
                        } else if (failures.isEmpty()) {
                            "Import complete. Content is stored in app-private verified storage."
                        } else {
                            "Import complete with skipped items."
                        },
                    )
                }
            } catch (_: CancellationException) {
                // ViewModel teardown cancels the job. Any promoted item is already durable.
            } finally {
                if (importJob === coroutineContext[Job]) {
                    importJob = null
                }
                importCancellationSignal = null
                importCancellationRequested = false
            }
        }
        importJob = job
    }

    /** Requests provider and token cancellation without deleting completed imports. */
    fun cancelImport() {
        if (importJob?.isActive != true) return
        importCancellationRequested = true
        importCancellationSignal?.cancel()
        uiState = uiState.copy(statusMessage = "Cancelling import…")
    }

    fun openLibrary() {
        if (uiState.playerSession != null) {
            exitPlayer()
            return
        }
        uiState = uiState.copy(route = AppRoute.Library, statusMessage = "Refreshing library…")
        refreshLibraryInBackground()
    }

    /** Hydrates metadata and save status without making navigation wait on disk I/O. */
    private fun refreshLibraryInBackground() {
        val generation = ++libraryRefreshGeneration
        libraryRefreshJob?.cancel()
        libraryRefreshJob = scope.launch(Dispatchers.IO) {
            val result = runCatching { repository.load() }
            withContext(Dispatchers.Main.immediate) {
                if (closed || generation != libraryRefreshGeneration) return@withContext
                uiState = result.fold(
                    onSuccess = { refreshed ->
                        uiState.copy(
                            library = refreshed,
                            collections = runCatching { collectionStore.load() }.getOrDefault(uiState.collections),
                            statusMessage = null,
                        )
                    },
                    onFailure = { failure ->
                        uiState.copy(
                            statusMessage = "Library refresh failed: ${failure.message ?: "storage is unavailable"}",
                        )
                    },
                )
            }
        }
    }

    /** Handles system back without letting a player or import disappear silently. */
    fun navigateBack(): Boolean {
        return when (uiState.route) {
            AppRoute.Welcome -> false
            AppRoute.ContentSource -> {
                cancelFolderScan()
                if (uiState.library.isEmpty()) {
                    uiState = uiState.copy(route = AppRoute.Welcome, statusMessage = null)
                } else {
                    openLibrary()
                }
                true
            }
            AppRoute.Import -> {
                if (importJob?.isActive == true) cancelImport() else chooseGames()
                true
            }
            AppRoute.Library -> false
            AppRoute.Details -> {
                openLibrary()
                true
            }
            AppRoute.Player -> {
                if (uiState.playerState is PlayerState.Paused) resume() else exitPlayer()
                true
            }
            AppRoute.Settings -> {
                closeSettings()
                true
            }
            AppRoute.Recovery -> {
                openLibrary()
                true
            }
        }
    }

    fun openDetails(itemId: String) {
        if (uiState.library.any { it.id == itemId }) {
            uiState = uiState.copy(
                route = AppRoute.Details,
                selectedItemId = itemId,
                statusMessage = null,
            )
        }
    }

    fun retryScan(itemId: String) {
        val item = uiState.library.firstOrNull { it.id == itemId } ?: return
        val sourceUri = item.sourceUri
        if (sourceUri == null) {
            uiState = uiState.copy(statusMessage = "The original source is not retained; choose the game again to restore this copy.")
            return
        }
        uiState = uiState.copy(statusMessage = "Re-verifying the original source and restoring the private copy…")
        scope.launch(Dispatchers.IO) {
            try {
                when (repository.verifySource(contentResolver, item.id).status) {
                    SourceVerificationStatus.Matches -> Unit
                    SourceVerificationStatus.Changed -> {
                        withContext(Dispatchers.Main.immediate) {
                            if (!closed) uiState = uiState.copy(
                                statusMessage = "The original source changed after import. Nothing was overwritten; choose the current file as a new import if you want to keep it.",
                            )
                        }
                        return@launch
                    }
                    SourceVerificationStatus.Unavailable -> {
                        withContext(Dispatchers.Main.immediate) {
                            if (!closed) uiState = uiState.copy(
                                statusMessage = "The retained source could not be read. Nothing was overwritten; choose the file again to restore it.",
                            )
                        }
                        return@launch
                    }
                }
                repository.importSaf(
                    resolver = contentResolver,
                    uri = sourceUri,
                    displayName = item.sourceDisplayName,
                )
                val refreshedLibrary = repository.load()
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) uiState = uiState.copy(
                        library = refreshedLibrary,
                        statusMessage = "Verified private copy restored from the original source.",
                    )
                }
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) uiState = uiState.copy(
                        statusMessage = "Could not restore this copy: ${failure.message ?: "the source is unavailable"}",
                    )
                }
            }
        }
    }

    fun openPlayer(itemId: String) {
        if (closed || exitSavePending || uiState.playerSession != null) return
        val item = uiState.library.firstOrNull { it.id == itemId } ?: return
        cancelCheckpointWork()
        automaticCheckpoints = emptyList()
        automaticCheckpointsReady = false
        val launchToken = ++playerLaunchGeneration
        uiState = uiState.copy(
            route = AppRoute.Player,
            selectedItemId = item.id,
            playerState = PlayerState.Ready,
            saveStateSlots = emptyList(),
            saveStateSlotsReady = false,
            runtimeCapabilities = emptySet(),
            runtimeDiagnostics = null,
            statusMessage = "Verifying the private game copy…",
        )
        scope.launch(Dispatchers.IO) {
            val verified = runCatching { repository.verifyPrivateContent(item.id) }.getOrDefault(false)
            if (!verified) {
                withContext(Dispatchers.Main.immediate) {
                    if (isCurrentPlayerLaunch(launchToken)) {
                        uiState = uiState.copy(
                            route = AppRoute.Recovery,
                            recovery = RecoveryState(
                                title = "This private copy needs attention",
                                message = "The imported game file is missing or failed its SHA-256 integrity check. Restore it from the original source before launching.",
                                safeData = "The library record and original source were not changed. No unverified bytes were sent to the runtime.",
                                primaryAction = "Return to library",
                            ),
                        )
                    }
                }
                return@launch
            }

            runCatching { repository.markPlayed(item.id) }
            val refreshedLibrary = try {
                repository.load()
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (isCurrentPlayerLaunch(launchToken)) {
                        uiState = uiState.copy(
                            route = AppRoute.Recovery,
                            recovery = RecoveryState(
                                title = "The library could not be refreshed",
                                message = failure.message ?: "Storage became unavailable while preparing the session.",
                                safeData = "Your imported content and verified saves were not modified. Return to the library and try again when storage is available.",
                                primaryAction = "Return to library",
                            ),
                        )
                    }
                }
                return@launch
            }
            val saveStateSlots = item.coreId?.let { coreId ->
                runCatching {
                    repository.readSaveStateSlots(
                        itemId = item.id,
                        identity = LibraryRepository.CanonicalSaveIdentity(
                            contentSha256 = item.contentId,
                            coreId = coreId,
                            coreVersion = BUNDLED_CORE_VERSION,
                            stateFormatVersion = SAVE_STATE_FORMAT_VERSION,
                        ),
                    )
                }.getOrNull()
            }
            val history = saveIdentity(item)?.let { identity -> runCatching { repository.readAutomaticCheckpoints(item.id, identity) }.getOrNull() }
            withContext(Dispatchers.Main.immediate) {
                if (!isCurrentPlayerLaunch(launchToken)) return@withContext
                automaticCheckpoints = history.orEmpty()
                automaticCheckpointsReady = history != null
                uiState = uiState.copy(
                    library = refreshedLibrary,
                    saveStateSlots = saveStateSlots.orEmpty(),
                    saveStateSlotsReady = saveStateSlots != null,
                    statusMessage = "Checking runtime availability…",
                )
                sessionCoordinator.launch(
                    item = item,
                    onEvent = { event ->
                        if (isCurrentPlayerLaunch(launchToken)) handleSessionEvent(item, event)
                    },
                ) { result ->
                    if (!isCurrentPlayerLaunch(launchToken)) return@launch
                    uiState = when (result) {
                        LaunchResult.PreviewOnly -> uiState.copy(
                            playerState = PlayerState.Ready,
                            statusMessage = "This title has no connected runtime core; playback is unavailable.",
                        )

                        is LaunchResult.Connected -> uiState.copy(
                            playerState = PlayerState.Running,
                            playerSession = result.handle,
                            statusMessage = "Runtime session started.",
                        ).also {
                            uiState = it
                            autoResumeLatestState(result.handle)
                        }

                        is LaunchResult.Failed -> uiState.copy(
                            route = AppRoute.Recovery,
                            recovery = RecoveryState(
                                title = "Launch could not start",
                                message = result.reason,
                                safeData = "Your durable library record, imported copy, and original source were not changed.",
                                primaryAction = "Return to library",
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Restores the newest verified checkpoint without making it a requirement for launch. */
    private fun autoResumeLatestState(handle: dev.codex.libretroplatform.runtime.api.SessionHandle) {
        if (!uiState.preferences.autoResumeFromLatestState || uiState.playerSession != handle) return
        // Manual slots remain explicit user choices, never automatic recovery candidates.
        restoreAutomaticCheckpoint(id = null, automaticResume = true)
    }

    fun pause(reason: PauseReason = PauseReason.User, afterPause: (() -> Unit)? = null) {
        ensurePaused(reason) { paused ->
            if (paused) {
                requestAutomaticCheckpoint { afterPause?.invoke() }
            } else {
                // A rejected pause must still release lifecycle/exit waiters;
                // the serialized runtime command path can then decide whether
                // a native save is safe while the session remains running.
                afterPause?.invoke()
            }
        }
    }

    private fun ensurePaused(reason: PauseReason, onComplete: (Boolean) -> Unit) {
        val session = uiState.playerSession
        if (closed || session == null) { onComplete(false); return }
        if (!pauseInFlight && uiState.playerState is PlayerState.Paused) { onComplete(true); return }
        pauseWaiters += onComplete
        if (pauseInFlight) return
        pauseInFlight = true
        val launch = playerLaunchGeneration
        clearInputState()
        uiState = uiState.copy(statusMessage = "Pausing runtime session…")
        sessionCoordinator.command(SessionCommand.Pause) { commandResult ->
            if (closed || launch != playerLaunchGeneration || session != uiState.playerSession) return@command
            pauseInFlight = false
            val waiters = pauseWaiters.toList()
            pauseWaiters.clear()
            if (commandResult.accepted) {
                uiState = uiState.copy(
                    playerState = PlayerState.Paused(reason),
                    statusMessage = if (reason == PauseReason.Lifecycle) {
                        "Paused because the app went to the background. Resume explicitly when ready."
                    } else {
                        commandResult.message
                    },
                )
            } else {
                uiState = uiState.copy(
                    playerState = PlayerState.Running,
                    statusMessage = commandResult.message,
                )
            }
            waiters.forEach {
                if (closed || launch != playerLaunchGeneration || session != uiState.playerSession) return@forEach
                it(commandResult.accepted)
            }
        }
    }

    fun resume() {
        if (closed || exitSavePending || checkpointBusy || pauseInFlight) {
            uiState = uiState.copy(statusMessage = "Wait for the session save or pause to finish before resuming.")
            return
        }
        if (uiState.playerSession == null) {
            uiState = uiState.copy(
                statusMessage = "Playback cannot resume until a runtime session exists.",
            )
            return
        }
        uiState = uiState.copy(statusMessage = "Resuming runtime session…")
        sessionCoordinator.command(SessionCommand.Resume) { commandResult ->
            if (commandResult.accepted) {
                uiState = uiState.copy(
                    playerState = PlayerState.Running,
                    statusMessage = commandResult.message,
                )
            } else {
                uiState = uiState.copy(statusMessage = commandResult.message)
            }
        }
    }

    /** Serialize automatic and manual state transfers through the one runtime staging path. */
    private suspend fun awaitStateCommand(command: SessionCommand): RuntimeCommandPreview = suspendCancellableCoroutine { continuation ->
        sessionCoordinator.command(command) { result -> if (continuation.isActive) continuation.resume(result) }
    }

    private fun cancelCheckpointWork() {
        // Invalidate first: a cancelled coroutine's finally must not finish a newer transfer.
        stateTransfers.cancel()
        checkpointJob?.cancel()
        checkpointJob = null
        checkpointRefreshGeneration++
        pauseInFlight = false
        pauseWaiters.clear()
        // Cancellation drops the exit completion along with the other waiters.
        // A failed/closed session must not leave the next launch locked out.
        exitSavePending = false
        exitSaveFailure = null
        automaticCheckpoints = emptyList()
        automaticCheckpointsReady = false
    }

    /** Refreshes verified history for the pause overlay; stale refreshes cannot replace newer saves. */
    fun refreshAutomaticCheckpoints() {
        val item = selectedItem() ?: return
        val identity = saveIdentity(item) ?: return
        val launch = playerLaunchGeneration
        val refresh = ++checkpointRefreshGeneration
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { repository.readAutomaticCheckpoints(item.id, identity) } }
            if (closed || launch != playerLaunchGeneration || refresh != checkpointRefreshGeneration || selectedItem()?.id != item.id) return@launch
            automaticCheckpointsReady = result.isSuccess
            result.onSuccess { automaticCheckpoints = it }
                .onFailure { uiState = uiState.copy(statusMessage = "Checkpoint history could not be verified: ${it.message}") }
        }
    }

    /** Returns true for a safe no-op when automatic capture is disabled/unsupported. */
    fun requestAutomaticCheckpoint(onComplete: (Boolean) -> Unit = {}) {
        val item = selectedItem()
        val identity = item?.let(::saveIdentity)
        val session = uiState.playerSession
        val captureEnabled = uiState.preferences.autoSaveOnBackground &&
            dev.codex.libretroplatform.runtime.api.Capability.SaveState in uiState.runtimeCapabilities
        if (closed || item == null || identity == null || session == null) { onComplete(false); return }
        val launch = playerLaunchGeneration
        stateTransfers.requestAutomatic(start = { ticket ->
            launchStateTransfer(ticket, launch, session, "Automatic checkpoint failed") {
                // Even a disabled capture acts as an exit barrier for an active manual transfer.
                if (!captureEnabled) return@launchStateTransfer
                val thumbnail = captureVideoThumbnailPng()
                withContext(Dispatchers.IO) { repository.prepareSaveStateCapture(item.id, identity, RUNTIME_SAVE_STATE_STAGING_SLOT) }
                requireStateTransferSession(ticket, launch, session)
                val result = awaitStateCommand(SessionCommand.SaveState)
                if (!result.accepted) error(result.message)
                requireStateTransferSession(ticket, launch, session)
                val history = withContext(Dispatchers.IO) {
                    repository.commitAutomaticCheckpointFromStaging(item.id, identity, RUNTIME_SAVE_STATE_STAGING_SLOT, thumbnail)
                    repository.readAutomaticCheckpoints(item.id, identity)
                }
                requireStateTransferSession(ticket, launch, session)
                checkpointRefreshGeneration++
                automaticCheckpoints = history
                automaticCheckpointsReady = true
                uiState = uiState.copy(statusMessage = "Automatic checkpoint saved. Manual slots are unchanged.")
            }
        }, onComplete = onComplete)
    }

    fun restoreAutomaticCheckpoint(id: String) = restoreAutomaticCheckpoint(id, automaticResume = false)

    fun restoreAutomaticCheckpoint(slot: Int) {
        val checkpoint = automaticCheckpoints.firstOrNull { it.slot == slot } ?: return
        restoreAutomaticCheckpoint(checkpoint.id)
    }

    private fun restoreAutomaticCheckpoint(id: String?, automaticResume: Boolean) {
        val item = selectedItem() ?: return
        val identity = saveIdentity(item) ?: return
        val session = uiState.playerSession ?: return
        if (closed || exitSavePending || pauseInFlight) return
        val ticket = beginManualStateTransfer() ?: return
        val launch = playerLaunchGeneration
        launchStateTransfer(ticket, launch, session, "Checkpoint could not be restored") {
            val (restored, history) = withContext(Dispatchers.IO) {
                repository.stageAutomaticCheckpointForRuntime(item.id, identity, RUNTIME_SAVE_STATE_STAGING_SLOT, id) to
                    repository.readAutomaticCheckpoints(item.id, identity)
            }
            requireStateTransferSession(ticket, launch, session)
            checkpointRefreshGeneration++
            automaticCheckpoints = history
            automaticCheckpointsReady = true
            if (restored == null) {
                if (!automaticResume) error("This checkpoint is no longer available or did not pass verification")
                uiState = uiState.copy(statusMessage = "No compatible automatic checkpoint; starting normally. Manual slots are unchanged.")
                return@launchStateTransfer
            }
            val result = awaitStateCommand(SessionCommand.LoadState)
            requireStateTransferSession(ticket, launch, session)
            if (!result.accepted) error(result.message)
            uiState = uiState.copy(statusMessage = "Automatic checkpoint restored (${restored.payloadSizeBytes} bytes).")
        }
    }

    private fun beginManualStateTransfer(): Long? {
        if (closed || exitSavePending || pauseInFlight) return null
        return stateTransfers.beginManual().also {
            if (it == null) uiState = uiState.copy(statusMessage = "Please wait for the current save-state transfer to finish.")
        }
    }

    private fun requireStateTransferSession(ticket: Long, launch: Long, session: dev.codex.libretroplatform.runtime.api.SessionHandle) {
        if (closed || launch != playerLaunchGeneration || session != uiState.playerSession || !stateTransfers.isCurrent(ticket)) {
            throw CancellationException("The state transfer belongs to a previous session")
        }
    }

    private fun launchStateTransfer(
        ticket: Long,
        launch: Long,
        session: dev.codex.libretroplatform.runtime.api.SessionHandle,
        failureMessage: String,
        operation: suspend () -> Unit,
    ) {
        // LAZY avoids an immediately-completed job overwriting the queued transfer's job reference.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var success = false
            try {
                requireStateTransferSession(ticket, launch, session)
                operation()
                success = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (!closed && launch == playerLaunchGeneration && session == uiState.playerSession) {
                    uiState = uiState.copy(statusMessage = "$failureMessage: ${failure.message ?: "storage unavailable"}. Earlier verified saves are safe.")
                }
            } finally {
                if (stateTransfers.isCurrent(ticket)) {
                    checkpointJob = null
                    stateTransfers.finish(ticket, success)
                }
            }
        }
        checkpointJob = job
        job.start()
    }

    fun saveNative(onComplete: (Boolean) -> Unit = {}) {
        val item = selectedItem()
        val identity = item?.let(::saveIdentity)
        if (item == null || identity == null || uiState.playerSession == null) {
            uiState = uiState.copy(statusMessage = "There is no active title to save.")
            onComplete(false)
            return
        }
        if (nativeSaveOperationInFlight) {
            nativeSaveWaiters += onComplete
            uiState = uiState.copy(statusMessage = "A native save is already being verified…")
            return
        }
        val session = uiState.playerSession ?: run {
            onComplete(false)
            return
        }
        val launchToken = playerLaunchGeneration
        val operationGeneration = ++nativeSaveOperationGeneration
        nativeSaveOperationInFlight = true
        val completeSave: (Boolean) -> Unit = { success ->
            if (operationGeneration == nativeSaveOperationGeneration) {
                nativeSaveOperationInFlight = false
                val waiters = nativeSaveWaiters.toList()
                nativeSaveWaiters.clear()
                (listOf(onComplete) + waiters).forEach { waiter ->
                    // An earlier waiter may close/fail the session. Starting a
                    // new save in the same live session does not invalidate the
                    // other callers waiting for this completed save's result.
                    if (closed || launchToken != playerLaunchGeneration || uiState.playerSession != session) return@forEach
                    waiter(success)
                }
            }
        }
        sessionCoordinator.command(SessionCommand.SaveNative) { commandResult ->
            if (operationGeneration != nativeSaveOperationGeneration) return@command
            if (commandResult.unsupportedCapability == dev.codex.libretroplatform.runtime.api.Capability.BatterySave) {
                // ROM-only cartridges have no battery memory. This is a safe
                // no-op, unlike a rejected write or failed repository commit.
                uiState = uiState.copy(statusMessage = "This game has no battery-save data.")
                completeSave(true)
                return@command
            }
            if (!commandResult.accepted) {
                uiState = uiState.copy(statusMessage = commandResult.message)
                completeSave(false)
                return@command
            }
            scope.launch(Dispatchers.IO) {
                try {
                    repository.commitNativeSaveFromStaging(item.id, identity)
                    val refreshedLibrary = repository.load()
                    withContext(Dispatchers.Main.immediate) {
                        if (closed || !isCurrentPlayerLaunch(launchToken) || uiState.playerSession != session) {
                            completeSave(false)
                            return@withContext
                        }
                        uiState = uiState.copy(
                            library = refreshedLibrary,
                            statusMessage = "Native battery data verified and committed.",
                        )
                        completeSave(true)
                    }
                } catch (failure: Exception) {
                    withContext(Dispatchers.Main.immediate) {
                        if (closed || !isCurrentPlayerLaunch(launchToken) || uiState.playerSession != session) {
                            completeSave(false)
                            return@withContext
                        }
                        uiState = uiState.copy(
                            statusMessage = "The runtime returned a save, but verification failed: ${failure.message ?: "unknown error"}",
                        )
                        completeSave(false)
                    }
                }
            }
        }
    }

    fun saveState() {
        val item = selectedItem()
        val identity = item?.let(::saveIdentity)
        if (item == null || identity == null || uiState.playerSession == null) {
            uiState = uiState.copy(statusMessage = "There is no active title to save.")
            return
        }
        val ticket = beginManualStateTransfer() ?: return
        val session = uiState.playerSession ?: return
        val launch = playerLaunchGeneration
        val slotNumber = uiState.saveStateSlot
        val slot = saveStateSlotId(slotNumber)
        launchStateTransfer(ticket, launch, session, "Manual save state failed") {
            val thumbnailPng = captureVideoThumbnailPng()
            withContext(Dispatchers.IO) { repository.prepareSaveStateCapture(item.id, identity, RUNTIME_SAVE_STATE_STAGING_SLOT) }
            requireStateTransferSession(ticket, launch, session)
            val result = awaitStateCommand(SessionCommand.SaveState)
            if (!result.accepted) error(result.message)
            requireStateTransferSession(ticket, launch, session)
            val (library, slots) = withContext(Dispatchers.IO) {
                repository.commitSaveStateFromStaging(item.id, identity, slot, RUNTIME_SAVE_STATE_STAGING_SLOT, thumbnailPng)
                repository.load() to repository.readSaveStateSlots(item.id, identity)
            }
            requireStateTransferSession(ticket, launch, session)
            uiState = uiState.copy(library = library, saveStateSlots = slots, saveStateSlotsReady = true,
                statusMessage = "Save state verified and committed to slot $slotNumber.")
        }
    }

    fun loadState() {
        val item = selectedItem()
        val identity = item?.let(::saveIdentity)
        if (item == null || identity == null || uiState.playerSession == null) {
            uiState = uiState.copy(statusMessage = "There is no active title to load.")
            return
        }
        val ticket = beginManualStateTransfer() ?: return
        val session = uiState.playerSession ?: return
        val launch = playerLaunchGeneration
        val slotNumber = uiState.saveStateSlot
        val slot = saveStateSlotId(slotNumber)
        uiState = uiState.copy(statusMessage = "Preparing verified save state…")
        launchStateTransfer(ticket, launch, session, "Manual save state could not be loaded") {
            val staged = withContext(Dispatchers.IO) {
                repository.stageSaveStateForRuntime(
                    item.id,
                    identity,
                    slot = slot,
                    stagingSlot = RUNTIME_SAVE_STATE_STAGING_SLOT,
                )
            }
            requireStateTransferSession(ticket, launch, session)
            check(staged) { "No verified save state exists in slot $slotNumber" }
            val result = awaitStateCommand(SessionCommand.LoadState)
            requireStateTransferSession(ticket, launch, session)
            if (!result.accepted) error(result.message)
            uiState = uiState.copy(statusMessage = "Save state loaded from slot $slotNumber.")
        }
    }

    fun suggestedSaveFileName(state: Boolean): String {
        val title = selectedItem()?.title
            ?.replace(Regex("[^A-Za-z0-9._-]+"), "-")
            ?.trim('-')
            ?.ifBlank { null }
            ?: "game"
        return "$title.${if (state) "state-slot-${uiState.saveStateSlot}" else "sav"}"
    }

    /** Exports a verified save to a user-selected SAF destination. */
    fun exportNativeSave(targetUri: Uri) {
        val item = selectedItem()
        val identity = item?.let(::saveIdentity)
        if (item == null || identity == null) {
            uiState = uiState.copy(statusMessage = "There is no verified native save to export.")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val exported = repository.exportNativeSave(
                    itemId = item.id,
                    identity = identity,
                    target = SafSaveExportTarget(contentResolver, targetUri),
                )
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) uiState = uiState.copy(
                        statusMessage = "Native save exported and verified (${exported.payloadSizeBytes} bytes).",
                    )
                }
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) uiState = uiState.copy(
                        statusMessage = "Native save export failed: ${failure.message ?: "the destination could not be verified"}",
                    )
                }
            }
        }
    }

    /** Exports a verified slot payload to a user-selected SAF destination. */
    fun exportSaveState(targetUri: Uri) {
        val item = selectedItem()
        val identity = item?.let(::saveIdentity)
        if (item == null || identity == null) {
            uiState = uiState.copy(statusMessage = "There is no verified save state to export.")
            return
        }
        val slotNumber = uiState.saveStateSlot
        val slot = saveStateSlotId(slotNumber)
        scope.launch(Dispatchers.IO) {
            try {
                val exported = repository.exportSaveState(
                    itemId = item.id,
                    identity = identity,
                    slot = slot,
                    target = SafSaveExportTarget(contentResolver, targetUri),
                )
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) uiState = uiState.copy(
                        statusMessage = "Save state exported and verified (${exported.payloadSizeBytes} bytes).",
                    )
                }
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) uiState = uiState.copy(
                        statusMessage = "Save-state export failed: ${failure.message ?: "the destination could not be verified"}",
                    )
                }
            }
        }
    }

    fun suggestedBackupFileName(): String = "libretro-platform-backup-v1.zip"

    /** Exports settings, metadata, saves, and states without copying game binaries. */
    fun exportPortableBackup(targetUri: Uri) {
        val store = backupStore ?: run {
            uiState = uiState.copy(statusMessage = "Portable backup is unavailable in this build.")
            return
        }
        val preferences = uiState.preferences
        scope.launch(Dispatchers.IO) {
            try {
                val summary = contentResolver.openOutputStream(targetUri)?.use { output ->
                    store.export(output, preferences)
                } ?: error("the selected destination could not be opened")
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) uiState = uiState.copy(
                        statusMessage = "Backup exported: ${summary.libraryRecords} title(s), ${summary.nativeSaves + summary.saveStates} manual save file(s), and ${summary.automaticCheckpoints} automatic file(s). Game binaries were not included.",
                    )
                }
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) uiState = uiState.copy(
                        statusMessage = "Backup export failed: ${failure.message ?: "the destination could not be verified"}",
                    )
                }
            }
        }
    }

    /** Restores only a validated portable bundle; importing while playing is blocked. */
    fun importPortableBackup(sourceUri: Uri) {
        val store = backupStore ?: run {
            uiState = uiState.copy(statusMessage = "Portable restore is unavailable in this build.")
            return
        }
        if (uiState.playerSession != null) {
            uiState = uiState.copy(statusMessage = "Exit the player before restoring a backup.")
            return
        }
        uiState = uiState.copy(statusMessage = "Validating backup before changing local data…")
        scope.launch(Dispatchers.IO) {
            try {
                val restored = contentResolver.openInputStream(sourceUri)?.use(store::restore)
                    ?: error("the selected backup could not be opened")
                preferencesStore.save(restored.preferences)
                val refreshedLibrary = repository.load()
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) uiState = uiState.copy(
                        library = refreshedLibrary,
                        preferences = restored.preferences,
                        statusMessage = "Backup restored: ${restored.libraryRecords} title record(s), ${restored.nativeSaves + restored.saveStates} manual save file(s), and ${restored.automaticCheckpoints} automatic file(s). Game binaries remain untouched.",
                    )
                }
            } catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (!closed) uiState = uiState.copy(
                        statusMessage = "Backup rejected safely: ${failure.message ?: "the bundle is invalid"}",
                    )
                }
            }
        }
    }

    fun sendInput(button: Button, pressed: Boolean) {
        if (pressed) pressedInputs += button else pressedInputs -= button
        dispatchInput()
    }

    fun sendAnalog(axis: AnalogAxis, value: Float) {
        analogInputs[axis] = value.coerceIn(-1f, 1f)
        dispatchInput()
    }

    fun sendAnalogSnapshot(values: Map<AnalogAxis, Float>) {
        values.forEach { (axis, value) -> analogInputs[axis] = value.coerceIn(-1f, 1f) }
        dispatchInput()
    }

    fun clearInputState() {
        val hadInput = pressedInputs.isNotEmpty() || analogInputs.values.any { it != 0f }
        pressedInputs.clear()
        analogInputs.clear()
        if (hadInput) dispatchInput()
    }

    fun setVideoThumbnailProvider(provider: () -> Bitmap?) {
        videoThumbnailProvider = provider
    }

    fun clearVideoThumbnailProvider() {
        videoThumbnailProvider = null
    }

    private fun captureVideoThumbnailPng(): ByteArray? {
        val bitmap = runCatching { videoThumbnailProvider?.invoke() }.getOrNull() ?: return null
        return try {
            val width = bitmap.width.coerceAtMost(320)
            val height = (bitmap.height.toFloat() * width / bitmap.width).roundToInt().coerceAtLeast(1)
            val thumbnail = if (width == bitmap.width && height == bitmap.height) {
                bitmap
            } else {
                bitmap.scale(width, height, filter = true)
            }
            try {
                ByteArrayOutputStream().use { output ->
                    if (!thumbnail.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
                    output.toByteArray()
                }
            } finally {
                if (thumbnail !== bitmap && !thumbnail.isRecycled) thumbnail.recycle()
            }
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun dispatchInput() {
        if (uiState.playerSession == null) return
        sessionCoordinator.input(pressedInputs.toSet(), analogInputs.toMap()) { commandResult ->
            if (!commandResult.accepted) {
                uiState = uiState.copy(statusMessage = commandResult.message)
            }
        }
    }

    fun attachSurface(surface: Surface) {
        sessionCoordinator.attachSurface(surface) { result ->
            if (!result.accepted) uiState = uiState.copy(statusMessage = result.message)
        }
    }

    fun detachSurface() {
        sessionCoordinator.detachSurface()
    }

    fun openRecovery() {
        uiState = uiState.copy(
            route = AppRoute.Recovery,
            recovery = RecoveryState(
                title = "Runtime recovery and limits",
                message = "Selected content is copied through ContentImporter into app-private verified storage. Bundled cores can play supported systems, and save payloads are staged through the native runtime before transactional repository verification.",
                safeData = "Imported copies, content identities, library records, and any verified repository saves are preserved. Original SAF sources are never modified."
                    ,
                primaryAction = "Return to library",
            ),
        )
    }

    fun exitPlayer() {
        if (exitSavePending) return
        exitSaveFailure = null
        val sessionIsLive = uiState.playerSession != null && uiState.playerState != PlayerState.Ready
        if (sessionIsLive) {
            val session = uiState.playerSession
            val launch = playerLaunchGeneration
            exitSavePending = true
            uiState = uiState.copy(statusMessage = "Saving your session…")
            val saveAndExit = {
                requestAutomaticCheckpoint { checkpointSaved ->
                    if (closed || launch != playerLaunchGeneration || session != uiState.playerSession) return@requestAutomaticCheckpoint
                    if (!checkpointSaved) {
                        // Automatic history is best-effort. A corrupt or
                        // unsupported checkpoint must not strand the user in
                        // the player or prevent the canonical battery save.
                        uiState = uiState.copy(statusMessage = "Automatic checkpoint unavailable; verifying the native save…")
                    }
                    saveFreshNativeForExit(
                        transferInFlight = nativeSaveOperationInFlight,
                        save = ::saveNative,
                        isSessionCurrent = {
                            !closed && launch == playerLaunchGeneration && session == uiState.playerSession
                        },
                    ) { nativeSaveSucceeded ->
                        exitSavePending = false
                        if (!nativeSaveSucceeded) {
                            exitSaveFailure = uiState.statusMessage ?: "The battery save could not be verified."
                        } else {
                            finishExitPlayer()
                        }
                    }
                }
            }
            // pause() also captures a checkpoint. Using it here followed by
            // saveAndExit would rotate history twice for one exit action.
            ensurePaused(PauseReason.User) { paused ->
                if (paused) {
                    saveAndExit()
                } else {
                    exitSavePending = false
                    exitSaveFailure = uiState.statusMessage ?: "The session could not be paused for a safe save."
                }
            }
            return
        }
        finishExitPlayer()
    }

    fun dismissExitSaveFailure() {
        exitSaveFailure = null
    }

    /** Only the explicit failure-dialog action may discard a live unsaved session. */
    fun exitWithoutSaving() {
        if (exitSaveFailure == null || exitSavePending) return
        finishExitPlayer()
    }

    private fun finishExitPlayer() {
        playerLaunchGeneration++
        cancelCheckpointWork()
        clearVideoThumbnailProvider()
        clearInputState()
        nativeSaveOperationGeneration++
        nativeSaveOperationInFlight = false
        nativeSaveWaiters.clear()
        sessionCoordinator.clearSession()
        uiState = uiState.copy(
            route = AppRoute.Library,
            playerState = PlayerState.Ready,
            playerSession = null,
            saveStateSlots = emptyList(),
            saveStateSlotsReady = false,
            statusMessage = null,
        )
    }

    private fun releasePlayerSession() {
        playerLaunchGeneration++
        cancelCheckpointWork()
        clearVideoThumbnailProvider()
        nativeSaveOperationGeneration++
        nativeSaveOperationInFlight = false
        nativeSaveWaiters.clear()
        uiState = uiState.copy(
            playerState = PlayerState.Ready,
            playerSession = null,
            saveStateSlots = emptyList(),
            saveStateSlotsReady = false,
            runtimeCapabilities = emptySet(),
            runtimeDiagnostics = null,
        )
        clearInputState()
        sessionCoordinator.clearSession()
    }

    private fun handleSessionEvent(item: LibraryItem, event: SessionEvent) {
        if (uiState.route != AppRoute.Player || uiState.selectedItemId != item.id) return
        when (event) {
            is SessionEvent.CapabilitySnapshot -> {
                uiState = uiState.copy(runtimeCapabilities = event.capabilities)
            }

            is SessionEvent.DiagnosticsSnapshot -> {
                uiState = uiState.copy(runtimeDiagnostics = event.diagnostics)
            }

            is SessionEvent.StateChanged -> when (event.state) {
                SessionState.Running -> uiState = uiState.copy(playerState = PlayerState.Running)
                SessionState.Paused -> {
                    val reason = (uiState.playerState as? PlayerState.Paused)?.reason ?: PauseReason.User
                    uiState = uiState.copy(playerState = PlayerState.Paused(reason))
                }
                SessionState.Failed -> handleSessionFailure(item, "The runtime session stopped unexpectedly.")
                SessionState.Created, SessionState.Closing, SessionState.Closed -> Unit
            }

            is SessionEvent.Failure -> handleSessionFailure(item, event.message)
        }
    }

    private fun handleSessionFailure(item: LibraryItem, message: String) {
        cancelCheckpointWork()
        clearVideoThumbnailProvider()
        uiState = uiState.copy(
            route = AppRoute.Recovery,
            playerState = PlayerState.Ready,
            playerSession = null,
            runtimeCapabilities = emptySet(),
            runtimeDiagnostics = null,
            recovery = RecoveryState(
                title = "Runtime session stopped",
                message = message,
                safeData = "Your imported content and verified saves were not discarded. The session will be closed before returning to the library.",
                primaryAction = "Return to library",
            ),
        )
        nativeSaveOperationGeneration++
        nativeSaveOperationInFlight = false
        nativeSaveWaiters.clear()
        clearInputState()
        sessionCoordinator.clearSession()
    }

    fun removeSelectedItem() {
        val itemId = uiState.selectedItemId ?: return
        uiState = uiState.copy(statusMessage = "Removing app-private content…")
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                repository.remove(itemId)
                repository.load()
            }
            withContext(Dispatchers.Main.immediate) {
                if (closed) return@withContext
                result.fold(
                    onSuccess = { refreshedLibrary ->
                        // A deleted title must not remain as a dangling favorite. Keep
                        // the preference store and the visible state in the same
                        // transaction from the user's point of view.
                        updatePreferences { preferences ->
                            preferences.copy(favorites = preferences.favorites - itemId)
                        }
                        uiState = uiState.copy(
                            route = AppRoute.Library,
                            library = refreshedLibrary,
                            selectedItemId = null,
                            statusMessage = "Removed the app-private content copy and library record. The original source was not changed.",
                        )
                    },
                    onFailure = { failure ->
                        uiState = uiState.copy(
                            statusMessage = failure.message ?: "The library item could not be removed.",
                        )
                    },
                )
            }
        }
    }

    fun onLifecycleStopped() {
        if (uiState.route == AppRoute.Player &&
            uiState.playerState == PlayerState.Running &&
            uiState.playerSession != null
        ) {
            pause(PauseReason.Lifecycle) {
                if (uiState.preferences.autoSaveOnBackground && !exitSavePending) {
                    saveNative()
                }
            }
        }
    }

    fun selectedItem(): LibraryItem? = uiState.library.firstOrNull { it.id == uiState.selectedItemId }

    fun effectivePreferences(item: LibraryItem?): UserPreferences {
        val global = uiState.preferences
        val override = item?.titlePreferences ?: return global
        return global.copy(
            playerPresentation = override.playerPresentation ?: global.playerPresentation,
            displayPreset = override.displayPreset ?: global.displayPreset,
            playerDisplaySize = override.playerDisplaySize ?: global.playerDisplaySize,
            screenTreatment = override.screenTreatment ?: global.screenTreatment,
            pixelScalingMode = override.pixelScalingMode ?: global.pixelScalingMode,
            touchLayoutSize = override.touchLayoutSize ?: global.touchLayoutSize,
            touchReachMode = override.touchReachMode ?: global.touchReachMode,
            touchLayout = override.touchLayout ?: global.touchLayout,
            audioVolume = override.audioVolume ?: global.audioVolume,
            audioMuted = override.audioMuted ?: global.audioMuted,
            controlsOpacity = override.controlsOpacity ?: global.controlsOpacity,
            leftHanded = override.leftHanded ?: global.leftHanded,
            showButtonLabels = override.showButtonLabels ?: global.showButtonLabels,
            hapticsEnabled = override.hapticsEnabled ?: global.hapticsEnabled,
        )
    }

    fun saveCurrentPreferencesForTitle() {
        val item = selectedItem() ?: return
        val current = uiState.preferences
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                repository.updateTitlePreferences(
                    item.id,
                    TitlePlayerPreferences(
                        playerPresentation = current.playerPresentation,
                        displayPreset = current.displayPreset,
                        playerDisplaySize = current.playerDisplaySize,
                        screenTreatment = current.screenTreatment,
                        pixelScalingMode = current.pixelScalingMode,
                        touchLayoutSize = current.touchLayoutSize,
                        touchReachMode = current.touchReachMode,
                        touchLayout = current.touchLayout,
                        audioVolume = current.audioVolume,
                        audioMuted = current.audioMuted,
                        controlsOpacity = current.controlsOpacity,
                        leftHanded = current.leftHanded,
                        showButtonLabels = current.showButtonLabels,
                        hapticsEnabled = current.hapticsEnabled,
                    ),
                )
            }
            withContext(Dispatchers.Main.immediate) {
                if (closed) return@withContext
                result.fold(
                    onSuccess = { updated ->
                        uiState = uiState.copy(
                            library = uiState.library.map { currentItem -> if (currentItem.id == updated.id) updated else currentItem },
                            statusMessage = "Current presentation settings saved for ${updated.displayTitle}.",
                        )
                    },
                    onFailure = { failure -> uiState = uiState.copy(statusMessage = "Could not save title settings: ${failure.message ?: "storage is unavailable"}") },
                )
            }
        }
    }

    fun clearTitlePreferences() {
        val item = selectedItem() ?: return
        scope.launch(Dispatchers.IO) {
            val result = runCatching { repository.updateTitlePreferences(item.id, null) }
            withContext(Dispatchers.Main.immediate) {
                if (closed) return@withContext
                result.fold(
                    onSuccess = { updated ->
                        uiState = uiState.copy(
                            library = uiState.library.map { currentItem -> if (currentItem.id == updated.id) updated else currentItem },
                            statusMessage = "Title-specific settings cleared; global defaults are active.",
                        )
                    },
                    onFailure = { failure -> uiState = uiState.copy(statusMessage = "Could not clear title settings: ${failure.message ?: "storage is unavailable"}") },
                )
            }
        }
    }

    private fun saveIdentity(item: LibraryItem): LibraryRepository.CanonicalSaveIdentity? {
        val coreId = item.coreId ?: return null
        return LibraryRepository.CanonicalSaveIdentity(
            contentSha256 = item.contentId,
            coreId = coreId,
            coreVersion = BUNDLED_CORE_VERSION,
            stateFormatVersion = SAVE_STATE_FORMAT_VERSION,
        )
    }

    fun clearStatus() {
        uiState = uiState.copy(statusMessage = null)
    }

    fun close() {
        if (closed) return
        closed = true
        cancelCheckpointWork()
        exitSavePending = false
        playerLaunchGeneration++
        libraryRefreshGeneration++
        libraryRefreshJob?.cancel()
        clearInputState()
        importCancellationSignal?.cancel()
        importJob?.cancel()
        importJob = null
        folderScanJob?.cancel()
        folderScanJob = null
        folderScanCancellationSignal?.cancel()
        folderScanCancellationSignal = null
        nativeSaveOperationGeneration++
        nativeSaveOperationInFlight = false
        nativeSaveWaiters.clear()
        sessionCoordinator.clearSession()
    }

    companion object {
        private const val MIN_SAVE_STATE_SLOT = 1
        private const val MAX_SAVE_STATE_SLOT = 9
        private const val SAVE_STATE_FORMAT_VERSION = 1
        private const val BUNDLED_CORE_VERSION = "bundled-v1"
        private const val MAX_FOLDER_FILES = 2000
    }

    private var playerLaunchGeneration = 0L

    private fun saveStateSlotId(slot: Int): String = "slot-${slot.coerceIn(MIN_SAVE_STATE_SLOT, MAX_SAVE_STATE_SLOT)}"

    private fun isCurrentPlayerLaunch(token: Long): Boolean =
        !closed && token == playerLaunchGeneration && uiState.route == AppRoute.Player
}
