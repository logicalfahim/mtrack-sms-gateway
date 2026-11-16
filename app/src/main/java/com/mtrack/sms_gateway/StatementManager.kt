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

object StatementManager {

    const val ACTION_STATEMENTS_UPDATED = "com.mtrack.sms_gateway.ACTION_STATEMENTS_UPDATED"
    private const val PREFS_NAME = "MTrackStatements"
    private const val KEY_STATEMENTS = "statement_list"
    private const val MAX_STATEMENTS = 200

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
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.optString("trxId") == trxId && trxId.isNotEmpty()) {
                    existingIndex = i
                    break
                }
            }

            val entry = JSONObject().apply {
                put("trxId", trxId)
                put("platform", platform)
                put("type", type)
                put("sender", sender)
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

            prefs.edit().putString(KEY_STATEMENTS, updatedArray.toString()).apply()

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

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_STATEMENTS, "[]").apply()
        context.sendBroadcast(Intent(ACTION_STATEMENTS_UPDATED))
    }
}
