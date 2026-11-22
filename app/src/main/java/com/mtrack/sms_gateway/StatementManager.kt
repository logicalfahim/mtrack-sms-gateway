package com.mtrack.sms_gateway

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

data class PaymentStatement(
    val trxId: String,
    val platform: String,
    val type: String,
    val sender: String,
    val amount: String,
    val date: String,
    val status: String, // "SYNCED", "FAILED", "PENDING"
    val rawMessage: String = ""
)

object DeduplicationManager {
    private val seenTrxIds = java.util.Collections.synchronizedSet(java.util.LinkedHashSet<String>())

    fun markIfNew(trxId: String): Boolean {
        if (trxId.isEmpty()) return false
        synchronized(seenTrxIds) {
            if (seenTrxIds.contains(trxId)) {
                return false
            }
            seenTrxIds.add(trxId)
            if (seenTrxIds.size > 500) {
                val it = seenTrxIds.iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            return true
        }
    }
}

object StatementManager {

    const val ACTION_STATEMENTS_UPDATED = "com.mtrack.sms_gateway.ACTION_STATEMENTS_UPDATED"
    private const val PREFS_NAME = "MTrackStatements"
    private const val KEY_STATEMENTS = "statement_list"
    private const val KEY_LAST_SYNC_TIME = "last_sync_timestamp"
    private const val MAX_STATEMENTS = 1000

    fun setLastSyncTime(context: Context, time: Long = System.currentTimeMillis()) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_LAST_SYNC_TIME, time).apply()
            context.sendBroadcast(Intent(ACTION_STATEMENTS_UPDATED))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLastSyncTime(context: Context): Long {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        } catch (e: Exception) {
            0L
        }
    }

    fun getFormattedLastSyncTime(context: Context): String {
        val timestamp = getLastSyncTime(context)
        if (timestamp <= 0L) return "Never synced"
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 45_000 -> "Just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            android.text.format.DateUtils.isToday(timestamp) -> {
                val timeFormat = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
                "Today, ${timeFormat.format(java.util.Date(timestamp))}"
            }
            else -> {
                val dateFormat = java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
                dateFormat.format(java.util.Date(timestamp))
            }
        }
    }

    fun recordStatement(
        context: Context,
        trxId: String,
        platform: String,
        type: String,
        sender: String,
        amount: String,
        date: String,
        status: String,
        rawMessage: String = ""
    ) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray(prefs.getString(KEY_STATEMENTS, "[]") ?: "[]")

            // Check if already exists by TrxID to prevent duplicates
            var existingIndex = -1
            var existingSender = ""
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.optString("trxId") == trxId && trxId.isNotEmpty()) {
                    existingIndex = i
                    existingSender = obj.optString("sender", "")
                    break
                }
            }

            // Always preserve customer phone/account over generic operator labels
            val finalSender = if (sender.isNotEmpty() && !sender.equals("bKash", ignoreCase = true) && !sender.equals("Nagad", ignoreCase = true) && !sender.equals("16216", ignoreCase = true)) {
                sender
            } else if (existingSender.isNotEmpty()) {
                existingSender
            } else {
                sender
            }

            val entry = JSONObject().apply {
                put("trxId", trxId)
                put("platform", platform)
                put("type", type)
                put("sender", finalSender)
                put("amount", amount)
                put("date", date)
                put("status", status)
                put("rawMessage", rawMessage)
            }

            val updatedArray = JSONArray()
            if (existingIndex != -1) {
                // Update in place
                for (i in 0 until jsonArray.length()) {
                    if (i == existingIndex) {
                        updatedArray.put(entry)
                    } else {
                        updatedArray.put(jsonArray.getJSONObject(i))
                    }
                }
            } else {
                // Prepend newest first
                updatedArray.put(entry)
                for (i in 0 until minOf(jsonArray.length(), MAX_STATEMENTS - 1)) {
                    updatedArray.put(jsonArray.getJSONObject(i))
                }
            }

            val editor = prefs.edit().putString(KEY_STATEMENTS, updatedArray.toString())
            if (status.equals("SYNCED", ignoreCase = true)) {
                editor.putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            }
            editor.apply()

            // Broadcast update to UI
            context.sendBroadcast(Intent(ACTION_STATEMENTS_UPDATED))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getStatements(context: Context): List<PaymentStatement> {
        val list = mutableListOf<PaymentStatement>()
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray(prefs.getString(KEY_STATEMENTS, "[]") ?: "[]")
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    PaymentStatement(
                        trxId = obj.optString("trxId", "N/A"),
                        platform = obj.optString("platform", "bKash"),
                        type = obj.optString("type", "Receive Money"),
                        sender = obj.optString("sender", "Unknown"),
                        amount = obj.optString("amount", "0.00"),
                        date = obj.optString("date", ""),
                        status = obj.optString("status", "SYNCED"),
                        rawMessage = obj.optString("rawMessage", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun clearFailed(context: Context): Int {
        var removedCount = 0
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray(prefs.getString(KEY_STATEMENTS, "[]") ?: "[]")
            val newArray = JSONArray()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val status = obj.optString("status", "")
                if (status != "FAILED" && status != "PENDING") {
                    newArray.put(obj)
                } else {
                    removedCount++
                }
            }
            prefs.edit().putString(KEY_STATEMENTS, newArray.toString()).apply()
            context.sendBroadcast(Intent(ACTION_STATEMENTS_UPDATED))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return removedCount
    }

    fun cleanOlderThanDays(context: Context, days: Int): Int {
        if (days <= 0) return 0
        var removedCount = 0
        try {
            val cutoffMillis = System.currentTimeMillis() - (days.toLong() * 24L * 60L * 60L * 1000L)
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonArray = JSONArray(prefs.getString(KEY_STATEMENTS, "[]") ?: "[]")
            val newArray = JSONArray()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val dateStr = obj.optString("date", "")
                val recordTime = try {
                    dateFormat.parse(dateStr)?.time ?: System.currentTimeMillis()
                } catch (e: Exception) {
                    System.currentTimeMillis()
                }

                if (recordTime >= cutoffMillis) {
                    newArray.put(obj)
                } else {
                    removedCount++
                }
            }

            if (removedCount > 0) {
                prefs.edit().putString(KEY_STATEMENTS, newArray.toString()).apply()
                context.sendBroadcast(Intent(ACTION_STATEMENTS_UPDATED))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return removedCount
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_STATEMENTS, "[]").apply()
        context.sendBroadcast(Intent(ACTION_STATEMENTS_UPDATED))
    }
}
