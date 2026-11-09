package com.example.mocklocation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that continuously injects a mock location using Android's
 * official test-provider API ([LocationManager.setTestProviderLocation]).
 *
 * Requires this app to be selected as the "Mock location app" under
 * Settings > Developer options. This is the only non-root, officially sanctioned
 * way to provide mock locations, and every injected [Location] is flagged as mock.
 */
class MockLocationService : Service() {

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var latitude = 0.0
    private var longitude = 0.0
    private var altitude = 0.0
    private var accuracy = 1.0f

    private val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER
    )

    private val pushRunnable = object : Runnable {
        override fun run() {
            pushLocation()
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMocking()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_UPDATE -> {
                latitude = intent.getDoubleExtra(EXTRA_LAT, latitude)
                longitude = intent.getDoubleExtra(EXTRA_LNG, longitude)
                altitude = intent.getDoubleExtra(EXTRA_ALT, altitude)
                accuracy = intent.getFloatExtra(EXTRA_ACC, accuracy)
                startMocking()
            }
        }
        return START_STICKY
    }

    private fun startMocking() {
        try {
            // Inside try: on Android 14+ a location-type FGS throws SecurityException
            // if location permission has not been granted.
            startForegroundCompat()
            registerProviders()
            running = true
            handler.removeCallbacks(pushRunnable)
            handler.post(pushRunnable)
            broadcastState(true, null)
        } catch (e: SecurityException) {
            // App is not selected as the mock location app, or permission missing.
            running = false
            broadcastState(false, getString(R.string.error_not_mock_app))
            stopForegroundCompat()
            stopSelf()
        } catch (e: Exception) {
            running = false
            broadcastState(false, e.message)
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun registerProviders() {
        for (p in providers) {
            try {
                if (locationManager.getProvider(p) == null || !isTestProvider(p)) {
                    @Suppress("DEPRECATION")
                    locationManager.addTestProvider(
                        p,
                        false, false, false, false,
                        true, true, true,
                        android.location.Criteria.POWER_LOW,
                        android.location.Criteria.ACCURACY_FINE
                    )
                }
                locationManager.setTestProviderEnabled(p, true)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Provider $p not available: ${e.message}")
            }
        }
    }

    private fun isTestProvider(provider: String): Boolean = try {
        // setTestProviderEnabled throws if it is not a test provider; cheap probe.
        locationManager.setTestProviderEnabled(provider, true)
        true
    } catch (e: Exception) {
        false
    }

    private fun pushLocation() {
        for (p in providers) {
            try {
                val loc = Location(p).apply {
                    latitude = this@MockLocationService.latitude
                    longitude = this@MockLocationService.longitude
                    altitude = this@MockLocationService.altitude
                    accuracy = this@MockLocationService.accuracy
                    bearing = 0f
                    speed = 0f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        bearingAccuracyDegrees = 0.1f
                        verticalAccuracyMeters = 0.1f
                        speedAccuracyMetersPerSecond = 0.01f
                    }
                }
                locationManager.setTestProviderLocation(p, loc)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to push to $p: ${e.message}")
            }
        }
    }

    private fun stopMocking() {
        handler.removeCallbacks(pushRunnable)
        for (p in providers) {
            try {
                locationManager.setTestProviderEnabled(p, false)
                locationManager.removeTestProvider(p)
            } catch (e: Exception) {
                // Provider may already be gone.
            }
        }
        running = false
        broadcastState(false, null)
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(pushRunnable)
        running = false
        super.onDestroy()
    }

    private fun broadcastState(active: Boolean, error: String?) {
        activeLat = latitude
        activeLng = longitude
        sendBroadcast(Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_ACTIVE, active)
            putExtra(EXTRA_LAT, latitude)
            putExtra(EXTRA_LNG, longitude)
            if (error != null) putExtra(EXTRA_ERROR, error)
        })
    }

    private fun startForegroundCompat() {
        createChannel()
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                getString(R.string.notif_text, latitude, longitude)
            )
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "mock_location_channel"
        private const val NOTIF_ID = 42
        private const val UPDATE_INTERVAL_MS = 1000L

        const val ACTION_START = "com.example.mocklocation.START"
        const val ACTION_STOP = "com.example.mocklocation.STOP"
        const val ACTION_UPDATE = "com.example.mocklocation.UPDATE"
        const val ACTION_STATE = "com.example.mocklocation.STATE"

        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ALT = "alt"
        const val EXTRA_ACC = "acc"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_ERROR = "error"

        /** Simple in-process state so the UI can reflect status on recreate. */
        @Volatile var running: Boolean = false
            private set
        @Volatile var activeLat: Double = 0.0
            private set
        @Volatile var activeLng: Double = 0.0
            private set
    }
}
