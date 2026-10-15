package dev.mushinlab.claudewatch

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.MaterialTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    private var tts: TextToSpeech? = null
    private var showSettings by mutableStateOf(false)

    private val speechLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) vm.send(text)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { }
        lifecycleScope.launch { vm.replies.collect { speak(it) } }

        setContent {
            MaterialTheme {
                if (showSettings) {
                    SettingsScreen(vm.settings, onClose = { showSettings = false })
                } else {
                    ChatScreen(
                        vm = vm,
                        onMic = ::listen,
                        onNewChat = { vm.newChat(); listen() },
                        onSettings = { showSettings = true },
                    )
                }
            }
        }
        if (savedInstanceState == null) handleLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    /** Every launch (button, launcher, assist) opens a fresh chat and starts listening. */
    private fun handleLaunch(intent: Intent?) {
        // Debug-only test hook: adb shell am start -n dev.mushinlab.claudewatch/.MainActivity --es prompt "..."
        showSettings = false
        vm.newChat()
        val prompt = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_PROMPT) else null
        if (vm.settings.apiKey.isBlank()) {
            showSettings = true
        } else if (!prompt.isNullOrBlank()) {
            vm.send(prompt)
        } else {
            listen()
        }
    }

    private fun listen() {
        tts?.stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Спроси Claude")
        try {
            speechLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // No recognizer on this watch: the chat screen still works with the keyboard button.
        }
    }

    private fun speak(text: String) {
        val clean = text.replace(Regex("[*_#`>]"), "")
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    override fun onStop() {
        super.onStop()
        tts?.stop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PROMPT = "prompt"
    }
}
