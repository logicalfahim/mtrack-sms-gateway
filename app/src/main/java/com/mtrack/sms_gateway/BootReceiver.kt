package com.mtrack.sms_gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            val prefs = context.getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)
            val isEnabled = prefs.getBoolean("service_enabled", true)
            
            if (isEnabled) {
                GatewayLogger.log(context, "Device Booted: Auto-starting Gateway Service", "INFO", "SYSTEM")
                GatewayService.start(context)
            }
        }
    }
}
