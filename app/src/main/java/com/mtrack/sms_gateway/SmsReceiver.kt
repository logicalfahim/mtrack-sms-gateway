package com.mtrack.sms_gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            
            // Detect SIM Slot / Subscription ID from extras
            val simSlot = detectSimSlot(intent)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            for (sms in messages) {
                val sender = sms.displayOriginatingAddress ?: ""
                val messageBody = sms.messageBody ?: ""

                if (sender.isEmpty() || messageBody.isEmpty()) continue

                val timestamp = if (sms.timestampMillis > 0) sms.timestampMillis else System.currentTimeMillis()
                val formattedDate = dateFormat.format(Date(timestamp))

                val platform = detectPlatform(sender, messageBody)
                val type = detectType(messageBody)

                Log.d("SmsReceiver", "SMS Received: $sender [$platform, Type: $type, Date: $formattedDate, SIM: $simSlot]")
                GatewayLogger.log(
                    context,
                    "SMS ($platform • $type): $sender",
                    "INFO",
                    platform
                )

                val inputData = Data.Builder()
                    .putString("sender", sender)
                    .putString("message", messageBody)
                    .putString("platform", platform)
                    .putString("type", type)
                    .putString("sms_date", formattedDate)
                    .putString("sms_timestamp", timestamp.toString())
                    .putInt("sim_slot", simSlot)
                    .putString("action", "realtime_sms")
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
            b.contains("trxid") || b.contains("txnid") -> "Payment"
            else -> "SMS"
        }
    }

    private fun detectType(body: String): String {
        val b = body.lowercase()
        return when {
            b.contains("cash in") || b.contains("cash-in") -> "Cash In"
            b.contains("merchant") || b.contains("payment to") || b.contains("payment tk") -> "Merchant"
            else -> "Receive Money"
        }
    }
}