package com.mtrack.sms_gateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    // Views
    private lateinit var swServiceToggle: SwitchMaterial
    private lateinit var ivStatusDot: ImageView
    private lateinit var tvHeroStatus: TextView
    private lateinit var tvHeroBattery: TextView
    private lateinit var tvHeroNetwork: TextView
    private lateinit var tvHeroSim: TextView
    private lateinit var tvHeroLastPing: TextView

    private lateinit var tvStatTodaySynced: TextView
    private lateinit var tvStatFailed: TextView
    private lateinit var tvStatLastPlatform: TextView

    private lateinit var etWebhookUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var btnTestConnection: Button
    private lateinit var btnSave: Button

    private lateinit var spSyncDays: Spinner
    private lateinit var btnSyncNow: Button
    private lateinit var pbSyncProgress: ProgressBar

    private lateinit var tvTerminalLogs: TextView
    private lateinit var btnRefreshLogs: TextView
    private lateinit var btnClearLogs: TextView

    private lateinit var llBatteryBanner: LinearLayout
    private lateinit var btnFixBattery: Button

    private val daysOptions = arrayOf("Last 1 Day", "Last 3 Days", "Last 7 Days", "Last 15 Days", "Last 30 Days")
    private val daysValues = arrayOf(1, 3, 7, 15, 30)

    // Broadcast receiver for real-time log updates
    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderLogs()
            renderMetrics()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        loadConfig()
        requestSystemPermissions()
        checkBatteryOptimization()
        updateDeviceTelemetry()

        // Auto-start background service if enabled
        val prefs = getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("service_enabled", true)) {
            GatewayService.start(this)
        }

        renderMetrics()
        renderLogs()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(GatewayLogger.ACTION_LOG_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
        updateDeviceTelemetry()
        renderMetrics()
        renderLogs()
        checkBatteryOptimization()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(logReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }

    private fun initViews() {
        swServiceToggle = findViewById(R.id.swServiceToggle)
        ivStatusDot = findViewById(R.id.ivStatusDot)
        tvHeroStatus = findViewById(R.id.tvHeroStatus)
        tvHeroBattery = findViewById(R.id.tvHeroBattery)
        tvHeroNetwork = findViewById(R.id.tvHeroNetwork)
        tvHeroSim = findViewById(R.id.tvHeroSim)
        tvHeroLastPing = findViewById(R.id.tvHeroLastPing)

        tvStatTodaySynced = findViewById(R.id.tvStatTodaySynced)
        tvStatFailed = findViewById(R.id.tvStatFailed)
        tvStatLastPlatform = findViewById(R.id.tvStatLastPlatform)

        etWebhookUrl = findViewById(R.id.etWebhookUrl)
        etApiKey = findViewById(R.id.etApiKey)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnSave = findViewById(R.id.btnSave)

        spSyncDays = findViewById(R.id.spSyncDays)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        pbSyncProgress = findViewById(R.id.pbSyncProgress)

        tvTerminalLogs = findViewById(R.id.tvTerminalLogs)
        btnRefreshLogs = findViewById(R.id.btnRefreshLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)

        llBatteryBanner = findViewById(R.id.llBatteryBanner)
        btnFixBattery = findViewById(R.id.btnFixBattery)

        // Spinner Setup
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, daysOptions)
        spSyncDays.adapter = adapter
    }

    private fun setupListeners() {
        val prefs = getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)

        // Master Service Switch
        swServiceToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("service_enabled", isChecked).apply()
            if (isChecked) {
                GatewayService.start(this)
                updateServiceStatusUI(true)
                GatewayLogger.log(this, "Gateway Service enabled by user", "SUCCESS", "SYSTEM")
            } else {
                GatewayService.stop(this)
                updateServiceStatusUI(false)
                GatewayLogger.log(this, "Gateway Service paused by user", "WARNING", "SYSTEM")
            }
        }

        // Save Configuration
        btnSave.setOnClickListener {
            val url = etWebhookUrl.text.toString().trim()
            val key = etApiKey.text.toString().trim()

            if (url.isNotEmpty() && key.isNotEmpty()) {
                prefs.edit().putString("webhook_url", url).putString("api_key", key).apply()
                Toast.makeText(this, "Credentials saved successfully!", Toast.LENGTH_SHORT).show()
                GatewayLogger.log(this, "API Credentials updated", "SUCCESS", "SYSTEM")
            } else {
                Toast.makeText(this, "Please fill in Webhook URL and API Key", Toast.LENGTH_SHORT).show()
            }
        }

        // Test Connection Ping
        btnTestConnection.setOnClickListener {
            Toast.makeText(this, "Sending Test Ping to Webhook...", Toast.LENGTH_SHORT).show()
            GatewayLogger.log(this, "Test Connection initiated...", "INFO", "SYSTEM")

            val inputData = Data.Builder()
                .putString("action", "test_ping")
                .putString("sender", "TEST_PING")
                .putString("message", "PING_VERIFICATION_TEST")
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(this).enqueue(workRequest)
        }

        // Manual Historical Sync
        btnSyncNow.setOnClickListener {
            val selectedDaysIndex = spSyncDays.selectedItemPosition
            val daysToSync = daysValues[selectedDaysIndex]
            syncOldSMS(daysToSync)
        }

        // Logs Actions
        btnRefreshLogs.setOnClickListener {
            renderLogs()
            renderMetrics()
            Toast.makeText(this, "Logs Refreshed", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs.setOnClickListener {
            GatewayLogger.clearLogs(this)
            renderLogs()
            Toast.makeText(this, "Logs Cleared", Toast.LENGTH_SHORT).show()
        }

        // Battery Banner Fix
        btnFixBattery.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }
    }

    private fun loadConfig() {
        val prefs = getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)
        etWebhookUrl.setText(prefs.getString("webhook_url", "https://erp.mtrack.com.bd/api/webhook.php"))
        etApiKey.setText(prefs.getString("api_key", "MTRACK_SMS_SECURE_TOKEN_2026"))

        val isServiceEnabled = prefs.getBoolean("service_enabled", true)
        swServiceToggle.isChecked = isServiceEnabled
        updateServiceStatusUI(isServiceEnabled)
    }

    private fun updateServiceStatusUI(isRunning: Boolean) {
        if (isRunning) {
            ivStatusDot.setImageResource(R.drawable.ic_dot_green)
            tvHeroStatus.text = "Gateway Active & Listening"
            tvHeroStatus.setTextColor(ContextCompat.getColor(this, R.color.white))
        } else {
            ivStatusDot.setImageResource(R.drawable.ic_dot_red)
            tvHeroStatus.text = "Gateway Paused (Offline)"
            tvHeroStatus.setTextColor(ContextCompat.getColor(this, R.color.status_danger))
        }
    }

    private fun updateDeviceTelemetry() {
        // Battery
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        tvHeroBattery.text = if (isCharging) "⚡ $batteryPct% Charging" else "🔋 $batteryPct% Battery"

        // Network
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        val netType = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "📶 Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "📶 4G/5G"
            else -> "📶 Connected"
        }
        tvHeroNetwork.text = netType

        // SIM
        tvHeroSim.text = "📱 SIM Ready"

        // Stats
        val stats = getSharedPreferences("GatewayStats", Context.MODE_PRIVATE)
        val lastPing = stats.getString("last_sync_time", "Just now")
        tvHeroLastPing.text = "Heartbeat & Relay: Active • Last: $lastPing"
    }

    private fun renderMetrics() {
        val stats = getSharedPreferences("GatewayStats", Context.MODE_PRIVATE)
        tvStatTodaySynced.text = stats.getInt("today_synced", 0).toString()
        tvStatFailed.text = stats.getInt("today_failed", 0).toString()
        tvStatLastPlatform.text = stats.getString("last_platform", "None")
    }

    private fun renderLogs() {
        val logs = GatewayLogger.getLogs(this)
        if (logs.isEmpty()) {
            tvTerminalLogs.text = "[READY] Gateway listening for bKash, Nagad, Rocket SMS...\n[LOG] No recent transactions recorded."
            return
        }

        val builder = SpannableStringBuilder()
        for (item in logs) {
            val color = when (item.type) {
                "SUCCESS" -> ContextCompat.getColor(this, R.color.status_online)
                "FAILED" -> ContextCompat.getColor(this, R.color.status_danger)
                "WARNING" -> ContextCompat.getColor(this, R.color.status_warning)
                else -> ContextCompat.getColor(this, R.color.status_info)
            }

            val line = "[${item.timestamp}] [${item.platform}] ${item.message}\n"
            val start = builder.length
            builder.append(line)
            builder.setSpan(
                ForegroundColorSpan(color),
                start,
                start + line.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        tvTerminalLogs.text = builder
    }

    private fun syncOldSMS(days: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "READ_SMS permission is required!", Toast.LENGTH_SHORT).show()
            return
        }

        pbSyncProgress.visibility = View.VISIBLE
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

                val inputData = Data.Builder()
                    .putString("sender", sender)
                    .putString("message", body)
                    .putString("action", "historical_sync")
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(inputData)
                    .build()

                WorkManager.getInstance(this).enqueue(workRequest)
                count++
            }
        }

        pbSyncProgress.visibility = View.GONE
        GatewayLogger.log(this, "Queued $count historical SMS for sync (Last $days days)", "INFO", "SYSTEM")
        Toast.makeText(this, "Initiated sync for $count SMS from last $days day(s)", Toast.LENGTH_LONG).show()
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
            llBatteryBanner.visibility = if (isIgnoring) View.GONE else View.VISIBLE
        } else {
            llBatteryBanner.visibility = View.GONE
        }
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this, "Please disable battery optimization in device settings", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestSystemPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

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
