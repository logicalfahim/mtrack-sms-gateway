package com.mtrack.sms_gateway

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AlphaAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val ivLogo = findViewById<ImageView>(R.id.ivSplashLogo)
        val tvStatus = findViewById<TextView>(R.id.tvSplashStatus)

        // Smooth subtle fade-in animation
        val fadeIn = AlphaAnimation(0f, 1f).apply {
            duration = 800
            fillAfter = true
        }
        ivLogo.startAnimation(fadeIn)

        // Status update sequence for enterprise feel
        Handler(Looper.getMainLooper()).postDelayed({
            tvStatus.text = "Checking Gateway Security..."
        }, 600)

        Handler(Looper.getMainLooper()).postDelayed({
            tvStatus.text = "Starting Relay Engine..."
        }, 1100)

        // Transition to MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this@SplashActivity, MainActivity::class.java)
            startActivity(intent)
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 1600)
    }
}
