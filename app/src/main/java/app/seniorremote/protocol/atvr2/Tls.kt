package app.seniorremote.protocol.atvr2

import app.seniorremote.domain.TvCredentials
import app.seniorremote.net.SocketProvider
import app.seniorremote.net.closeOnCancel
import app.seniorremote.net.closeQuietly
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/** The phone's TLS client identity, presented to the TV during pairing and every connection. */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    companion object {
        fun generate(commonName: String = "atvremote"): ClientIdentity {
            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048, SecureRandom())
            val keyPair = generator.generateKeyPair()
            val now = System.currentTimeMillis()
            val name = X500Name("CN=$commonName")
            val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
            val builder = JcaX509v3CertificateBuilder(
                name,
                serial,
                Date(now - 24L * 3600 * 1000),
                Date(now + 10L * 365 * 24 * 3600 * 1000),
                name,
                keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return ClientIdentity(keyPair.private, certificate)
        }

        fun from(credentials: TvCredentials): ClientIdentity {
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(credentials.clientPrivateKeyPkcs8))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(credentials.clientCertificateDer)) as X509Certificate
            return ClientIdentity(key, cert)
        }
    }
}

internal object Tls {
    fun publicKeySha256(certificate: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)

    fun context(identity: ClientIdentity, trust: X509TrustManager): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(SingleKeyManager(identity)), arrayOf(trust), SecureRandom())
        return context
    }

    /**
     * Opens TCP then TLS. Failures are wrapped in [TlsOpenException] so callers can tell
     * "nothing answered" apart from "something answered but the TLS handshake failed".
     */
    suspend fun open(
        sockets: SocketProvider,
        context: SSLContext,
        host: String,
        port: Int,
        connectTimeoutMs: Int,
        handshakeTimeoutMs: Int,
    ): SSLSocket {
        val raw: Socket = sockets.create()
        return closeOnCancel(raw) {
            try {
                raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
                raw.tcpNoDelay = true
            } catch (e: IOException) {
                raw.closeQuietly()
                throw TlsOpenException(TlsOpenException.Phase.TCP, e)
            }
            try {
                val ssl = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                ssl.useClientMode = true
                ssl.soTimeout = handshakeTimeoutMs
                ssl.startHandshake()
                ssl
            } catch (e: IOException) {
                raw.closeQuietly()
                throw TlsOpenException(TlsOpenException.Phase.TLS, e)
            }
        }
    }
}

internal class TlsOpenException(val phase: Phase, cause: IOException) : IOException(cause.toString(), cause) {
    enum class Phase { TCP, TLS }
}

/**
 * Accepts the TV's self-signed certificate. With [expectedPublicKeySha256] set, only the paired
 * TV's key is accepted, so a different device at the same address is never controlled.
 */
internal class PinningTrustManager(private val expectedPublicKeySha256: ByteArray?) : X509TrustManager {
    @Volatile
    var peerCertificate: X509Certificate? = null
        private set

    @Volatile
    var identityMismatch: Boolean = false
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("TV presented no certificate")
        peerCertificate = leaf
        if (expectedPublicKeySha256 != null &&
            !MessageDigest.isEqual(Tls.publicKeySha256(leaf), expectedPublicKeySha256)
        ) {
            identityMismatch = true
            throw CertificateException("Device identity does not match the paired TV")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client mode only")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private class SingleKeyManager(private val identity: ClientIdentity) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(identity.certificate)
    override fun getPrivateKey(alias: String?): PrivateKey = identity.privateKey

    private companion object {
        const val ALIAS = "client"
    }
}
