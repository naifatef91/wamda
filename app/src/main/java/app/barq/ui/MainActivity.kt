package app.barq.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.IntentCompat
import app.barq.data.MediaRepository
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.barq.Direction
import app.barq.R
import app.barq.TransferController
import app.barq.TransferState
import app.barq.web.PcSession
import app.barq.net.usb.UsbSession
import app.barq.service.TransferService
import app.barq.data.Settings
import app.barq.data.ThemeChoice
import app.barq.ui.theme.BarqTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        intakeShare(intent)
        setContent {
            val prefs by Settings.get(this).state.collectAsStateWithLifecycle()
            val dark = when (prefs.theme) {
                ThemeChoice.SYSTEM -> isSystemInDarkTheme()
                ThemeChoice.LIGHT -> false
                ThemeChoice.DARK -> true
            }
            BarqTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PermissionGate { BarqNav() }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intakeShare(intent)
    }

    /** Files arriving from the system share sheet become a pending send. */
    private fun intakeShare(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        // Only content:// URIs the sending app actually granted us; a file:// or ungranted URI could
        // point at this app's own private files. A running transfer is never interrupted by an intent.
        if (TransferController.state.value is TransferState.Running) return
        val granted = uris.filter {
            it.scheme == android.content.ContentResolver.SCHEME_CONTENT &&
                checkCallingOrSelfUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        }
        val files = granted.mapNotNull { MediaRepository.fromDocument(this, it) }
        if (files.isNotEmpty()) TransferController.shareIntake.value = files
    }
}

fun requiredPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.NEARBY_WIFI_DEVICES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}

@Composable
fun PermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    fun allGranted() = requiredPermissions().all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var granted by remember { mutableStateOf(allGranted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = allGranted() }

    if (granted) {
        content()
        return
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.perm_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BarqButton(stringResource(R.string.perm_grant), { launcher.launch(requiredPermissions().toTypedArray()) })
    }
}

@Composable
fun BarqNav() {
    val nav = rememberNavController()
    val intake by TransferController.shareIntake.collectAsStateWithLifecycle()
    // Cable: listen app-wide so an incoming USB transfer opens the cable screen from anywhere.
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { TransferController.attachUsb(ctx) }
    val tstate by TransferController.state.collectAsStateWithLifecycle()
    LaunchedEffect(tstate) {
        val s = tstate
        val usbIncoming = (s is TransferState.AwaitingAccept && UsbSession.isConnected) ||
            (s is TransferState.Running && s.band == "USB" && s.direction == Direction.RECEIVE)
        if (usbIncoming && nav.currentDestination?.route == "home") nav.navigate("cable")
    }
    LaunchedEffect(intake) {
        if (intake.isNotEmpty() && nav.currentDestination?.route != "send") {
            TransferController.reset()
            nav.navigate("send") { launchSingleTop = true }
        }
    }
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onSend = { nav.navigate("send") }, onReceive = { nav.navigate("receive") },
                onHistory = { nav.navigate("history") }, onSettings = { nav.navigate("settings") },
                onOpenTransfer = {
                    val s = TransferController.state.value
                    if (s is TransferState.Running) nav.navigate(if (s.direction == Direction.SEND) "send" else "receive")
                },
                onPc = { nav.navigate("pc") },
                onCable = { nav.navigate("cable") },
            )
        }
        composable("cable") {
            CableScreen(onBack = { TransferController.reset(); nav.popBackStack("home", false) }, onPickFiles = { nav.navigate("usb_pick") })
        }
        composable("usb_pick") {
            val ctx = LocalContext.current
            PickerScreen(
                onBack = { nav.popBackStack() },
                onDone = { files -> nav.popBackStack(); TransferService.start(ctx); TransferController.startUsbSend(ctx, files) },
                doneLabel = stringResource(R.string.cable_send),
            )
        }
        composable("pc") {
            val shared by PcSession.shared.collectAsStateWithLifecycle()
            PcScreen(
                onBack = { nav.popBackStack("home", false) },
                onPickFiles = { nav.navigate("pc_pick") },
                shared = shared,
            )
        }
        composable("pc_pick") {
            PickerScreen(onBack = { nav.popBackStack() }, onDone = { PcSession.setShared(it); nav.popBackStack() }, doneLabel = stringResource(R.string.pc_share))
        }
        composable("history") {
            HistoryScreen(onBack = { nav.popBackStack() }, onReceive = { nav.navigate("receive") })
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(it) })
        }
        composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
        composable("privacy") { PrivacyScreen(onBack = { nav.popBackStack() }) }
        composable("terms") { TermsScreen(onBack = { nav.popBackStack() }) }
        composable("receive") {
            ReceiveScreen(
                onHome = { TransferController.reset(); nav.popBackStack("home", false) },
                onMinimize = { nav.popBackStack("home", false) },
            )
        }
        composable("send") {
            SendScreen(
                onHome = { TransferController.reset(); nav.popBackStack("home", false) },
                onMinimize = { nav.popBackStack("home", false) },
            )
        }
    }
}
