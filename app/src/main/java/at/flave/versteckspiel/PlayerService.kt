package at.flave.versteckspiel

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

/**
 * Haelt das Versteck-Handy wach, damit Geraeusche auch bei schwarzem Display
 * und gesperrtem Bildschirm ankommen:
 *  - Foreground Service  -> Android beendet den Prozess nicht
 *  - Partial Wake Lock   -> CPU laeuft weiter, der UDP-Thread bleibt am Port
 *  - WiFi Lock           -> WLAN geht im Standby nicht schlafen
 */
class PlayerService : Service() {

    companion object {
        const val ACTION_STOP = "at.flave.versteckspiel.STOP"
        private const val CHANNEL = "versteck"
        private const val NOTIF_ID = 1

        /** Nach so langer Ruhe schaltet sich das Spiel von selbst ab. */
        private const val IDLE_TIMEOUT_MS = 10 * 60 * 1000L

        @Volatile var isRunning = false
            private set

        /** Setzt die Activity, solange sie sichtbar ist. */
        @Volatile var listener: ((Event) -> Unit)? = null
    }

    private var peer: Peer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isRunning) {
            isRunning = true
            startInForeground()
            acquireLocks()
            peer = Peer(this) { event -> listener?.invoke(event) }.also { it.start() }
            startIdleWatch()
        }
        return START_STICKY
    }

    /**
     * Wenn zehn Minuten lang niemand ruft, beendet sich das Spiel selbst -
     * ein vergessenes Handy soll nicht den halben Tag den Akku leersaugen.
     */
    private fun startIdleWatch() = Thread {
        while (isRunning) {
            Thread.sleep(30_000)
            val p = peer ?: continue
            if (p.idleMillis() > IDLE_TIMEOUT_MS) {
                stopSelf()
                return@Thread
            }
        }
    }.start()

    private fun startInForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Versteckspiel", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, PlayerService::class.java).setAction(ACTION_STOP), flags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)

        val notification = builder
            .setContentTitle("Versteckspiel läuft")
            .setContentText("Dieses Handy spielt mit · endet nach 10 Min Ruhe")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "versteckspiel:hider")
            .apply { setReferenceCounted(false); acquire() }

        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = runCatching {
            wm.createWifiLock(mode, "versteckspiel:wifi").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
    }

    override fun onDestroy() {
        isRunning = false
        peer?.stop(); peer = null
        runCatching { wakeLock?.release() }; wakeLock = null
        runCatching { wifiLock?.release() }; wifiLock = null
        super.onDestroy()
    }
}
