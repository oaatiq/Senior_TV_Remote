package app.seniorremote.connection

import app.seniorremote.domain.ConnectFailure
import app.seniorremote.domain.ConnectionState
import app.seniorremote.testing.TV_A
import app.seniorremote.testing.TV_B
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionMachineTest {
    private val config = ConnectionConfig(retryJitter = 0.0)
    private val machine = ConnectionMachine(config, Random(1))

    /** Applies events in order, returning the final transition. */
    private fun run(start: Model, vararg steps: Pair<Event, Long>): Transition {
        var model = start
        var last = Transition(start, emptyList())
        for ((event, now) in steps) {
            last = machine.reduce(model, event, now)
            model = last.model
        }
        return last
    }

    private fun configuredForeground(): Transition = run(
        Model(),
        Event.TargetChanged(TV_A) to 0L,
        Event.Foreground to 0L,
    )

    private fun readyModel(): Model {
        val start = configuredForeground().model
        return machine.reduce(start, Event.AttemptSucceeded(start.activeAttempt!!), 10).model
    }

    @Test
    fun `no saved TV means not configured and no attempts`() {
        val t = run(Model(), Event.Foreground to 0L)
        assertEquals(ConnectionState.NotConfigured, t.model.state)
        assertTrue(t.effects.none { it is Effect.StartAttempt })
    }

    @Test
    fun `configured TV in background stays suspended without connecting`() {
        val t = run(Model(), Event.TargetChanged(TV_A) to 0L)
        assertEquals(ConnectionState.Suspended, t.model.state)
        assertTrue(t.effects.none { it is Effect.StartAttempt })
    }

    @Test
    fun `launch starts exactly one attempt immediately and shows Connecting`() {
        val t = configuredForeground()
        assertEquals(ConnectionState.Connecting, t.model.state)
        assertEquals(1, t.effects.count { it is Effect.StartAttempt })
        assertEquals(1L, t.model.activeAttempt)
    }

    @Test
    fun `success enters Ready with a new generation and accepts the session`() {
        val start = configuredForeground().model
        val t = machine.reduce(start, Event.AttemptSucceeded(1), 50)
        assertEquals(ConnectionState.Ready(1, TV_A), t.model.state)
        assertTrue(Effect.AcceptSession(1, 1) in t.effects)
        assertNull(t.model.activeAttempt)
    }

    @Test
    fun `outdated success is discarded and does not change state`() {
        val start = configuredForeground().model
        // A recovery request while an attempt is in flight must not start a second one.
        val dup = machine.reduce(start, Event.RecoveryRequested("tap"), 5)
        assertTrue(dup.effects.none { it is Effect.StartAttempt })

        val stale = machine.reduce(start, Event.AttemptSucceeded(99), 50)
        assertEquals(ConnectionState.Connecting, stale.model.state)
        assertEquals(listOf<Effect>(Effect.DiscardSession(99)), stale.effects)
    }

    @Test
    fun `outdated failure is ignored`() {
        val start = configuredForeground().model
        val t = machine.reduce(start, Event.AttemptFailed(42, ConnectFailure.UNREACHABLE, "x"), 50)
        assertEquals(start, t.model)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `failure schedules a retry with increasing delays capped near 10 seconds`() {
        var model = configuredForeground().model
        val delays = mutableListOf<Long>()
        var now = 0L
        repeat(10) {
            val failed = machine.reduce(model, Event.AttemptFailed(model.activeAttempt!!, ConnectFailure.UNREACHABLE, "x"), now)
            val retry = failed.effects.filterIsInstance<Effect.ScheduleRetry>().single()
            delays += retry.delayMs
            now += retry.delayMs
            model = machine.reduce(failed.model, Event.RetryTimer(retry.token), now).model
        }
        assertEquals(listOf(250L, 500, 1_000, 2_000, 4_000, 7_000, 10_000, 10_000, 10_000, 10_000), delays)
        assertTrue(delays.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun `jitter stays within bounds and never exceeds the maximum`() {
        val jittery = ConnectionConfig(retryJitter = 0.2)
        val random = Random(7)
        repeat(200) {
            val d = jittery.retryDelay(1, random)
            assertTrue(d in 200L..300L)
            assertTrue(jittery.retryDelay(20, random) <= 10_000)
        }
    }

    @Test
    fun `repeated unreachable failures escalate to TvUnreachable after the configured time`() {
        var model = configuredForeground().model
        val early = machine.reduce(model, Event.AttemptFailed(1, ConnectFailure.UNREACHABLE, "x"), 1_000)
        assertEquals(ConnectionState.Connecting, early.model.state)
        model = early.model
        val token = early.effects.filterIsInstance<Effect.ScheduleRetry>().single().token
        model = machine.reduce(model, Event.RetryTimer(token), 1_300).model
        val late = machine.reduce(model, Event.AttemptFailed(model.activeAttempt!!, ConnectFailure.UNREACHABLE, "x"), 7_000)
        assertEquals(ConnectionState.TvUnreachable, late.model.state)
        // Retries continue in the background while showing TV unavailable.
        assertTrue(late.effects.any { it is Effect.ScheduleRetry })
    }

    @Test
    fun `stale retry timer is ignored`() {
        val model = configuredForeground().model
        val failed = machine.reduce(model, Event.AttemptFailed(1, ConnectFailure.UNREACHABLE, "x"), 100)
        val t = machine.reduce(failed.model, Event.RetryTimer(failed.model.retryToken!! + 5), 500)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `credential rejection requires confirmation before PairingRequired and then stops retrying`() {
        var model = configuredForeground().model
        repeat(2) {
            val t = machine.reduce(model, Event.AttemptFailed(model.activeAttempt!!, ConnectFailure.AUTH_REJECTED, "closed"), 100)
            assertEquals(ConnectionState.Connecting, t.model.state)
            val token = t.effects.filterIsInstance<Effect.ScheduleRetry>().single().token
            model = machine.reduce(t.model, Event.RetryTimer(token), 200).model
        }
        val final = machine.reduce(model, Event.AttemptFailed(model.activeAttempt!!, ConnectFailure.AUTH_REJECTED, "closed"), 300)
        assertEquals(ConnectionState.PairingRequired, final.model.state)
        assertTrue(final.effects.none { it is Effect.ScheduleRetry })
    }

    @Test
    fun `identity mismatch never results in Ready and leads to administrator state`() {
        var model = configuredForeground().model
        var last: Transition? = null
        repeat(3) {
            last = machine.reduce(model, Event.AttemptFailed(model.activeAttempt!!, ConnectFailure.IDENTITY_MISMATCH, "pin"), 100)
            val token = last!!.effects.filterIsInstance<Effect.ScheduleRetry>().singleOrNull()?.token
            model = if (token != null) machine.reduce(last!!.model, Event.RetryTimer(token), 200).model else last!!.model
        }
        assertEquals(ConnectionState.PairingRequired, last!!.model.state)
    }

    @Test
    fun `missing credentials go straight to PairingRequired`() {
        val model = configuredForeground().model
        val t = machine.reduce(model, Event.AttemptFailed(1, ConnectFailure.CREDENTIALS_MISSING, "none"), 10)
        assertEquals(ConnectionState.PairingRequired, t.model.state)
    }

    @Test
    fun `unsupported device stops all attempts`() {
        val model = configuredForeground().model
        val t = machine.reduce(model, Event.AttemptFailed(1, ConnectFailure.UNSUPPORTED, "not atv"), 10)
        assertTrue(t.model.state is ConnectionState.Unsupported)
        assertTrue(t.effects.isEmpty())
        val again = machine.reduce(t.model, Event.RecoveryRequested("tap"), 20)
        assertTrue(again.effects.isEmpty())
    }

    @Test
    fun `stable session loss reconnects immediately and shows Reconnecting`() {
        val ready = readyModel()
        val t = machine.reduce(ready, Event.SessionLost(1, "closed by TV"), 20_000)
        assertEquals(ConnectionState.Recovering, t.model.state)
        assertTrue(Effect.CloseSession in t.effects)
        assertTrue(t.effects.any { it is Effect.StartAttempt })
        assertEquals(0, t.model.failures)
    }

    @Test
    fun `short-lived session loss backs off instead of reconnecting in a tight loop`() {
        val ready = readyModel()
        val t = machine.reduce(ready, Event.SessionLost(1, "closed"), 1_000)
        assertTrue(t.effects.none { it is Effect.StartAttempt })
        assertTrue(t.effects.any { it is Effect.ScheduleRetry })
    }

    @Test
    fun `loss of an outdated session generation is ignored`() {
        val ready = readyModel()
        val t = machine.reduce(ready, Event.SessionLost(0, "old"), 20_000)
        assertEquals(ready, t.model)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `background cancels attempts and retries and keeps a ready session only for the grace period`() {
        val connecting = configuredForeground().model
        val bg = machine.reduce(connecting, Event.Background, 5)
        assertEquals(ConnectionState.Suspended, bg.model.state)
        assertTrue(Effect.CancelAttempt(1) in bg.effects)
        assertNull(bg.model.activeAttempt)

        val ready = readyModel()
        val bgReady = machine.reduce(ready, Event.Background, 100)
        val grace = bgReady.effects.filterIsInstance<Effect.ScheduleGrace>().single()
        assertTrue(bgReady.model.state is ConnectionState.Ready)
        val expired = machine.reduce(bgReady.model, Event.GraceExpired(grace.token), 100 + grace.delayMs)
        assertEquals(ConnectionState.Suspended, expired.model.state)
        assertTrue(Effect.CloseSession in expired.effects)
    }

    @Test
    fun `returning within the grace period validates the existing session instead of reconnecting`() {
        val ready = readyModel()
        val bg = machine.reduce(ready, Event.Background, 100).model
        val fg = machine.reduce(bg, Event.Foreground, 2_000)
        assertTrue(fg.model.state is ConnectionState.Ready)
        assertTrue(Effect.CancelGrace in fg.effects)
        assertTrue(Effect.ValidateSession(1) in fg.effects)
        assertTrue(fg.effects.none { it is Effect.StartAttempt })
    }

    @Test
    fun `stale session on return triggers recovery`() {
        val ready = readyModel()
        val t = machine.reduce(ready, Event.SessionStale(1), 30_000)
        assertEquals(ConnectionState.Recovering, t.model.state)
        assertTrue(t.effects.any { it is Effect.StartAttempt })
    }

    @Test
    fun `foreground after background starts a fresh attempt`() {
        val bg = machine.reduce(configuredForeground().model, Event.Background, 5).model
        val fg = machine.reduce(bg, Event.Foreground, 1_000)
        assertEquals(ConnectionState.Connecting, fg.model.state)
        assertEquals(1, fg.effects.count { it is Effect.StartAttempt })
    }

    @Test
    fun `network loss shows Check Wi-Fi and network return retries immediately`() {
        val ready = readyModel()
        val lost = machine.reduce(ready, Event.NetworkChanged(usable = false, networkReplaced = false), 100)
        assertEquals(ConnectionState.NetworkUnavailable, lost.model.state)
        assertTrue(Effect.CloseSession in lost.effects)

        val tap = machine.reduce(lost.model, Event.RecoveryRequested("tap"), 200)
        assertTrue(tap.effects.isEmpty())

        val back = machine.reduce(lost.model, Event.NetworkChanged(usable = true, networkReplaced = false), 5_000)
        assertEquals(ConnectionState.Recovering, back.model.state)
        assertTrue(back.effects.any { it is Effect.StartAttempt })
    }

    @Test
    fun `replaced network while ready replaces the session`() {
        val ready = readyModel()
        val t = machine.reduce(ready, Event.NetworkChanged(usable = true, networkReplaced = true), 100)
        assertEquals(ConnectionState.Recovering, t.model.state)
        assertTrue(Effect.CloseSession in t.effects)
        assertTrue(t.effects.any { it is Effect.StartAttempt })
    }

    @Test
    fun `recovery request skips a pending backoff`() {
        val model = configuredForeground().model
        val failed = machine.reduce(model, Event.AttemptFailed(1, ConnectFailure.UNREACHABLE, "x"), 10)
        val t = machine.reduce(failed.model, Event.RecoveryRequested("tap"), 20)
        assertTrue(Effect.CancelRetry in t.effects)
        assertTrue(t.effects.any { it is Effect.StartAttempt })
    }

    @Test
    fun `TV announcement skips the backoff wait`() {
        val model = configuredForeground().model
        val failed = machine.reduce(model, Event.AttemptFailed(1, ConnectFailure.UNREACHABLE, "x"), 10)
        val t = machine.reduce(failed.model, Event.TvAnnounced, 20)
        assertTrue(Effect.CancelRetry in t.effects)
        assertTrue(t.effects.any { it is Effect.StartAttempt })
    }

    @Test
    fun `TV announcement during an attempt retries immediately if that attempt fails`() {
        val model = configuredForeground().model
        val flagged = machine.reduce(model, Event.TvAnnounced, 5)
        assertTrue("no second concurrent attempt", flagged.effects.none { it is Effect.StartAttempt })
        assertTrue(flagged.model.retryImmediately)
        val failed = machine.reduce(flagged.model, Event.AttemptFailed(1, ConnectFailure.UNREACHABLE, "x"), 10)
        assertTrue(failed.effects.any { it is Effect.StartAttempt })
        assertTrue(failed.effects.none { it is Effect.ScheduleRetry })
        assertFalse(failed.model.retryImmediately)
    }

    @Test
    fun `TV announcement is ignored when ready or when pairing is required`() {
        val ready = readyModel()
        assertTrue(machine.reduce(ready, Event.TvAnnounced, 100).effects.isEmpty())
        val pairing = ready.copy(state = ConnectionState.PairingRequired)
        assertTrue(machine.reduce(pairing, Event.TvAnnounced, 100).effects.isEmpty())
    }

    @Test
    fun `TV screen reports update Ready without touching the session`() {
        val ready = readyModel()
        val off = machine.reduce(ready, Event.ScreenChanged(1, on = false), 100)
        assertEquals(ConnectionState.Ready(1, TV_A, tvAsleep = true), off.model.state)
        assertTrue(off.effects.isEmpty())
        val on = machine.reduce(off.model, Event.ScreenChanged(1, on = true), 200)
        assertEquals(ConnectionState.Ready(1, TV_A), on.model.state)
    }

    @Test
    fun `screen reports from an old session are ignored`() {
        val ready = readyModel()
        assertEquals(ready, machine.reduce(ready, Event.ScreenChanged(0, on = false), 100).model)
    }

    @Test
    fun `a TV that reported standby and vanished is off, and retries continue`() {
        val asleep = machine.reduce(readyModel(), Event.ScreenChanged(1, on = false), 100).model
        val lost = machine.reduce(asleep, Event.SessionLost(1, "no data"), 20_000)
        assertEquals(ConnectionState.TvOff, lost.model.state)
        assertTrue(lost.effects.any { it is Effect.StartAttempt })
        assertTrue("not a recovery trial", lost.effects.none { it is Effect.MarkRecoveryStart })

        val failed = machine.reduce(lost.model, Event.AttemptFailed(lost.model.activeAttempt!!, ConnectFailure.UNREACHABLE, "timeout"), 60_000)
        assertEquals("status does not change to a fault", ConnectionState.TvOff, failed.model.state)
        assertTrue(failed.effects.any { it is Effect.ScheduleRetry })
    }

    @Test
    fun `TV coming back after being off reconnects`() {
        val asleep = machine.reduce(readyModel(), Event.ScreenChanged(1, on = false), 100).model
        val lost = machine.reduce(asleep, Event.SessionLost(1, "no data"), 20_000).model
        val back = machine.reduce(lost, Event.AttemptSucceeded(lost.activeAttempt!!), 30_000)
        assertEquals(ConnectionState.Ready(2, TV_A), back.model.state)
    }

    @Test
    fun `losing a session without a standby report is still only Reconnecting`() {
        val lost = machine.reduce(readyModel(), Event.SessionLost(1, "closed"), 20_000)
        assertEquals(ConnectionState.Recovering, lost.model.state)
    }

    @Test
    fun `TV off is not claimed again after leaving and returning`() {
        val asleep = machine.reduce(readyModel(), Event.ScreenChanged(1, on = false), 100).model
        val off = machine.reduce(asleep, Event.SessionLost(1, "no data"), 20_000).model
        val background = machine.reduce(off, Event.Background, 21_000).model
        val back = machine.reduce(background, Event.Foreground, 90_000_000)
        assertEquals(ConnectionState.Connecting, back.model.state)
    }

    @Test
    fun `switching TV closes the session and starts one attempt for the new TV`() {
        val ready = readyModel()
        val t = machine.reduce(ready, Event.TargetChanged(TV_B), 100)
        assertEquals(ConnectionState.Connecting, t.model.state)
        assertEquals(TV_B, t.model.target)
        assertTrue(Effect.CloseSession in t.effects)
        assertEquals(TV_B, t.effects.filterIsInstance<Effect.StartAttempt>().single().tvId)
    }

    @Test
    fun `switching TV cancels the attempt for the previous TV and ignores its result`() {
        val connecting = configuredForeground().model
        val switched = machine.reduce(connecting, Event.TargetChanged(TV_B), 50)
        assertTrue(Effect.CancelAttempt(1) in switched.effects)
        val late = machine.reduce(switched.model, Event.AttemptSucceeded(1), 60)
        assertEquals(ConnectionState.Connecting, late.model.state)
        assertEquals(listOf<Effect>(Effect.DiscardSession(1)), late.effects)
        val done = machine.reduce(late.model, Event.AttemptSucceeded(2), 70)
        assertEquals(ConnectionState.Ready(1, TV_B), done.model.state)
    }

    @Test
    fun `switching TV clears the failure history of the previous TV`() {
        val model = configuredForeground().model
        val failed = machine.reduce(model, Event.AttemptFailed(1, ConnectFailure.AUTH_REJECTED, "closed"), 10)
        val switched = machine.reduce(failed.model, Event.TargetChanged(TV_B), 20)
        assertEquals(0, switched.model.failures)
        assertEquals(0, switched.model.rejections)
        assertTrue(Effect.CancelRetry in switched.effects)
    }

    @Test
    fun `switching away from an unreachable or rejected TV connects to the new one`() {
        val stuck = configuredForeground().model.copy(activeAttempt = null, state = ConnectionState.PairingRequired)
        val t = machine.reduce(stuck, Event.TargetChanged(TV_B), 20)
        assertEquals(ConnectionState.Connecting, t.model.state)
        assertTrue(t.effects.any { it is Effect.StartAttempt })
    }

    @Test
    fun `removing the TV closes everything`() {
        val ready = readyModel()
        val t = machine.reduce(ready, Event.TargetChanged(null), 100)
        assertEquals(ConnectionState.NotConfigured, t.model.state)
        assertTrue(Effect.CloseSession in t.effects)
        assertFalse(t.model.hasTv)
    }
}
