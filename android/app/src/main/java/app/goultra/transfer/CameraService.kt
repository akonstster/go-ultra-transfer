package app.goultra.transfer

import app.goultra.transfer.ui.tr
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.goultra.transfer.ui.DownloadProgress
import app.goultra.transfer.ui.details
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs while the camera is connected or a download is going on. As a foreground service it keeps the process
 * from being frozen in the background, so the controller's timers (background grace period, idle disconnect)
 * really fire and the camera is released — an app frozen with an open BLE link kept the camera awake all night.
 * Shows the download progress, or «Камера подключена» with a Disconnect action.
 */
class CameraService : Service() {
    companion object {
        private const val CHANNEL = "camera"
        private const val ID_ONGOING = 1
        private const val ID_DONE = 2
        private const val ACTION_CANCEL = "app.goultra.transfer.CANCEL"
        private const val ACTION_DISCONNECT = "app.goultra.transfer.DISCONNECT"

        /** Call while the app is in the foreground (Android 12+ forbids starting it from the background). */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CameraService::class.java))
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastUpdate = 0L
    private var lastKey: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Подключение к камере", "Camera connection"), NotificationManager.IMPORTANCE_LOW),
        )
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "goultra:camera")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        wifiLock = getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "goultra:camera")
            .apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = cameraController
        when (intent?.action) {
            ACTION_CANCEL -> controller.cancelDownload()
            ACTION_DISCONNECT -> controller.disconnect()
        }
        ServiceCompat.startForeground(
            this,
            ID_ONGOING,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        if (watcher == null) {
            watcher = scope.launch {
                launch {
                    // Refresh the background countdown text.
                    while (true) {
                        delay(15_000)
                        if (controller.download == null) refresh(force = true)
                    }
                }
                snapshotFlow { Triple(controller.download, controller.conn, controller.inBackground) }.collect {
                    if (controller.download == null && !controller.isLinked && !controller.isDownloading) {
                        finish(controller.takeDownloadResult())
                    } else {
                        refresh(force = false)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun refresh(force: Boolean) {
        val p = cameraController.download
        val key = p?.name ?: "connected:${cameraController.inBackground}"
        val now = System.currentTimeMillis()
        if (!force && now - lastUpdate < 700 && key == lastKey) return
        lastUpdate = now
        lastKey = key
        notify(ID_ONGOING, buildNotification())
    }

    private fun finish(result: String?) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (!result.isNullOrEmpty()) {
            notify(
                ID_DONE,
                NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("GO Ultra")
                    .setContentText(result)
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build(),
            )
        }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val controller = cameraController
        val p: DownloadProgress? = controller.download
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
        if (p != null || controller.isDownloading) {
            val percent = if (p != null && p.total > 0) (p.done * 100 / p.total).toInt() else 0
            builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(if (p == null) tr("Подготовка к скачиванию…", "Preparing download…") else tr("Скачивание ${p.index} из ${p.count}", "Downloading ${p.index} of ${p.count}"))
                .setContentText(p?.let { "${it.name} · ${it.details()}" })
                .setProgress(100, percent, p == null || p.total <= 0)
                .addAction(0, tr("Стоп", "Stop"), serviceIntent(ACTION_CANCEL, 1))
        } else {
            val left = controller.backgroundSecondsLeft()
            builder.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(tr("Камера подключена", "Camera connected"))
                .setContentText(
                    if (left != null) tr("Отключится через ${(left + 59) / 60} мин, если не вернуться в приложение", "Disconnects in ${(left + 59) / 60} min unless you return to the app")
                    else "GO Ultra Transfer",
                )
                .addAction(0, tr("Отключить", "Disconnect"), serviceIntent(ACTION_DISCONNECT, 2))
        }
        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, CameraService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    @Suppress("MissingPermission") // POST_NOTIFICATIONS is optional: without it the notification is just hidden
    private fun notify(id: Int, notification: Notification) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(id, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
