package com.vpnpinger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors

/**
 * Always-on foreground service.
 *
 * Watches the device's VPN connections and, for every change (connect, disconnect or
 * server switch), performs one HTTP GET to the configured URL and posts a
 * notification. Runs continuously: it is started from [MainActivity] and
 * [BootReceiver], survives as a foreground service with a persistent notification,
 * and restarts itself ([android.app.Service.START_STICKY]) if the system kills it.
 *
 * Detection: a VPN appears to Android as a network whose capabilities carry the
 * [NetworkCapabilities.TRANSPORT_VPN] transport. A [ConnectivityManager.NetworkCallback]
 * (registered for VPN-transport networks only - deliberately *not* filtered on
 * NET_CAPABILITY_INTERNET, which tunnels can briefly lack while (dis)connecting)
 * pushes changes to [VpnTransitionDetector]. Because callback delivery is not
 * guaranteed on every OEM/transition, a low-cost poll every [POLL_MS] reconciles
 * the detector with the authoritative [ConnectivityManager] state as a safety net.
 *
 * Trigger semantics:
 *  - no VPN  -> VPN          : "VPN connected"
 *  - VPN     -> no VPN       : "VPN disconnected"
 *  - VPN network replaced / changed while still connected (e.g. server switch,
 *    reconnect):             : "VPN changed"
 * Rapid same-kind flapping within a short window is coalesced into one trigger so
 * an overlapping server switch doesn't fire twice.
 *
 * After a change is detected, the URL call is postponed by a user-configurable
 * delay (see the setup screen; default 3 s) so a freshly connected tunnel has time
 * to fully establish before the request fires. The manual test action
 * [ACTION_PING_NOW] pings the URL immediately, without a VPN event.
 *
 * Independently of VPN events, the URL can also be pinged on a fixed, user-
 * configurable period in seconds (0 disables it); see [reschedulePeriodic].
 *
 * All state transitions run on the main thread: the network callback is dispatched
 * through [handler], and the poll is posted to the same handler, so no locking is
 * needed around [VpnTransitionDetector].
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "VpnPinger"

        private const val CHANNEL_ONGOING = "ongoing"
        private const val CHANNEL_EVENTS = "events"
        private const val NOTIF_ONGOING = 1
        private const val NOTIF_EVENT = 2
        private const val PREFS = "vpn_pinger"
        private const val KEY_URL = "url"
        private const val KEY_DELAY_SECONDS = "ping_delay_seconds"
        private const val KEY_PERIODIC_INTERVAL_SECONDS = "periodic_interval_seconds"
        private const val DEFAULT_URL = "https://example.com"

        /** Same-kind transitions closer than this are collapsed into one trigger. */
        private const val COALESCE_MS = 2_000L

        /** Internal safety-net re-check interval in case a network callback was missed. */
        private const val POLL_MS = 3_000L

        /** Seconds to wait after a VPN change before calling the URL. */
        const val MIN_DELAY_SECONDS = 0
        const val MAX_DELAY_SECONDS = 3600
        private const val DEFAULT_DELAY_SECONDS = 3

        /**
         * Optional periodic pings: fire the URL every N seconds, independent of any
         * VPN event. 0 disables them entirely (VPN-change-triggered pings still work).
         */
        const val MIN_PERIODIC_INTERVAL_SECONDS = 0
        const val MAX_PERIODIC_INTERVAL_SECONDS = 86_400 // 24 h
        private const val DEFAULT_PERIODIC_INTERVAL_SECONDS = 0

        /** Explicit action that triggers an immediate manual ping (test). */
        const val ACTION_PING_NOW = "com.vpnpinger.action.PING_NOW"

        /**
         * Action sent after the user saves settings: re-reads the interval and
         * reschedules the periodic pings so a change takes effect immediately in an
         * already-running service.
         */
        const val ACTION_APPLY_SETTINGS = "com.vpnpinger.action.APPLY_SETTINGS"

        fun urlOf(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_URL, null)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_URL

        fun saveUrl(context: Context, url: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_URL, url.trim())
                .apply()
        }

        fun periodicIntervalSecondsOf(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_PERIODIC_INTERVAL_SECONDS, DEFAULT_PERIODIC_INTERVAL_SECONDS)
                .coerceIn(MIN_PERIODIC_INTERVAL_SECONDS, MAX_PERIODIC_INTERVAL_SECONDS)

        fun savePeriodicIntervalSeconds(context: Context, seconds: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(
                    KEY_PERIODIC_INTERVAL_SECONDS,
                    seconds.coerceIn(
                        MIN_PERIODIC_INTERVAL_SECONDS,
                        MAX_PERIODIC_INTERVAL_SECONDS,
                    ),
                )
                .apply()
        }

        fun delaySecondsOf(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_DELAY_SECONDS, DEFAULT_DELAY_SECONDS)
                .coerceIn(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS)

        fun saveDelaySeconds(context: Context, seconds: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(
                    KEY_DELAY_SECONDS,
                    seconds.coerceIn(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS),
                )
                .apply()
        }

        /** Starts the monitoring service. Safe to call even when it is already running. */
        fun start(context: Context) = start(context, action = null)

        /** Triggers an immediate manual ping for testing (also starts the monitor). */
        fun pingNow(context: Context) = start(context, action = ACTION_PING_NOW)

        /**
         * Signals a running monitor that settings were just saved, so it reschedules
         * its periodic pings from the new interval (also starts it if stopped).
         */
        fun startApplySettings(context: Context) =
            start(context, action = ACTION_APPLY_SETTINGS)

        private fun start(context: Context, action: String?) {
            val intent = Intent(context, MonitorService::class.java)
            if (action != null) intent.action = action
            try {
                context.startForegroundService(intent)
            } catch (_: Exception) {
                // Some OEMs / Android versions block background starts (e.g. right after
                // boot before the user unlocks). Opening the app always starts it.
            }
        }
    }

    private val pingExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private val detector = VpnTransitionDetector<Network>(COALESCE_MS) {
        SystemClock.elapsedRealtime()
    }

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var triggerCount = 0

    /** Periodic safety-net check so a missed callback still triggers quickly. */
    private val pollRunnable = object : Runnable {
        override fun run() {
            onNetworkEvent("poll")
            handler.postDelayed(this, POLL_MS)
        }
    }

    /**
     * The optional "ping the URL on a fixed schedule" tick: fires the URL, then
     * reschedules itself from the *current* saved interval, so changing the
     * interval (0 disables it) takes effect from the very next tick.
     */
    private val periodicRunnable = object : Runnable {
        override fun run() {
            performPing("Periodic ping", urlOf(this@MonitorService))
            reschedulePeriodic()
        }
    }

    /**
     * (Re)schedules the periodic pings from the currently saved interval.
     * Main thread only.
     */
    private fun reschedulePeriodic() {
        handler.removeCallbacks(periodicRunnable)
        val seconds = periodicIntervalSecondsOf(this)
        if (seconds > 0) {
            Log.i(TAG, "periodic ping scheduled every ${seconds}s")
            handler.postDelayed(periodicRunnable, seconds * 1000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        makeForeground()

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkEvent("available")
            override fun onLost(network: Network) = onNetworkEvent("lost")
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) = onNetworkEvent("capabilities")

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: LinkProperties,
            ) = onNetworkEvent("link")
        }
        networkCallback = callback

        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .build(),
                callback,
                handler, // guarantees single-threaded (main) dispatch
            )
        } catch (_: SecurityException) {
            // Missing ACCESS_NETWORK_STATE: nothing we can do without it.
        }

        // First evaluation only records the baseline; it never fires a trigger.
        detector.onSnapshot(currentVpnNetworks())
        Log.i(TAG, "baseline: vpnUp=${detector.isUp}")
        handler.postDelayed(pollRunnable, POLL_MS)
        reschedulePeriodic()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be answered with startForeground().
        createChannels()
        makeForeground()
        when (intent?.action) {
            // Manual test trigger: ping immediately, no VPN event involved.
            ACTION_PING_NOW -> performPing("Manual ping", urlOf(this))
            // Settings were just saved: pick up any changed interval right away.
            ACTION_APPLY_SETTINGS -> reschedulePeriodic()
            else -> {}
        }
        return START_STICKY
    }

    /**
     * Core detection entry point: snapshot the current VPN networks, ask the
     * detector to classify any change, and schedule ping + notification for it.
     * Main thread only (network callback + poll).
     */
    private fun onNetworkEvent(reason: String) {
        val change = detector.onSnapshot(currentVpnNetworks()) ?: return
        val title = when (change) {
            VpnTransitionDetector.Change.CONNECT -> "VPN connected"
            VpnTransitionDetector.Change.DISCONNECT -> "VPN disconnected"
            VpnTransitionDetector.Change.CHANGE -> "VPN changed"
        }
        triggerCount++
        Log.i(TAG, "TRIGGER $title (reason=$reason, trigger#$triggerCount)")
        updateOngoingNotification()

        // Wait the user-configured delay so the new/old VPN tunnel has time to fully
        // establish (or tear down) before the URL is called. Announce the trigger
        // immediately; the follow-up notification (same id) replaces this one with
        // the HTTP result once the delayed call completes.
        val url = urlOf(this)
        val delayMs = delaySecondsOf(this) * 1000L
        Log.i(TAG, "calling URL in ${delayMs}ms")
        if (delayMs > 0L) {
            notifyEvent(title, "GET $url in ${delayMs / 1000L}s…")
        }
        handler.postDelayed({ performPing(title, url) }, delayMs)
    }

    /**
     * Runs the HTTP ping on the single-thread executor and posts a notification with
     * the result. A short wakelock keeps the request from being deferred in Doze.
     */
    private fun performPing(title: String, url: String) {
        pingExecutor.execute {
            val wakeLock = acquireWakeLock()
            val pingResult = try {
                Pinger.ping(url)
            } finally {
                wakeLock?.release()
            }
            notifyEvent(title, "GET $url\n$pingResult")
        }
    }

    /**
     * Authoritative snapshot of every currently connected VPN network.
     *
     * getAllNetworks() is deprecated on newer APIs but remains fully functional and -
     * unlike the active/default network - includes non-default networks too, which is
     * essential for detecting overlapping server switches (new tunnel up while the
     * old one has not been torn down yet).
     */
    @Suppress("DEPRECATION")
    private fun currentVpnNetworks(): List<Network> {
        val cm = connectivityManager ?: return emptyList()
        return cm.allNetworks
            .mapNotNull { network ->
                val capabilities = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) network else null
            }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vpn_pinger:ping").apply {
            setReferenceCounted(false)
            acquire(20_000)
        }
    } catch (_: Exception) {
        null
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                "Monitoring",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                "VPN changes",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    /** The mandatory persistent notification that keeps this service alive. */
    private fun makeForeground() {
        startForegroundCompat(buildOngoingNotification())
    }

    /** Refresh the persistent notification (called after every trigger). */
    private fun updateOngoingNotification() {
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIF_ONGOING,
            buildOngoingNotification(),
        )
    }

    private fun buildOngoingNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val interval = periodicIntervalSecondsOf(this)
        return Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("VPN Pinger running")
            .setContentText(
                "VPN: ${if (detector.isUp) "connected" else "not connected"} | " +
                    "triggers: $triggerCount | ${urlOf(this)}" +
                    (if (interval > 0) " | every ${interval}s" else ""),
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ONGOING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ONGOING, notification)
        }
    }

    private fun notifyEvent(title: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val contentIntent = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(
            NOTIF_EVENT,
            Notification.Builder(this, CHANNEL_EVENTS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build(),
        )
    }

    override fun onDestroy() {
        // Cancel the poll, the periodic schedule and any still-pending delayed pings.
        handler.removeCallbacksAndMessages(null)
        connectivityManager?.let { cm ->
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        }
        connectivityManager = null
        networkCallback = null
        pingExecutor.shutdownNow()
        super.onDestroy()
    }
}
