package app.seniorremote.protocol.atvr2

import app.seniorremote.diagnostics.Diagnostics
import app.seniorremote.domain.ConnectFailure
import app.seniorremote.domain.RemoteKey
import app.seniorremote.domain.SendOutcome
import app.seniorremote.domain.TransportResult
import app.seniorremote.domain.TvCredentials
import app.seniorremote.domain.TvSession
import app.seniorremote.domain.TvTransport
import app.seniorremote.net.SocketProvider
import app.seniorremote.net.closeOnCancel
import app.seniorremote.net.closeQuietly
import app.seniorremote.protocol.atvr2.proto.RemoteConfigure
import app.seniorremote.protocol.atvr2.proto.RemoteDeviceInfo
import app.seniorremote.protocol.atvr2.proto.RemoteDirection
import app.seniorremote.protocol.atvr2.proto.RemoteEditInfo
import app.seniorremote.protocol.atvr2.proto.RemoteImeBatchEdit
import app.seniorremote.protocol.atvr2.proto.RemoteImeObject
import app.seniorremote.protocol.atvr2.proto.RemoteKeyInject
import app.seniorremote.protocol.atvr2.proto.RemoteMessage
import app.seniorremote.protocol.atvr2.proto.RemotePingResponse
import app.seniorremote.protocol.atvr2.proto.RemoteSetActive
import com.google.protobuf.InvalidProtocolBufferException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Control channel of the Android TV Remote Service v2. A session counts as established only
 * after the TV's configure / set-active exchange has completed - an open socket is not enough.
 */
class AtvTransport(
    private val sockets: SocketProvider,
    private val diagnostics: Diagnostics,
    private val config: AtvConfig = AtvConfig(),
    private val clock: () -> Long = System::nanoTime,
) : TvTransport {

    override suspend fun connect(host: String, port: Int, credentials: TvCredentials): TransportResult {
        // withContext drops the result if cancelled as it finishes; a finished session must not leak.
        var established: AtvSession? = null
        try {
            return open(host, port, credentials) { established = it }
        } catch (e: CancellationException) {
            established?.close()
            throw e
        }
    }

    private suspend fun open(
        host: String,
        port: Int,
        credentials: TvCredentials,
        onEstablished: (AtvSession) -> Unit,
    ): TransportResult =
        withContext(Dispatchers.IO) {
            val identity = try {
                ClientIdentity.from(credentials)
            } catch (e: Exception) {
                diagnostics.log("transport", "Stored credentials unreadable: ${e.javaClass.simpleName}")
                return@withContext TransportResult.Failed(ConnectFailure.CREDENTIALS_MISSING, "credentials unreadable")
            }
            val trust = PinningTrustManager(credentials.serverPublicKeySha256)
            val socket = try {
                Tls.open(sockets, Tls.context(identity, trust), host, port, config.connectTimeoutMs, config.tlsHandshakeTimeoutMs)
            } catch (e: TlsOpenException) {
                return@withContext classifyOpenFailure(e, trust)
            }

            val session = AtvSession(socket, diagnostics, config, clock)
            try {
                withTimeout(config.sessionSetupTimeoutMs) { session.establish() }
                diagnostics.log("transport", "Session established${session.tvDescription?.let { " with $it" } ?: ""}")
                onEstablished(session)
                TransportResult.Connected(session)
            } catch (e: TimeoutCancellationException) {
                session.close()
                diagnostics.log("transport", "TV did not complete session setup in time")
                TransportResult.Failed(ConnectFailure.UNREACHABLE, "session setup timed out")
            } catch (e: SessionSetupException) {
                session.close()
                diagnostics.log("transport", "Session setup failed: ${e.failure} (${e.message})")
                TransportResult.Failed(e.failure, e.message ?: "")
            } catch (e: CancellationException) {
                session.close()
                throw e
            }
        }

    private fun classifyOpenFailure(e: TlsOpenException, trust: PinningTrustManager): TransportResult.Failed {
        val cause = e.cause
        val failure = when {
            trust.identityMismatch -> ConnectFailure.IDENTITY_MISMATCH
            e.phase == TlsOpenException.Phase.TCP -> ConnectFailure.UNREACHABLE
            cause is SocketTimeoutException -> ConnectFailure.UNREACHABLE
            cause is SSLException -> ConnectFailure.AUTH_REJECTED
            else -> ConnectFailure.UNREACHABLE
        }
        diagnostics.log("transport", "Connect failed in ${e.phase}: ${cause?.javaClass?.simpleName} -> $failure")
        return TransportResult.Failed(failure, "${e.phase}: ${cause?.javaClass?.simpleName}")
    }
}

