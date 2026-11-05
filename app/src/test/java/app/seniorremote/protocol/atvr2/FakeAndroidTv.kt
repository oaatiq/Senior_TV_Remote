package app.seniorremote.protocol.atvr2

import app.seniorremote.protocol.atvr2.proto.PairingMessage
import app.seniorremote.protocol.atvr2.proto.PairingOption
import app.seniorremote.protocol.atvr2.proto.PairingRequestAck
import app.seniorremote.protocol.atvr2.proto.PairingConfigurationAck
import app.seniorremote.protocol.atvr2.proto.PairingSecretAck
import app.seniorremote.protocol.atvr2.proto.RemoteConfigure
import app.seniorremote.protocol.atvr2.proto.RemoteDeviceInfo
import app.seniorremote.protocol.atvr2.proto.RemoteImeBatchEdit
import app.seniorremote.protocol.atvr2.proto.RemoteMessage
import app.seniorremote.protocol.atvr2.proto.RemotePingRequest
import app.seniorremote.protocol.atvr2.proto.RemoteSetActive
import app.seniorremote.protocol.atvr2.proto.RemoteStart
import app.seniorremote.protocol.atvr2.proto.RoleType
import com.google.protobuf.ByteString
import java.io.Closeable
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * A SIMULATED Android TV for automated tests. It implements the protocol as this app
 * understands it, so passing tests prove the client is internally consistent - not that a real
 * TV behaves this way. Real-TV validation is tracked in docs/COMPATIBILITY_REPORT.md.
 */
