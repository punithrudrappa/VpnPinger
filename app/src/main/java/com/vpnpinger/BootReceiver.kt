package com.vpnpinger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Auto-starts the VPN monitor after the device boots. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            MonitorService.start(context)
        }
    }
}
