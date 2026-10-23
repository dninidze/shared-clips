package app.sharedclips

import android.app.Application
import android.app.PendingIntent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sharedclips.action.MediaMover
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.CleanupMode
import app.sharedclips.data.CleanupSettings
import app.sharedclips.data.MediaStoreScanner
import app.sharedclips.data.ScannedVideo
import app.sharedclips.work.CleanupScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeState(
    val scanning: Boolean = false,
    val videos: List<ScannedVideo> = emptyList(),
    val message: String? = null,
    val mode: CleanupMode = CleanupMode.MOVE_TO_FOLDER,
    val autoMove: Boolean = false,
    val deleteAfterDays: Int = 0,
    val canManageMedia: Boolean = false,
    val hasAllFilesAccess: Boolean = false,
    val log: List<String> = emptyList(),
    val versionName: String = "",
) {
    val totalBytes: Long get() = videos.sumOf { it.sizeBytes }
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val scanner = MediaStoreScanner(app)
    private val mover = MediaMover(app)
    private val settings = CleanupSettings(app)

    private val _state = MutableStateFlow(
        HomeState(
            mode = settings.mode,
            autoMove = settings.autoMove,
            deleteAfterDays = settings.deleteAfterDays,
            canManageMedia = mover.canManageMedia(),
            hasAllFilesAccess = mover.hasAllFilesAccess(),
            log = ActivityLog.read(app),
            versionName = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }
                .getOrNull().orEmpty(),
        )
    )

    /** The videos a move or delete request was made for, so the approved set is what gets moved. */
    private var requested: List<Uri> = emptyList()
    private var scanJob: Job? = null
    val state: StateFlow<HomeState> = _state

    init {
        CleanupScheduler.apply(app)
    }

    fun setAutoMove(enabled: Boolean) {
        settings.autoMove = enabled
        _state.update { it.copy(autoMove = enabled) }
        CleanupScheduler.apply(getApplication())
    }

    fun setDeleteAfterDays(days: Int) {
        settings.deleteAfterDays = days
        _state.update { it.copy(deleteAfterDays = days) }
        CleanupScheduler.apply(getApplication())
    }

    /** Re-reads "Media management" and "All files access", which the user changes in system settings. */
    fun refreshAccess() {
        _state.update { it.copy(canManageMedia = mover.canManageMedia(), hasAllFilesAccess = mover.hasAllFilesAccess()) }
    }

    fun setMode(mode: CleanupMode) {
        settings.mode = mode
        _state.update { it.copy(mode = mode) }
    }

    /** Rescans Camera. A newer scan replaces one still running, so an old result never wins. */
    fun scan(clearMessage: Boolean = true) {
        _state.update { it.copy(scanning = true, message = if (clearMessage) null else it.message) }
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val found = scanner.scanCameraFolder()
            _state.update { it.copy(scanning = false, videos = found) }
        }
    }

    /**
     * Rescans, so "finished saving" is judged now rather than at the last scan, then moves or deletes
     * the videos that have finished saving: directly with All files access, otherwise through the
     * system request that [launchRequest] shows. One still being written is left for the next tap.
     */
    fun cleanUp(launchRequest: (PendingIntent) -> Unit) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true) }
            val found = scanner.scanCameraFolder()
            val (ready, saving) = found.partition { it.isSettled() }
            _state.update {
                it.copy(
                    scanning = false,
                    videos = found,
                    message = if (saving.isEmpty()) null else "${saving.size} video(s) still saving, skipped for now",
                )
            }
            requested = ready.map { it.uri }
            if (requested.isEmpty()) return@launch
            val mode = _state.value.mode
            if (mover.hasAllFilesAccess()) {
                when (mode) {
                    CleanupMode.MOVE_TO_FOLDER -> onMoveApproved()
                    CleanupMode.DELETE -> onTrashApproved(mover.trashDirectly(requested))
                }
            } else {
                val request = when (mode) {
                    CleanupMode.MOVE_TO_FOLDER -> mover.writeRequest(requested)
                    CleanupMode.DELETE -> mover.trashRequest(requested)
                }
                request?.let(launchRequest)
            }
        }
    }

    fun refreshLog() {
        _state.update { it.copy(log = ActivityLog.read(getApplication())) }
    }

    fun clearLog() {
        ActivityLog.clear(getApplication())
        refreshLog()
    }

    /** Called after the user approved the write request, or directly with All files access. */
    fun onMoveApproved() {
        viewModelScope.launch {
            val moved = mover.moveOutOfCamera(requested)
            val left = scanner.stillInCamera(requested)
            ActivityLog.add(getApplication(), "Home screen move: moved $moved, $left left in Camera")
            _state.update { it.copy(message = "Moved $moved videos to TikTok Downloads") }
            refreshLog()
            scan(clearMessage = false)
        }
    }

    fun onTrashApproved(trashed: Int = requested.size) {
        ActivityLog.add(getApplication(), "Home screen delete: trashed $trashed")
        _state.update { it.copy(message = "Moved $trashed videos to trash") }
        refreshLog()
        scan(clearMessage = false)
    }
}
