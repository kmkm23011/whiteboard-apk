package com.whiteboard.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etScript: EditText
    private lateinit var etSceneSeconds: EditText
    private lateinit var spResolution: Spinner
    private lateinit var spAspect: Spinner
    private lateinit var swLowHeat: SwitchCompat
    private lateinit var btnPickAudio: Button
    private lateinit var btnCreate: Button
    private lateinit var tvAudioName: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    private var audioUri: Uri? = null
    private val handler = Handler(Looper.getMainLooper())

    private val pickAudio = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // some providers do not allow persistable permission
            }
            audioUri = uri
            tvAudioName.text = "Audio selected"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etScript = findViewById(R.id.etScript)
        etSceneSeconds = findViewById(R.id.etSceneSeconds)
        spResolution = findViewById(R.id.spResolution)
        spAspect = findViewById(R.id.spAspect)
        swLowHeat = findViewById(R.id.swLowHeat)
        btnPickAudio = findViewById(R.id.btnPickAudio)
        btnCreate = findViewById(R.id.btnCreate)
        tvAudioName = findViewById(R.id.tvAudioName)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)

        spResolution.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("480p (fastest)", "720p (recommended)", "1080p (slowest)")
        )
        spResolution.setSelection(1)

        spAspect.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("16:9 Landscape", "9:16 Portrait", "1:1 Square")
        )

        btnPickAudio.setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }

        btnCreate.setOnClickListener { startRender() }

        askNotificationPermission()
        startPolling()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
                )
            }
        }
    }

    private fun startRender() {
        val script = etScript.text.toString().trim()
        if (script.isEmpty()) {
            Toast.makeText(this, "Please type a script first", Toast.LENGTH_SHORT).show()
            return
        }
        if (RenderState.running) {
            Toast.makeText(this, "A render is already running", Toast.LENGTH_SHORT).show()
            return
        }

        val height = when (spResolution.selectedItemPosition) {
            0 -> 480
            2 -> 1080
            else -> 720
        }

        val aspect = when (spAspect.selectedItemPosition) {
            1 -> "9:16"
            2 -> "1:1"
            else -> "16:9"
        }

        val seconds = etSceneSeconds.text.toString().toIntOrNull() ?: 5

        val intent = Intent(this, RenderService::class.java).apply {
            putExtra("script", script)
            putExtra("height", height)
            putExtra("aspect", aspect)
            putExtra("sceneSeconds", seconds.coerceIn(1, 60))
            putExtra("lowHeat", swLowHeat.isChecked)
            putExtra("audioUri", audioUri?.toString())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        tvStatus.text = "Starting..."
        progressBar.progress = 0
    }

    private fun startPolling() {
        handler.post(object : Runnable {
            override fun run() {
                progressBar.progress = RenderState.progress
                tvStatus.text = RenderState.status
                btnCreate.isEnabled = !RenderState.running
                handler.postDelayed(this, 500)
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}