class FakeAndroidTv(
    private val serverName: String = "Fake TV",
    private val fixedPairingPort: Int = 0,
    private val fixedRemotePort: Int = 0,
    private val pingIntervalMs: Long = 0,
    private val acceptsText: Boolean = true,
    private val onEvent: (String) -> Unit = {},
) : Closeable {
    /** A text edit as it arrived at the TV. */
    data class ReceivedText(val ime: Int, val field: Int, val insert: Int, val start: Int, val end: Int, val value: String)

    val identity: ClientIdentity = ClientIdentity.generate("fake-tv")
    private val pairedClients = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val codes = LinkedBlockingQueue<String>()
    val receivedKeys = LinkedBlockingQueue<Pair<Int, Int>>()
    val pingResponses = LinkedBlockingQueue<Int>()
    val receivedTexts = LinkedBlockingQueue<ReceivedText>()

    @Volatile
    private var remoteOut: OutputStream? = null

    @Volatile
    var acceptPairedClients = true

    private val context: SSLContext = run {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null)
            setKeyEntry("tv", identity.privateKey, PASSWORD, arrayOf(identity.certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, PASSWORD) }
        val acceptAnyClient = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(acceptAnyClient), SecureRandom()) }
    }

    @Volatile
    private var pairingServer = server(fixedPairingPort)

    @Volatile
    private var remoteServer = server(fixedRemotePort)
    val pairingPort: Int = pairingServer.localPort
    val remotePort: Int = remoteServer.localPort

    init {
        acceptLoop(pairingServer, ::handlePairing)
        acceptLoop(remoteServer, ::handleRemote)
    }

    /** Like unplugging the TV: nothing answers on either port. Identity and pairings are kept. */
    fun goOffline() {
        runCatching { pairingServer.close() }
        runCatching { remoteServer.close() }
        dropConnections()
        onEvent("OFFLINE")
    }

    /** Power restored: same identity and pairings, same ports. */
    fun goOnline() {
        pairingServer = server(pairingPort)
        remoteServer = server(remotePort)
        acceptLoop(pairingServer, ::handlePairing)
        acceptLoop(remoteServer, ::handleRemote)
        onEvent("ONLINE")
    }

    fun awaitCode(): String = codes.poll(10, TimeUnit.SECONDS) ?: error("TV never displayed a code")

    fun revokeAllPairings() = pairedClients.clear()

    fun dropConnections() {
        sockets.forEach { runCatching { it.close() } }
    }

    fun sendPing(value: Int) = sendToRemote(
        RemoteMessage.newBuilder().setRemotePingRequest(RemotePingRequest.newBuilder().setVal1(value).setVal2(0)).build(),
    )

    /** The counters a TV hands out for its text field; the client must echo them. */
    fun sendImeState(ime: Int, field: Int) = sendToRemote(
        RemoteMessage.newBuilder().setRemoteImeBatchEdit(RemoteImeBatchEdit.newBuilder().setImeCounter(ime).setFieldCounter(field)).build(),
    )

    /** What a real TV sends when its screen turns on or off. */
    fun sendScreenState(on: Boolean) = sendToRemote(
        RemoteMessage.newBuilder().setRemoteStart(RemoteStart.newBuilder().setStarted(on)).build(),
    )

    private fun sendToRemote(message: RemoteMessage) {
        // The client may count the session as established a moment before this side records it.
        val deadline = System.currentTimeMillis() + 5_000
        while (remoteOut == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val out = remoteOut ?: error("no remote session")
        synchronized(out) {
            message.writeDelimitedTo(out)
            out.flush()
        }
    }

    override fun close() {
        runCatching { pairingServer.close() }
        runCatching { remoteServer.close() }
        dropConnections()
    }

    private fun server(port: Int): SSLServerSocket =
        (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), 10)
            needClientAuth = true
        }

    private fun acceptLoop(server: ServerSocket, handler: (SSLSocket) -> Unit) {
        thread(isDaemon = true, name = "fake-tv-accept-${server.localPort}") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                sockets += socket
                thread(isDaemon = true) {
                    runCatching {
                        socket.startHandshake()
                        handler(socket)
                    }
                    runCatching { socket.close() }
                }
            }
        }
    }

    private fun clientKey(socket: SSLSocket): RSAPublicKey =
        (socket.session.peerCertificates.first() as X509Certificate).publicKey as RSAPublicKey

    private fun fingerprint(key: RSAPublicKey): String =
        MessageDigest.getInstance("SHA-256").digest(key.encoded).joinToString("") { "%02x".format(it) }

    private fun handlePairing(socket: SSLSocket) {
        val input = socket.inputStream
        val out = socket.outputStream
        fun reply(status: PairingMessage.Status = PairingMessage.Status.STATUS_OK, build: PairingMessage.Builder.() -> Unit = {}) {
            PairingMessage.newBuilder().setProtocolVersion(2).setStatus(status).apply(build).build().writeDelimitedTo(out)
            out.flush()
        }

        check(PairingMessage.parseDelimitedFrom(input).hasPairingRequest())
        reply { setPairingRequestAck(PairingRequestAck.newBuilder().setServerName(serverName)) }
        check(PairingMessage.parseDelimitedFrom(input).hasPairingOption())
        reply {
            setPairingOption(
                PairingOption.newBuilder().addInputEncodings(PairingWire.hexEncoding()).setPreferredRole(RoleType.ROLE_TYPE_INPUT),
            )
        }
        check(PairingMessage.parseDelimitedFrom(input).hasPairingConfiguration())
        reply { setPairingConfigurationAck(PairingConfigurationAck.getDefaultInstance()) }

        val client = clientKey(socket)
        val random = ByteArray(2).also { SecureRandom().nextBytes(it) }
        val randomHex = random.joinToString("") { "%02X".format(it) }
        val expected = PairingCode.secret(client, identity.certificate.publicKey as RSAPublicKey, "00$randomHex")
        val code = "%02X".format(expected[0]) + randomHex
        codes.offer(code)
        onEvent("CODE $code")

        val secret = PairingMessage.parseDelimitedFrom(input) ?: return
        if (secret.hasPairingSecret() && secret.pairingSecret.secret.toByteArray().contentEquals(expected)) {
            pairedClients += fingerprint(client)
            reply { setPairingSecretAck(PairingSecretAck.newBuilder().setSecret(ByteString.copyFrom(expected))) }
            onEvent("PAIRED")
        } else {
            reply(PairingMessage.Status.STATUS_BAD_SECRET)
            onEvent("WRONG CODE")
        }
    }

    private fun handleRemote(socket: SSLSocket) {
        if (!acceptPairedClients || fingerprint(clientKey(socket)) !in pairedClients) {
            onEvent("REJECTED unknown client")
            return // hang up: unknown client
        }
        val input = socket.inputStream
        val out = socket.outputStream
        fun send(message: RemoteMessage) = synchronized(out) {
            message.writeDelimitedTo(out)
            out.flush()
        }

        send(
            RemoteMessage.newBuilder().setRemoteConfigure(
                RemoteConfigure.newBuilder().setCode1(if (acceptsText) 622 else 618).setDeviceInfo(RemoteDeviceInfo.newBuilder().setModel("FakeModel").setVendor("FakeVendor")),
            ).build(),
        )
        check(RemoteMessage.parseDelimitedFrom(input).hasRemoteConfigure())
        send(RemoteMessage.newBuilder().setRemoteSetActive(RemoteSetActive.newBuilder().setActive(622)).build())
        check(RemoteMessage.parseDelimitedFrom(input).hasRemoteSetActive())
        send(RemoteMessage.newBuilder().setRemoteStart(RemoteStart.newBuilder().setStarted(true)).build())
        remoteOut = out
        onEvent("SESSION established")
        if (pingIntervalMs > 0) {
            thread(isDaemon = true) {
                var n = 0
                while (!socket.isClosed) {
                    Thread.sleep(pingIntervalMs)
                    runCatching {
                        send(RemoteMessage.newBuilder().setRemotePingRequest(RemotePingRequest.newBuilder().setVal1(++n).setVal2(0)).build())
                    }.onFailure { return@thread }
                }
            }
        }

        while (true) {
            val message = RemoteMessage.parseDelimitedFrom(input) ?: run {
                onEvent("SESSION closed by client")
                return
            }
            if (message.hasRemoteKeyInject()) {
                receivedKeys.offer(message.remoteKeyInject.keyCode to message.remoteKeyInject.direction.number)
                onEvent("KEY ${message.remoteKeyInject.keyCode} ${message.remoteKeyInject.direction}")
            }
            if (message.hasRemotePingResponse()) pingResponses.offer(message.remotePingResponse.val1)
            if (message.hasRemoteImeBatchEdit()) {
                val edit = message.remoteImeBatchEdit
                edit.editInfoList.forEach {
                    val status = it.textFieldStatus
                    receivedTexts.offer(ReceivedText(edit.imeCounter, edit.fieldCounter, it.insert, status.start, status.end, status.value))
                    onEvent("TEXT ${status.value.length} characters")
                }
            }
        }
    }

    private companion object {
        val PASSWORD = "fake-tv".toCharArray()
    }
}
