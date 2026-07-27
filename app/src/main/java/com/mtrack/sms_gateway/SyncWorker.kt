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

        if (webhookUrl.isEmpty() || message.isEmpty()) {
            return Result.failure()
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val formBody = FormBody.Builder()
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

            Log.d("SyncWorker", "Server Response: $responseBody")

            return if (response.isSuccessful) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Error sending SMS: ${e.message}")
            return Result.retry()
        }
    }
}