package com.mtrack.sms_gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.Telephony
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var etWebhookUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var spSyncDays: Spinner
    private lateinit var btnSave: Button
    private lateinit var btnSyncNow: Button
    private lateinit var tvStatus: TextView

    private val daysOptions = arrayOf("Last 1 Day", "Last 3 Days", "Last 7 Days", "Last 15 Days", "Last 30 Days")
    private val daysValues = arrayOf(1, 3, 7, 15, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etWebhookUrl = findViewById(R.id.etWebhookUrl)
        etApiKey = findViewById(R.id.etApiKey)
        spSyncDays = findViewById(R.id.spSyncDays)
        btnSave = findViewById(R.id.btnSave)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        tvStatus = findViewById(R.id.tvStatus)

        // Spinner Set Up
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, daysOptions)
        spSyncDays.adapter = adapter

        val prefs = getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)
        etWebhookUrl.setText(prefs.getString("webhook_url", "https://erp.mtrack.com.bd/api/webhook.php"))
        etApiKey.setText(prefs.getString("api_key", "MTRACK_SMS_SECURE_TOKEN_2026"))

        requestPermissions()

        btnSave.setOnClickListener {
            val url = etWebhookUrl.text.toString().trim()
            val key = etApiKey.text.toString().trim()

            if (url.isNotEmpty() && key.isNotEmpty()) {
                prefs.edit().putString("webhook_url", url).putString("api_key", key).apply()
                Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show()
                tvStatus.text = "Status: Configured & Ready"
            } else {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            }
        }

        btnSyncNow.setOnClickListener {
            val selectedDaysIndex = spSyncDays.selectedItemPosition
            val daysToSync = daysValues[selectedDaysIndex]
            
            syncOldSMS(daysToSync)
        }
    }

    private fun syncOldSMS(days: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "READ_SMS permission is required!", Toast.LENGTH_SHORT).show()
            return
        }

        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        val timeInMillis = calendar.timeInMillis

        val cursor: Cursor? = contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(timeInMillis.toString()),
            "${Telephony.Sms.DATE} DESC"
        )

        var count = 0
        cursor?.use {
            val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)

            while (it.moveToNext()) {
                val sender = it.getString(addressIdx) ?: ""
                val body = it.getString(bodyIdx) ?: ""

                // Background Worker দিয়ে সার্ভারে পাঠানো
                val inputData = Data.Builder()
                    .putString("sender", sender)
                    .putString("message", body)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(inputData)
                    .build()

                WorkManager.getInstance(this).enqueue(workRequest)
                count++
            }
        }

        Toast.makeText(this, "Initiated sync for $count SMS from last $days day(s)", Toast.LENGTH_LONG).show()
        tvStatus.text = "Status: Syncing $count SMS in background..."
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.INTERNET
        )

        val listPermissionsNeeded = ArrayList<String>()
        for (p in permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p)
            }
        }

        if (listPermissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toTypedArray(), 101)
        }
    }
}
