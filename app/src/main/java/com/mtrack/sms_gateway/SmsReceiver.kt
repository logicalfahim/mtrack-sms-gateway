package com.mtrack.sms_gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val pendingResult = goAsync()
            val appContext = context.applicationContext ?: context

            Thread {
                try {
                    processIncomingSms(appContext, intent)
                } catch (e: Exception) {
                    Log.e("SmsReceiver", "Error processing incoming SMS: ${e.message}", e)
                } finally {
                    try {
                        pendingResult.finish()
                    } catch (e: Exception) {
                        Log.e("SmsReceiver", "Error finishing pendingResult: ${e.message}")
                    }
                }
            }.start()
        }
    }

    private fun processIncomingSms(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val simSlot = detectSimSlot(intent)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        for (sms in messages) {
            val sender = sms.displayOriginatingAddress ?: ""
            val messageBody = sms.messageBody ?: ""

            if (sender.isEmpty() || messageBody.isEmpty()) continue

            // 1. Strict Incoming Payment Filter
            // Drops any outgoing SMS (e.g. bKash to Bank, Cash Out) and non-bKash/Nagad/Rocket SMS
            // Rocket is strictly sender 16216
            val parsed = PaymentFilter.parse(sender, messageBody)
            if (!parsed.isValid || parsed.trxId.isEmpty()) {
                Log.d("SmsReceiver", "Ignored non-incoming/non-payment SMS from: $sender")
                continue
            }

            // Check deduplication (avoids double-sync if ContentObserver also catches this SMS)
            if (!DeduplicationManager.markIfNew(parsed.trxId)) {
                Log.d("SmsReceiver", "Skipping duplicate TrxID: ${parsed.trxId}")
                continue
            }

            val timestamp = if (sms.timestampMillis > 0) sms.timestampMillis else System.currentTimeMillis()
            val formattedDate = dateFormat.format(Date(timestamp))
            val resolvedSender = if (parsed.senderNumber.isNotEmpty()) parsed.senderNumber else sender

            Log.d("SmsReceiver", "Incoming Payment: ${parsed.platform} [${parsed.type}, Trx: ${parsed.trxId}, Tk ${parsed.amount}]")

            // Immediately log in Live Terminal
            GatewayLogger.log(
                context,
                "${parsed.platform} (${parsed.type}): Tk ${parsed.amount} [Trx: ${parsed.trxId}]",
                "INFO",
                parsed.platform
            )

            // Announce payment via voice/audio alert
            PaymentVoiceNotifier.speakPayment(context, parsed.platform, parsed.amount)

            // Record in Statements Ledger as PENDING
            StatementManager.recordStatement(
                context = context,
                trxId = parsed.trxId,
                platform = parsed.platform,
                type = parsed.type,
                sender = resolvedSender,
                amount = parsed.amount,
                date = formattedDate,
                status = "PENDING",
                rawMessage = messageBody
            )

            // Enqueue background sync worker with Expedited execution
            val inputData = Data.Builder()
                .putString("sender", sender)
                .putString("sender_number", parsed.senderNumber)
                .putString("message", messageBody)
                .putString("platform", parsed.platform)
                .putString("type", parsed.type)
                .putString("trx_id", parsed.trxId)
                .putString("amount", parsed.amount)
                .putString("sms_date", formattedDate)
                .putString("sms_timestamp", timestamp.toString())
                .putInt("sim_slot", simSlot)
                .putString("action", "realtime_sms")
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
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

        return 1
    }
}