package com.rs.ocportable.wear

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * One glance: what the agent is doing, and the one decision it needs. Everything else lives on the
 * phone.
 */
class WatchActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var phaseText: TextView
    private lateinit var activityText: TextView
    private lateinit var pendingText: TextView
    private lateinit var pendingScroll: ScrollView
    private lateinit var approve: Button
    private lateinit var deny: Button
    private lateinit var stop: Button
    private lateinit var talk: Button

    private lateinit var client: WatchClient
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var audioActive = false
    private var answeredRequest: String? = null
    private val audio by lazy { WatchAudio() }

    private val poll = object : Runnable {
        private var ticks = 0

        override fun run() {
            render()
            ticks++
            if (ticks % 10 == 0) client.refresh()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        WearState.load(this)
        client = WatchClient(this) { connected ->
            runOnUiThread {
                if (connected) client.sendCommand("refresh")
                render()
            }
        }
        client.refresh()
        if (client.connected) client.sendCommand("refresh")
        main.post(poll)
        requestBluetoothIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        WearState.load(this)
        client.refresh()
        if (client.connected) client.sendCommand("refresh")
        render()
    }

    override fun onPause() {
        if (listening) {
            recognizer?.cancel()
            finishListening()
        }
        if (audioActive) {
            audio.cancel()
            audioActive = false
            talk.text = "Talk"
        }
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        audio.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else if (requestCode == REQ_AUDIO) {
            toast("Microphone permission is required.")
        } else if (requestCode == REQ_BLUETOOTH) {
            client.refresh()
            if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                toast("Bluetooth permission is required for the watch link.")
            }
        }
    }

    private fun buildUi() {
        phaseText = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        activityText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#B8B8C0"))
            setPadding(0, dp(8), 0, dp(8))
        }
        pendingText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        pendingScroll = ScrollView(this).apply {
            visibility = View.GONE
            addView(pendingText, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        approve = button("Approve") { answer("approve") }
        deny = button("Deny") { answer("deny") }
        stop = button("Stop") { client.sendCommand("stop"); activityText.text = "Stopping…" }
        talk = button("Talk")
        talk.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    askToTalk()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopTalk()
                    true
                }

                else -> false
            }
        }

        val decisions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(approve, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(deny, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(stop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(talk, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(phaseText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(activityText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(pendingScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(decisions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(controls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
    }

    private fun button(label: String, action: (() -> Unit)? = null): Button = Button(this).apply {
        text = label
        isAllCaps = false
        minHeight = dp(48)
        setOnClickListener { action?.invoke() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun render() {
        val status = WearState.status
        val connected = client.connected
        if (!connected) {
            phaseText.text = "Disconnected"
            activityText.text = "Move closer to the phone"
            pendingScroll.visibility = View.GONE
            approve.visibility = View.GONE
            deny.visibility = View.GONE
            stop.visibility = View.GONE
            talk.isEnabled = false
            return
        }
        talk.isEnabled = true
        if (status == null) {
            phaseText.text = "Waiting for phone"
            activityText.text = "Open the app on your phone"
            pendingScroll.visibility = View.GONE
            approve.visibility = View.GONE
            deny.visibility = View.GONE
            stop.visibility = View.GONE
            return
        }
        when (status.phase) {
            "waiting" -> {
                phaseText.text = "Approval needed"
                activityText.text = "Review and answer"
                pendingText.text = status.pending?.text ?: "Permission requested"
                pendingScroll.visibility = View.VISIBLE
                approve.visibility = View.VISIBLE
                deny.visibility = View.VISIBLE
                val answerable = status.pending?.requestID != answeredRequest
                approve.isEnabled = answerable
                deny.isEnabled = answerable
                stop.visibility = View.GONE
            }

            "running" -> {
                phaseText.text = "Working"
                activityText.text = buildString {
                    append(status.activity.ifBlank { "working" })
                    val elapsed = elapsed(status)
                    if (elapsed.isNotBlank()) append(" · $elapsed")
                }
                pendingScroll.visibility = View.GONE
                approve.visibility = View.GONE
                deny.visibility = View.GONE
                stop.visibility = if (status.sessionID != null) View.VISIBLE else View.GONE
            }

            else -> {
                phaseText.text = "Idle"
                activityText.text = status.activity.ifBlank { "last task complete" }
                pendingScroll.visibility = View.GONE
                approve.visibility = View.GONE
                deny.visibility = View.GONE
                stop.visibility = View.GONE
            }
        }
    }

    private fun answer(action: String) {
        answeredRequest = WearState.status?.pending?.requestID
        client.sendCommand(action)
        approve.isEnabled = false
        deny.isEnabled = false
        activityText.text = "Sent…"
    }

    private fun elapsed(status: WearProtocol.Status): String {
        if (status.startedAt <= 0) return ""
        val seconds = ((System.currentTimeMillis() - status.startedAt) / 1000).coerceAtLeast(0)
        return if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"
    }

    private fun askToTalk() {
        if (!client.connected) {
            toast("Phone is not connected.")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            return
        }
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            startListening()
        } else {
            startAudio()
        }
    }

    private fun startAudio() {
        if (!audio.start()) {
            toast("Could not start the microphone.")
            return
        }
        audioActive = true
        talk.text = "Listening…"
    }

    private fun stopTalk() {
        if (!audioActive) {
            stopListening()
            return
        }
        val bytes = audio.stop()
        audioActive = false
        if (bytes.isEmpty()) {
            talk.text = "Talk"
            return
        }
        talk.text = "Sending…"
        client.sendAudio(bytes) { sent ->
            runOnUiThread {
                talk.text = "Talk"
                activityText.text = if (sent) "Sent to phone" else "Send failed"
            }
        }
    }

    private fun requestBluetoothIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT < 31) return
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH)
        }
    }

    private fun startListening() {
        if (listening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("Voice is not available on this watch.")
            return
        }
        val speech = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = speech
        listening = true
        talk.text = "Listening…"
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                toast("Did not catch that.")
                finishListening()
            }

            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finishListening()
                if (!text.isNullOrBlank()) {
                    client.sendCommand("prompt", WearState.status, text)
                    activityText.text = "Sent to phone"
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    activityText.text = it
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speech.startListening(intent)
    }

    private fun stopListening() {
        if (!listening) return
        recognizer?.stopListening()
    }

    private fun finishListening() {
        listening = false
        talk.text = "Talk"
        destroyRecognizer()
    }

    private fun destroyRecognizer() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQ_AUDIO = 20
        const val REQ_BLUETOOTH = 21
    }
}
