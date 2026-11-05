package app.seniorremote.text

import app.seniorremote.data.InMemoryDeviceRepository
import app.seniorremote.diagnostics.DiagnosticsLog
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.TvLibrary
import app.seniorremote.testing.FakeController
import app.seniorremote.testing.TV_A
import app.seniorremote.testing.TV_B
import app.seniorremote.testing.otherTv
import app.seniorremote.testing.testTv
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextEntryControllerTest {
    private val controller = FakeController()
    private val repository = InMemoryDeviceRepository(TvLibrary(listOf(testTv(), otherTv(TV_B, "10.0.0.6")), TV_A))
    private val log = DiagnosticsLog()
    private var sent = 0
    private var closed = 0

    private fun TestScope.entry(tvId: String = TV_A) = TextEntryController(
        tvId = tvId,
        repository = repository,
        connection = controller,
        diagnostics = log,
        onSent = { sent++ },
        onClosed = { closed++ },
        context = StandardTestDispatcher(testScheduler),
    ).also { runCurrent() }

    @Test
    fun `empty or whitespace-only text cannot be sent`() = runTest {
        val session = controller.ready(1)
        val e = entry()
        assertFalse(e.ui.value.canSend)
        e.edit("   \t  ")
        runCurrent()
        assertFalse(e.ui.value.canSend)
        e.send()
        runCurrent()
        assertTrue(session.sentTexts.isEmpty())
        e.dispose()
    }

    @Test
    fun `editing sends nothing until Send to TV`() = runTest {
        val session = controller.ready(1)
        val e = entry()
        "news".forEachIndexed { index, _ ->
            e.edit("news".take(index + 1))
            runCurrent()
        }
        advanceTimeBy(60_000)
        assertTrue(session.sentTexts.isEmpty())
        assertEquals(0, sent)
        e.dispose()
    }

    @Test
    fun `text is sent exactly as typed, with no key press, then the draft is gone`() = runTest {
        val session = controller.ready(1)
        val e = entry()
        e.edit("  two  spaces, é 你好 ")
        e.send()
        runCurrent()
        assertEquals(listOf("  two  spaces, é 你好 "), session.sentTexts)
        assertTrue("no Enter, Search or OK afterwards", session.sent.isEmpty())
        assertEquals(1, sent)
        assertEquals("", e.ui.value.draft)
        e.dispose()
    }

    @Test
    fun `rapid taps transmit once`() = runTest {
        val session = controller.ready(1)
        session.sendDelayMs = 300
        val e = entry()
        e.edit("news")
        repeat(10) { e.send() }
        assertTrue(e.ui.value.sending)
        assertFalse(e.ui.value.canSend)
        advanceTimeBy(1_000)
        assertEquals(listOf("news"), session.sentTexts)
        assertEquals(1, sent)
        e.dispose()
    }

    @Test
    fun `switching TV closes the screen, clears the draft and sends nothing`() = runTest {
        val first = controller.ready(1, TV_A)
        val e = entry(TV_A)
        e.edit("private words")
        repository.edit { it.selecting(TV_B) }
        val second = controller.ready(2, TV_B)
        runCurrent()
        assertEquals(1, closed)
        assertEquals("", e.ui.value.draft)
        e.send()
        runCurrent()
        assertTrue(first.sentTexts.isEmpty())
        assertTrue(second.sentTexts.isEmpty())
        e.dispose()
    }

    @Test
    fun `a session of another TV never receives the draft, even if it is the one that is ready`() = runTest {
        val other = controller.ready(5, TV_B)
        val e = entry(TV_A)
        e.edit("news")
        runCurrent()
        assertFalse(e.ui.value.canSend)
        e.send()
        runCurrent()
        assertTrue(other.sentTexts.isEmpty())
        e.dispose()
    }

    @Test
    fun `connection loss keeps the draft, disables sending and never delivers it later`() = runTest {
        val first = controller.ready(1)
        val e = entry()
        e.edit("news")
        runCurrent()
        controller.stateFlow.value = ConnectionState.Recovering
        runCurrent()
        assertEquals("news", e.ui.value.draft)
        assertFalse(e.ui.value.connected)
        e.send()
        runCurrent()

        val second = controller.ready(2)
        advanceTimeBy(60_000)
        assertTrue("no automatic delivery after reconnecting", first.sentTexts.isEmpty() && second.sentTexts.isEmpty())
        assertTrue(e.ui.value.canSend)

        e.send()
        runCurrent()
        assertEquals("only on an explicit tap", listOf("news"), second.sentTexts)
        e.dispose()
    }

    @Test
    fun `a failed send is uncertain, is not retried and keeps the draft`() = runTest {
        val session = controller.ready(1)
        session.failTextSends = true
        val e = entry()
        e.edit("news")
        e.send()
        advanceTimeBy(60_000)
        assertTrue(e.ui.value.uncertain)
        assertEquals("news", e.ui.value.draft)
        assertEquals(0, sent)
        assertEquals(listOf(1L), controller.sendFailures)
        assertTrue(session.sentTexts.isEmpty())
        e.dispose()
    }

    @Test
    fun `a result that arrives after the session changed is uncertain, not success`() = runTest {
        val session = controller.ready(1)
        session.sendDelayMs = 300
        val e = entry()
        e.edit("news")
        e.send()
        runCurrent()
        controller.ready(2)
        advanceTimeBy(1_000)
        assertEquals(0, sent)
        assertTrue(e.ui.value.uncertain)
        e.dispose()
    }

    @Test
    fun `closing while sending ignores the result`() = runTest {
        val session = controller.ready(1)
        session.sendDelayMs = 300
        val e = entry()
        e.edit("news")
        e.send()
        runCurrent()
        e.dispose()
        advanceTimeBy(1_000)
        assertEquals(0, sent)
        assertEquals("", e.ui.value.draft)
    }

    @Test
    fun `a TV that reports standby cannot receive text`() = runTest {
        controller.ready(1)
        controller.stateFlow.value = ConnectionState.Ready(1, TV_A, tvAsleep = true)
        val e = entry()
        e.edit("news")
        runCurrent()
        assertFalse(e.ui.value.canSend)
        e.dispose()
    }

    @Test
    fun `pasted line breaks become spaces, so nothing can submit the search`() = runTest {
        controller.ready(1)
        val e = entry()
        e.edit("one\ntwo\r\nthree\rfour")
        assertEquals("one two three four", e.ui.value.draft)
        e.dispose()
    }

    @Test
    fun `very long text is limited`() = runTest {
        controller.ready(1)
        val e = entry()
        e.edit("x".repeat(5_000))
        assertEquals(TextEntryController.MAX_LENGTH, e.ui.value.draft.length)
        e.dispose()
    }

    @Test
    fun `typed text never reaches the log, on success or failure`() = runTest {
        val session = controller.ready(1)
        val e = entry()
        e.edit("MySecretPhrase")
        e.send()
        runCurrent()
        val failing = controller.ready(2)
        failing.failTextSends = true
        e.edit("AnotherPhrase")
        runCurrent()
        e.send()
        runCurrent()
        assertEquals(listOf("MySecretPhrase"), session.sentTexts)
        val text = log.export()
        assertFalse(text.contains("MySecretPhrase"))
        assertFalse(text.contains("AnotherPhrase"))
        assertTrue("the event itself is still recorded", text.contains("14 characters"))
        e.dispose()
    }
}
