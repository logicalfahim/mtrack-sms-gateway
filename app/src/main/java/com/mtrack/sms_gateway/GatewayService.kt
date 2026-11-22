package com.mtrack.sms_gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GatewayService : android.app.Service() {

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatInterval = 10 * 60 * 1000L // 10 minutes
    private var smsObserver: ContentObserver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            triggerHeartbeat()
            heartbeatHandler.postDelayed(this, heartbeatInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerSmsObserver()
        registerNetworkCallback()
        PaymentVoiceNotifier.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        GatewayLogger.log(this, "Gateway Foreground Service started (24/7 Active)", "SUCCESS", "SYSTEM")

        // Start heartbeat cycle
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, 30 * 1000L) // first after 30s

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSmsObserver()
        unregisterNetworkCallback()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        GatewayLogger.log(this, "Gateway Service stopped", "WARNING", "SYSTEM")
    }

    private fun registerSmsObserver() {
        if (smsObserver != null) return
        val handler = Handler(Looper.getMainLooper())
        smsObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                scanRecentInboxSms()
            }
        }
        try {
            contentResolver.registerContentObserver(
                Telephony.Sms.CONTENT_URI,
                true,
                smsObserver!!
            )
            Log.d("GatewayService", "SmsObserver registered on Telephony.Sms.CONTENT_URI")
        } catch (e: Exception) {
            Log.e("GatewayService", "Failed to register SmsObserver: ${e.message}")
        }
    }

    private fun unregisterSmsObserver() {
        smsObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                Log.e("GatewayService", "Failed to unregister SmsObserver: ${e.message}")
            }
            smsObserver = null
        }
    }

    private fun scanRecentInboxSms() {
        try {
            val prefs = getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("service_enabled", true)) return

            val cursor: Cursor? = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE
                ),
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT 5"
            )

            cursor?.use { c ->
                val addressIdx = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndex(Telephony.Sms.DATE)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

                while (c.moveToNext()) {
                    val sender = if (addressIdx != -1) c.getString(addressIdx) ?: "" else ""
                    val body = if (bodyIdx != -1) c.getString(bodyIdx) ?: "" else ""
                    val timeMillis = if (dateIdx != -1) c.getLong(dateIdx) else System.currentTimeMillis()

                    if (sender.isEmpty() || body.isEmpty()) continue

                    val parsed = PaymentFilter.parse(sender, body)
                    if (!parsed.isValid || parsed.trxId.isEmpty()) continue

                    // Deduplication check
                    if (!DeduplicationManager.markIfNew(parsed.trxId)) {
                        continue
                    }

                    // Check if already in StatementManager as SYNCED
                    val existing = StatementManager.getStatements(this).find { it.trxId == parsed.trxId }
                    if (existing != null && existing.status == "SYNCED") {
                        continue
                    }

                    val formattedDate = dateFormat.format(Date(timeMillis))
                    val resolvedSender = if (parsed.senderNumber.isNotEmpty()) parsed.senderNumber else sender

                    GatewayLogger.log(
                        this,
                        "${parsed.platform} (${parsed.type}): Tk ${parsed.amount} [Trx: ${parsed.trxId}]",
                        "INFO",
                        parsed.platform
                    )

                    // Record as PENDING
                    StatementManager.recordStatement(
                        context = this,
                        trxId = parsed.trxId,
                        platform = parsed.platform,
                        type = parsed.type,
                        sender = resolvedSender,
                        amount = parsed.amount,
                        date = formattedDate,
                        status = "PENDING",
                        rawMessage = body
                    )

                    val inputData = Data.Builder()
                        .putString("sender", sender)
                        .putString("sender_number", parsed.senderNumber)
                        .putString("message", body)
                        .putString("platform", parsed.platform)
                        .putString("type", parsed.type)
                        .putString("trx_id", parsed.trxId)
                        .putString("amount", parsed.amount)
                        .putString("sms_date", formattedDate)
                        .putString("sms_timestamp", timeMillis.toString())
                        .putInt("sim_slot", 1)
                        .putString("action", "realtime_sms")
                        .build()

                    val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(inputData)
                        .build()

                    WorkManager.getInstance(this).enqueue(workRequest)

                    // Announce payment via voice/audio alert
                    PaymentVoiceNotifier.speakPayment(this, parsed.platform, parsed.amount)
                }
            }
        } catch (e: Exception) {
            Log.e("GatewayService", "scanRecentInboxSms error: ${e.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val prefs = getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("service_enabled", true)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                        .setInputData(Data.Builder().putString("action", "heartbeat").build())
                        .build()
                    WorkManager.getInstance(applicationContext).enqueue(workRequest)
                } else {
                    val restartIntent = Intent(applicationContext, GatewayService::class.java).apply {
                        setPackage(packageName)
                    }
                    val restartPendingIntent = PendingIntent.getService(
                        applicationContext,
                        101,
                        restartIntent,
                        PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                    alarmManager?.set(
                        android.app.AlarmManager.ELAPSED_REALTIME,
                        android.os.SystemClock.elapsedRealtime() + 1000,
                        restartPendingIntent
                    )
                }
                GatewayLogger.log(applicationContext, "App closed from Recents: Background Service kept active", "INFO", "SYSTEM")
            } catch (e: Exception) {
                Log.e("GatewayService", "onTaskRemoved error: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "M-Track SMS Relay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps SMS Gateway listening 24/7 for payment transactions"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("M-Track Gateway Active")
            .setContentText("24/7 Listening for bKash, Nagad & Rocket SMS...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    retryPendingOfflineStatements()
                }
            }
            cm.registerDefaultNetworkCallback(networkCallback!!)
            Log.d("GatewayService", "NetworkCallback registered successfully")
        } catch (e: Exception) {
            Log.e("GatewayService", "registerNetworkCallback error: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            networkCallback = null
        }
    }

    private fun retryPendingOfflineStatements() {
        try {
            val pending = StatementManager.getStatements(this).filter { it.status == "PENDING" || it.status == "FAILED" }
            if (pending.isEmpty()) return

            Log.d("GatewayService", "Network restored: auto-retrying ${pending.size} pending statements")
            GatewayLogger.log(this, "Network restored: retrying ${pending.size} pending statements", "INFO", "SYSTEM")

            for (st in pending.take(15)) {
                val inputData = Data.Builder()
                    .putString("sender", st.sender)
                    .putString("sender_number", st.sender)
                    .putString("message", st.rawMessage)
                    .putString("platform", st.platform)
                    .putString("type", st.type)
                    .putString("trx_id", st.trxId)
                    .putString("amount", st.amount)
                    .putString("sms_date", st.date)
                    .putString("action", "realtime_sms")
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(inputData)
                    .build()

                WorkManager.getInstance(this).enqueue(workRequest)
            }
        } catch (e: Exception) {
            Log.e("GatewayService", "retryPendingOfflineStatements error: ${e.message}")
        }
    }

    private fun checkBatteryStatus() {
        try {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100

            if (batteryPct <= 15 && !isCharging) {
                sendLowBatteryNotification(batteryPct)
                GatewayLogger.log(this, "Warning: Low Battery ($batteryPct%). Connect charger!", "WARNING", "SYSTEM")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun sendLowBatteryNotification(pct: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚠️ Low Battery Alert ($pct%)")
            .setContentText("Connect phone to charger to maintain 24/7 SMS Gateway uptime.")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager?.notify(2027, notif)
    }

    private fun triggerHeartbeat() {
        checkBatteryStatus()

        // Apply auto-clean policy if configured (0 = Never, 7, 15, 30 days)
        val prefs = getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)
        val cleanDays = prefs.getInt("auto_clean_days", 0)
        if (cleanDays > 0) {
            val purged = StatementManager.cleanOlderThanDays(this, cleanDays)
            if (purged > 0) {
                GatewayLogger.log(this, "Auto-cleaned $purged statements older than $cleanDays days", "INFO", "SYSTEM")
            }
        }

        val inputData = Data.Builder()
            .putString("action", "heartbeat")
            .putString("sender", "DEVICE_HEARTBEAT")
            .putString("message", "PING_HEALTH_CHECK")
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(this).enqueue(workRequest)
    }

    companion object {
        const val CHANNEL_ID = "mtrack_gateway_service_channel"
        const val NOTIFICATION_ID = 2026

        fun start(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GatewayService::class.java)
            context.stopService(intent)
        }
    }
}