internal class SessionSetupException(val failure: ConnectFailure, message: String) : Exception(message)

internal class AtvSession(
    private val socket: SSLSocket,
    private val diagnostics: Diagnostics,
    private val config: AtvConfig,
    private val clock: () -> Long,
) : TvSession {
    private val input = BufferedInputStream(socket.inputStream)
    private val output = BufferedOutputStream(socket.outputStream)
    private val writeLock = Any()
    private val established = CompletableDeferred<Unit>()
    private val closedSignal = CompletableDeferred<String>()
    private val finished = AtomicBoolean(false)
    private val _screenOn = MutableStateFlow<Boolean?>(null)

    @Volatile
    private var currentIdleMs = config.idleTimeoutMs

    // Echoed back when sending text. They come from the TV and start at zero.
    @Volatile
    private var imeCounter = 0

    @Volatile
    private var imeFieldCounter = 0

    @Volatile
    override var textEntrySupported: Boolean? = null
        private set

    @Volatile
    private var lastInboundNanos = clock()

    @Volatile
    private var receivedConfigure = false

    @Volatile
    var tvDescription: String? = null
        private set

    override val closed: Deferred<String> get() = closedSignal
    override val screenOn: StateFlow<Boolean?> get() = _screenOn

    /** Starts the reader and waits for the configure / set-active exchange. */
    suspend fun establish() {
        socket.soTimeout = config.idleTimeoutMs
        val reader = Thread({ readLoop() }, "atv-session-reader")
        reader.isDaemon = true
        reader.start()
        established.await()
    }

    override fun millisSinceLastInbound(): Long = (clock() - lastInboundNanos) / 1_000_000

    override suspend fun send(key: RemoteKey): SendOutcome {
        val message = RemoteMessage.newBuilder()
            .setRemoteKeyInject(
                RemoteKeyInject.newBuilder()
                    .setKeyCode(AtvKeyCodes.of(key))
                    .setDirection(RemoteDirection.SHORT),
            )
            .build()
        return transmit(message)
    }

    override suspend fun sendText(text: String): SendOutcome {
        val lastIndex = text.codePointCount(0, text.length) - 1
        val message = RemoteMessage.newBuilder()
            .setRemoteImeBatchEdit(
                RemoteImeBatchEdit.newBuilder()
                    .setImeCounter(imeCounter)
                    .setFieldCounter(imeFieldCounter)
                    .addEditInfo(
                        RemoteEditInfo.newBuilder()
                            .setInsert(1)
                            .setTextFieldStatus(
                                RemoteImeObject.newBuilder().setStart(lastIndex).setEnd(lastIndex).setValue(text),
                            ),
                    ),
            )
            .build()
        return transmit(message)
    }

    private suspend fun transmit(message: RemoteMessage): SendOutcome {
        if (finished.get()) return SendOutcome.Failed("session closed")
        return try {
            withTimeout(config.sendTimeoutMs) { closeOnCancel(socket) { write(message) } }
            SendOutcome.Transmitted
        } catch (e: TimeoutCancellationException) {
            finish("send timed out", null)
            SendOutcome.Failed("send timed out")
        } catch (e: IOException) {
            finish("send failed: ${e.javaClass.simpleName}", e)
            SendOutcome.Failed(e.javaClass.simpleName)
        }
    }

    override fun close() {
        finish("closed by app", null)
    }

    private fun readLoop() {
        try {
            while (!finished.get()) {
                val message = RemoteMessage.parseDelimitedFrom(input) ?: throw EOFException("TV closed the connection")
                lastInboundNanos = clock()
                handle(message)
            }
        } catch (e: Throwable) {
            // protobuf wraps socket errors (timeouts, resets) while reading the length prefix in
            // InvalidProtocolBufferException; unwrap so they are not mistaken for bad data.
            val cause = if (e is InvalidProtocolBufferException) e.unwrapIOException() else e
            finish(describe(cause), cause)
        }
    }

    private fun handle(message: RemoteMessage) {
        when {
            message.hasRemoteConfigure() -> {
                val info = message.remoteConfigure.deviceInfo
                textEntrySupported = message.remoteConfigure.code1 and AtvConfig.FEATURE_IME != 0
                diagnostics.log("transport", "TV feature flags ${message.remoteConfigure.code1}")
                tvDescription = listOf(info.vendor, info.model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
                write(
                    RemoteMessage.newBuilder().setRemoteConfigure(
                        RemoteConfigure.newBuilder()
                            .setCode1(config.featureMask)
                            .setDeviceInfo(
                                RemoteDeviceInfo.newBuilder()
                                    .setModel(config.deviceModel)
                                    .setVendor(config.deviceVendor)
                                    .setUnknown1(1)
                                    .setUnknown2("1")
                                    .setPackageName(config.packageName)
                                    .setAppVersion(config.appVersion),
                            ),
                    ).build(),
                )
                receivedConfigure = true
            }
            message.hasRemoteSetActive() -> {
                write(
                    RemoteMessage.newBuilder()
                        .setRemoteSetActive(RemoteSetActive.newBuilder().setActive(config.featureMask))
                        .build(),
                )
                if (receivedConfigure && established.complete(Unit)) {
                    diagnostics.log("transport", "Configure/set-active exchange complete")
                }
            }
            message.hasRemotePingRequest() -> write(
                RemoteMessage.newBuilder()
                    .setRemotePingResponse(RemotePingResponse.newBuilder().setVal1(message.remotePingRequest.val1))
                    .build(),
            )
            message.hasRemoteStart() -> {
                val on = message.remoteStart.started
                diagnostics.log("transport", "TV reports screen ${if (on) "on" else "off/standby"}")
                _screenOn.value = on
                // Runs on the reader thread, so it applies to the very next read.
                currentIdleMs = if (on) config.idleTimeoutMs else config.standbyIdleTimeoutMs
                socket.soTimeout = currentIdleMs
            }
            message.hasRemoteError() -> diagnostics.log("transport", "TV reported a remote error")
            // Lengths and counters only: what is in a TV text field must never reach the log.
            message.hasRemoteImeBatchEdit() -> {
                val edit = message.remoteImeBatchEdit
                imeCounter = edit.imeCounter
                imeFieldCounter = edit.fieldCounter
                diagnostics.log("transport", "TV text state: ime ${edit.imeCounter}, field ${edit.fieldCounter}, ${edit.editInfoCount} edit(s), length ${edit.editInfoList.sumOf { it.textFieldStatus.value.length }}")
            }
            message.hasRemoteImeShowRequest() -> {
                val field = message.remoteImeShowRequest.remoteTextFieldStatus
                diagnostics.log("transport", "TV text field shown: counter ${field.counterField}, length ${field.value.length}, cursor ${field.start}-${field.end}")
            }
            message.hasRemoteImeKeyInject() -> {
                val field = message.remoteImeKeyInject.textFieldStatus
                diagnostics.log("transport", "TV input focus: counter ${field.counterField}, length ${field.value.length}")
            }
        }
    }

    private fun write(message: RemoteMessage) {
        synchronized(writeLock) {
            message.writeDelimitedTo(output)
            output.flush()
        }
    }

    private fun describe(e: Throwable): String = when (e) {
        is EOFException -> "closed by TV"
        is SocketTimeoutException -> "no data from TV for $currentIdleMs ms"
        is InvalidProtocolBufferException -> "unreadable message from TV"
        else -> e.javaClass.simpleName
    }

    private fun finish(reason: String, cause: Throwable?) {
        if (!finished.compareAndSet(false, true)) return
        socket.closeQuietly()
        if (!established.isCompleted) {
            val failure = when (cause) {
                is InvalidProtocolBufferException -> ConnectFailure.UNSUPPORTED
                is SocketTimeoutException -> ConnectFailure.UNREACHABLE
                // The TLS handshake succeeded but the TV hung up before the session was set
                // up - this is how an unknown or revoked client certificate presents itself.
                is EOFException, is SSLException -> ConnectFailure.AUTH_REJECTED
                is IOException -> ConnectFailure.AUTH_REJECTED
                else -> ConnectFailure.UNREACHABLE
            }
            established.completeExceptionally(SessionSetupException(failure, reason))
        } else {
            diagnostics.log("transport", "Session ended: $reason")
        }
        closedSignal.complete(reason)
    }
}
