package app.barq.update

import android.content.Context
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the Settings "app update" section shows. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    /** A newer version is on Google Play. [staleDays] is how long it has been available (null when unknown). */
    data class Available(val versionCode: Int, val staleDays: Int?) : UpdateState
    data class Downloading(val percent: Int) : UpdateState
    /** Downloaded in the background; installing restarts the app. */
    data object Downloaded : UpdateState
    /** Not installed from Google Play (APK from Drive, USB, a test build): Play cannot update it. */
    data object NotFromPlay : UpdateState
    data object Failed : UpdateState
}

/**
 * Google Play in-app updates (flexible flow: the update downloads while the app stays usable, then the
 * user taps once to restart). It only does anything for a build installed from Google Play; for any other
 * install the check reports [UpdateState.NotFromPlay] and nothing else happens. Never runs by itself with
 * network access: a check happens when the user opens the section or taps "Check".
 */
object AppUpdater {
    private const val ERROR_APP_NOT_OWNED = -10

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> get() = _state

    private var manager: AppUpdateManager? = null
    private var info: AppUpdateInfo? = null

    private val listener = InstallStateUpdatedListener { st ->
        when (st.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                val total = st.totalBytesToDownload()
                _state.value = UpdateState.Downloading(if (total > 0) (st.bytesDownloaded() * 100 / total).toInt().coerceIn(0, 100) else 0)
            }
            InstallStatus.DOWNLOADED -> _state.value = UpdateState.Downloaded
            InstallStatus.FAILED, InstallStatus.CANCELED -> _state.value = UpdateState.Failed
            else -> {}
        }
    }

    private fun manager(ctx: Context): AppUpdateManager =
        manager ?: AppUpdateManagerFactory.create(ctx.applicationContext).also { manager = it }

    /** Asks Google Play whether a newer version exists; also picks up an update that finished downloading earlier. */
    fun check(ctx: Context) {
        val m = manager(ctx)
        _state.value = UpdateState.Checking
        m.appUpdateInfo
            .addOnSuccessListener { i ->
                info = i
                _state.value = when {
                    i.installStatus() == InstallStatus.DOWNLOADED -> UpdateState.Downloaded
                    i.installStatus() == InstallStatus.DOWNLOADING -> UpdateState.Downloading(0)
                    i.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && i.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) ->
                        UpdateState.Available(i.availableVersionCode(), i.clientVersionStalenessDays())
                    else -> UpdateState.UpToDate
                }
            }
            .addOnFailureListener { e ->
                val code = (e as? com.google.android.play.core.install.InstallException)?.errorCode
                _state.value = if (code == ERROR_APP_NOT_OWNED) UpdateState.NotFromPlay else UpdateState.Failed
            }
    }

    /** Starts the flexible download. [launcher] comes from the Activity so Play can show its confirmation sheet. */
    fun start(ctx: Context, launcher: ActivityResultLauncher<IntentSenderRequest>) {
        val i = info ?: return check(ctx)
        val m = manager(ctx)
        m.unregisterListener(listener)
        m.registerListener(listener)
        val started = runCatching { m.startUpdateFlowForResult(i, launcher, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()) }
        if (started.isFailure || started.getOrNull() != true) _state.value = UpdateState.Failed
    }

    /** Installs the downloaded update and restarts the app. */
    fun install(ctx: Context) { manager(ctx).completeUpdate() }

    /** The user dismissed Play's sheet: back to showing that an update is available. */
    fun onFlowDeclined() {
        val i = info
        _state.value = if (i != null) UpdateState.Available(i.availableVersionCode(), i.clientVersionStalenessDays()) else UpdateState.Idle
    }
}
