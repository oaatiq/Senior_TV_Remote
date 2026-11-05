package app.seniorremote.command

import app.seniorremote.diagnostics.Diagnostics
import app.seniorremote.diagnostics.Metric
import app.seniorremote.diagnostics.PerformanceTracker
import app.seniorremote.domain.CommandFeedback
import app.seniorremote.domain.ConnectionController
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.RemoteCommandDispatcher
import app.seniorremote.domain.RemoteKey
import app.seniorremote.domain.SendOutcome
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/** Hold-to-repeat and pipeline settings. Starting values; tune against the real TV. */
data class DispatcherConfig(
    val initialRepeatDelayMs: Long = 400,
    val repeatIntervalMs: Long = 150,
    /** A command not transmitted within this time after the press is dropped, never sent late. */
    val staleAfterMs: Long = 600,
    val queueCapacity: Int = 8,
    /** Repeats are skipped while this many commands are still waiting, so holds cannot pile up. */
    val maxPendingForRepeat: Int = 1,
    /** Safety stop in case a release is never delivered. */
    val maxHoldMs: Long = 30_000,
)

/**
 * Sends key presses in order through the current session.
 *
 * - Presses while not Ready are rejected with feedback and trigger recovery; nothing is queued.
 * - Commands carry the TV the screen showed and the session generation. A press is accepted only
 *   if that TV has the Ready session, and dropped if the session changed before delivery, so no
 *   command can cross from one TV to another.
 * - On a failed send nothing is resent and everything pending is discarded.
 * - Hold-to-repeat stops on release, cancellation, connection change, or [cancelAll].
 */
class CommandDispatcher(
    private val scope: CoroutineScope,
    private val connection: ConnectionController,
    private val diagnostics: Diagnostics,
    private val perf: PerformanceTracker? = null,
    private val config: DispatcherConfig = DispatcherConfig(),
    private val clock: () -> Long,
) : RemoteCommandDispatcher {

    private class Command(
        val tvId: String,
        val key: RemoteKey,
        val generation: Long,
        val pressedAt: Long,
        val isRepeat: Boolean,
    )

    private val queue = Channel<Command>(config.queueCapacity)
    private val pending = AtomicInteger(0)
    private val lock = Any()
    private var holdJob: Job? = null
    private var holdKey: RemoteKey? = null
    private var started = false

    private val _feedback = MutableSharedFlow<CommandFeedback>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val feedback: Flow<CommandFeedback> = _feedback.asSharedFlow()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            for (command in queue) {
                pending.decrementAndGet()
                deliver(command)
            }
        }
        scope.launch {
            connection.state.collect { state ->
                if (state !is ConnectionState.Ready) {
                    cancelHold()
                    drain()
                }
            }
        }
    }

    override fun tap(tvId: String, key: RemoteKey, pressedAtMs: Long) {
        cancelHold()
        submit(tvId, key, pressedAtMs)
    }

    override fun holdStart(tvId: String, key: RemoteKey, pressedAtMs: Long) {
        cancelHold()
        val generation = submit(tvId, key, pressedAtMs) ?: return
        if (!key.repeatable) return
        synchronized(lock) {
            holdKey = key
            holdJob = scope.launch { repeatWhileHeld(tvId, key, generation) }
        }
    }

    override fun holdEnd(key: RemoteKey) {
        synchronized(lock) {
            if (holdKey == key) {
                holdJob?.cancel()
                holdJob = null
                holdKey = null
            }
        }
    }

    override fun cancelAll() {
        cancelHold()
        drain()
    }

    private suspend fun repeatWhileHeld(tvId: String, key: RemoteKey, generation: Long) {
        val startedAt = clock()
        delay(config.initialRepeatDelayMs)
        // Cancellation (release, cancelAll, connection change) ends the loop at delay().
        while (true) {
            val state = connection.state.value
            if (state !is ConnectionState.Ready || state.generation != generation || state.tvId != tvId) return
            if (clock() - startedAt > config.maxHoldMs) {
                diagnostics.log("command", "Hold of $key stopped after ${config.maxHoldMs} ms safety limit")
                return
            }
            if (pending.get() < config.maxPendingForRepeat) {
                enqueue(Command(tvId, key, generation, clock(), isRepeat = true))
            }
            delay(config.repeatIntervalMs)
        }
    }

    /** Returns the session generation the command was queued for, or null if rejected. */
    private fun submit(tvId: String, key: RemoteKey, pressedAtMs: Long): Long? {
        val state = connection.state.value
        if (state !is ConnectionState.Ready || state.tvId != tvId) {
            _feedback.tryEmit(CommandFeedback.NotReady(state))
            connection.requestRecovery("button pressed while not ready")
            return null
        }
        return if (enqueue(Command(tvId, key, state.generation, pressedAtMs, isRepeat = false))) state.generation else null
    }

    private fun enqueue(command: Command): Boolean {
        pending.incrementAndGet()
        if (queue.trySend(command).isFailure) {
            pending.decrementAndGet()
            diagnostics.log("command", "Pipeline full; dropped ${command.key}")
            return false
        }
        return true
    }

    private suspend fun deliver(command: Command) {
        val age = clock() - command.pressedAt
        if (age > config.staleAfterMs) {
            diagnostics.log("command", "Dropped ${command.key}: ${age} ms old, too late to send")
            return
        }
        val session = connection.sessionFor(command.generation, command.tvId)
        if (session == null) {
            diagnostics.log("command", "Dropped ${command.key}: its connection was replaced")
            return
        }
        when (val outcome = session.send(command.key)) {
            SendOutcome.Transmitted -> if (!command.isRepeat) {
                val elapsed = clock() - command.pressedAt
                perf?.record(Metric.PRESS_TO_TRANSMIT, elapsed)
                diagnostics.log("command", "${command.key} transmitted in $elapsed ms (TV gives no per-key confirmation)")
            }
            is SendOutcome.Failed -> {
                diagnostics.log("command", "Sending ${command.key} failed (${outcome.detail}); not resending")
                cancelHold()
                drain()
                _feedback.tryEmit(CommandFeedback.SendFailed)
                connection.reportSendFailure(command.generation, outcome.detail)
            }
        }
    }

    private fun cancelHold() {
        synchronized(lock) {
            holdJob?.cancel()
            holdJob = null
            holdKey = null
        }
    }

    private fun drain() {
        while (queue.tryReceive().isSuccess) pending.decrementAndGet()
    }
}
