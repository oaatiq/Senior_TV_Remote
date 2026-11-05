package app.seniorremote.testing

import app.seniorremote.domain.CommandFeedback
import app.seniorremote.domain.ConnectFailure
import app.seniorremote.domain.ConnectionController
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.RemoteCommandDispatcher
import app.seniorremote.domain.RemoteKey
import app.seniorremote.domain.SavedTv
import app.seniorremote.domain.SendOutcome
import app.seniorremote.domain.TransportResult
import app.seniorremote.domain.TvCredentials
import app.seniorremote.domain.TvImageStore
import app.seniorremote.domain.TvSession
import app.seniorremote.domain.TvTransport
import app.seniorremote.net.NetworkStatus
import app.seniorremote.net.NetworkStatusSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

val testCredentials = TvCredentials(byteArrayOf(1), byteArrayOf(2), ByteArray(32) { 3 })

const val TV_A = "tv-a"
const val TV_B = "tv-b"
const val TV_C = "tv-c"

fun testTv(host: String = "10.0.0.5") = SavedTv(
    id = TV_A,
    originalName = "Living room TV",
    serviceName = "Living room TV",
    deviceId = "AA:BB",
    lastKnownHost = host,
    port = 6466,
    credentialRef = "ref1",
)

/** A second or third saved TV with its own identity, address and pairing. */
fun otherTv(id: String, host: String, name: String = "TV $id") = SavedTv(
    id = id,
    originalName = name,
    serviceName = name,
    deviceId = "id-$id",
    lastKnownHost = host,
    port = 6466,
    credentialRef = "ref-$id",
)

/** Records dispatcher calls, for checks on the order of operations when switching TVs. */
class RecordingDispatcher(private val onCancelAll: () -> Unit = {}) : RemoteCommandDispatcher {
    var cancelAllCalls = 0
    override val feedback: Flow<CommandFeedback> = emptyFlow()
    override fun tap(tvId: String, key: RemoteKey, pressedAtMs: Long) = Unit
    override fun holdStart(tvId: String, key: RemoteKey, pressedAtMs: Long) = Unit
    override fun holdEnd(key: RemoteKey) = Unit
    override fun cancelAll() {
        cancelAllCalls++
        onCancelAll()
    }
}

class RecordingImages : TvImageStore {
    val deleted = mutableListOf<String>()
    override suspend fun delete(fileName: String) {
        deleted += fileName
    }
}

class FakeSession(val host: String) : TvSession {
    val sent = mutableListOf<RemoteKey>()
    val closedByApp = CompletableDeferred<Unit>()
    var failSends = false
    var sendDelayMs = 0L
    var inboundAgeMs = 0L
    private val closedSignal = CompletableDeferred<String>()
    override val closed: Deferred<String> get() = closedSignal
    val screen = MutableStateFlow<Boolean?>(null)
    override val screenOn: StateFlow<Boolean?> get() = screen

    override fun millisSinceLastInbound(): Long = inboundAgeMs

    val sentTexts = mutableListOf<String>()
    var failTextSends = false
    override var textEntrySupported: Boolean? = true

    override suspend fun sendText(text: String): SendOutcome {
        if (sendDelayMs > 0) delay(sendDelayMs)
        if (failTextSends || closedSignal.isCompleted) return SendOutcome.Failed("broken pipe")
        sentTexts += text
        return SendOutcome.Transmitted
    }

    override suspend fun send(key: RemoteKey): SendOutcome {
        if (sendDelayMs > 0) delay(sendDelayMs)
        if (failSends || closedSignal.isCompleted) return SendOutcome.Failed("broken pipe")
        sent += key
        return SendOutcome.Transmitted
    }

    /** Simulates the TV or network ending the session. */
    fun drop(reason: String = "closed by TV") {
        closedSignal.complete(reason)
    }

    override fun close() {
        closedByApp.complete(Unit)
        closedSignal.complete("closed by app")
    }
}

/**
 * Scripted transport. [behavior] decides each connect by host; it may suspend to model slow
 * attempts. Tracks concurrency so tests can assert at most one attempt at a time.
 */
class FakeTransport(
    var behavior: suspend (host: String) -> TransportResult = { TransportResult.Connected(FakeSession(it)) },
) : TvTransport {
    val sessions = mutableListOf<FakeSession>()
    val hosts = mutableListOf<String>()
    var inFlight = 0
    var maxInFlight = 0

    override suspend fun connect(host: String, port: Int, credentials: TvCredentials): TransportResult {
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        hosts += host
        try {
            val result = behavior(host)
            if (result is TransportResult.Connected) sessions += result.session as FakeSession
            return result
        } finally {
            inFlight--
        }
    }

    companion object {
        fun unreachable() = TransportResult.Failed(ConnectFailure.UNREACHABLE, "timeout")
    }
}

class FakeNetwork(usable: Boolean = true) : NetworkStatusSource {
    val flow = MutableStateFlow(NetworkStatus(usable, if (usable) 1L else null))
    override val status: StateFlow<NetworkStatus> get() = flow
}

/** Connection controller with directly settable state, for dispatcher tests. */
class FakeController : ConnectionController {
    val stateFlow = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    override val state: StateFlow<ConnectionState> get() = stateFlow
    val sessions = mutableMapOf<Long, FakeSession>()
    private val sessionTvs = mutableMapOf<Long, String>()
    val recoveryRequests = mutableListOf<String>()
    val sendFailures = mutableListOf<Long>()

    fun ready(generation: Long, tvId: String = TV_A): FakeSession {
        val session = FakeSession(tvId)
        sessions[generation] = session
        sessionTvs[generation] = tvId
        stateFlow.value = ConnectionState.Ready(generation, tvId)
        return session
    }

    override fun onForeground() = Unit
    override fun onBackground() = Unit
    override fun requestRecovery(reason: String) {
        recoveryRequests += reason
    }

    override fun sessionFor(generation: Long, tvId: String): TvSession? =
        sessions[generation].takeIf { (stateFlow.value as? ConnectionState.Ready)?.generation == generation && sessionTvs[generation] == tvId }

    override fun reportSendFailure(generation: Long, detail: String) {
        sendFailures += generation
        stateFlow.value = ConnectionState.Recovering
    }
}
