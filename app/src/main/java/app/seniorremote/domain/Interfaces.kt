package app.seniorremote.domain

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Finds TVs on the local network. Collecting the flow runs discovery; cancelling stops it. */
fun interface TvDiscovery {
    fun discover(): Flow<List<TvCandidate>>
}

/** Performs the protocol's real pairing procedure. */
interface TvPairing {
    /** Opens a pairing session; on success the TV is showing a code. */
    suspend fun begin(host: String): PairingSession
}

interface PairingSession {
    /** Name the TV reported for itself during pairing, if any. */
    val serverName: String?

    suspend fun submitCode(code: String): PairingResult

    fun cancel()
}

sealed interface PairingResult {
    class Success(val credentials: TvCredentials) : PairingResult
    data object WrongCode : PairingResult
    data class Failed(val reason: PairingFailure) : PairingResult
}

enum class PairingFailure { UNREACHABLE, REJECTED, PROTOCOL_ERROR, TIMEOUT }

class PairingException(val reason: PairingFailure, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Opens control sessions. Implementations must not return a session that is not fully established. */
interface TvTransport {
    suspend fun connect(host: String, port: Int, credentials: TvCredentials): TransportResult
}

sealed interface TransportResult {
    class Connected(val session: TvSession) : TransportResult
    data class Failed(val failure: ConnectFailure, val detail: String) : TransportResult
}

enum class ConnectFailure {
    /** Nothing answered, or the network path failed. */
    UNREACHABLE,

    /** A device answered but refused our pairing credentials. */
    AUTH_REJECTED,

    /** A device answered but is not the paired TV (pinned key mismatch). */
    IDENTITY_MISMATCH,

    /** No saved credentials exist for the TV. */
    CREDENTIALS_MISSING,

    /** The device does not speak a protocol this app supports. */
    UNSUPPORTED,
}

/** An established, usable control session. */
interface TvSession {
    /** Completes (once) when the session ends for any reason. */
    val closed: Deferred<String>

    /** What the TV last reported about its screen: on, off (standby), or null if not yet known. */
    val screenOn: StateFlow<Boolean?>

    /** Elapsed time since the TV last sent anything. Used to judge whether the session is fresh. */
    fun millisSinceLastInbound(): Long

    /**
     * Transmits one key press. [SendOutcome.Transmitted] means the bytes were written to the
     * socket, not that the TV executed the command; this protocol has no per-key acknowledgement.
     */
    suspend fun send(key: RemoteKey): SendOutcome

    /** Whether the TV says it accepts text, or null if it has not said. */
    val textEntrySupported: Boolean?

    /**
     * Transmits [text] to the TV's active text field, exactly as given. Like [send], success means
     * written to the socket, not that the text appeared. Nothing is pressed or submitted after it.
     */
    suspend fun sendText(text: String): SendOutcome

    fun close()
}

sealed interface SendOutcome {
    data object Transmitted : SendOutcome
    data class Failed(val detail: String) : SendOutcome
}

interface DeviceRepository {
    val library: StateFlow<TvLibrary>

    /** Applies one change atomically and persists it. */
    suspend fun edit(transform: (TvLibrary) -> TvLibrary)
}

suspend fun DeviceRepository.updateTv(id: String, transform: (SavedTv) -> SavedTv) =
    edit { it.updating(id, transform) }

interface CredentialStore {
    suspend fun save(ref: String, credentials: TvCredentials)
    suspend fun load(ref: String): TvCredentials?

    /** Removes one TV's credentials; other TVs are unaffected. */
    suspend fun delete(ref: String)
    suspend fun deleteAll()
}

/** Pictures chosen by the administrator. Stored privately, one file per TV. */
interface TvImageStore {
    suspend fun delete(fileName: String)
}

/** Single owner of connection state, lifecycle and recovery. */
interface ConnectionController {
    val state: StateFlow<ConnectionState>

    fun onForeground()
    fun onBackground()

    /** Something suggests the connection should be retried now (press while not ready, Try again). */
    fun requestRecovery(reason: String)

    /** The session for [generation] if it is still the active one and belongs to TV [tvId]. */
    fun sessionFor(generation: Long, tvId: String): TvSession?

    /** A send on [generation] failed; the command may or may not have reached the TV. */
    fun reportSendFailure(generation: Long, detail: String)
}

/** Ordering, repetition, expiry and cancellation of key commands. */
interface RemoteCommandDispatcher {
    val feedback: Flow<CommandFeedback>

    /**
     * A single press (also used by accessibility services). [tvId] is the TV the screen showed
     * when the press happened: a press is only ever delivered to that TV.
     */
    fun tap(tvId: String, key: RemoteKey, pressedAtMs: Long)

    /** Finger down on a remote showing [tvId]. Repeats while held for repeatable keys. */
    fun holdStart(tvId: String, key: RemoteKey, pressedAtMs: Long)

    /** Finger up or touch cancelled. */
    fun holdEnd(key: RemoteKey)

    /** Stop all repetition and drop anything pending (backgrounding, disposal). */
    fun cancelAll()
}

sealed interface CommandFeedback {
    /** A press arrived while no usable session existed. Nothing was queued. */
    data class NotReady(val state: ConnectionState) : CommandFeedback

    /** Transmission failed; the command was not resent. */
    data object SendFailed : CommandFeedback

    /** Text was transmitted. It is not known whether it appeared on the TV. */
    data object TextSent : CommandFeedback

    /** This TV does not accept text, or it was tested and did not work. */
    data object TextEntryUnavailable : CommandFeedback

    /** Text entry has not been checked on this TV yet. */
    data object TextEntryNotTested : CommandFeedback
}
