package dev.mushinlab.claudewatch.data

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStoreTest {
    private val dir: File = Files.createTempDirectory("chat-store").toFile()
    private val store = ChatStore(File(dir, "chat.json"))

    @Test
    fun roundTripsCompletedChat() {
        val session = ChatSession()
            .withUserMessage(1, "погода в Тбилиси")
            .started(2)
            .completed(2, "Солнечно, +18.\nВетер слабый.")
        store.save(session)

        assertEquals(session.copy(usingTools = false), store.load())
    }

    @Test
    fun roundTripsFailureDetail() {
        val session = ChatSession().withUserMessage(1, "q").started(2).failed(2, Failure(FailureReason.API, "529: overloaded"))
        store.save(session)

        assertEquals(Failure(FailureReason.API, "529: overloaded"), store.load().failure)
    }

    @Test
    fun pendingTurnLoadsAsInterrupted() {
        store.save(ChatSession().withUserMessage(1, "q").started(2))

        val loaded = store.load()
        assertEquals(TurnStatus.FAILED, loaded.status)
        assertEquals(FailureReason.INTERRUPTED, loaded.failure?.reason)
        assertEquals(listOf("q"), loaded.messages.map { it.text })
    }

    @Test
    fun corruptFileLoadsEmptyChat() {
        File(dir, "chat.json").writeText("{not json")

        assertEquals(ChatSession(), store.load())
    }
}
