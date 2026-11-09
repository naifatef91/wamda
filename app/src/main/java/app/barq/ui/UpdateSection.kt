package app.barq.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.barq.BuildConfig
import app.barq.R
import app.barq.update.AppUpdater
import app.barq.update.UpdateState

/**
 * Settings card for Google Play in-app updates. Nothing is requested until the user taps "Check"; an update
 * that already finished downloading is offered for install the moment the check sees it.
 */
@Composable
fun UpdateCard() {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // RESULT_OK means Play accepted and is downloading (the listener reports progress); anything else is "no thanks".
        if (result.resultCode != android.app.Activity.RESULT_OK) AppUpdater.onFlowDeclined()
    }

    BarqCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.upd_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (val s = state) {
                            UpdateState.Idle -> stringResource(R.string.upd_idle, BuildConfig.VERSION_NAME)
                            UpdateState.Checking -> stringResource(R.string.upd_checking)
                            UpdateState.UpToDate -> stringResource(R.string.upd_uptodate, BuildConfig.VERSION_NAME)
                            is UpdateState.Available -> stringResource(R.string.upd_available)
                            is UpdateState.Downloading -> stringResource(R.string.upd_downloading, s.percent)
                            UpdateState.Downloaded -> stringResource(R.string.upd_downloaded)
                            UpdateState.NotFromPlay -> stringResource(R.string.upd_not_play)
                            UpdateState.Failed -> stringResource(R.string.upd_failed)
                        },
                        style = MaterialTheme.typography.bodySmall, color = if (state == UpdateState.Failed) c.error else c.onSurfaceVariant,
                    )
                }
                when (state) {
                    UpdateState.Idle, UpdateState.UpToDate, UpdateState.Failed, UpdateState.NotFromPlay ->
                        TextButton(onClick = { AppUpdater.check(ctx) }) { Text(stringResource(R.string.upd_check), color = c.primary) }
                    is UpdateState.Available ->
                        TextButton(onClick = { AppUpdater.start(ctx, launcher) }) { Text(stringResource(R.string.upd_update), color = c.primary) }
                    UpdateState.Downloaded ->
                        TextButton(onClick = { AppUpdater.install(ctx) }) { Text(stringResource(R.string.upd_install), color = c.primary) }
                    else -> {}
                }
            }
            (state as? UpdateState.Downloading)?.let { LinearProgressIndicator(progress = { it.percent / 100f }, modifier = Modifier.fillMaxWidth()) }
        }
    }
}
