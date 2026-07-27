package com.mtrack.sms_gateway

import android.content.Context
import android.os.Build
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

        val sender = inputData.getString("sender") ?: ""
        val message = inputData.getString("message") ?: ""
        val deviceId = "${Build.MANUFACTURER}_${Build.MODEL}"

        // ওয়েবহুক ইউআরএল বা মেসেজ ফাঁকা থাকলে এক্সিকিউশন বাতিল করবে
        if (webhookUrl.isEmpty() || message.isEmpty()) {
            Log.e("SyncWorker", "Sync aborted: Webhook URL or Message is empty.")
            return Result.failure()
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            // Header-এর পাশাপাশি POST Body তেও api_key দেওয়া হলো যাতে cPanel কাস্টম হেডার ফিল্টার করলেও সমস্যা না হয়
            val formBody = FormBody.Builder()
                .add("api_key", apiKey)
                .add("sender", sender)
                .add("message", message)
                .add("device_id", deviceId)
                .build()

            val request = Request.Builder()
                .url(webhookUrl)
                .addHeader("X-API-KEY", apiKey)
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            Log.d("SyncWorker", "Server Response Code: ${response.code}")
            Log.d("SyncWorker", "Server Response Body: $responseBody")

            return if (response.isSuccessful) {
                Log.d("SyncWorker", "SMS synced successfully!")
                Result.success()
            } else {
                Log.w("SyncWorker", "Server returned non-200 code. Retrying later...")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error sending SMS sync request: ${e.message}", e)
            return Result.retry()
        }
    }
}
