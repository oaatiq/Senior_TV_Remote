package app.seniorremote.command

import app.seniorremote.diagnostics.NoDiagnostics
import app.seniorremote.domain.CommandFeedback
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.RemoteKey
import app.seniorremote.testing.FakeController
import app.seniorremote.testing.TV_A
import app.seniorremote.testing.TV_B
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandDispatcherTest {

    private fun TestScope.dispatcher(controller: FakeController) = CommandDispatcher(
        scope = backgroundScope,
        connection = controller,
        diagnostics = NoDiagnostics,
        clock = { testScheduler.currentTime },
    ).also {
        it.start()
        runCurrent()
    }

    private fun TestScope.now() = testScheduler.currentTime

    @Test
    fun `taps are sent in order`() = runTest {
        val controller = FakeController()
        val session = controller.ready(1)
        val d = dispatcher(controller)
        listOf(RemoteKey.UP, RemoteKey.LEFT, RemoteKey.OK, RemoteKey.BACK).forEach { d.tap(TV_A, it, now()) }
        runCurrent()
        assertEquals(listOf(RemoteKey.UP, RemoteKey.LEFT, RemoteKey.OK, RemoteKey.BACK), session.sent)
    }

    @Test
    fun `press while not ready gives feedback, requests recovery and is never replayed`() = runTest {
        val controller = FakeController()
        val d = dispatcher(controller)
        val feedback = mutableListOf<CommandFeedback>()
        backgroundScope.launch { d.feedback.toList(feedback) }
        runCurrent()

        repeat(5) { d.tap(TV_A, RemoteKey.VOLUME_UP, now()) }
        runCurrent()
        assertTrue(feedback.isNotEmpty() && feedback.all { it is CommandFeedback.NotReady })
        assertEquals(5, controller.recoveryRequests.size) // rate limiting happens in the real controller

        val session = controller.ready(1)
        advanceTimeBy(5_000)
        assertTrue("no backlog may be replayed", session.sent.isEmpty())
    }

    @Test
    fun `hold repeats after the initial delay at the configured interval and stops on release`() = runTest {
        val controller = FakeController()
        val session = controller.ready(1)
        val d = dispatcher(controller)
        d.holdStart(TV_A, RemoteKey.VOLUME_UP, now())
        runCurrent()
        assertEquals(1, session.sent.size) // first action immediately

        advanceTimeBy(399)
        assertEquals(1, session.sent.size) // no repeat before 400 ms
        advanceTimeBy(2)
        assertEquals(2, session.sent.size)
        advanceTimeBy(150 * 4)
        assertEquals(6, session.sent.size)

        d.holdEnd(RemoteKey.VOLUME_UP)
        advanceTimeBy(5_000)
        assertEquals(6, session.sent.size)
    }

    @Test
    fun `non-repeatable keys are sent once even when held`() = runTest {
        val controller = FakeController()
        val session = controller.ready(1)
        val d = dispatcher(controller)
        d.holdStart(TV_A, RemoteKey.POWER, now())
        advanceTimeBy(3_000)
        d.holdEnd(RemoteKey.POWER)
        assertEquals(listOf(RemoteKey.POWER), session.sent)
    }

    @Test
    fun `losing the connection while holding stops repetition and it does not resume`() = runTest {
        val controller = FakeController()
        val session = controller.ready(1)
        val d = dispatcher(controller)
        d.holdStart(TV_A, RemoteKey.RIGHT, now())
        advanceTimeBy(700)
        val sentBeforeLoss = session.sent.size
        controller.stateFlow.value = ConnectionState.Recovering
        advanceTimeBy(1_000)
        val newSession = controller.ready(2)
        advanceTimeBy(3_000)
        assertEquals(sentBeforeLoss, session.sent.size)
        assertTrue("held key must not continue on the new session", newSession.sent.isEmpty())
    }

    @Test
    fun `cancelAll on backgrounding stops repetition immediately`() = runTest {
        val controller = FakeController()
        val session = controller.ready(1)
        val d = dispatcher(controller)
        d.holdStart(TV_A, RemoteKey.VOLUME_DOWN, now())
        advanceTimeBy(600)
        val count = session.sent.size
        d.cancelAll()
        advanceTimeBy(5_000)
        assertEquals(count, session.sent.size)
    }

    @Test
    fun `failed send is not retried and is reported once`() = runTest {
        val controller = FakeController()
        val session = controller.ready(1)
        session.failSends = true
        val d = dispatcher(controller)
        d.tap(TV_A, RemoteKey.MUTE, now())
        runCurrent()
        assertEquals(listOf(1L), controller.sendFailures)
        session.failSends = false
        val next = controller.ready(2)
        advanceTimeBy(5_000)
        assertTrue(session.sent.isEmpty())
        assertTrue("the failed mute must not be resent", next.sent.isEmpty())
    }

    @Test
    fun `commands that wait too long are dropped instead of sent late`() = runTest {
        val controller = FakeController()
        val session = controller.ready(1)
        session.sendDelayMs = 1_000 // a stalled TV
        val d = dispatcher(controller)
        d.tap(TV_A, RemoteKey.DOWN, now())
        d.tap(TV_A, RemoteKey.DOWN, now())
        d.tap(TV_A, RemoteKey.OK, now())
        advanceTimeBy(10_000)
        assertEquals(listOf(RemoteKey.DOWN), session.sent)
    }

    @Test
    fun `commands for a replaced session are dropped`() = runTest {
        val controller = FakeController()
        val session = controller.ready(1)
        session.sendDelayMs = 100
        val d = dispatcher(controller)
        d.tap(TV_A, RemoteKey.UP, now())
        d.tap(TV_A, RemoteKey.DOWN, now())
        runCurrent()
        controller.ready(2) // replaced before the second command is delivered
        advanceTimeBy(1_000)
        assertEquals(listOf(RemoteKey.UP), session.sent)
        assertTrue(controller.sessions.getValue(2).sent.isEmpty())
    }

    @Test
    fun `a press meant for one TV is never delivered to another`() = runTest {
        val controller = FakeController()
        val session = controller.ready(1, TV_B)
        val d = dispatcher(controller)
        val feedback = mutableListOf<CommandFeedback>()
        backgroundScope.launch { d.feedback.toList(feedback) }
        runCurrent()

        // The screen still showed TV A when this press happened, but TV B holds the session.
        d.tap(TV_A, RemoteKey.OK, now())
        d.holdStart(TV_A, RemoteKey.VOLUME_UP, now())
        advanceTimeBy(2_000)
        assertTrue(session.sent.isEmpty())
        assertEquals(2, feedback.size)
    }

    @Test
    fun `queued commands do not cross to the next TV`() = runTest {
        val controller = FakeController()
        val first = controller.ready(1, TV_A)
        first.sendDelayMs = 100
        val d = dispatcher(controller)
        d.tap(TV_A, RemoteKey.UP, now())
        d.tap(TV_A, RemoteKey.OK, now())
        d.tap(TV_A, RemoteKey.MUTE, now())
        runCurrent()
        d.cancelAll()
        val second = controller.ready(2, TV_B)
        advanceTimeBy(2_000)
        assertEquals(listOf(RemoteKey.UP), first.sent)
        assertTrue(second.sent.isEmpty())
    }

    @Test
    fun `switching TV during a held button stops repetition for good`() = runTest {
        val controller = FakeController()
        val first = controller.ready(1, TV_A)
        val d = dispatcher(controller)
        d.holdStart(TV_A, RemoteKey.VOLUME_UP, now())
        advanceTimeBy(700)
        val before = first.sent.size
        val second = controller.ready(2, TV_B)
        advanceTimeBy(5_000)
        assertEquals(before, first.sent.size)
        assertTrue(second.sent.isEmpty())
    }

    @Test
    fun `hold has a safety limit`() = runTest {
        val controller = FakeController()
        val session = controller.ready(1)
        val d = dispatcher(controller)
        d.holdStart(TV_A, RemoteKey.VOLUME_UP, now())
        advanceTimeBy(60_000)
        val count = session.sent.size
        advanceTimeBy(10_000)
        assertEquals(count, session.sent.size)
        assertTrue(count < 60_000 / 150)
    }
}
