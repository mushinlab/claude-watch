package dev.mushinlab.claudewatch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionTest {
    private val asked = ChatSession().withUserMessage(1, "погода?")

    @Test
    fun freshChatOnlyWhenNothingToShow() {
        assertTrue(ChatSession().shouldStartFreshChat)
        assertFalse(asked.started(2).shouldStartFreshChat)
        assertFalse(asked.started(2).completed(2, "+18").shouldStartFreshChat)
        assertTrue(
            asked
                .started(2)
                .completed(2, "+18")
                .copy(seen = true)
                .shouldStartFreshChat,
        )
        assertFalse(asked.started(2).failed(2, Failure(FailureReason.NETWORK)).shouldStartFreshChat)
    }

    @Test
    fun failureKeepsQuestionAndDropsReply() {
        val failed = asked.started(2).withReplyText(2, "частичный").failed(2, Failure(FailureReason.TIMEOUT))
        assertEquals(TurnStatus.FAILED, failed.status)
        assertEquals(listOf("погода?"), failed.messages.map { it.text })
        assertEquals(listOf("погода?"), failed.historyForRequest().map { it.text })
    }

    @Test
    fun staleReplyIdIsIgnored() {
        val fresh = ChatSession()
        assertSame(fresh, fresh.completed(2, "поздно"))
        assertSame(fresh, fresh.failed(2, Failure(FailureReason.NETWORK)))
    }

    @Test
    fun interruptedPendingTurnBecomesFailed() {
        val restored = asked.started(2).withReplyText(2, "обры").interrupted()
        assertEquals(TurnStatus.FAILED, restored.status)
        assertEquals(FailureReason.INTERRUPTED, restored.failure?.reason)
        assertEquals(1, restored.messages.size)
        assertTrue(restored.hasUnseenResult)
    }

    @Test
    fun paragraphsSkipBlankLines() {
        assertEquals(listOf("a", "b"), ChatMessage(1, false, "a\n\nb").paragraphs)
    }
}
