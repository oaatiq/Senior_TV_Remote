package app.seniorremote.protocol.atvr2

import app.seniorremote.diagnostics.NoDiagnostics
import app.seniorremote.domain.ConnectFailure
import app.seniorremote.domain.PairingResult
import app.seniorremote.domain.RemoteKey
import app.seniorremote.domain.SendOutcome
import app.seniorremote.domain.TransportResult
import app.seniorremote.domain.TvCredentials
import app.seniorremote.net.SocketProvider
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Client protocol code against the simulated TV in [FakeAndroidTv]. */
class AtvProtocolTest {
    private val tv = FakeAndroidTv()
    private val sockets = SocketProvider { Socket() }
    private val config = AtvConfig(pairingPort = tv.pairingPort, idleTimeoutMs = 3_000)
    private val pairing = AtvPairing(sockets, NoDiagnostics, config)
    private val transport = AtvTransport(sockets, NoDiagnostics, config)

    @After
    fun tearDown() = tv.close()

    private fun pair(): TvCredentials = runBlocking {
        val session = pairing.begin("127.0.0.1")
        assertEquals("Fake TV", session.serverName)
        val result = session.submitCode(tv.awaitCode().lowercase())
        assertTrue("expected success but was $result", result is PairingResult.Success)
        (result as PairingResult.Success).credentials
    }

    private fun connect(credentials: TvCredentials, port: Int = tv.remotePort): TransportResult = runBlocking {
        withTimeout(15_000) { transport.connect("127.0.0.1", port, credentials) }
    }

    @Test
    fun `pairing produces credentials and the control session sends keys in order`() {
        val credentials = pair()
        val result = connect(credentials)
        assertTrue("connect failed: $result", result is TransportResult.Connected)
        val session = (result as TransportResult.Connected).session
        runBlocking {
            assertEquals(SendOutcome.Transmitted, session.send(RemoteKey.UP))
            assertEquals(SendOutcome.Transmitted, session.send(RemoteKey.OK))
            assertEquals(SendOutcome.Transmitted, session.send(RemoteKey.VOLUME_UP))
        }
        val shortPress = 3
        assertEquals(19 to shortPress, tv.receivedKeys.poll(5, TimeUnit.SECONDS))
        assertEquals(23 to shortPress, tv.receivedKeys.poll(5, TimeUnit.SECONDS))
        assertEquals(24 to shortPress, tv.receivedKeys.poll(5, TimeUnit.SECONDS))
        session.close()
    }

    @Test
    fun `wrong pairing code is reported as wrong code`() = runBlocking {
        val session = pairing.begin("127.0.0.1")
        val real = tv.awaitCode()
        val wrong = real.substring(0, 2) + (if (real.substring(2) == "0000") "0001" else "0000")
        assertEquals(PairingResult.WrongCode, session.submitCode(wrong))
    }

    @Test
    fun `badly formatted code is rejected before anything is sent`() = runBlocking {
        val session = pairing.begin("127.0.0.1")
        tv.awaitCode()
        assertEquals(PairingResult.WrongCode, session.submitCode("12G4"))
    }

    @Test
    fun `session answers TV pings`() {
        val session = (connect(pair()) as TransportResult.Connected).session
        tv.sendPing(7)
        assertEquals(7, tv.pingResponses.poll(5, TimeUnit.SECONDS))
        assertTrue(session.millisSinceLastInbound() < 5_000)
        session.close()
    }

    @Test
    fun `revoked pairing is classified as rejected credentials`() {
        val credentials = pair()
        tv.revokeAllPairings()
        val result = connect(credentials)
        assertEquals(ConnectFailure.AUTH_REJECTED, (result as TransportResult.Failed).failure)
    }

    @Test
    fun `a different TV at the address is never controlled`() {
        val credentials = pair()
        FakeAndroidTv("Fake TV").use { impostor ->
            val result = connect(credentials, impostor.remotePort)
            assertEquals(ConnectFailure.IDENTITY_MISMATCH, (result as TransportResult.Failed).failure)
        }
    }

    @Test
    fun `nothing listening is classified as unreachable`() {
        val credentials = pair()
        val freePort = ServerSocket(0).use { it.localPort }
        val result = connect(credentials, freePort)
        assertEquals(ConnectFailure.UNREACHABLE, (result as TransportResult.Failed).failure)
    }

    @Test
    fun `connection dropped by the TV ends the session and later sends fail`() {
        val session = (connect(pair()) as TransportResult.Connected).session
        tv.dropConnections()
        val reason = runBlocking { withTimeout(5_000) { session.closed.await() } }
        assertNotNull(reason)
        val outcome = runBlocking { session.send(RemoteKey.DOWN) }
        assertTrue(outcome is SendOutcome.Failed)
    }

