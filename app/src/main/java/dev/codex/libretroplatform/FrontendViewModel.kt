package dev.codex.libretroplatform

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.codex.libretroplatform.runtime.host.NativeEmulationEngine
import dev.codex.libretroplatform.runtime.host.SurfaceBindable

/** Retains the controller across Activity recreation and cancels active imports on teardown. */
class FrontendViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = NativeEmulationEngine(application)
    private val repository = LibraryRepository.fromContext(application)
    private val backupStore = PortableBackupStore(application.filesDir.toPath())
    val controller = FrontendController(
        repository = repository,
        contentResolver = application.contentResolver,
        scope = viewModelScope,
        preferencesStore = SharedPreferencesUserPreferencesStore(application),
        backupStore = backupStore,
        collectionStore = FileLibraryCollectionStore(application.filesDir.toPath().resolve("library/collections")),
        systemFileStore = FileSystemFileStore(application.filesDir.toPath().resolve("runtime/system/user-files")),
        sessionCoordinator = EmulationSessionCoordinator(
            engine = engine,
            scope = viewModelScope,
            surfaceBindable = engine as SurfaceBindable,
            nativeSaveStagingPathFor = { item ->
                item.coreId?.let { coreId -> repository.nativeSaveStagingPath(item.id, coreId).toString() }
            },
            nativeSaveRestorePreparedFor = { item ->
                val coreId = item.coreId
                if (coreId == null) {
                    false
                } else {
                    repository.stageNativeSaveForRuntime(
                        item.id,
                        LibraryRepository.CanonicalSaveIdentity(
                            contentSha256 = item.contentId,
                            coreId = coreId,
                            coreVersion = "bundled-v1",
                            stateFormatVersion = 1,
                        ),
                    )
                }
            },
            saveStateStagingPathFor = { item ->
                item.coreId?.let { coreId -> repository.saveStateStagingPath(item.id, coreId, RUNTIME_SAVE_STATE_STAGING_SLOT).toString() }
            },
        ),
    )

    override fun onCleared() {
        controller.close()
        engine.shutdown()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(FrontendViewModel::class.java))
            return FrontendViewModel(application) as T
        }
    }
}
