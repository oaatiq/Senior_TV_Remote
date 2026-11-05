package app.seniorremote.protocol.atvr2

import app.seniorremote.diagnostics.Diagnostics
import app.seniorremote.domain.PairingException
import app.seniorremote.domain.PairingFailure
import app.seniorremote.domain.PairingResult
import app.seniorremote.domain.PairingSession
import app.seniorremote.domain.TvCredentials
import app.seniorremote.domain.TvPairing
import app.seniorremote.net.SocketProvider
import app.seniorremote.net.closeOnCancel
import app.seniorremote.net.closeQuietly
import app.seniorremote.protocol.atvr2.proto.PairingConfiguration
import app.seniorremote.protocol.atvr2.proto.PairingEncoding
import app.seniorremote.protocol.atvr2.proto.PairingMessage
import app.seniorremote.protocol.atvr2.proto.PairingOption
import app.seniorremote.protocol.atvr2.proto.PairingRequest
import app.seniorremote.protocol.atvr2.proto.PairingSecret
import app.seniorremote.protocol.atvr2.proto.RoleType
import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import java.io.EOFException
import java.io.IOException
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pairing for the Android TV Remote Service v2: TLS with a client certificate on the pairing
 * port, a short message exchange, then the TV displays a 6-character hexadecimal code which
 * the administrator types in. The code proves both sides hold the certificates seen in TLS.
 */
class AtvPairing(
    private val sockets: SocketProvider,
    private val diagnostics: Diagnostics,
    private val config: AtvConfig = AtvConfig(),
) : TvPairing {

    override suspend fun begin(host: String): PairingSession = withContext(Dispatchers.IO) {
        diagnostics.log("pairing", "Starting pairing with $host")
        val identity = ClientIdentity.generate()
        val trust = PinningTrustManager(expectedPublicKeySha256 = null)
        val socket = try {
            Tls.open(sockets, Tls.context(identity, trust), host, config.pairingPort, config.connectTimeoutMs, config.tlsHandshakeTimeoutMs)
        } catch (e: TlsOpenException) {
            diagnostics.log("pairing", "Could not open pairing channel (${e.phase}): ${e.cause?.javaClass?.simpleName}")
            val reason = if (e.phase == TlsOpenException.Phase.TCP) PairingFailure.UNREACHABLE else PairingFailure.PROTOCOL_ERROR
            throw PairingException(reason, "Could not open the pairing channel", e)
        }
        val serverCert = trust.peerCertificate
        if (serverCert == null || serverCert.publicKey !is RSAPublicKey) {
            socket.closeQuietly()
            diagnostics.log("pairing", "TV certificate is missing or not RSA; cannot compute pairing secret")
            throw PairingException(PairingFailure.PROTOCOL_ERROR, "Unsupported TV certificate")
        }
        try {
            socket.soTimeout = config.pairingStepTimeoutMs
            val serverName = closeOnCancel(socket) { handshake(socket) }
            diagnostics.log("pairing", "TV accepted pairing request; code should now be visible on the TV")
            AtvPairingSession(socket, identity, serverCert, serverName, diagnostics)
        } catch (e: CancellationException) {
            socket.closeQuietly()
            throw e
        } catch (e: PairingException) {
            socket.closeQuietly()
            throw e
        } catch (e: IOException) {
            socket.closeQuietly()
            diagnostics.log("pairing", "Pairing exchange failed: ${e.javaClass.simpleName}")
            val reason = if (e is SocketTimeoutException) PairingFailure.TIMEOUT else PairingFailure.PROTOCOL_ERROR
            throw PairingException(reason, "Pairing exchange failed", e)
        }
    }

    /** Request -> option -> configuration. Returns the TV's self-reported name. */
    private fun handshake(socket: SSLSocket): String? {
        PairingWire.write(
            socket,
            PairingWire.message {
                setPairingRequest(
                    PairingRequest.newBuilder()
                        .setServiceName(config.pairingServiceName)
                        .setClientName(config.clientName),
                )
            },
        )
        val ack = PairingWire.expect(socket) { it.hasPairingRequestAck() }
        val serverName = ack.pairingRequestAck.serverName.takeIf { it.isNotBlank() }

        PairingWire.write(
            socket,
            PairingWire.message {
                setPairingOption(
                    PairingOption.newBuilder()
                        .addInputEncodings(PairingWire.hexEncoding())
                        .setPreferredRole(RoleType.ROLE_TYPE_INPUT),
                )
            },
        )
        PairingWire.expect(socket) { it.hasPairingOption() }

        PairingWire.write(
            socket,
            PairingWire.message {
                setPairingConfiguration(
                    PairingConfiguration.newBuilder()
                        .setEncoding(PairingWire.hexEncoding())
                        .setClientRole(RoleType.ROLE_TYPE_INPUT),
                )
            },
        )
        PairingWire.expect(socket) { it.hasPairingConfigurationAck() }
        return serverName
    }
}

