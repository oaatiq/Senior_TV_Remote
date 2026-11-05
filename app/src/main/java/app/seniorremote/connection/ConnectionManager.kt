package app.seniorremote.connection

import app.seniorremote.diagnostics.Diagnostics
import app.seniorremote.diagnostics.PerformanceTracker
import app.seniorremote.domain.ConnectFailure
import app.seniorremote.domain.ConnectionController
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.CredentialStore
import app.seniorremote.domain.DeviceRepository
import app.seniorremote.domain.SavedTv
import app.seniorremote.domain.TransportResult
import app.seniorremote.domain.TvCandidate
import app.seniorremote.domain.TvDiscovery
import app.seniorremote.domain.TvSession
import app.seniorremote.domain.TvTransport
import app.seniorremote.domain.updateTv
import app.seniorremote.net.NetworkStatus
import app.seniorremote.net.NetworkStatusSource
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the connection: feeds every signal (lifecycle, network, transport, timers, user
 * requests) through [ConnectionMachine] on one coroutine and executes the resulting effects.
 * Nothing else opens sessions or runs retry loops.
 */
class ConnectionManager(
    private val scope: CoroutineScope,
    private val repository: DeviceRepository,
    private val credentials: CredentialStore,
    private val transport: TvTransport,
    private val network: NetworkStatusSource,
    private val rediscovery: TvRediscovery?,
    /** Watched only while the app is visible and the TV is unreachable, to retry as soon as it reappears. */
    private val announcements: TvDiscovery? = null,
    private val diagnostics: Diagnostics,
    private val perf: PerformanceTracker? = null,
    private val config: ConnectionConfig = ConnectionConfig(),
    /** Monotonic milliseconds (SystemClock.elapsedRealtime on Android). */
    private val clock: () -> Long,
    private val wallClock: () -> Long = System::currentTimeMillis,
    random: Random = Random.Default,
) : ConnectionController {

    private class ActiveSession(val generation: Long, val tvId: String, val session: TvSession)

    private class Announcement(val tvId: String, val candidate: TvCandidate)

    private val machine = ConnectionMachine(config, random)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private var model = Model(networkUsable = network.status.value.usable)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.NotConfigured)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    // Touched only from the event loop.
    private val attemptJobs = HashMap<Long, Job>()
    private val attemptTargets = HashMap<Long, String>()
    private var retryJob: Job? = null
    private var graceJob: Job? = null

    // Written by attempt jobs, read by the loop.
    private val pendingSessions = HashMap<Long, TvSession>()

    @Volatile
    private var active: ActiveSession? = null
    private val lastRecoveryRequest = AtomicLong(Long.MIN_VALUE / 2)
    private val lastRediscovery = AtomicLong(Long.MIN_VALUE / 2)
    private val announcedElsewhere = AtomicReference<Announcement?>(null)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            for (event in events) process(event)
        }
        scope.launch {
            // Only a different target (selection, or renewed pairing) restarts the connection;
            // renaming, address or capability updates do not.
            repository.library
                .map { library -> library.selected?.let { it.id to it.credentialRef } }
                .distinctUntilChanged()
                .collect { target -> post(Event.TargetChanged(target?.first)) }
        }
        scope.launch {
            var previous: NetworkStatus? = null
            network.status.collect { now ->
                val before = previous
                previous = now
                val replaced = before != null && before.usable && now.usable && before.networkId != now.networkId
                if (before == null || before.usable != now.usable || replaced) {
                    diagnostics.log("network", if (now.usable) "Local network available${if (replaced) " (changed)" else ""}" else "No local network")
                    post(Event.NetworkChanged(now.usable, replaced))
                }
            }
        }
        announcements?.let { source -> scope.launch { watchAnnouncements(source) } }
    }

    /**
     * While the TV cannot be reached, listen for it announcing itself (it does so when it starts
     * up or rejoins the network) and retry at once instead of waiting for the next backoff step.
     * Passive and limited to the time the app is visible with an unreachable TV.
     */
    private suspend fun watchAnnouncements(source: TvDiscovery) {
        state.map { it == ConnectionState.TvUnreachable || it == ConnectionState.Recovering || it == ConnectionState.TvOff }
            .distinctUntilChanged()
            .collectLatest { watching ->
                if (!watching) return@collectLatest
                val present = HashSet<String>()
                source.discover()
                    .catch { diagnostics.log("connection", "Announcement watch stopped: ${it.javaClass.simpleName}") }
                    .collect { list ->
                        val tv = repository.library.value.selected ?: return@collect
                        val matches = list.filter { it.mayBe(tv) }
                        present.retainAll(matches.map { it.key() }.toSet())
                        for (candidate in matches) {
                            if (!present.add(candidate.key())) continue
                            diagnostics.log("connection", "Saved TV announced itself on the network; retrying now")
                            if (candidate.host != tv.lastKnownHost || candidate.port != tv.port) announcedElsewhere.set(Announcement(tv.id, candidate))
                            post(Event.TvAnnounced)
                        }
                    }
            }
    }

    private fun TvCandidate.key() = "$host:$port"

    private fun TvCandidate.mayBe(tv: SavedTv): Boolean =
        host == tv.lastKnownHost ||
            (tv.deviceId != null && deviceId == tv.deviceId) ||
            (tv.serviceName != null && serviceName == tv.serviceName)

    override fun onForeground() {
        perf?.markForeground(clock())
        post(Event.Foreground)
    }

    override fun onBackground() {
        perf?.abandonPending()
        post(Event.Background)
    }

    override fun requestRecovery(reason: String) {
        val now = clock()
        val last = lastRecoveryRequest.get()
        if (now - last < config.minRecoveryRequestIntervalMs) return
        if (!lastRecoveryRequest.compareAndSet(last, now)) return
        post(Event.RecoveryRequested(reason))
    }

    override fun sessionFor(generation: Long, tvId: String): TvSession? =
        active?.takeIf { it.generation == generation && it.tvId == tvId }?.session

    override fun reportSendFailure(generation: Long, detail: String) {
        post(Event.SessionLost(generation, "send failed: $detail"))
    }

    private fun post(event: Event) {
        events.trySend(event)
    }

    private fun process(event: Event) {
        when (event) {
            is Event.AttemptSucceeded -> attemptJobs.remove(event.attemptId)
            is Event.AttemptFailed -> attemptJobs.remove(event.attemptId)
            else -> Unit
        }
        val now = clock()
        val previous = model.state
        if (event is Event.TargetChanged && model.target != null && event.tvId != model.target) {
            // Timing trials and rediscovery limits belong to the TV that was being reached.
            perf?.abandonPending()
            lastRediscovery.set(Long.MIN_VALUE / 2)
            announcedElsewhere.set(null)
        }
        val transition = machine.reduce(model, event, now)
        model = transition.model
        transition.effects.forEach { execute(it, now) }
        if (model.state != previous) {
            diagnostics.log("state", "${previous.label()} -> ${model.state.label()} (${event.label()})")
            when (model.state) {
                is ConnectionState.Ready -> perf?.markReady(now)
                // Only the administrator can resolve these; do not let open trials run on.
                ConnectionState.PairingRequired, ConnectionState.NotConfigured, is ConnectionState.Unsupported -> perf?.abandonPending()
                else -> Unit
            }
            _state.value = model.state
        }
    }

    private fun execute(effect: Effect, now: Long) {
        when (effect) {
            is Effect.StartAttempt -> startAttempt(effect.attemptId, effect.tvId)
            is Effect.CancelAttempt -> {
                attemptJobs.remove(effect.attemptId)?.cancel()
                attemptTargets.remove(effect.attemptId)
                synchronized(pendingSessions) { pendingSessions.remove(effect.attemptId) }?.close()
            }
            is Effect.AcceptSession -> {
                val session = synchronized(pendingSessions) { pendingSessions.remove(effect.attemptId) }
                val tvId = attemptTargets.remove(effect.attemptId)
                if (session == null || tvId == null) {
                    session?.close()
                    post(Event.SessionLost(effect.generation, "session missing"))
                    return
                }
                active = ActiveSession(effect.generation, tvId, session)
                val screenWatch = scope.launch {
                    session.screenOn.collect { on -> if (on != null) post(Event.ScreenChanged(effect.generation, on)) }
                }
                scope.launch {
                    val reason = session.closed.await()
                    screenWatch.cancel()
                    post(Event.SessionLost(effect.generation, reason))
                }
                scope.launch {
                    runCatching { repository.updateTv(tvId) { it.copy(lastConnectedAtEpochMs = wallClock()) } }
                }
            }
            is Effect.DiscardSession -> {
                attemptTargets.remove(effect.attemptId)
                synchronized(pendingSessions) { pendingSessions.remove(effect.attemptId) }?.close()
            }
            Effect.CloseSession -> {
                active?.session?.close()
                active = null
            }
            is Effect.ScheduleRetry -> {
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(effect.delayMs)
                    post(Event.RetryTimer(effect.token))
                }
            }
            Effect.CancelRetry -> {
                retryJob?.cancel()
                retryJob = null
            }
            is Effect.ScheduleGrace -> {
                graceJob?.cancel()
                graceJob = scope.launch {
                    delay(effect.delayMs)
                    post(Event.GraceExpired(effect.token))
                }
            }
            Effect.CancelGrace -> {
                graceJob?.cancel()
                graceJob = null
            }
            is Effect.ValidateSession -> {
                val current = active
                val fresh = current != null &&
                    current.generation == effect.generation &&
                    !current.session.closed.isCompleted &&
                    current.session.millisSinceLastInbound() <= config.sessionFreshnessMs
                if (fresh) {
                    perf?.markReady(now)
                } else {
                    diagnostics.log("connection", "Session not fresh on return to foreground; replacing it")
                    post(Event.SessionStale(effect.generation))
                }
            }
            is Effect.MarkRecoveryStart -> perf?.markRecoveryStart(now, effect.reachableAgain)
        }
    }

    private fun startAttempt(attemptId: Long, tvId: String) {
        attemptTargets[attemptId] = tvId
        attemptJobs[attemptId] = scope.launch {
            // Cancellation can land just as a connection succeeds, and then the result is thrown
            // away. Remember the session so it can be closed rather than left open.
            var connected: TvSession? = null
            val result = try {
                withTimeoutOrNull(config.attemptTimeoutMs) {
                    attempt(tvId).also { if (it is TransportResult.Connected) connected = it.session }
                } ?: run {
                    connected?.close()
                    TransportResult.Failed(ConnectFailure.UNREACHABLE, "attempt timed out")
                }
            } catch (e: CancellationException) {
                connected?.close()
                throw e
            } catch (e: Exception) {
                TransportResult.Failed(ConnectFailure.UNREACHABLE, e.javaClass.simpleName)
            }
            when (result) {
                is TransportResult.Connected -> {
                    synchronized(pendingSessions) { pendingSessions[attemptId] = result.session }
                    // Always reported; the state machine decides whether it is still wanted.
                    post(Event.AttemptSucceeded(attemptId))
                }
                is TransportResult.Failed -> post(Event.AttemptFailed(attemptId, result.failure, result.detail))
            }
        }
    }

    /**
     * Connects to TV [tvId] and no other: last known address first, then the same TV elsewhere on
     * the network. The pinned key from its own pairing is checked on every connection.
     */
    private suspend fun attempt(tvId: String): TransportResult {
        val tv = repository.library.value.find(tvId)
            ?: return TransportResult.Failed(ConnectFailure.CREDENTIALS_MISSING, "no saved TV")
        val creds = credentials.load(tv.credentialRef)
            ?: return TransportResult.Failed(ConnectFailure.CREDENTIALS_MISSING, "no stored credentials")

        val first = transport.connect(tv.lastKnownHost, tv.port, creds)
        if (first is TransportResult.Connected) return first
        first as TransportResult.Failed
        if (first.failure != ConnectFailure.UNREACHABLE && first.failure != ConnectFailure.IDENTITY_MISMATCH) return first

        // The TV announced itself at another address: try there (identity is still verified).
        announcedElsewhere.getAndSet(null)?.takeIf { it.tvId == tvId }?.candidate?.let { candidate ->
            val result = transport.connect(candidate.host, candidate.port, creds)
            if (result is TransportResult.Connected) {
                diagnostics.log("connection", "Paired TV verified at announced address ${candidate.host}; address updated")
                repository.updateTv(tvId) { it.copy(lastKnownHost = candidate.host, port = candidate.port) }
                return result
            }
        }

        val finder = rediscovery ?: return first
        val now = clock()
        val last = lastRediscovery.get()
        if (now - last < config.rediscoveryMinIntervalMs || !lastRediscovery.compareAndSet(last, now)) return first

        // Other saved TVs are known not to be this one, so they are never contacted for it.
        val otherSavedHosts = repository.library.value.tvs.filter { it.id != tvId }.map { it.lastKnownHost }.toSet()
        val candidates = finder.candidatesFor(tv, config.rediscoveryTimeoutMs).filter { it.host !in otherSavedHosts }
        diagnostics.log("connection", "Rediscovery found ${candidates.size} other candidate(s)")
        for (candidate in candidates) {
            val result = transport.connect(candidate.host, candidate.port, creds)
            if (result is TransportResult.Connected) {
                diagnostics.log("connection", "Paired TV verified at a new address ${candidate.host}; address updated")
                repository.updateTv(tvId) { it.copy(lastKnownHost = candidate.host, port = candidate.port) }
                return result
            }
        }
        return first
    }

    private fun ConnectionState.label(): String = when (this) {
        is ConnectionState.Ready -> "Ready#$generation${if (tvAsleep) " (TV screen off)" else ""}"
        is ConnectionState.Unsupported -> "Unsupported"
        else -> javaClass.simpleName
    }

    private fun Event.label(): String = when (this) {
        is Event.AttemptFailed -> "attempt failed: $failure, $detail"
        is Event.SessionLost -> "session lost: $reason"
        is Event.RecoveryRequested -> "recovery requested: $reason"
        is Event.NetworkChanged -> "network usable=$usable replaced=$networkReplaced"
        is Event.TargetChanged -> if (tvId == null) "no TV selected" else "TV selected"
        is Event.ScreenChanged -> if (on) "TV screen on" else "TV screen off"
        else -> javaClass.simpleName
    }
}
