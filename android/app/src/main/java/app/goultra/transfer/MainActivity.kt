package app.goultra.transfer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.goultra.transfer.ui.GalleryScreen
import app.goultra.transfer.ui.OnboardingScreen
import app.goultra.transfer.ui.Splash
import app.goultra.transfer.ui.AppPrefs
import app.goultra.transfer.ui.ThemeMode
import app.goultra.transfer.ui.tr
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    companion object {
        /** Home-screen shortcut «Начать запись» (res/xml/shortcuts.xml). */
        const val ACTION_RECORD = "app.goultra.transfer.RECORD"
    }

    /** Set by the shortcut intent; consumed once the UI (and permissions) are ready. */
    private var recordRequested by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_RECORD) recordRequested = true
        // Debug: `am start -n app.goultra.transfer/.MainActivity --ez snapshot true` from any tab (no live view).
        // Debug: `--es opts 133,134,135` logs GET_OPTIONS for exactly these type numbers (read-only).
        intent.getStringExtra("opts")?.let { list ->
            cameraController.debugOptions(list.split(',').mapNotNull { it.trim().toIntOrNull() })
        }
        // Debug: `--ez scan true` logs every nearby BLE advertiser (is the Action Pod a separate device?).
        if (intent.getBooleanExtra("scan", false)) cameraController.debugScan()
        // Debug: `--ei status 120` logs the raw capture status every second (mapping recording fields).
        intent.getIntExtra("status", 0).takeIf { it > 0 }?.let(cameraController::debugCaptureStatus)
        // Debug: `--ez videoinfo true` logs the newest video's metadata fields.
        if (intent.getBooleanExtra("videoinfo", false)) cameraController.debugVideoInfo(intent.getStringExtra("video"))
        // Debug: `--es fetch /DCIM/Camera01/…` copies a camera file into Android/data/…/files.
        intent.getStringExtra("fetch")?.let(cameraController::debugFetch)
        // Debug: `--es cmd 56:0801` sends one allowed command (code:hex body) and logs the reply.
        intent.getStringExtra("cmd")?.split(':')?.let { p ->
            p[0].toIntOrNull()?.let { cameraController.debugCommand(it, p.getOrElse(1) { "" }) }
        }
        // Debug: `--ez selftest true` runs the settings self-test (same as menu ⋮ → «Самопроверка настроек»).
        if (intent.getBooleanExtra("selftest", false)) cameraController.selfTest()
        // `--es fms 0-40` widens the function modes read (finding an unknown mode's number).
        if (intent.getBooleanExtra("snapshot", false)) {
            val fms = intent.getStringExtra("fms")?.split('-')?.mapNotNull { it.toIntOrNull() }
            cameraController.debugSnapshot(fms?.takeIf { it.size == 2 }?.let { (a, b) -> (a..b).toList() })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && intent?.action == ACTION_RECORD) recordRequested = true
        val controller = cameraController
        // Splash on a cold start only; not for the «start recording» shortcut and not with animations switched off.
        val animations = android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        val splashFirst = savedInstanceState == null && intent?.action != ACTION_RECORD && animations
        setContent {
            var showSplash by rememberSaveable { mutableStateOf(splashFirst) }
            AppTheme { Box(Modifier.fillMaxSize()) {
                var hasPermissions by remember { mutableStateOf(hasPermissions()) }
                var afterGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
                val permissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    hasPermissions = hasPermissions()
                    if (hasPermissions) afterGrant?.invoke()
                    else controller.message = tr("Без доступа к Bluetooth и геолокации камеру не найти", "Without Bluetooth and location access the camera can't be found")
                    afterGrant = null
                }
                val withPermissions = { action: () -> Unit ->
                    if (hasPermissions) action() else {
                        afterGrant = action
                        permissions.launch(requestedPermissions())
                    }
                }

                val shareLog = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, tr("GO Ultra Transfer — журнал", "GO Ultra Transfer — log"))
                        .putExtra(Intent.EXTRA_TEXT, controller.logText())
                    startActivity(Intent.createChooser(send, tr("Отправить журнал", "Send log")))
                }
                if (!controller.onboarded) {
                    OnboardingScreen(
                        controller = controller,
                        hasPermissions = hasPermissions,
                        onStart = { controller.showLinkHints(); withPermissions(controller::runSetup) },
                        onShareLog = shareLog,
                    )
                } else {
                    LaunchedEffect(recordRequested) {
                        if (recordRequested) {
                            recordRequested = false
                            withPermissions(controller::onRecordShortcut)
                        } else if (hasPermissions) {
                            controller.autoConnect()
                        }
                    }
                    GalleryScreen(
                        controller = controller,
                        onConnect = { withPermissions(controller::connect) },
                        onOpenDiagnostics = {
                            if (controller.isDownloading) {
                                controller.message = tr("Идёт скачивание — дождитесь его или нажмите «Стоп»", "Downloading — wait for it to finish or press «Stop»")
                            } else {
                                controller.disconnect()
                                startActivity(Intent(this@MainActivity, DiagActivity::class.java))
                            }
                        },
                        onShareLog = shareLog,
                        onRecord = { withPermissions(controller::startRecording) },
                    )
                }
                if (showSplash) Splash { showSplash = false }
            } }
        }
    }

    override fun onStart() {
        super.onStart()
        cameraController.onForeground()
    }

    override fun onStop() {
        // Leaving the app (also with Back) starts the background grace period; rotation doesn't count.
        if (!isChangingConfigurations) cameraController.onBackground()
        super.onStop()
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = when (AppPrefs.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    // The status / navigation bar icons follow the app theme, not the phone's (they may differ now).
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
