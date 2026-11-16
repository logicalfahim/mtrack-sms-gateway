package com.mtrack.sms_gateway

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Drawer & Navigation
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var btnMenuToggle: ImageView
    private lateinit var tvHeaderTitle: TextView
    private lateinit var ivHeaderStatusDot: ImageView
    private lateinit var tvHeaderStatusText: TextView

    // View Containers (Separate Pages)
    private lateinit var viewDashboard: View
    private lateinit var viewStatements: View
    private lateinit var viewActivities: View
    private lateinit var viewSettings: View
    private lateinit var viewAbout: View

    // Sidebar Menu Items
    private lateinit var menuNavDashboard: View
    private lateinit var menuNavStatements: View
    private lateinit var menuNavActivities: View
    private lateinit var menuNavSettings: View
    private lateinit var menuNavAbout: View

    // Dashboard Views
    private lateinit var swServiceToggle: SwitchMaterial
    private lateinit var tvHeroTitle: TextView
    private lateinit var tvHeroSubtitle: TextView
    private lateinit var tvHeroBattery: TextView
    private lateinit var tvHeroNetwork: TextView
    private lateinit var tvHeroSim: TextView

    private lateinit var tvStatTodaySynced: TextView
    private lateinit var tvStatCashIn: TextView
    private lateinit var tvStatFailed: TextView
    private lateinit var tvStatLastPlatform: TextView

    private lateinit var spSyncDays: Spinner
    private lateinit var btnSyncNow: Button
    private lateinit var btnNavStatements: Button
    private lateinit var btnTestPingHome: Button

    // Statements Page Views
    private lateinit var tvStatementCount: TextView
    private lateinit var etSearchStatement: EditText
    private lateinit var llStatementsContainer: LinearLayout
    private lateinit var tvNoStatements: TextView

    // Date & Operator Filter Chips
    private lateinit var chipDateToday: TextView
    private lateinit var chipDateMonth: TextView
    private lateinit var chipDateAll: TextView
    private var activeDateFilter = "TODAY" // Default: TODAY

    private lateinit var chipFilterAll: TextView
    private lateinit var chipFilterBkash: TextView
    private lateinit var chipFilterNagad: TextView
    private lateinit var chipFilterRocket: TextView
    private lateinit var chipFilterCashIn: TextView
    private lateinit var chipFilterFailed: TextView
    private var activeFilter = "ALL" // ALL, bKash, Nagad, Rocket, CashIn, Failed

    // Activities Page Views
    private lateinit var tvTerminalLogs: TextView
    private lateinit var btnRefreshLogs: TextView
    private lateinit var btnClearLogs: TextView

    // Settings Page Views
    private lateinit var etWebhookUrl: EditText
    private lateinit var etApiKey: EditText
    private lateinit var btnSaveSettings: Button
    private lateinit var btnTestConnectionSettings: Button

    // Sync Days Options
    private val daysOptions = arrayOf(
        "This Month (চলতি মাস)",
        "Last 1 Day",
        "Last 3 Days",
        "Last 7 Days",
        "Last 15 Days",
        "Last 30 Days",
        "All Payment SMS"
    )

    // Broadcast receiver for automatic real-time UI refresh (Zero click needed!)
    private val realtimeUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            renderMetrics()
            if (viewStatements.visibility == View.VISIBLE) {
                renderStatements()
            }
            if (viewActivities.visibility == View.VISIBLE) {
                renderLogs()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupSidebarNavigation()
        setupDashboardActions()
        setupStatementsPage()
        setupActivitiesPage()
        setupSettingsPage()
        setupAboutPage()

        requestSystemPermissions()
        loadConfig()
        updateDeviceTelemetry()

        // Auto-start background service if enabled
        val prefs = getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("service_enabled", true)) {
            GatewayService.start(this)
        }

        renderMetrics()
        renderStatements()
        renderLogs()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(GatewayLogger.ACTION_LOG_UPDATED)
            addAction(StatementManager.ACTION_STATEMENTS_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(realtimeUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(realtimeUpdateReceiver, filter)
        }
        updateDeviceTelemetry()
        renderMetrics()
        renderStatements()
        renderLogs()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(realtimeUpdateReceiver)
        } catch (e: Exception) {
            // Ignored
        }
    }

    private fun initViews() {
        drawerLayout = findViewById(R.id.drawerLayout)
        btnMenuToggle = findViewById(R.id.btnMenuToggle)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        ivHeaderStatusDot = findViewById(R.id.ivHeaderStatusDot)
        tvHeaderStatusText = findViewById(R.id.tvHeaderStatusText)

        // View Containers
        viewDashboard = findViewById(R.id.viewDashboard)
        viewStatements = findViewById(R.id.viewStatements)
        viewActivities = findViewById(R.id.viewActivities)
        viewSettings = findViewById(R.id.viewSettings)
        viewAbout = findViewById(R.id.viewAbout)

        // Sidebar Menu Items
        menuNavDashboard = findViewById(R.id.menuNavDashboard)
        menuNavStatements = findViewById(R.id.menuNavStatements)
        menuNavActivities = findViewById(R.id.menuNavActivities)
        menuNavSettings = findViewById(R.id.menuNavSettings)
        menuNavAbout = findViewById(R.id.menuNavAbout)

        // Dashboard Views
        swServiceToggle = findViewById(R.id.swServiceToggle)
        tvHeroTitle = findViewById(R.id.tvHeroTitle)
        tvHeroSubtitle = findViewById(R.id.tvHeroSubtitle)
        tvHeroBattery = findViewById(R.id.tvHeroBattery)
        tvHeroNetwork = findViewById(R.id.tvHeroNetwork)
        tvHeroSim = findViewById(R.id.tvHeroSim)

        tvStatTodaySynced = findViewById(R.id.tvStatTodaySynced)
        tvStatCashIn = findViewById(R.id.tvStatCashIn)
        tvStatFailed = findViewById(R.id.tvStatFailed)
        tvStatLastPlatform = findViewById(R.id.tvStatLastPlatform)

        spSyncDays = findViewById(R.id.spSyncDays)
        btnSyncNow = findViewById(R.id.btnSyncNow)
        btnNavStatements = findViewById(R.id.btnNavStatements)
        btnTestPingHome = findViewById(R.id.btnTestPingHome)

        // Statements Views
        tvStatementCount = findViewById(R.id.tvStatementCount)
        etSearchStatement = findViewById(R.id.etSearchStatement)
        llStatementsContainer = findViewById(R.id.llStatementsContainer)
        tvNoStatements = findViewById(R.id.tvNoStatements)

        chipDateToday = findViewById(R.id.chipDateToday)
        chipDateMonth = findViewById(R.id.chipDateMonth)
        chipDateAll = findViewById(R.id.chipDateAll)

        chipFilterAll = findViewById(R.id.chipFilterAll)
        chipFilterBkash = findViewById(R.id.chipFilterBkash)
        chipFilterNagad = findViewById(R.id.chipFilterNagad)
        chipFilterRocket = findViewById(R.id.chipFilterRocket)
        chipFilterCashIn = findViewById(R.id.chipFilterCashIn)
        chipFilterFailed = findViewById(R.id.chipFilterFailed)

        // Activities Views
        tvTerminalLogs = findViewById(R.id.tvTerminalLogs)
        btnRefreshLogs = findViewById(R.id.btnRefreshLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)

        // Settings Views
        etWebhookUrl = findViewById(R.id.etWebhookUrl)
        etApiKey = findViewById(R.id.etApiKey)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        btnTestConnectionSettings = findViewById(R.id.btnTestConnectionSettings)

        // Spinner Setup
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, daysOptions)
        spSyncDays.adapter = adapter
    }

    private fun setupSidebarNavigation() {
        btnMenuToggle.setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        menuNavDashboard.setOnClickListener {
            switchPage("Dashboard")
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        menuNavStatements.setOnClickListener {
            switchPage("Statements")
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        menuNavActivities.setOnClickListener {
            switchPage("Activities")
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        menuNavSettings.setOnClickListener {
            switchPage("Settings")
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        menuNavAbout.setOnClickListener {
            switchPage("About")
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    private fun switchPage(pageName: String) {
        viewDashboard.visibility = if (pageName == "Dashboard") View.VISIBLE else View.GONE
        viewStatements.visibility = if (pageName == "Statements") View.VISIBLE else View.GONE
        viewActivities.visibility = if (pageName == "Activities") View.VISIBLE else View.GONE
        viewSettings.visibility = if (pageName == "Settings") View.VISIBLE else View.GONE
        viewAbout.visibility = if (pageName == "About") View.VISIBLE else View.GONE

        tvHeaderTitle.text = when (pageName) {
            "Statements" -> "Statements Ledger"
            "Activities" -> "Live Activities"
            "Settings" -> "API Settings"
            "About" -> "About Gateway"
            else -> "M Track"
        }

        if (pageName == "Statements") renderStatements()
        if (pageName == "Activities") renderLogs()
    }

    private fun setupDashboardActions() {
        val prefs = getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)

        swServiceToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("service_enabled", isChecked).apply()
            if (isChecked) {
                GatewayService.start(this)
                updateServiceStatusUI(true)
                GatewayLogger.log(this, "Gateway Service enabled (24/7 Active)", "SUCCESS", "SYSTEM")
            } else {
                GatewayService.stop(this)
                updateServiceStatusUI(false)
                GatewayLogger.log(this, "Gateway Service paused by user", "WARNING", "SYSTEM")
            }
        }

        btnSyncNow.setOnClickListener {
            val selectedIndex = spSyncDays.selectedItemPosition
            startBulkSyncWithProgress(selectedIndex)
        }

        btnNavStatements.setOnClickListener {
            switchPage("Statements")
        }

        btnTestPingHome.setOnClickListener {
            triggerTestPing()
        }
    }

    private fun setupStatementsPage() {
        // Search Filter TextWatcher
        etSearchStatement.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderStatements()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Date Filter Chips (Default: Today)
        val dateChips = listOf(chipDateToday, chipDateMonth, chipDateAll)
        fun resetDateChipStyles() {
            dateChips.forEach { chip ->
                chip.setBackgroundResource(R.drawable.bg_chip_flat)
                chip.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }

        // Set initial Today selection style
        resetDateChipStyles()
        chipDateToday.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary_subtle))
        chipDateToday.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))

        chipDateToday.setOnClickListener {
            activeDateFilter = "TODAY"
            resetDateChipStyles()
            chipDateToday.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary_subtle))
            chipDateToday.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
            renderStatements()
        }

        chipDateMonth.setOnClickListener {
            activeDateFilter = "THIS_MONTH"
            resetDateChipStyles()
            chipDateMonth.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary_subtle))
            chipDateMonth.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
            renderStatements()
        }

        chipDateAll.setOnClickListener {
            activeDateFilter = "ALL"
            resetDateChipStyles()
            chipDateAll.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary_subtle))
            chipDateAll.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
            renderStatements()
        }

        // Operator Filter Chips Click Listeners
        val operatorChips = listOf(chipFilterAll, chipFilterBkash, chipFilterNagad, chipFilterRocket, chipFilterCashIn, chipFilterFailed)
        fun resetOperatorChipStyles() {
            operatorChips.forEach { chip ->
                chip.setBackgroundResource(R.drawable.bg_chip_flat)
            }
        }

        chipFilterAll.setOnClickListener {
            activeFilter = "ALL"
            resetOperatorChipStyles()
            chipFilterAll.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary_subtle))
            renderStatements()
        }

        chipFilterBkash.setOnClickListener {
            activeFilter = "bKash"
            resetOperatorChipStyles()
            chipFilterBkash.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary_subtle))
            renderStatements()
        }

        chipFilterNagad.setOnClickListener {
            activeFilter = "Nagad"
            resetOperatorChipStyles()
            chipFilterNagad.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary_subtle))
            renderStatements()
        }

        chipFilterRocket.setOnClickListener {
            activeFilter = "Rocket"
            resetOperatorChipStyles()
            chipFilterRocket.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary_subtle))
            renderStatements()
        }

        chipFilterCashIn.setOnClickListener {
            activeFilter = "CashIn"
            resetOperatorChipStyles()
            chipFilterCashIn.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary_subtle))
            renderStatements()
        }

        chipFilterFailed.setOnClickListener {
            activeFilter = "Failed"
            resetOperatorChipStyles()
            chipFilterFailed.setBackgroundColor(ContextCompat.getColor(this, R.color.brand_primary_subtle))
            renderStatements()
        }
    }

    private fun setupAboutPage() {
        val rowDevPhone = findViewById<View>(R.id.rowDevPhone)
        val rowDevEmail = findViewById<View>(R.id.rowDevEmail)
        val rowCompanyPhone = findViewById<View>(R.id.rowCompanyPhone)
        val rowCompanyEmail = findViewById<View>(R.id.rowCompanyEmail)

        rowDevPhone?.setOnClickListener {
            val phone = "+8801321119887"
            try {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                startActivity(intent)
            } catch (e: Exception) {
                copyTextToClipboard(phone, "Developer Phone Copied: $phone")
            }
        }

        rowDevEmail?.setOnClickListener {
            val email = "mtrackspprt@gmail.com"
            try {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).apply {
                    putExtra(Intent.EXTRA_SUBJECT, "M Track Payment Gateway Support")
                }
                startActivity(intent)
            } catch (e: Exception) {
                copyTextToClipboard(email, "Developer Email Copied: $email")
            }
        }

        rowCompanyPhone?.setOnClickListener {
            val phone = "+8801321119888"
            try {
                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                startActivity(intent)
            } catch (e: Exception) {
                copyTextToClipboard(phone, "Company Hotline Copied: $phone")
            }
        }

        rowCompanyEmail?.setOnClickListener {
            val email = "mtrackbd@gmail.com"
            try {
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).apply {
                    putExtra(Intent.EXTRA_SUBJECT, "M Track Corporate Inquiry")
                }
                startActivity(intent)
            } catch (e: Exception) {
                copyTextToClipboard(email, "Company Email Copied: $email")
            }
        }
    }

    private fun setupActivitiesPage() {
        btnRefreshLogs.setOnClickListener {
            renderLogs()
            Toast.makeText(this, "Logs Refreshed", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs.setOnClickListener {
            GatewayLogger.clearLogs(this)
            renderLogs()
            Toast.makeText(this, "Logs Cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSettingsPage() {
        val prefs = getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)

        btnSaveSettings.setOnClickListener {
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

        btnTestConnectionSettings.setOnClickListener {
            triggerTestPing()
        }
    }

    private fun triggerTestPing() {
        Toast.makeText(this, "Sending Test Ping to Webhook...", Toast.LENGTH_SHORT).show()
        GatewayLogger.log(this, "Test Connection ping initiated...", "INFO", "SYSTEM")

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
            ivHeaderStatusDot.setImageResource(R.drawable.ic_dot_green)
            tvHeaderStatusText.text = "Live"
            tvHeaderStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_online))

            tvHeroTitle.text = "Gateway Service Active"
            tvHeroTitle.setTextColor(ContextCompat.getColor(this, R.color.brand_primary))
            tvHeroSubtitle.text = "24/7 Listening for bKash, Nagad, Rocket (16216)"
        } else {
            ivHeaderStatusDot.setImageResource(R.drawable.ic_dot_red)
            tvHeaderStatusText.text = "Paused"
            tvHeaderStatusText.setTextColor(ContextCompat.getColor(this, R.color.status_danger))

            tvHeroTitle.text = "Gateway Service Paused"
            tvHeroTitle.setTextColor(ContextCompat.getColor(this, R.color.status_danger))
            tvHeroSubtitle.text = "Turn switch on to enable automatic relaying"
        }
    }

    private fun updateDeviceTelemetry() {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        tvHeroBattery.text = if (isCharging) "⚡ $batteryPct% Charging" else "🔋 $batteryPct% Battery"

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
        tvHeroSim.text = "📱 SIM Ready"
    }

    private fun renderMetrics() {
        val stats = getSharedPreferences("GatewayStats", Context.MODE_PRIVATE)
        val todaySynced = stats.getInt("today_synced", 0)
        val todayFailed = stats.getInt("today_failed", 0)
        val lastPlatform = stats.getString("last_platform", "bKash")

        tvStatTodaySynced.text = todaySynced.toString()
        tvStatFailed.text = todayFailed.toString()
        tvStatLastPlatform.text = lastPlatform

        // Count Cash In from StatementManager
        val allStatements = StatementManager.getStatements(this)
        val cashInCount = allStatements.count { it.type.contains("Cash In", ignoreCase = true) }
        tvStatCashIn.text = cashInCount.toString()
    }

    private fun renderStatements() {
        val all = StatementManager.getStatements(this)
        val query = etSearchStatement.text.toString().trim().lowercase()

        val todayPrefix = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val thisMonthPrefix = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())

        // Filter based on active date chip, operator chip & search text
        val filtered = all.filter { st ->
            // Date Filter: Defaults to TODAY!
            val matchDate = when (activeDateFilter) {
                "TODAY" -> st.date.startsWith(todayPrefix)
                "THIS_MONTH" -> st.date.startsWith(thisMonthPrefix)
                else -> true // "ALL"
            }

            // Operator / Status Filter: bKash, Nagad, Rocket, CashIn show only SYNCED records
            val matchChip = when (activeFilter) {
                "bKash" -> st.platform.equals("bKash", ignoreCase = true) && st.status == "SYNCED"
                "Nagad" -> st.platform.equals("Nagad", ignoreCase = true) && st.status == "SYNCED"
                "Rocket" -> st.platform.equals("Rocket", ignoreCase = true) && st.status == "SYNCED"
                "CashIn" -> st.type.contains("Cash In", ignoreCase = true) && st.status == "SYNCED"
                "Failed" -> st.status == "FAILED" || st.status == "PENDING"
                else -> true
            }

            val matchQuery = if (query.isEmpty()) true else {
                st.trxId.lowercase().contains(query) ||
                st.sender.lowercase().contains(query) ||
                st.amount.lowercase().contains(query) ||
                st.platform.lowercase().contains(query)
            }

            matchDate && matchChip && matchQuery
        }

        tvStatementCount.text = "${filtered.size} records"
        llStatementsContainer.removeAllViews()

        if (filtered.isEmpty()) {
            tvNoStatements.visibility = View.VISIBLE
            llStatementsContainer.addView(tvNoStatements)
            return
        }

        tvNoStatements.visibility = View.GONE
        val inflater = LayoutInflater.from(this)

        for (st in filtered) {
            val cardView = inflater.inflate(R.layout.item_statement_card, llStatementsContainer, false)

            val tvPlatform = cardView.findViewById<TextView>(R.id.tvCardPlatform)
            val tvType = cardView.findViewById<TextView>(R.id.tvCardType)
            val tvStatus = cardView.findViewById<TextView>(R.id.tvCardStatus)
            val tvAmount = cardView.findViewById<TextView>(R.id.tvCardAmount)
            val tvTrxId = cardView.findViewById<TextView>(R.id.tvCardTrxId)
            val tvSender = cardView.findViewById<TextView>(R.id.tvCardSender)
            val tvDate = cardView.findViewById<TextView>(R.id.tvCardDate)
            val btnCopyTrx = cardView.findViewById<View>(R.id.btnCopyTrx)
            val btnCardRetry = cardView.findViewById<TextView>(R.id.btnCardRetry)

            tvPlatform.text = st.platform
            when (st.platform.lowercase()) {
                "bkash" -> tvPlatform.setTextColor(ContextCompat.getColor(this, R.color.operator_bkash))
                "nagad" -> tvPlatform.setTextColor(ContextCompat.getColor(this, R.color.operator_nagad))
                "rocket" -> tvPlatform.setTextColor(ContextCompat.getColor(this, R.color.operator_rocket))
            }

            tvType.text = st.type
            tvAmount.text = "৳ ${st.amount}"
            tvTrxId.text = st.trxId
            tvSender.text = if (st.sender.isNotEmpty()) st.sender else "N/A"
            tvDate.text = st.date

            if (st.status == "SYNCED") {
                tvStatus.text = "✓ Synced"
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_online))
                btnCardRetry.visibility = View.GONE
            } else {
                tvStatus.text = "✕ Failed"
                tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_danger))
                btnCardRetry.visibility = View.VISIBLE
                btnCardRetry.setOnClickListener {
                    retrySingleStatement(st)
                }
            }

            btnCopyTrx.setOnClickListener {
                copyTextToClipboard(st.trxId, "TrxID Copied!")
            }

            llStatementsContainer.addView(cardView)
        }
    }

    private fun retrySingleStatement(st: PaymentStatement) {
        Toast.makeText(this, "Retrying sync for Trx: ${st.trxId}...", Toast.LENGTH_SHORT).show()
        val inputData = Data.Builder()
            .putString("sender", st.sender)
            .putString("message", st.rawMessage.ifEmpty { "Cash In/Payment of Tk ${st.amount} TrxID ${st.trxId}" })
            .putString("platform", st.platform)
            .putString("type", st.type)
            .putString("trx_id", st.trxId)
            .putString("amount", st.amount)
            .putString("sms_date", st.date)
            .putString("action", "retry_sync")
            .build()

        val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .build()

        WorkManager.getInstance(this).enqueue(workRequest)
    }

    private fun renderLogs() {
        val logs = GatewayLogger.getLogs(this)
        if (logs.isEmpty()) {
            tvTerminalLogs.text = "[READY] Gateway listening for bKash, Nagad, Rocket (16216) incoming SMS...\n[LOG] No recent transactions recorded."
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

    /**
     * DYNAMIC LIVE SYNC PROGRESS DIALOG (Solves the "No progress visible" problem!)
     */
    private fun startBulkSyncWithProgress(optionIndex: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "READ_SMS permission is required!", Toast.LENGTH_SHORT).show()
            return
        }

        val calendar = Calendar.getInstance()
        val timeInMillis: Long
        val label: String

        when (optionIndex) {
            0 -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                timeInMillis = calendar.timeInMillis
                label = "This Month"
            }
            1 -> {
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                timeInMillis = calendar.timeInMillis
                label = "Last 1 Day"
            }
            2 -> {
                calendar.add(Calendar.DAY_OF_YEAR, -3)
                timeInMillis = calendar.timeInMillis
                label = "Last 3 Days"
            }
            3 -> {
                calendar.add(Calendar.DAY_OF_YEAR, -7)
                timeInMillis = calendar.timeInMillis
                label = "Last 7 Days"
            }
            4 -> {
                calendar.add(Calendar.DAY_OF_YEAR, -15)
                timeInMillis = calendar.timeInMillis
                label = "Last 15 Days"
            }
            5 -> {
                calendar.add(Calendar.DAY_OF_YEAR, -30)
                timeInMillis = calendar.timeInMillis
                label = "Last 30 Days"
            }
            else -> {
                timeInMillis = 0L
                label = "All Time"
            }
        }

        // Show Dynamic Progress Dialog
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_sync_progress, null)
        val tvProgressStatus = dialogView.findViewById<TextView>(R.id.tvProgressStatus)
        val tvProgressPercent = dialogView.findViewById<TextView>(R.id.tvProgressPercent)
        val tvProgressItem = dialogView.findViewById<TextView>(R.id.tvProgressItem)
        val pbSyncDialog = dialogView.findViewById<ProgressBar>(R.id.pbSyncDialog)
        val btnProgressDismiss = dialogView.findViewById<Button>(R.id.btnProgressDismiss)

        val progressDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        progressDialog.show()

        btnProgressDismiss.setOnClickListener {
            progressDialog.dismiss()
        }

        // Run SMS query on background thread
        Thread {
            val cursor: Cursor? = contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                if (timeInMillis > 0) "${Telephony.Sms.DATE} >= ?" else null,
                if (timeInMillis > 0) arrayOf(timeInMillis.toString()) else null,
                "${Telephony.Sms.DATE} ASC" // Process oldest to newest
            )

            val validPayments = mutableListOf<ParsedPaymentItem>()
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            cursor?.use {
                val addressIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)

                while (it.moveToNext()) {
                    val sender = it.getString(addressIdx) ?: ""
                    val body = it.getString(bodyIdx) ?: ""
                    val smsTimestamp = if (dateIdx != -1) it.getLong(dateIdx) else System.currentTimeMillis()

                    // Strict Payment Filter: Drops Outgoing (bKash to Bank, Cash Out) and Rocket non-16216
                    val parsed = PaymentFilter.parse(sender, body)
                    if (parsed.isValid) {
                        validPayments.add(
                            ParsedPaymentItem(
                                sender = sender,
                                body = body,
                                parsed = parsed,
                                timestamp = smsTimestamp,
                                formattedDate = dateFormat.format(Date(smsTimestamp))
                            )
                        )
                    }
                }
            }

            val total = validPayments.size
            val mainHandler = Handler(Looper.getMainLooper())

            if (total == 0) {
                mainHandler.post {
                    tvProgressStatus.text = "No incoming payment SMS found for $label"
                    tvProgressItem.text = "Completed (0 items)"
                    btnProgressDismiss.text = "Done"
                }
                return@Thread
            }

            mainHandler.post {
                tvProgressStatus.text = "Found $total payment SMS. Relaying to ERP..."
                pbSyncDialog.max = total
            }

            // Relay items with a 350ms throttle to prevent Apache server rate-limit blocks
            for ((index, item) in validPayments.withIndex()) {
                val current = index + 1
                val percent = (current * 100) / total

                // Enqueue work
                val inputData = Data.Builder()
                    .putString("sender", item.sender)
                    .putString("message", item.body)
                    .putString("platform", item.parsed.platform)
                    .putString("type", item.parsed.type)
                    .putString("trx_id", item.parsed.trxId)
                    .putString("amount", item.parsed.amount)
                    .putString("sms_date", item.formattedDate)
                    .putString("sms_timestamp", item.timestamp.toString())
                    .putString("action", "historical_sync")
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(inputData)
                    .build()

                WorkManager.getInstance(this).enqueue(workRequest)

                // Record in StatementManager as PENDING/QUEUED
                StatementManager.recordStatement(
                    context = this,
                    trxId = item.parsed.trxId,
                    platform = item.parsed.platform,
                    type = item.parsed.type,
                    sender = item.parsed.senderNumber,
                    amount = item.parsed.amount,
                    date = item.formattedDate,
                    status = "PENDING",
                    rawMessage = item.body
                )

                mainHandler.post {
                    pbSyncDialog.progress = current
                    tvProgressPercent.text = "$percent%"
                    tvProgressItem.text = "$current / $total • ${item.parsed.platform} ৳${item.parsed.amount} [${item.parsed.trxId}]"
                }

                try {
                    Thread.sleep(350) // 350ms throttle protects Apache IP ban
                } catch (e: Exception) {}
            }

            mainHandler.post {
                tvProgressStatus.text = "✅ Successfully queued $total SMS to ERP!"
                btnProgressDismiss.text = "Done"
                GatewayLogger.log(this, "Bulk sync queued: $total payment SMS ($label)", "SUCCESS", "SYSTEM")
                renderStatements()
                renderMetrics()
            }
        }.start()
    }

    private data class ParsedPaymentItem(
        val sender: String,
        val body: String,
        val parsed: ParsedPayment,
        val timestamp: Long,
        val formattedDate: String
    )

    private fun copyTextToClipboard(text: String, toastMsg: String) {
        if (text.isEmpty() || text == "N/A") return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("TrxID", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
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

        val listNeeded = ArrayList<String>()
        for (p in permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listNeeded.add(p)
            }
        }

        if (listNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, listNeeded.toTypedArray(), 101)
        }
    }
}
