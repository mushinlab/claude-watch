package dev.mushinlab.claudewatch

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)

    val messages = mutableStateListOf<ChatMessage>()
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    private val speakChannel = Channel<String>(Channel.CONFLATED)
    val replies = speakChannel.receiveAsFlow()

    private var job: Job? = null

    fun newChat() {
        job?.cancel()
        busy = false
        error = null
        messages.clear()
    }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        if (settings.apiKey.isBlank()) {
            error = "Не задан API-ключ. Открой настройки."
            return
        }
        error = null
        messages += ChatMessage(fromUser = true, text = text)
        val history = messages.toList()
        messages += ChatMessage(fromUser = false, text = "")
        val replyIndex = messages.lastIndex
        busy = true

        job = viewModelScope.launch {
            // Deltas arrive per token; batch them into one UI update every ~100 ms.
            val pending = StringBuilder()
            fun flush() {
                val chunk = synchronized(pending) { pending.toString().also { pending.setLength(0) } }
                if (chunk.isNotEmpty() && replyIndex <= messages.lastIndex) {
                    val current = messages[replyIndex]
                    messages[replyIndex] = current.copy(text = current.text + chunk)
                }
            }
            val flusher = launch {
                while (true) {
                    delay(100)
                    flush()
                }
            }
            try {
                val reply = withContext(Dispatchers.IO) {
                    val onStatus = { s: String? -> viewModelScope.launch(Dispatchers.Main.immediate) { status = s }; Unit }
                    ClaudeChat.stream(settings, history, onStatus) { delta ->
                        synchronized(pending) { pending.append(delta) }
                    }
                }
                flusher.cancel()
                if (reply.isBlank()) {
                    messages.removeAt(replyIndex)
                    error = "Claude не ответил. Попробуй переформулировать."
                } else {
                    messages[replyIndex] = messages[replyIndex].copy(text = reply)
                    if (settings.speakReplies) speakChannel.trySend(reply)
                }
            } catch (e: UnauthorizedException) {
                dropFailedTurn(replyIndex)
                error = "Ключ не принят (401). Проверь API-ключ."
            } catch (e: AnthropicServiceException) {
                dropFailedTurn(replyIndex)
                error = "Ошибка API ${e.statusCode()}: ${e.message}"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                dropFailedTurn(replyIndex)
                error = "Нет связи: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                flusher.cancel()
                busy = false
                status = null
            }
        }
    }

    /** Removes the empty reply and the unanswered question so the next turn stays valid. */
    private fun dropFailedTurn(replyIndex: Int) {
        if (replyIndex <= messages.lastIndex) messages.removeAt(replyIndex)
        if (messages.lastOrNull()?.fromUser == true) messages.removeAt(messages.lastIndex)
    }
}
