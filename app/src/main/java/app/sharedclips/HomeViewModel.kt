package app.sharedclips

import android.app.Application
import android.app.PendingIntent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.sharedclips.action.MediaMover
import app.sharedclips.data.ActivityLog
import app.sharedclips.data.AppAccess
import app.sharedclips.data.CleanupMode
import app.sharedclips.data.CleanupSettings
import app.sharedclips.data.MediaStoreScanner
import app.sharedclips.data.ScannedVideo
import app.sharedclips.work.CleanupScheduler
import app.sharedclips.work.Notifications
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A system consent request the screen has to launch, for a move or for the trash. */
data class CleanupRequest(val intent: PendingIntent, val delete: Boolean)

data class HomeState(
    val scanning: Boolean = false,
    val videos: List<ScannedVideo> = emptyList(),
    val message: String? = null,
    val messageAtMs: Long = 0,
    val mode: CleanupMode = CleanupMode.MOVE_TO_FOLDER,
    val autoMove: Boolean = false,
    val deleteAfterDays: Int = 0,
    val canRead: Boolean = false,
    val canManageMedia: Boolean = false,
    val hasAllFilesAccess: Boolean = false,
    val notificationsOn: Boolean = true,
    val runsUnrestricted: Boolean = true,
    val keptWhenUnused: Boolean = true,
    val request: CleanupRequest? = null,
    val log: List<String> = emptyList(),
    val versionName: String = "",
    /** Videos already in TikTok Downloads, so out of the backed-up Camera folder. */
    val keptCount: Int = 0,
    val keptBytes: Long = 0,
    val onboarded: Boolean = true,
) {
    val totalBytes: Long get() = videos.sumOf { it.sizeBytes }
}

class HomeViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val scanner = MediaStoreScanner(app)
    private val mover = MediaMover(app)
    private val settings = CleanupSettings(app)

    private val _state = MutableStateFlow(
        HomeState(
            mode = settings.mode,
            autoMove = settings.autoMove,
            deleteAfterDays = settings.deleteAfterDays,
            onboarded = settings.onboarded,
            log = ActivityLog.read(app),
            versionName = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }
                .getOrNull().orEmpty(),
        )
    )

    /**
     * The videos a move or delete request was made for, so the approved set is what gets moved. Kept
     * in saved state: Android may restart the app while its confirmation dialog is open.
     */
    private var requested: List<Uri>
        get() = saved.get<ArrayList<Uri>>(KEY_REQUESTED).orEmpty()
        set(value) {
            saved[KEY_REQUESTED] = ArrayList(value)
        }
    private var scanJob: Job? = null
    private var cleanJob: Job? = null
    private var settleJob: Job? = null
    private var changeJob: Job? = null
    val state: StateFlow<HomeState> = _state

    init {
        refreshAccess()
        CleanupScheduler.apply(app)
    }

    fun setAutoMove(enabled: Boolean) {
        settings.autoMove = enabled
        _state.update { it.copy(autoMove = enabled) }
        CleanupScheduler.apply(getApplication())
    }

    fun setDeleteAfterDays(days: Int) {
        // An open delete prompt was worked out with the old limit.
        if (days != settings.deleteAfterDays) Notifications.cancelDeletePrompt(getApplication())
        settings.deleteAfterDays = days
        _state.update { it.copy(deleteAfterDays = days) }
        CleanupScheduler.apply(getApplication())
    }

    /** Re-reads what Android lets the app do; the user changes all of it in system settings. */
    fun refreshAccess() {
        val app = getApplication<Application>()
        _state.update {
            it.copy(
                canRead = AppAccess.canReadVideos(app),
                canManageMedia = mover.canManageMedia(),
                hasAllFilesAccess = mover.hasAllFilesAccess(),
                notificationsOn = AppAccess.canNotify(app),
                runsUnrestricted = AppAccess.runsUnrestricted(app),
                keptWhenUnused = AppAccess.keptWhenUnused(app),
            )
        }
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
            showScan(scanner.scanCameraFolder())
            val (count, bytes) = scanner.downloadsSummary()
            _state.update { it.copy(keptCount = count, keptBytes = bytes) }
        }
    }

    fun finishOnboarding() {
        settings.onboarded = true
        _state.update { it.copy(onboarded = true) }
    }

    /** MediaStore changed while the screen is open: refresh once the changes calm down. */
    fun onMediaChanged() {
        changeJob?.cancel()
        changeJob = viewModelScope.launch {
            delay(MEDIA_CHANGE_DEBOUNCE_MS)
            refreshLog()
            scan(clearMessage = false)
        }
    }

    /** A result message from an earlier visit is not news any more. */
    fun dropStaleMessage() {
        val now = System.currentTimeMillis()
        _state.update { if (it.message != null && now - it.messageAtMs > MESSAGE_TTL_MS) it.copy(message = null) else it }
    }

    /**
     * Rescans, so "finished saving" is judged now rather than at the last scan, then moves or deletes
     * the videos that have finished saving: directly with All files access, otherwise through a
     * system request the screen launches. One still being written is left for the next tap.
     */
    fun cleanUp() {
        scanJob?.cancel()
        cleanJob?.cancel()
        // Its own job, so a rescan started meanwhile (a media change, a video finishing) cannot cancel it.
        cleanJob = viewModelScope.launch {
            _state.update { it.copy(scanning = true) }
            val found = scanner.scanCameraFolder()
            showScan(found)
            val now = System.currentTimeMillis()
            val mode = _state.value.mode
            // Deleting may also take an entry whose save failed (there is nothing to lose); moving never does.
            val ready = found.filter { it.isSettled(now) || (mode == CleanupMode.DELETE && it.isAbandoned(now)) }
            val saving = found.filter { !it.isSettled(now) && !it.isAbandoned(now) }
            val failed = if (mode == CleanupMode.MOVE_TO_FOLDER) found.count { it.isAbandoned(now) } else 0
            val notes = listOfNotNull(
                "${saving.size} video(s) still saving, skipped for now".takeIf { saving.isNotEmpty() },
                "$failed empty entry(s) from failed saves left; Delete removes them".takeIf { failed > 0 },
            )
            setMessage(notes.joinToString(". ").ifEmpty { null })
            requested = ready.map { it.uri }
            if (requested.isEmpty()) return@launch
            if (mover.hasAllFilesAccess()) {
                when (mode) {
                    CleanupMode.MOVE_TO_FOLDER -> onMoveApproved()
                    CleanupMode.DELETE -> onTrashApproved(mover.trashDirectly(requested))
                }
            } else {
                val intent = when (mode) {
                    CleanupMode.MOVE_TO_FOLDER -> mover.writeRequest(requested)
                    CleanupMode.DELETE -> mover.trashRequest(requested)
                } ?: return@launch
                _state.update { it.copy(request = CleanupRequest(intent, delete = mode == CleanupMode.DELETE)) }
            }
        }
    }

    /** The screen has launched [HomeState.request]; its result arrives in onMoveApproved or onTrashApproved. */
    fun requestLaunched() {
        _state.update { it.copy(request = null) }
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
            setMessage("Moved $moved videos to TikTok Downloads")
            refreshLog()
            scan(clearMessage = false)
        }
    }

    fun onTrashApproved(trashed: Int = requested.size) {
        ActivityLog.add(getApplication(), "Home screen delete: trashed $trashed")
        setMessage("Moved $trashed videos to trash")
        refreshLog()
        scan(clearMessage = false)
    }

    private fun showScan(found: List<ScannedVideo>) {
        _state.update { it.copy(scanning = false, videos = found) }
        // A move prompt whose videos have all left Camera (moved here, in a gallery app, or deleted) is stale.
        viewModelScope.launch { Notifications.withdrawMovePromptIfDone(getApplication()) }
        // Rescan when the first video still being saved should be done, so its label updates by itself.
        settleJob?.cancel()
        val now = System.currentTimeMillis()
        val waiting = found.filter { !it.isSettled(now) && !it.isAbandoned(now) }
        if (waiting.isNotEmpty()) {
            settleJob = viewModelScope.launch {
                delay(waiting.minOf { it.msUntilSettled(now) } + SETTLE_MARGIN_MS)
                scan(clearMessage = false)
            }
        }
    }

    private fun setMessage(text: String?) {
        _state.update { it.copy(message = text, messageAtMs = System.currentTimeMillis()) }
    }

    private companion object {
        const val MEDIA_CHANGE_DEBOUNCE_MS = 1_000L
        const val SETTLE_MARGIN_MS = 500L
        const val MESSAGE_TTL_MS = 60_000L
        const val KEY_REQUESTED = "requested"
    }
}