private class AtvPairingSession(
    private val socket: SSLSocket,
    private val identity: ClientIdentity,
    private val serverCert: X509Certificate,
    override val serverName: String?,
    private val diagnostics: Diagnostics,
) : PairingSession {

    override suspend fun submitCode(code: String): PairingResult = withContext(Dispatchers.IO) {
        val normalized = PairingCode.normalize(code)
            ?: return@withContext PairingResult.WrongCode
        val secret = PairingCode.secret(
            identity.certificate.publicKey as RSAPublicKey,
            serverCert.publicKey as RSAPublicKey,
            normalized,
        )
        // The first byte of the code is a checksum of the hash. The TV remains the authority;
        // this is recorded only to help diagnose typing mistakes vs. protocol differences.
        diagnostics.log("pairing", "Code checksum ${if (PairingCode.checksumMatches(secret, normalized)) "matches" else "does not match"} local computation")
        try {
            closeOnCancel(socket) {
                PairingWire.write(
                    socket,
                    PairingWire.message { setPairingSecret(PairingSecret.newBuilder().setSecret(ByteString.copyFrom(secret))) },
                )
                val reply = PairingWire.read(socket)
                when {
                    reply.status == PairingMessage.Status.STATUS_OK && reply.hasPairingSecretAck() -> {
                        diagnostics.log("pairing", "TV accepted the code; pairing complete")
                        PairingResult.Success(
                            TvCredentials(
                                clientPrivateKeyPkcs8 = identity.privateKey.encoded,
                                clientCertificateDer = identity.certificate.encoded,
                                serverPublicKeySha256 = Tls.publicKeySha256(serverCert),
                            ),
                        )
                    }
                    reply.status == PairingMessage.Status.STATUS_BAD_SECRET -> {
                        diagnostics.log("pairing", "TV rejected the code")
                        PairingResult.WrongCode
                    }
                    else -> {
                        diagnostics.log("pairing", "TV ended pairing with status ${reply.status}")
                        PairingResult.Failed(PairingFailure.REJECTED)
                    }
                }
            }
        } catch (e: PairingException) {
            diagnostics.log("pairing", "Pairing failed: ${e.reason}")
            PairingResult.Failed(e.reason)
        } catch (e: SocketTimeoutException) {
            PairingResult.Failed(PairingFailure.TIMEOUT)
        } catch (e: IOException) {
            diagnostics.log("pairing", "Pairing connection lost: ${e.javaClass.simpleName}")
            PairingResult.Failed(PairingFailure.PROTOCOL_ERROR)
        } finally {
            socket.closeQuietly()
        }
    }

    override fun cancel() {
        socket.closeQuietly()
    }
}

internal object PairingCode {
    private val pattern = Regex("^[0-9A-F]{6}$")

    /** Uppercases and strips spaces; null if it cannot be a valid code. */
    fun normalize(input: String): String? =
        input.filterNot { it.isWhitespace() }.uppercase().takeIf { pattern.matches(it) }

    fun secret(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(client.modulus.unsignedBytes())
        digest.update(client.publicExponent.unsignedBytes())
        digest.update(server.modulus.unsignedBytes())
        digest.update(server.publicExponent.unsignedBytes())
        digest.update(hexToBytes(code.substring(2)))
        return digest.digest()
    }

    fun checksumMatches(secret: ByteArray, code: String): Boolean =
        (secret[0].toInt() and 0xFF) == code.substring(0, 2).toInt(16)

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun BigInteger.unsignedBytes(): ByteArray {
        val bytes = toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
}

internal object PairingWire {
    fun message(build: PairingMessage.Builder.() -> Unit): PairingMessage =
        PairingMessage.newBuilder()
            .setProtocolVersion(2)
            .setStatus(PairingMessage.Status.STATUS_OK)
            .apply(build)
            .build()

    fun hexEncoding(): PairingEncoding =
        PairingEncoding.newBuilder()
            .setType(PairingEncoding.EncodingType.ENCODING_TYPE_HEXADECIMAL)
            .setSymbolLength(6)
            .build()

    fun write(socket: SSLSocket, message: PairingMessage) {
        val out = socket.outputStream
        message.writeDelimitedTo(out)
        out.flush()
    }

    fun read(socket: SSLSocket): PairingMessage = try {
        PairingMessage.parseDelimitedFrom(socket.inputStream) ?: throw EOFException("TV closed the pairing channel")
    } catch (e: InvalidProtocolBufferException) {
        // Socket errors while reading the length prefix arrive wrapped; rethrow them as I/O errors.
        val io = e.unwrapIOException()
        if (io !== e) throw io
        throw PairingException(PairingFailure.PROTOCOL_ERROR, "Unreadable pairing message", e)
    }

    fun expect(socket: SSLSocket, accept: (PairingMessage) -> Boolean): PairingMessage {
        val reply = read(socket)
        if (reply.status != PairingMessage.Status.STATUS_OK) {
            throw PairingException(PairingFailure.REJECTED, "TV answered with status ${reply.status}")
        }
        if (!accept(reply)) throw PairingException(PairingFailure.PROTOCOL_ERROR, "Unexpected pairing message")
        return reply
    }
}
