package com.mtrack.sms_gateway

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)
        val webhookUrl = prefs.getString("webhook_url", "https://erp.mtrack.com.bd/api/webhook.php") ?: ""
        val apiKey = prefs.getString("api_key", "MTRACK_SMS_SECURE_TOKEN_2026") ?: ""

        val action = inputData.getString("action") ?: "sync"
        val sender = inputData.getString("sender") ?: ""
        val message = inputData.getString("message") ?: ""
        val platform = inputData.getString("platform") ?: "bKash"
        val type = inputData.getString("type") ?: "Receive Money"
        val trxId = inputData.getString("trx_id") ?: ""
        val amount = inputData.getString("amount") ?: ""
        val smsDate = inputData.getString("sms_date") ?: ""
        val smsTimestamp = inputData.getString("sms_timestamp") ?: ""
        val simSlot = inputData.getInt("sim_slot", 1)

        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val androidId = try {
            Settings.Secure.getString(applicationContext.contentResolver, Settings.Secure.ANDROID_ID) ?: deviceModel
        } catch (e: Exception) {
            deviceModel
        }

        if (webhookUrl.isEmpty() || message.isEmpty()) {
            Log.e("SyncWorker", "Aborted: Webhook URL or Message is empty.")
            GatewayLogger.log(applicationContext, "Sync aborted: URL or message empty", "FAILED", platform)
            return Result.failure()
        }

        val batteryLevel = getBatteryLevel(applicationContext)
        val isCharging = isDeviceCharging(applicationContext)
        val networkType = getNetworkType(applicationContext)

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()

            val formBody = FormBody.Builder()
                .add("api_key", apiKey)
                .add("action", action)
                .add("sender", sender)
                .add("message", message)
                .add("platform", platform)
                .add("type", type)
                .add("trx_id", trxId)
                .add("amount", amount)
                .add("sms_date", smsDate)
                .add("sms_timestamp", smsTimestamp)
                .add("sim_slot", simSlot.toString())
                .add("device_id", androidId)
                .add("device_name", deviceModel)
                .add("battery_level", batteryLevel.toString())
                .add("is_charging", if (isCharging) "1" else "0")
                .add("network_type", networkType)
                .build()

            val request = Request.Builder()
                .url(webhookUrl)
                .addHeader("X-API-KEY", apiKey)
                .addHeader("User-Agent", "MTrack-Payment-Gateway/2.0 (Android)")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            val responseBody = response.body?.string() ?: ""

            Log.d("SyncWorker", "Server Response: HTTP $code | $responseBody")

            return if (response.isSuccessful) {
                if (action == "heartbeat") {
                    GatewayLogger.log(applicationContext, "Heartbeat acknowledged (HTTP $code)", "SUCCESS", "SYSTEM")
                } else if (action == "test_ping") {
                    GatewayLogger.log(applicationContext, "Test ping verified (HTTP $code)", "SUCCESS", "SYSTEM")
                } else {
                    GatewayLogger.log(applicationContext, "Synced: $platform ৳$amount [Trx: $trxId]", "SUCCESS", platform)
                    if (trxId.isNotEmpty()) {
                        StatementManager.recordStatement(
                            context = applicationContext,
                            trxId = trxId,
                            platform = platform,
                            type = type,
                            sender = sender,
                            amount = amount,
                            date = smsDate,
                            status = "SYNCED",
                            rawMessage = message
                        )
                    }
                }
                Result.success()
            } else {
                GatewayLogger.log(applicationContext, "Server error HTTP $code: $platform (Trx: $trxId)", "FAILED", platform)
                if (trxId.isNotEmpty()) {
                    StatementManager.recordStatement(
                        context = applicationContext,
                        trxId = trxId,
                        platform = platform,
                        type = type,
                        sender = sender,
                        amount = amount,
                        date = smsDate,
                        status = "FAILED",
                        rawMessage = message
                    )
                }
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Network Error: ${e.message}", e)
            val errText = e.localizedMessage ?: "Connection error"
            GatewayLogger.log(applicationContext, "Network Failed: $errText", "FAILED", platform)
            if (trxId.isNotEmpty()) {
                StatementManager.recordStatement(
                    context = applicationContext,
                    trxId = trxId,
                    platform = platform,
                    type = type,
                    sender = sender,
                    amount = amount,
                    date = smsDate,
                    status = "FAILED",
                    rawMessage = message
                )
            }
            return Result.retry()
        }
    }

    private fun getBatteryLevel(context: Context): Int {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        } catch (e: Exception) {
            -1
        }
    }

    private fun isDeviceCharging(context: Context): Boolean {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            false
        }
    }

    private fun getNetworkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "OFFLINE"
            val capabilities = cm.getNetworkCapabilities(network) ?: return "UNKNOWN"
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular (4G/5G)"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Connected"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }
}
