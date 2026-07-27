package com.mtrack.sms_gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etWebhookUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var btnSave: Button
    private lateinit var btnSyncNow: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etWebhookUrl = findViewById(R.id.etWebhookUrl)
        etApiKey = findViewById(R.id.etApiKey)
        btnSave = findViewById(R.id.btnSave)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        tvStatus = findViewById(R.id.tvStatus)

        val prefs = getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)
        etWebhookUrl.setText(prefs.getString("webhook_url", "https://erp.mtrack.com.bd/api/webhook.php"))
        etApiKey.setText(prefs.getString("api_key", "MTRACK_SMS_SECURE_TOKEN_2026"))

        requestPermissions()

        btnSave.setOnClickListener {
            val url = etWebhookUrl.text.toString().trim()
            val key = etApiKey.text.toString().trim()

            if (url.isNotEmpty() && key.isNotEmpty()) {
                prefs.edit().putString("webhook_url", url).putString("api_key", key).apply()
                Toast.makeText(this, "Settings Saved Successfully!", Toast.LENGTH_SHORT).show()
                tvStatus.text = "Status: Configured & Ready"
            } else {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show()
            }
        }

        btnSyncNow.setOnClickListener {
            Toast.makeText(this, "Test connection initiated...", Toast.LENGTH_SHORT).show()
            tvStatus.text = "Status: Monitoring Active"
        }
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