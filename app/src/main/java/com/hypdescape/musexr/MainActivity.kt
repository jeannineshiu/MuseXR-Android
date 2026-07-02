package com.hypdescape.musexr

import android.Manifest
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hypdescape.musexr.glasses.GlassesManager
import com.hypdescape.musexr.network.LouvreApiClient
import com.meta.wearable.dat.core.types.RegistrationState
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private val REQUIRED_PERMISSIONS =
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.CAMERA,
            )
    }

    private lateinit var glassesManager: GlassesManager
    private val apiClient = LouvreApiClient()
    private var textToSpeech: TextToSpeech? = null

    private lateinit var statusText: TextView
    private lateinit var connectButton: Button
    private lateinit var askButton: Button

    private val permissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                glassesManager.initialize()
            } else {
                statusText.text = getString(R.string.status_not_connected)
                Toast.makeText(this, "Bluetooth and camera permissions are required", Toast.LENGTH_LONG)
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Must be constructed before onStart(): it registers an ActivityResultLauncher.
        glassesManager = GlassesManager(this)

        statusText = findViewById(R.id.statusText)
        connectButton = findViewById(R.id.connectButton)
        askButton = findViewById(R.id.askButton)

        textToSpeech =
            TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.language = Locale.US
                }
            }

        connectButton.setOnClickListener { glassesManager.connect() }
        askButton.setOnClickListener { askAboutSculpture() }

        observeRegistrationState()
    }

    override fun onStart() {
        super.onStart()
        permissionsLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun observeRegistrationState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                glassesManager.registrationState.collect { state ->
                    statusText.text =
                        getString(
                            when (state) {
                                RegistrationState.REGISTERING -> R.string.status_connecting
                                RegistrationState.REGISTERED -> R.string.status_connected
                                else -> R.string.status_not_connected
                            }
                        )
                    connectButton.isEnabled = state != RegistrationState.REGISTERING
                    askButton.isEnabled = state == RegistrationState.REGISTERED
                }
            }
        }
    }

    private fun askAboutSculpture() {
        askButton.isEnabled = false
        statusText.text = getString(R.string.status_capturing)

        lifecycleScope.launch {
            glassesManager
                .capturePhoto()
                .mapCatching { photo ->
                    apiClient.ask(question = getString(R.string.default_question), image = photo)
                }
                .onSuccess { response -> speak(response.answer) }
                .onFailure { error ->
                    Toast.makeText(
                            this@MainActivity,
                            "Couldn't get an answer: ${error.message}",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                }

            statusText.text = getString(R.string.status_connected)
            askButton.isEnabled = true
        }
    }

    private fun speak(text: String) {
        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "musexr_answer")
    }

    override fun onDestroy() {
        glassesManager.stopSession()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }
}