    @Test
    fun `silence longer than the idle timeout ends the session`() {
        val session = (connect(pair()) as TransportResult.Connected).session
        val reason = runBlocking { withTimeout(10_000) { session.closed.await() } }
        assertTrue(reason, reason.contains("no data"))
    }

    @Test
    fun `a TV that reports standby and goes silent is detected sooner than the normal limit`() {
        val quick = AtvTransport(sockets, NoDiagnostics, config.copy(idleTimeoutMs = 20_000, standbyIdleTimeoutMs = 700))
        val credentials = pair()
        val session = runBlocking { quick.connect("127.0.0.1", tv.remotePort, credentials) as TransportResult.Connected }.session
        tv.sendScreenState(on = false)
        val reason = runBlocking { withTimeout(5_000) { session.closed.await() } }
        assertEquals(false, session.screenOn.value)
        assertTrue(reason, reason.contains("700"))
    }

    @Test
    fun `a TV that keeps talking in standby keeps its session`() {
        val quick = AtvTransport(sockets, NoDiagnostics, config.copy(idleTimeoutMs = 20_000, standbyIdleTimeoutMs = 700))
        val credentials = pair()
        val session = runBlocking { quick.connect("127.0.0.1", tv.remotePort, credentials) as TransportResult.Connected }.session
        tv.sendScreenState(on = false)
        repeat(6) {
            Thread.sleep(300)
            tv.sendPing(it)
        }
        assertTrue(!session.closed.isCompleted)
        tv.sendScreenState(on = true)
        Thread.sleep(1_200)
        assertEquals(true, session.screenOn.value)
        assertTrue("normal limit applies again", !session.closed.isCompleted)
        session.close()
    }

    @Test
    fun `text goes out as one edit, exactly as typed, with the TV's own counters`() {
        val session = (connect(pair()) as TransportResult.Connected).session
        assertEquals(true, session.textEntrySupported)
        tv.sendImeState(ime = 3, field = 7)
        tv.sendPing(1)
        assertEquals(1, tv.pingResponses.poll(5, TimeUnit.SECONDS)) // the counters have been processed

        val typed = "  Crème brûlée, 你好 مرحبا! "
        assertEquals(SendOutcome.Transmitted, runBlocking { session.sendText(typed) })

        val received = tv.receivedTexts.poll(5, TimeUnit.SECONDS)
        assertEquals(FakeAndroidTv.ReceivedText(3, 7, 1, typed.length - 1, typed.length - 1, typed), received)
        assertNull("nothing else is sent", tv.receivedKeys.poll(300, TimeUnit.MILLISECONDS))
        session.close()
    }

    @Test
    fun `text positions count characters, not UTF-16 units`() {
        val session = (connect(pair()) as TransportResult.Connected).session
        runBlocking { session.sendText("a\uD83D\uDE00") }
        val received = tv.receivedTexts.poll(5, TimeUnit.SECONDS)
        assertEquals(1, received?.start)
        assertEquals(1, received?.end)
        session.close()
    }

    @Test
    fun `long text arrives whole in one edit`() {
        val session = (connect(pair()) as TransportResult.Connected).session
        val long = "word ".repeat(100)
        runBlocking { session.sendText(long) }
        assertEquals(long, tv.receivedTexts.poll(5, TimeUnit.SECONDS)?.value)
        session.close()
    }

    @Test
    fun `a TV without the text feature says so`() {
        FakeAndroidTv("No text", acceptsText = false).use { plain ->
            val settings = AtvConfig(pairingPort = plain.pairingPort)
            val credentials = runBlocking {
                val pairingSession = AtvPairing(sockets, NoDiagnostics, settings).begin("127.0.0.1")
                (pairingSession.submitCode(plain.awaitCode()) as PairingResult.Success).credentials
            }
            val session = (connect(credentials, plain.remotePort) as TransportResult.Connected).session
            assertEquals(false, session.textEntrySupported)
            session.close()
        }
    }

    @Test
    fun `sending text on a closed session fails instead of pretending`() {
        val session = (connect(pair()) as TransportResult.Connected).session
        session.close()
        assertTrue(runBlocking { session.sendText("late") } is SendOutcome.Failed)
    }

    @Test
    fun `credentials pin the TV key and codes are normalized`() {
        val credentials = pair()
        assertEquals(32, credentials.serverPublicKeySha256.size)
        assertEquals(null, PairingCode.normalize("12345"))
        assertEquals("ABCDEF", PairingCode.normalize(" ab cd ef "))
    }
}
