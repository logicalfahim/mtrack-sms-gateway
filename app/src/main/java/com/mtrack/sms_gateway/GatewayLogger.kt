package com.mtrack.sms_gateway

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val message: String,
    val type: String, // SUCCESS, FAILED, INFO, WARNING
    val platform: String = "SYSTEM"
)

object GatewayLogger {

    const val ACTION_LOG_UPDATED = "com.mtrack.sms_gateway.ACTION_LOG_UPDATED"
    private const val PREFS_NAME = "GatewayLogsPrefs"
    private const val KEY_LOGS = "recent_logs"
    private const val MAX_LOGS = 50

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun log(context: Context, message: String, type: String = "INFO", platform: String = "SYSTEM") {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val currentLogsJson = prefs.getString(KEY_LOGS, "[]") ?: "[]"
            val jsonArray = JSONArray(currentLogsJson)

            val newEntry = JSONObject().apply {
                put("timestamp", timeFormat.format(Date()))
                put("message", message)
                put("type", type)
                put("platform", platform)
            }

            // Keep only latest MAX_LOGS (prepend to show newest first)
            val updatedArray = JSONArray()
            updatedArray.put(newEntry)
            for (i in 0 until minOf(jsonArray.length(), MAX_LOGS - 1)) {
                updatedArray.put(jsonArray.getJSONObject(i))
            }

            prefs.edit().putString(KEY_LOGS, updatedArray.toString()).apply()

            // Update stats
            updateMetrics(context, type, platform)

            // Notify UI
            val intent = Intent(ACTION_LOG_UPDATED)
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLogs(context: Context): List<LogEntry> {
        val list = mutableListOf<LogEntry>()
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray(prefs.getString(KEY_LOGS, "[]") ?: "[]")
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    LogEntry(
                        timestamp = obj.optString("timestamp", ""),
                        message = obj.optString("message", ""),
                        type = obj.optString("type", "INFO"),
                        platform = obj.optString("platform", "SYSTEM")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun clearLogs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LOGS, "[]").apply()
        context.sendBroadcast(Intent(ACTION_LOG_UPDATED))
    }

    private fun updateMetrics(context: Context, type: String, platform: String) {
        val prefs = context.getSharedPreferences("GatewayStats", Context.MODE_PRIVATE)
        val today = dateFormat.format(Date())
        val lastDate = prefs.getString("last_date", "")

        val editor = prefs.edit()
        if (lastDate != today) {
            editor.putString("last_date", today)
            editor.putInt("today_synced", 0)
            editor.putInt("today_failed", 0)
        }

        if (type == "SUCCESS") {
            val current = prefs.getInt("today_synced", 0)
            editor.putInt("today_synced", current + 1)
            editor.putString("last_platform", platform)
            editor.putString("last_sync_time", timeFormat.format(Date()))
        } else if (type == "FAILED") {
            val current = prefs.getInt("today_failed", 0)
            editor.putInt("today_failed", current + 1)
        }

        editor.apply()
    }
}
