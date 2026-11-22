package com.mtrack.sms_gateway

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

object PaymentVoiceNotifier {

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        if (tts != null) return
        try {
            val appContext = context.applicationContext
            mainHandler.post {
                tts = TextToSpeech(appContext) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val result = tts?.setLanguage(Locale.US)
                        isTtsReady = (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED)
                        Log.d("VoiceNotifier", "TTS Initialized successfully (Ready: $isTtsReady)")
                    } else {
                        Log.e("VoiceNotifier", "TTS Initialization failed: $status")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VoiceNotifier", "init error: ${e.message}")
        }
    }

    fun speakPayment(context: Context, platform: String, amount: String) {
        val prefs = context.getSharedPreferences("GatewayPrefs", Context.MODE_PRIVATE)
        val isVoiceEnabled = prefs.getBoolean("voice_alert_enabled", true)
        if (!isVoiceEnabled) return

        val speechText = "$platform payment. $amount Taka received."

        mainHandler.post {
            try {
                if (tts == null) {
                    init(context)
                }

                if (isTtsReady && tts != null) {
                    tts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, "PAYMENT_ALERT_${System.currentTimeMillis()}")
                } else {
                    // Fallback pleasant confirmation chime
                    playConfirmationBeep()
                }
            } catch (e: Exception) {
                Log.e("VoiceNotifier", "speakPayment error: ${e.message}")
                playConfirmationBeep()
            }
        }
    }

    fun playConfirmationBeep() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
