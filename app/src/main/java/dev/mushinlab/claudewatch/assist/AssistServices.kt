package dev.mushinlab.claudewatch.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.mushinlab.claudewatch.MainActivity

/** Registered as the system digital assistant; the side-button long-press lands here. */
class ClaudeVoiceInteractionService : VoiceInteractionService()

class ClaudeSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = ClaudeSession(this)
}

/** No overlay UI: the session just opens the chat activity with a fresh conversation. */
class ClaudeSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startAssistantActivity(intent)
        hide()
    }
}

/** Stub required by the voice interaction spec; speech input goes through RecognizerIntent. */
class ClaudeRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
