package app.seniorremote.connection

import app.seniorremote.domain.ConnectFailure
import app.seniorremote.domain.ConnectionState
import kotlin.random.Random

/** Retry and lifecycle timing. Starting values; tune against the real TV. */
data class ConnectionConfig(
    /** Delay before the retry that follows the n-th consecutive failure (index n-1). */
    val retryDelaysMs: List<Long> = listOf(250, 500, 1_000, 2_000, 4_000, 7_000, 10_000),
    val maxRetryDelayMs: Long = 10_000,
    /** +/- fraction applied to each retry delay so retries do not synchronize. */
    val retryJitter: Double = 0.2,
    /** How long attempts may fail before the status changes to "TV unavailable". */
    val escalateToUnreachableAfterMs: Long = 6_000,
    /** Consecutive credential/identity rejections before asking for the administrator. */
    val rejectionsBeforePairingRequired: Int = 3,
    /** A session that lasted at least this long resets the retry backoff. */
    val stableSessionMs: Long = 10_000,
    /** Keep the session this long after the app leaves the foreground, for quick app switches. */
    val backgroundGraceMs: Long = 30_000,
    /** On return to the foreground, a session silent for longer than this is replaced. */
    val sessionFreshnessMs: Long = 15_000,
    /** Upper bound for one attempt including any rediscovery. */
    val attemptTimeoutMs: Long = 12_000,
    /** Ignore repeated recovery requests (e.g. rapid taps) closer together than this. */
    val minRecoveryRequestIntervalMs: Long = 1_000,
    val rediscoveryMinIntervalMs: Long = 15_000,
    val rediscoveryTimeoutMs: Long = 4_000,
) {
    fun retryDelay(consecutiveFailures: Int, random: Random): Long {
        val base = retryDelaysMs[(consecutiveFailures - 1).coerceIn(0, retryDelaysMs.lastIndex)]
        val jitter = 1.0 + retryJitter * (random.nextDouble() * 2 - 1)
        return (base * jitter).toLong().coerceIn(0, maxRetryDelayMs)
    }
}

/** Inputs to the state machine. Every external signal is turned into one of these. */
sealed interface Event {
    /** The selected TV changed, its pairing was renewed, or no TV is selected any more. */
    data class TargetChanged(val tvId: String?) : Event
    data object Foreground : Event
    data object Background : Event
    data class GraceExpired(val token: Long) : Event
    data class NetworkChanged(val usable: Boolean, val networkReplaced: Boolean) : Event
    data class RecoveryRequested(val reason: String) : Event
    data class AttemptSucceeded(val attemptId: Long) : Event
    data class AttemptFailed(val attemptId: Long, val failure: ConnectFailure, val detail: String) : Event
    data class SessionLost(val generation: Long, val reason: String) : Event
    data class RetryTimer(val token: Long) : Event
    data class SessionStale(val generation: Long) : Event

    /** The TV reported its screen turning on or off on session [generation]. */
    data class ScreenChanged(val generation: Long, val on: Boolean) : Event

    /** The saved TV (re)appeared in network discovery, e.g. after a restart. */
    data object TvAnnounced : Event
}

/** Side effects requested by the state machine, executed by [ConnectionManager]. */
sealed interface Effect {
    data class StartAttempt(val attemptId: Long, val tvId: String) : Effect
    data class CancelAttempt(val attemptId: Long) : Effect
    data class AcceptSession(val attemptId: Long, val generation: Long) : Effect
    data class DiscardSession(val attemptId: Long) : Effect
    data object CloseSession : Effect
    data class ScheduleRetry(val token: Long, val delayMs: Long) : Effect
    data object CancelRetry : Effect
    data class ScheduleGrace(val token: Long, val delayMs: Long) : Effect
    data object CancelGrace : Effect
    data class ValidateSession(val generation: Long) : Effect

    /** Timing only. [reachableAgain] marks a signal that the network or TV has come back. */
    data class MarkRecoveryStart(val reachableAgain: Boolean) : Effect
}

data class Model(
    val state: ConnectionState = ConnectionState.NotConfigured,
    /** The TV every attempt and session belongs to. */
    val target: String? = null,
    val foreground: Boolean = false,
    val networkUsable: Boolean = true,
    val attemptSeq: Long = 0,
    val activeAttempt: Long? = null,
    val retryToken: Long? = null,
    val graceToken: Long? = null,
    val tokenSeq: Long = 0,
    val generation: Long = 0,
    val cycleStartedAt: Long? = null,
    val failures: Int = 0,
    val rejections: Int = 0,
    val readySince: Long? = null,
    /** The TV announced itself while an attempt was running: retry at once if that attempt fails. */
    val retryImmediately: Boolean = false,
) {
    val hasTv: Boolean get() = target != null
}

