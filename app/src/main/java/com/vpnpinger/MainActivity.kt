package com.vpnpinger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Bare-bones setup screen.
 *
 * Its only job is configuration: the URL to ping, the delay before the URL is
 * called after a VPN change, an optional fixed-period ping interval, plus
 * start/stop, a manual "ping now" test and the battery-optimization exemption.
 * All runtime monitoring happens in [MonitorService]; this screen is not
 * needed while the app runs.
 */
class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var delayInput: EditText
    private lateinit var periodicInput: EditText
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        urlInput = EditText(this).apply {
            hint = getString(R.string.url_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(MonitorService.urlOf(this@MainActivity))
            setSelection(text.length)
        }

        delayInput = EditText(this).apply {
            hint = getString(R.string.delay_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(MonitorService.delaySecondsOf(this@MainActivity).toString())
            setSelection(text.length)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }

        periodicInput = EditText(this).apply {
            hint = getString(R.string.periodic_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(MonitorService.periodicIntervalSecondsOf(this@MainActivity).toString())
            setSelection(text.length)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }

        val saveButton = Button(this).apply { text = getString(R.string.save_settings) }
        saveButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isEmpty()) {
                toast(R.string.enter_url_first)
                return@setOnClickListener
            }
            val seconds = delayInput.text.toString().trim().toIntOrNull()
            if (seconds == null ||
                seconds !in MonitorService.MIN_DELAY_SECONDS..MonitorService.MAX_DELAY_SECONDS
            ) {
                toast(R.string.delay_invalid)
                return@setOnClickListener
            }
            val periodicSeconds = periodicInput.text.toString().trim().toIntOrNull()
            if (periodicSeconds == null ||
                periodicSeconds !in MonitorService.MIN_PERIODIC_INTERVAL_SECONDS..
                    MonitorService.MAX_PERIODIC_INTERVAL_SECONDS
            ) {
                toast(R.string.periodic_invalid)
                return@setOnClickListener
            }
            MonitorService.saveUrl(this, url)
            MonitorService.saveDelaySeconds(this, seconds)
            MonitorService.savePeriodicIntervalSeconds(this, periodicSeconds)
            // Reschedules the periodic pings immediately in an already-running monitor.
            MonitorService.startApplySettings(this)
            toast(R.string.settings_saved, Toast.LENGTH_LONG)
            refreshStatus()
        }

        val startButton = Button(this).apply { text = getString(R.string.start_monitor) }
        startButton.setOnClickListener {
            // In addition to starting/restarting the monitor, also fire an immediate
            // manual ping of the URL. pingNow starts the service through the exact
            // same path as start (so the monitor still (re)starts), and additionally
            // delivers the ACTION_PING_NOW action that triggers the ping.
            MonitorService.pingNow(this)
            toast(R.string.start_monitor_started)
        }

        val stopButton = Button(this).apply { text = getString(R.string.stop_monitor) }
        stopButton.setOnClickListener {
            stopService(Intent(this, MonitorService::class.java))
            toast(R.string.monitor_stopped)
        }

        val pingNowButton = Button(this).apply { text = getString(R.string.ping_now) }
        pingNowButton.setOnClickListener {
            MonitorService.pingNow(this)
            toast(R.string.ping_now_started)
        }

        val batteryButton = createBatteryButton()

        statusView = TextView(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.FILL_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        layout.addView(textView(R.string.screen_intro), params())
        layout.addView(textView(R.string.url_label), params())
        layout.addView(urlInput, params())
        layout.addView(textView(R.string.delay_label), params())
        layout.addView(delayInput, params())
        layout.addView(textView(R.string.periodic_label), params())
        layout.addView(periodicInput, params())
        layout.addView(saveButton, params())
        layout.addView(startButton, params())
        layout.addView(stopButton, params())
        layout.addView(pingNowButton, params())
        layout.addView(batteryButton, params())
        layout.addView(statusView, params())
        setContentView(layout)

        MonitorService.start(this)
        requestNotificationPermission()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val pm = getSystemService(PowerManager::class.java)
        val batteryIgnored = pm.isIgnoringBatteryOptimizations(packageName)
        val notificationsGranted = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        val batteryText = getString(
            if (batteryIgnored) R.string.status_battery_ok else R.string.status_battery_active,
        )
        val notificationsText = getString(
            if (notificationsGranted) {
                R.string.status_notifications_granted
            } else {
                R.string.status_notifications_denied
            },
        )
        val periodicSeconds = MonitorService.periodicIntervalSecondsOf(this)
        statusView.text = getString(
            R.string.status_line,
            getString(R.string.status_saved_url, MonitorService.urlOf(this)),
            getString(R.string.status_delay, MonitorService.delaySecondsOf(this)),
            getString(
                R.string.status_periodic,
                if (periodicSeconds > 0) "every $periodicSeconds s" else "disabled",
            ),
            getString(R.string.status_battery, batteryText),
            getString(R.string.status_notifications, notificationsText),
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /**
     * The whole point of this utility is that the user explicitly opts into running
     * it "all the time"; the battery-optimization exemption is what makes that work
     * through Doze. This is a self-installed tool, not a Play Store app, so the Play
     * policy behind the [android.annotation.SuppressLint]="BatteryLife" warning does
     * not apply.
     */
    @SuppressLint("BatteryLife")
    private fun createBatteryButton(): Button = Button(this).apply {
        text = getString(R.string.ignore_battery)
        setOnClickListener {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (e: Exception) {
                toast(getString(R.string.battery_settings_error, e.message ?: "unknown error"))
            }
        }
    }

    private fun textView(textRes: Int): TextView = TextView(this).apply {
        setText(textRes)
    }

    private fun toast(textRes: Int, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, textRes, duration).show()
    }

    private fun toast(text: String, duration: Int = Toast.LENGTH_SHORT) {
        Toast.makeText(this, text, duration).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun params(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
}
