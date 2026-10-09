package de.codevoid.screensaver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.core.app.NotificationCompat

class BrightnessService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var handler: Handler
    private lateinit var controller: BrightnessController
    private lateinit var notificationManager: NotificationManager
    private var lightSensor: Sensor? = null
    private var onAc = false
    private var started = false
    private var previousTimeout = -1
    private var lastNotifiedText: String? = null

    private val stopIntent by lazy {
        PendingIntent.getService(
            this, 0,
            Intent(this, BrightnessService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Makes the "can't set brightness" notification actionable: tapping it
    // lands on the settings screen, which re-runs the permission flow.
    private val openIntent by lazy {
        PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val acReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> onAcConnected()
                Intent.ACTION_POWER_DISCONNECTED -> onAcDisconnected()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handler.removeCallbacks(tickRunnable)
                Intent.ACTION_SCREEN_ON  -> onScreenOn()
            }
        }
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            applyPrefs()
            controller.tick()
            updateNotification()
            handler.postDelayed(this, BrightnessController.TICK_MS)
        }
    }

    private val autoOffRunnable = Runnable { turnScreenOff() }
    private val restoreTimeoutRunnable = Runnable { restoreTimeout() }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        Prefs.init(this)
        handler = Handler(Looper.getMainLooper())
        controller = BrightnessController(contentResolver)
        sensorManager = getSystemService(SensorManager::class.java)
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        notificationManager = getSystemService(NotificationManager::class.java)
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            notificationManager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Brightness Service", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            controller.ensureManualMode()
            detectAcState()
            // No export flags: every action below is a protected system
            // broadcast, which Android 14's "declare export behavior" rule
            // exempts, and the flag constants would pin minSdk to 33.
            registerReceiver(acReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            })
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            })
            lightSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            if (getSystemService(PowerManager::class.java).isInteractive) {
                handler.post(tickRunnable)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(autoOffRunnable)
        handler.removeCallbacks(restoreTimeoutRunnable)
        if (started) {
            sensorManager.unregisterListener(this)
            try { unregisterReceiver(acReceiver) } catch (_: Exception) {}
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        }
        restoreTimeout()
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        controller.onLuxReading(event.values[0])
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    override fun onBind(intent: Intent?): IBinder? = null

    private fun detectAcState() {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        onAc = plugged != 0
        applyPrefs()
    }

    private fun onAcConnected() {
        onAc = true
        handler.removeCallbacks(autoOffRunnable)
        restoreTimeout()
        applyPrefs()
        wakeScreen()
        updateNotification()
    }

    private fun onAcDisconnected() {
        onAc = false
        applyPrefs()
        scheduleAutoOff()
        updateNotification()
    }

    private fun applyPrefs() {
        controller.windowSize = Prefs.reactionWindow
        controller.darkLux = Prefs.darkLux
        controller.brightLux = Prefs.brightLux
        controller.capFraction = if (onAc) 1.0f else Prefs.brightnessCap
    }

    private fun onScreenOn() {
        controller.resetBuffer()
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    private fun scheduleAutoOff() {
        val minutes = Prefs.autoOffMinutes
        if (minutes <= 0) return
        handler.postDelayed(autoOffRunnable, minutes * 60_000L)
    }

    private fun turnScreenOff() {
        if (previousTimeout >= 0) return  // restore already pending
        val current = AndroidSettings.System.getInt(
            contentResolver, AndroidSettings.System.SCREEN_OFF_TIMEOUT, 30_000)
        previousTimeout = current
        AndroidSettings.System.putInt(contentResolver, AndroidSettings.System.SCREEN_OFF_TIMEOUT, 1_000)
        handler.postDelayed(restoreTimeoutRunnable, 5_000L)
    }

    private fun restoreTimeout() {
        if (previousTimeout < 0) return
        AndroidSettings.System.putInt(
            contentResolver, AndroidSettings.System.SCREEN_OFF_TIMEOUT, previousTimeout)
        previousTimeout = -1
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(PowerManager::class.java)
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ScreenSaver:wake"
        ).acquire(3_000L)
    }

    private fun notificationText(): String {
        if (!controller.hasControl) return getString(R.string.notification_no_control)
        val power = if (onAc) "On AC — full brightness"
                    else "On battery — cap ${(Prefs.brightnessCap * 100).toInt()}%"
        return if (controller.targetBrightness >= 0)
            "$power · ${formatLux(controller.medianLux)} → ${controller.targetBrightness}/255"
        else power
    }

    private fun buildNotification(text: String = notificationText()): Notification {
        lastNotifiedText = text
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Dimmer")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    // Reposting an identical notification every tick would churn the shade
    // (and battery) for nothing — skip when the text is unchanged.
    private fun updateNotification() {
        val text = notificationText()
        if (text == lastNotifiedText) return
        notificationManager.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "de.codevoid.screensaver.STOP"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "brightness_service"

        private val stateListeners = LinkedHashSet<() -> Unit>()

        fun addStateListener(l: () -> Unit)    { stateListeners.add(l) }
        fun removeStateListener(l: () -> Unit) { stateListeners.remove(l) }

        var isRunning = false
            private set(value) { field = value; stateListeners.forEach { it() } }
    }
}