data class Transition(val model: Model, val effects: List<Effect>)

/**
 * The connection state machine as a pure function: (model, event, time) -> (model, effects).
 * Only [ConnectionManager] calls it, from a single coroutine, so state has exactly one owner.
 *
 * Rules enforced here:
 *  - at most one attempt in flight and at most one session, always for the current target TV;
 *  - results of cancelled or superseded attempts are discarded (attempt ids / generations);
 *  - Ready only after the transport reports a fully established session;
 *  - retries back off and stop while the app is not visible;
 *  - commands are never queued here, so nothing can be replayed after reconnecting.
 */
class ConnectionMachine(
    private val config: ConnectionConfig,
    private val random: Random = Random.Default,
) {
    fun reduce(m: Model, event: Event, now: Long): Transition = when (event) {
        is Event.TargetChanged -> onTargetChanged(m, event.tvId, now)
        Event.Foreground -> onForeground(m, now)
        Event.Background -> onBackground(m)
        is Event.GraceExpired -> onGraceExpired(m, event.token)
        is Event.NetworkChanged -> onNetwork(m, event, now)
        is Event.RecoveryRequested -> onRecoveryRequested(m, now)
        is Event.AttemptSucceeded -> onAttemptSucceeded(m, event.attemptId, now)
        is Event.AttemptFailed -> onAttemptFailed(m, event, now)
        is Event.SessionLost -> onSessionLost(m, event.generation, now)
        is Event.SessionStale -> onSessionLost(m, event.generation, now)
        is Event.RetryTimer -> onRetryTimer(m, event.token, now)
        is Event.ScreenChanged -> onScreenChanged(m, event)
        Event.TvAnnounced -> onTvAnnounced(m, now)
    }

    private fun onScreenChanged(m: Model, e: Event.ScreenChanged): Transition {
        val state = m.state
        if (state !is ConnectionState.Ready || state.generation != e.generation) return Transition(m, emptyList())
        return Transition(m.copy(state = state.copy(tvAsleep = !e.on)), emptyList())
    }

    private fun onTvAnnounced(m: Model, now: Long): Transition {
        if (!m.hasTv || !m.foreground || !m.networkUsable) return Transition(m, emptyList())
        when (m.state) {
            is ConnectionState.Ready, is ConnectionState.Unsupported, ConnectionState.PairingRequired ->
                return Transition(m, emptyList())
            else -> Unit
        }
        val effects = mutableListOf<Effect>(Effect.MarkRecoveryStart(reachableAgain = true))
        if (m.activeAttempt != null) return Transition(m.copy(retryImmediately = true), effects)
        if (m.retryToken != null) effects += Effect.CancelRetry
        val display = if (m.state == ConnectionState.Suspended) ConnectionState.Connecting else m.state
        return beginAttempt(m.copy(retryToken = null, state = display, cycleStartedAt = m.cycleStartedAt ?: now), effects)
    }

    /**
     * Switching TVs drops everything belonging to the previous one: its session, its attempt and
     * its retry timer. Late results from it no longer match the attempt id and are discarded.
     */
    private fun onTargetChanged(m: Model, tvId: String?, now: Long): Transition {
        val effects = mutableListOf<Effect>()
        val cleared = stopEverything(m, effects).copy(
            target = tvId,
            failures = 0,
            rejections = 0,
            cycleStartedAt = null,
            readySince = null,
        )
        if (tvId == null) return Transition(cleared.copy(state = ConnectionState.NotConfigured), effects)
        if (!cleared.foreground) return Transition(cleared.copy(state = ConnectionState.Suspended), effects)
        return startCycle(cleared.copy(state = ConnectionState.Suspended), now, effects, ConnectionState.Connecting)
    }

    private fun onForeground(m: Model, now: Long): Transition {
        val effects = mutableListOf<Effect>()
        var model = m.copy(foreground = true)
        if (model.graceToken != null) {
            effects += Effect.CancelGrace
            model = model.copy(graceToken = null)
        }
        val state = model.state
        return when {
            !model.hasTv -> Transition(model.copy(state = ConnectionState.NotConfigured), effects)
            state is ConnectionState.Ready -> {
                effects += Effect.ValidateSession(state.generation)
                Transition(model, effects)
            }
            state is ConnectionState.Unsupported -> Transition(model, effects)
            model.activeAttempt != null -> Transition(model, effects)
            else -> startCycle(model.copy(failures = 0, rejections = 0), now, effects, ConnectionState.Connecting)
        }
    }

    private fun onBackground(m: Model): Transition {
        val effects = mutableListOf<Effect>()
        var model = m.copy(foreground = false)
        model.activeAttempt?.let { effects += Effect.CancelAttempt(it) }
        if (model.retryToken != null) effects += Effect.CancelRetry
        model = model.copy(activeAttempt = null, retryToken = null, cycleStartedAt = null)
        return when {
            !model.hasTv -> Transition(model.copy(state = ConnectionState.NotConfigured), effects)
            model.state is ConnectionState.Ready -> {
                val token = model.tokenSeq + 1
                effects += Effect.ScheduleGrace(token, config.backgroundGraceMs)
                Transition(model.copy(tokenSeq = token, graceToken = token), effects)
            }
            model.state is ConnectionState.Unsupported -> Transition(model, effects)
            else -> Transition(model.copy(state = ConnectionState.Suspended), effects)
        }
    }

    private fun onGraceExpired(m: Model, token: Long): Transition {
        if (token != m.graceToken || m.foreground) return Transition(m, emptyList())
        val effects = mutableListOf<Effect>()
        if (m.state is ConnectionState.Ready) effects += Effect.CloseSession
        return Transition(
            m.copy(graceToken = null, state = ConnectionState.Suspended, readySince = null),
            effects,
        )
    }

    private fun onNetwork(m: Model, e: Event.NetworkChanged, now: Long): Transition {
        val effects = mutableListOf<Effect>()
        if (!e.usable) {
            val model = stopEverything(m, effects).copy(networkUsable = false, failures = 0, rejections = 0)
            val state = when {
                !model.hasTv -> ConnectionState.NotConfigured
                model.state is ConnectionState.Unsupported -> model.state
                model.foreground -> ConnectionState.NetworkUnavailable
                else -> ConnectionState.Suspended
            }
            return Transition(model.copy(state = state), effects)
        }

        var model = m.copy(networkUsable = true)
        if (!model.hasTv || !model.foreground || model.state is ConnectionState.Unsupported) {
            return Transition(model, effects)
        }
        val state = model.state
        if (state is ConnectionState.Ready) {
            if (!e.networkReplaced) return Transition(model, effects)
            // The old socket belongs to a network that is gone; replace the session now.
            effects += Effect.CloseSession
            effects += Effect.MarkRecoveryStart(reachableAgain = true)
            model = model.copy(state = ConnectionState.Recovering, readySince = null, failures = 0, cycleStartedAt = now)
            return beginAttempt(model, effects)
        }
        if (model.activeAttempt != null && !e.networkReplaced) return Transition(model, effects)
        model.activeAttempt?.let { effects += Effect.CancelAttempt(it) }
        if (model.retryToken != null) effects += Effect.CancelRetry
        model = model.copy(activeAttempt = null, retryToken = null, failures = 0)
        val display = when (state) {
            ConnectionState.NetworkUnavailable -> ConnectionState.Recovering
            else -> state
        }
        // The network is back: from here on, time is recovery time, not outage time.
        effects += Effect.MarkRecoveryStart(reachableAgain = true)
        return beginAttempt(model.copy(state = display, cycleStartedAt = now), effects)
    }

    private fun onRecoveryRequested(m: Model, now: Long): Transition {
        if (!m.hasTv || !m.foreground || !m.networkUsable) return Transition(m, emptyList())
        if (m.state is ConnectionState.Ready || m.state is ConnectionState.Unsupported) return Transition(m, emptyList())
        if (m.activeAttempt != null) return Transition(m, emptyList())
        val effects = mutableListOf<Effect>()
        if (m.retryToken != null) effects += Effect.CancelRetry
        val display = if (m.state == ConnectionState.Suspended) ConnectionState.Connecting else m.state
        return beginAttempt(
            m.copy(retryToken = null, state = display, cycleStartedAt = m.cycleStartedAt ?: now),
            effects,
        )
    }

    private fun onAttemptSucceeded(m: Model, attemptId: Long, now: Long): Transition {
        if (attemptId != m.activeAttempt) {
            return Transition(m, listOf(Effect.DiscardSession(attemptId)))
        }
        val generation = m.generation + 1
        val effects = mutableListOf<Effect>(Effect.AcceptSession(attemptId, generation))
        if (m.retryToken != null) effects += Effect.CancelRetry
        return Transition(
            m.copy(
                state = ConnectionState.Ready(generation, requireNotNull(m.target)),
                generation = generation,
                activeAttempt = null,
                retryToken = null,
                cycleStartedAt = null,
                rejections = 0,
                readySince = now,
                retryImmediately = false,
            ),
            effects,
        )
    }

    private fun onAttemptFailed(m: Model, e: Event.AttemptFailed, now: Long): Transition {
        if (e.attemptId != m.activeAttempt) return Transition(m, emptyList())
        val effects = mutableListOf<Effect>()
        var model = m.copy(activeAttempt = null, failures = m.failures + 1)
        when (e.failure) {
            ConnectFailure.UNSUPPORTED ->
                return Transition(model.copy(state = ConnectionState.Unsupported(e.detail), cycleStartedAt = null), effects)

            ConnectFailure.CREDENTIALS_MISSING ->
                return Transition(model.copy(state = ConnectionState.PairingRequired, cycleStartedAt = null), effects)

            ConnectFailure.AUTH_REJECTED, ConnectFailure.IDENTITY_MISMATCH -> {
                model = model.copy(rejections = model.rejections + 1)
                if (model.rejections >= config.rejectionsBeforePairingRequired || model.state == ConnectionState.PairingRequired) {
                    return Transition(model.copy(state = ConnectionState.PairingRequired, cycleStartedAt = null), effects)
                }
            }

            ConnectFailure.UNREACHABLE -> {
                model = model.copy(rejections = 0)
                val started = model.cycleStartedAt ?: now
                val escalate = now - started >= config.escalateToUnreachableAfterMs
                if (escalate && (model.state == ConnectionState.Connecting || model.state == ConnectionState.Recovering)) {
                    model = model.copy(state = ConnectionState.TvUnreachable)
                }
            }
        }
        if (model.retryImmediately && model.foreground) {
            return beginAttempt(model.copy(retryImmediately = false), effects)
        }
        return scheduleRetry(model, effects)
    }

    private fun onSessionLost(m: Model, generation: Long, now: Long): Transition {
        val state = m.state
        if (state !is ConnectionState.Ready || state.generation != generation) return Transition(m, emptyList())
        val effects = mutableListOf<Effect>(Effect.CloseSession)
        val stable = m.readySince != null && now - m.readySince >= config.stableSessionMs
        var model = m.copy(readySince = null)
        if (!model.foreground) {
            if (model.graceToken != null) effects += Effect.CancelGrace
            return Transition(model.copy(state = ConnectionState.Suspended, graceToken = null), effects)
        }
        if (!model.networkUsable) return Transition(model.copy(state = ConnectionState.NetworkUnavailable), effects)
        // A TV that said it was going to standby and then vanished is off, not a failed connection.
        // Retries continue, so it reconnects by itself as soon as someone turns the TV on.
        if (!state.tvAsleep) effects += Effect.MarkRecoveryStart(reachableAgain = false)
        val display = if (state.tvAsleep) ConnectionState.TvOff else ConnectionState.Recovering
        model = model.copy(state = display, cycleStartedAt = now)
        return if (stable) {
            beginAttempt(model.copy(failures = 0), effects)
        } else {
            // A session that dropped quickly counts as a failure so a flapping TV cannot
            // cause a tight reconnect loop.
            scheduleRetry(model.copy(failures = model.failures + 1), effects)
        }
    }

    private fun onRetryTimer(m: Model, token: Long, now: Long): Transition {
        if (token != m.retryToken) return Transition(m, emptyList())
        val model = m.copy(retryToken = null)
        if (!model.hasTv || !model.foreground || !model.networkUsable || model.activeAttempt != null) {
            return Transition(model, emptyList())
        }
        return beginAttempt(model.copy(cycleStartedAt = model.cycleStartedAt ?: now), mutableListOf())
    }

    private fun startCycle(m: Model, now: Long, effects: MutableList<Effect>, display: ConnectionState): Transition {
        if (!m.networkUsable) return Transition(m.copy(state = ConnectionState.NetworkUnavailable), effects)
        val shown = when (m.state) {
            ConnectionState.TvUnreachable, ConnectionState.PairingRequired, ConnectionState.Recovering -> m.state
            else -> display
        }
        return beginAttempt(m.copy(state = shown, cycleStartedAt = now), effects)
    }

    private fun beginAttempt(m: Model, effects: MutableList<Effect>): Transition {
        val id = m.attemptSeq + 1
        effects += Effect.StartAttempt(id, requireNotNull(m.target))
        return Transition(m.copy(attemptSeq = id, activeAttempt = id), effects)
    }

    private fun scheduleRetry(m: Model, effects: MutableList<Effect>): Transition {
        if (!m.foreground || !m.hasTv) return Transition(m, effects)
        val token = m.tokenSeq + 1
        effects += Effect.ScheduleRetry(token, config.retryDelay(m.failures, random))
        return Transition(m.copy(tokenSeq = token, retryToken = token), effects)
    }

    /** Cancels attempt, retry, grace and session. */
    private fun stopEverything(m: Model, effects: MutableList<Effect>): Model {
        m.activeAttempt?.let { effects += Effect.CancelAttempt(it) }
        if (m.retryToken != null) effects += Effect.CancelRetry
        if (m.graceToken != null) effects += Effect.CancelGrace
        if (m.state is ConnectionState.Ready) effects += Effect.CloseSession
        return m.copy(activeAttempt = null, retryToken = null, graceToken = null, readySince = null, retryImmediately = false)
    }
}
