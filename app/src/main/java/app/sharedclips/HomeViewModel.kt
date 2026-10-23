package app.sharedclips

import android.app.Application
import android.app.PendingIntent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.sharedclips.action.MediaMover
import app.sharedclips.data.CleanupMode
import app.sharedclips.data.CleanupSettings
import app.sharedclips.data.MediaStoreScanner
import app.sharedclips.data.ScannedVideo
import app.sharedclips.work.CleanupScheduler
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
        )
    )
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

    /** Re-reads "Media management" access, which the user changes in system settings. */
    fun refreshAccess() {
        _state.update { it.copy(canManageMedia = mover.canManageMedia()) }
    }

    fun setMode(mode: CleanupMode) {
        settings.mode = mode
        _state.update { it.copy(mode = mode) }
    }

    fun scan() {
        _state.update { it.copy(scanning = true, message = null) }
        viewModelScope.launch {
            val found = scanner.scanCameraFolder()
            _state.update { it.copy(scanning = false, videos = found) }
        }
    }

    fun moveRequest(): PendingIntent? = mover.writeRequest(_state.value.videos.map { it.uri })

    fun trashRequest(): PendingIntent? = mover.trashRequest(_state.value.videos.map { it.uri })

    /** Called after the user approved the write request. */
    fun onMoveApproved() {
        viewModelScope.launch {
            val moved = mover.moveOutOfCamera(_state.value.videos.map { it.uri })
            _state.update { it.copy(message = "Moved $moved videos to TikTok Downloads") }
            scan()
        }
    }

    fun onTrashApproved() {
        _state.update { it.copy(message = "Moved ${it.videos.size} videos to trash") }
        scan()
    }
}
