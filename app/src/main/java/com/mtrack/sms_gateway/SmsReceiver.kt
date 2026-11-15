package com.mtrack.sms_gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            
            // Detect SIM Slot / Subscription ID from extras
            val simSlot = detectSimSlot(intent)

            for (sms in messages) {
                val sender = sms.displayOriginatingAddress ?: ""
                val messageBody = sms.messageBody ?: ""

                if (sender.isEmpty() || messageBody.isEmpty()) continue

                val platform = detectPlatform(sender, messageBody)

                Log.d("SmsReceiver", "SMS Received from: $sender [SIM $simSlot, Platform: $platform]")
                GatewayLogger.log(
                    context,
                    "SMS Detected: $sender (SIM $simSlot)",
                    "INFO",
                    platform
                )

                val inputData = Data.Builder()
                    .putString("sender", sender)
                    .putString("message", messageBody)
                    .putString("platform", platform)
                    .putInt("sim_slot", simSlot)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(inputData)
                    .build()

                WorkManager.getInstance(context).enqueue(workRequest)
            }
        }
    }

    private fun detectSimSlot(intent: Intent): Int {
        val slot = intent.getIntExtra("slot", -1)
        if (slot != -1) return slot + 1

        val simSlot = intent.getIntExtra("simSlot", -1)
        if (simSlot != -1) return simSlot + 1

        val phone = intent.getIntExtra("phone", -1)
        if (phone != -1) return phone + 1

        val subscription = intent.getIntExtra("subscription", -1)
        if (subscription != -1) return subscription

        return 1 // Default to SIM 1
    }

    private fun detectPlatform(sender: String, body: String): String {
        val s = sender.lowercase()
        val b = body.lowercase()

        return when {
            s.contains("bkash") || s == "16247" || b.contains("bkash") -> "bKash"
            s.contains("nagad") || s == "16167" || b.contains("nagad") -> "Nagad"
            s.contains("rocket") || s == "16216" || b.contains("rocket") || s.contains("dbbl") -> "Rocket"
            s.contains("upay") || b.contains("upay") -> "Upay"
            b.contains("trxid") || b.contains("trans id") -> "Payment"
            else -> "SMS"
        }
    }
}