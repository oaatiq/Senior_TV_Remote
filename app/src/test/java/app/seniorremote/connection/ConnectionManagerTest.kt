package app.seniorremote.connection

import app.seniorremote.data.InMemoryCredentialStore
import app.seniorremote.data.InMemoryDeviceRepository
import app.seniorremote.diagnostics.NoDiagnostics
import app.seniorremote.domain.ConnectFailure
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.TransportResult
import app.seniorremote.domain.TvCandidate
import app.seniorremote.domain.TvDiscovery
import app.seniorremote.domain.TvLibrary
import app.seniorremote.domain.updateTv
import app.seniorremote.net.NetworkStatus
import app.seniorremote.testing.FakeNetwork
import app.seniorremote.testing.FakeSession
import app.seniorremote.testing.FakeTransport
import app.seniorremote.testing.TV_A
import app.seniorremote.testing.TV_B
import app.seniorremote.testing.TV_C
import app.seniorremote.testing.otherTv
import app.seniorremote.testing.testCredentials
import app.seniorremote.testing.testTv
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionManagerTest {

    private class Harness(
        scope: TestScope,
        val transport: FakeTransport = FakeTransport(),
        val network: FakeNetwork = FakeNetwork(),
        rediscovery: TvRediscovery? = null,
        announcements: TvDiscovery? = null,
        library: TvLibrary = TvLibrary(listOf(testTv()), TV_A),
    ) {
        val repository = InMemoryDeviceRepository(library)
        val credentials = InMemoryCredentialStore()
        val manager = ConnectionManager(
            scope = scope.backgroundScope,
            repository = repository,
            credentials = credentials,
            transport = transport,
            network = network,
            rediscovery = rediscovery,
            announcements = announcements,
            diagnostics = NoDiagnostics,
            config = ConnectionConfig(retryJitter = 0.0),
            clock = { scope.testScheduler.currentTime },
            random = Random(0),
        )
    }

    private suspend fun Harness.startForeground(scope: TestScope) {
        repository.library.value.tvs.forEach { credentials.save(it.credentialRef, testCredentials) }
        manager.start()
        manager.onForeground()
        scope.runCurrent()
    }

    @Test
    fun `launch reaches Ready with a single attempt`() = runTest {
        val h = Harness(this)
        h.startForeground(this)
        assertTrue(h.manager.state.value is ConnectionState.Ready)
        assertEquals(1, h.transport.hosts.size)
        assertEquals(1, h.transport.maxInFlight)
    }

    @Test
    fun `lost session recovers automatically with a new generation and the old one is unusable`() = runTest {
        val h = Harness(this)
        h.startForeground(this)
        val first = h.manager.state.value as ConnectionState.Ready
        advanceTimeBy(20_000) // a stable session: reconnect immediately when it drops
        h.transport.sessions.single().drop()
        runCurrent()
        val second = h.manager.state.value as ConnectionState.Ready
        assertTrue(second.generation > first.generation)
        assertNull(h.manager.sessionFor(first.generation, TV_A))
        assertEquals(h.transport.sessions.last(), h.manager.sessionFor(second.generation, TV_A))
    }

    @Test
    fun `retries never overlap and back off while the TV is unreachable`() = runTest {
        val transport = FakeTransport { kotlinx.coroutines.delay(3_000); FakeTransport.unreachable() }
        val h = Harness(this, transport)
        h.startForeground(this)
        advanceTimeBy(60_000)
        assertEquals(1, transport.maxInFlight)
        assertEquals(ConnectionState.TvUnreachable, h.manager.state.value)
        // 60 s with 3 s attempts and growing delays: far fewer attempts than a tight loop would make.
        assertTrue("attempts=${transport.hosts.size}", transport.hosts.size in 5..12)
    }

    @Test
    fun `recovers once the TV becomes reachable again`() = runTest {
        var reachable = false
        val transport = FakeTransport { host -> if (reachable) TransportResult.Connected(FakeSession(host)) else FakeTransport.unreachable() }
        val h = Harness(this, transport)
        h.startForeground(this)
        advanceTimeBy(20_000)
        assertEquals(ConnectionState.TvUnreachable, h.manager.state.value)
        reachable = true
        advanceTimeBy(10_001)
        assertTrue(h.manager.state.value is ConnectionState.Ready)
    }

    @Test
    fun `background cancels a slow attempt and its late session is closed, not used`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport { host -> gate.await(); TransportResult.Connected(FakeSession(host)) }
        val h = Harness(this, transport)
        h.startForeground(this)
        assertEquals(ConnectionState.Connecting, h.manager.state.value)
        h.manager.onBackground()
        runCurrent()
        assertEquals(ConnectionState.Suspended, h.manager.state.value)
        gate.complete(Unit)
        runCurrent()
        assertEquals(ConnectionState.Suspended, h.manager.state.value)
        assertTrue(h.transport.sessions.all { it.closedByApp.isCompleted })
    }

    @Test
    fun `quick return to foreground reuses a fresh session`() = runTest {
        val h = Harness(this)
        h.startForeground(this)
        h.manager.onBackground()
        advanceTimeBy(5_000)
        h.manager.onForeground()
        runCurrent()
        assertTrue(h.manager.state.value is ConnectionState.Ready)
        assertEquals(1, h.transport.hosts.size)
    }

    @Test
    fun `return after the grace period reconnects`() = runTest {
        val h = Harness(this)
        h.startForeground(this)
        h.manager.onBackground()
        advanceTimeBy(ConnectionConfig().backgroundGraceMs + 1)
        assertEquals(ConnectionState.Suspended, h.manager.state.value)
        assertTrue(h.transport.sessions.single().closedByApp.isCompleted)
        h.manager.onForeground()
        runCurrent()
        assertTrue(h.manager.state.value is ConnectionState.Ready)
        assertEquals(2, h.transport.hosts.size)
    }

    @Test
    fun `network loss and restoration`() = runTest {
        val h = Harness(this)
        h.startForeground(this)
        h.network.flow.value = NetworkStatus(false, null)
        runCurrent()
        assertEquals(ConnectionState.NetworkUnavailable, h.manager.state.value)
        h.network.flow.value = NetworkStatus(true, 2)
        runCurrent()
        assertTrue(h.manager.state.value is ConnectionState.Ready)
    }

    @Test
    fun `changed TV address is found by rediscovery and saved after verification`() = runTest {
        val transport = FakeTransport { host ->
            if (host == "10.0.0.9") TransportResult.Connected(FakeSession(host)) else FakeTransport.unreachable()
        }
        val rediscovery = TvRediscovery { _, _ ->
            listOf(TvCandidate("Living room TV", "Living room TV", "10.0.0.9", 6466, "AA:BB", ""))
        }
        val h = Harness(this, transport, rediscovery = rediscovery)
        h.startForeground(this)
        assertTrue(h.manager.state.value is ConnectionState.Ready)
        assertEquals("10.0.0.9", h.repository.library.value.find(TV_A)?.lastKnownHost)
    }

    @Test
    fun `similarly named TV that fails identity check is never substituted`() = runTest {
        val transport = FakeTransport { host ->
            if (host == "10.0.0.9") TransportResult.Failed(ConnectFailure.IDENTITY_MISMATCH, "pin") else FakeTransport.unreachable()
        }
        val rediscovery = TvRediscovery { _, _ ->
            listOf(TvCandidate("Living room TV", "Living room TV", "10.0.0.9", 6466, null, ""))
        }
        val h = Harness(this, transport, rediscovery = rediscovery)
        h.startForeground(this)
        advanceTimeBy(30_000)
        assertTrue(h.manager.state.value !is ConnectionState.Ready)
        assertEquals("10.0.0.5", h.repository.library.value.find(TV_A)?.lastKnownHost)
    }

    @Test
    fun `revoked pairing ends in PairingRequired without endless retries`() = runTest {
        val transport = FakeTransport { TransportResult.Failed(ConnectFailure.AUTH_REJECTED, "closed") }
        val h = Harness(this, transport)
        h.startForeground(this)
        advanceTimeBy(60_000)
        assertEquals(ConnectionState.PairingRequired, h.manager.state.value)
        assertEquals(ConnectionConfig().rejectionsBeforePairingRequired, transport.hosts.size)
    }

    @Test
    fun `send failure closes the session and reconnects`() = runTest {
        val h = Harness(this)
        h.startForeground(this)
        val ready = h.manager.state.value as ConnectionState.Ready
        h.manager.reportSendFailure(ready.generation, "broken pipe")
        advanceTimeBy(1_000)
        val now = h.manager.state.value as ConnectionState.Ready
        assertTrue(now.generation > ready.generation)
        assertTrue(h.transport.sessions.first().closedByApp.isCompleted)
    }

    @Test
    fun `TV announcing itself after a restart triggers an immediate reconnect`() = runTest {
        var reachable = false
        val transport = FakeTransport { host -> if (reachable) TransportResult.Connected(FakeSession(host)) else FakeTransport.unreachable() }
        val announced = MutableStateFlow<List<TvCandidate>>(emptyList())
        val h = Harness(this, transport, announcements = TvDiscovery { announced })
        h.startForeground(this)
        advanceTimeBy(40_000) // well into the 10 s backoff steps
        assertEquals(ConnectionState.TvUnreachable, h.manager.state.value)

        reachable = true
        announced.value = listOf(TvCandidate("Living room TV", "Living room TV", "10.0.0.5", 6466, "AA:BB", ""))
        advanceTimeBy(100)
        assertTrue("should not wait for the next backoff step", h.manager.state.value is ConnectionState.Ready)
    }

    @Test
    fun `TV announcing at a new address is used once its identity is verified`() = runTest {
        val transport = FakeTransport { host ->
            if (host == "10.0.0.77") TransportResult.Connected(FakeSession(host)) else FakeTransport.unreachable()
        }
        val announced = MutableStateFlow<List<TvCandidate>>(emptyList())
        val h = Harness(this, transport, announcements = TvDiscovery { announced })
        h.startForeground(this)
        advanceTimeBy(30_000)
        announced.value = listOf(TvCandidate("Living room TV", "Living room TV", "10.0.0.77", 6466, "AA:BB", ""))
        advanceTimeBy(100)
        assertTrue(h.manager.state.value is ConnectionState.Ready)
        assertEquals("10.0.0.77", h.repository.library.value.find(TV_A)?.lastKnownHost)
    }

    private val threeTvs = TvLibrary(
        listOf(testTv("10.0.0.5"), otherTv(TV_B, "10.0.0.6"), otherTv(TV_C, "10.0.0.7")),
        TV_A,
    )

    private suspend fun Harness.select(id: String) = repository.edit { it.selecting(id) }

    @Test
    fun `switching connects only to the newly selected TV`() = runTest {
        val h = Harness(this, library = threeTvs)
        h.startForeground(this)
        val first = h.manager.state.value as ConnectionState.Ready
        assertEquals(TV_A, first.tvId)

        h.select(TV_B)
        runCurrent()
        val second = h.manager.state.value as ConnectionState.Ready
        assertEquals(TV_B, second.tvId)
        assertEquals(listOf("10.0.0.5", "10.0.0.6"), h.transport.hosts)
        assertTrue(h.transport.sessions.first().closedByApp.isCompleted)
        assertNull(h.manager.sessionFor(first.generation, TV_A))
        assertNull("a session answers only for its own TV", h.manager.sessionFor(second.generation, TV_A))
        assertEquals(h.transport.sessions.last(), h.manager.sessionFor(second.generation, TV_B))
    }

    @Test
    fun `late session from the previous TV is closed and never used`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = FakeTransport { host ->
            // A transport that only notices cancellation after it has already connected.
            if (host == "10.0.0.5") withContext(NonCancellable) { gate.await() }
            TransportResult.Connected(FakeSession(host))
        }
        val h = Harness(this, transport, library = threeTvs)
        h.startForeground(this)
        h.select(TV_B)
        runCurrent()
        assertEquals(TV_B, (h.manager.state.value as ConnectionState.Ready).tvId)
        gate.complete(Unit)
        runCurrent()
        assertEquals(TV_B, (h.manager.state.value as ConnectionState.Ready).tvId)
        assertEquals("10.0.0.6", (h.manager.sessionFor(1, TV_B) as FakeSession).host)
        assertTrue(h.transport.sessions.single { it.host == "10.0.0.5" }.closedByApp.isCompleted)
    }

    @Test
    fun `rapid switching ends connected to the last choice and never overlaps`() = runTest {
        val transport = FakeTransport { host ->
            kotlinx.coroutines.delay(500)
            TransportResult.Connected(FakeSession(host))
        }
        val h = Harness(this, transport, library = threeTvs)
        h.startForeground(this)
        listOf(TV_B, TV_C, TV_A, TV_B, TV_C).forEach { id ->
            h.select(id)
            advanceTimeBy(120)
        }
        advanceTimeBy(2_000)
        assertEquals(TV_C, (h.manager.state.value as ConnectionState.Ready).tvId)
        assertEquals(1, transport.maxInFlight)
        assertEquals(1, transport.sessions.size)
        assertEquals("10.0.0.7", transport.sessions.single().host)
    }

    @Test
    fun `unreachable selected TV keeps the selection even if another TV is available`() = runTest {
        val transport = FakeTransport { host ->
            if (host == "10.0.0.5") FakeTransport.unreachable() else TransportResult.Connected(FakeSession(host))
        }
        val h = Harness(this, transport, library = threeTvs)
        h.startForeground(this)
        advanceTimeBy(60_000)
        assertEquals(ConnectionState.TvUnreachable, h.manager.state.value)
        assertEquals(TV_A, h.repository.library.value.selectedId)
        assertTrue("other TVs are never contacted", transport.hosts.all { it == "10.0.0.5" })
    }

    @Test
    fun `another TV can be chosen while the selected TV is failing`() = runTest {
        val transport = FakeTransport { host ->
            if (host == "10.0.0.5") FakeTransport.unreachable() else TransportResult.Connected(FakeSession(host))
        }
        val h = Harness(this, transport, library = threeTvs)
        h.startForeground(this)
        advanceTimeBy(20_000)
        h.select(TV_B)
        runCurrent()
        assertEquals(TV_B, (h.manager.state.value as ConnectionState.Ready).tvId)
    }

    @Test
    fun `rediscovery never tries another saved TV`() = runTest {
        val transport = FakeTransport { FakeTransport.unreachable() }
        val rediscovery = TvRediscovery { _, _ ->
            listOf(TvCandidate("x", "x", "10.0.0.6", 6466, null, ""), TvCandidate("y", "y", "10.0.0.99", 6466, null, ""))
        }
        val h = Harness(this, transport, rediscovery = rediscovery, library = threeTvs)
        h.startForeground(this)
        assertTrue("10.0.0.6" !in transport.hosts)
        assertTrue("10.0.0.99" in transport.hosts)
    }

    @Test
    fun `a new address found for one TV is saved on that TV only`() = runTest {
        val transport = FakeTransport { host ->
            if (host == "10.0.0.9") TransportResult.Connected(FakeSession(host)) else FakeTransport.unreachable()
        }
        val rediscovery = TvRediscovery { _, _ -> listOf(TvCandidate("x", "x", "10.0.0.9", 6466, null, "")) }
        val h = Harness(this, transport, rediscovery = rediscovery, library = threeTvs)
        h.startForeground(this)
        val library = h.repository.library.value
        assertEquals("10.0.0.9", library.find(TV_A)?.lastKnownHost)
        assertEquals("10.0.0.6", library.find(TV_B)?.lastKnownHost)
    }

    @Test
    fun `renaming or updating a TV does not disturb its session`() = runTest {
        val h = Harness(this, library = threeTvs)
        h.startForeground(this)
        h.repository.updateTv(TV_A) { it.copy(customName = "Grandma's TV") }
        h.repository.updateTv(TV_B) { it.copy(customName = "Kitchen") }
        advanceTimeBy(1_000)
        assertTrue(h.manager.state.value is ConnectionState.Ready)
        assertEquals(1, h.transport.hosts.size)
    }

    @Test
    fun `renewed pairing of the selected TV reconnects with the new credentials`() = runTest {
        val h = Harness(this, library = threeTvs)
        h.startForeground(this)
        h.credentials.save("ref-new", testCredentials)
        h.repository.updateTv(TV_A) { it.copy(credentialRef = "ref-new") }
        runCurrent()
        assertEquals(2, h.transport.hosts.size)
        assertTrue(h.transport.sessions.first().closedByApp.isCompleted)
        assertEquals(TV_A, (h.manager.state.value as ConnectionState.Ready).tvId)
    }

    @Test
    fun `removing the selected TV with several left connects to nothing until one is chosen`() = runTest {
        val h = Harness(this, library = threeTvs)
        h.startForeground(this)
        h.repository.edit { it.removing(TV_A) }
        advanceTimeBy(30_000)
        assertEquals(ConnectionState.NotConfigured, h.manager.state.value)
        assertEquals(1, h.transport.hosts.size)
        h.select(TV_C)
        runCurrent()
        assertEquals(TV_C, (h.manager.state.value as ConnectionState.Ready).tvId)
    }

    @Test
    fun `removing the selected TV with one left connects to the remaining TV`() = runTest {
        val two = TvLibrary(listOf(testTv(), otherTv(TV_B, "10.0.0.6")), TV_A)
        val h = Harness(this, library = two)
        h.startForeground(this)
        h.repository.edit { it.removing(TV_A) }
        runCurrent()
        assertEquals(TV_B, (h.manager.state.value as ConnectionState.Ready).tvId)
    }

    @Test
    fun `TV that sleeps and leaves the network shows off, then returns by itself`() = runTest {
        var awake = true
        val transport = FakeTransport { host -> if (awake) TransportResult.Connected(FakeSession(host)) else FakeTransport.unreachable() }
        val announced = MutableStateFlow<List<TvCandidate>>(emptyList())
        val h = Harness(this, transport, announcements = TvDiscovery { announced })
        h.startForeground(this)
        val session = transport.sessions.single()

        session.screen.value = false
        runCurrent()
        assertEquals(true, (h.manager.state.value as ConnectionState.Ready).tvAsleep)

        awake = false
        session.drop("no data from TV")
        advanceTimeBy(60_000)
        assertEquals(ConnectionState.TvOff, h.manager.state.value)

        // Someone turns the TV on with its own remote: it announces itself and the app reconnects.
        awake = true
        announced.value = listOf(TvCandidate("Living room TV", "Living room TV", "10.0.0.5", 6466, "AA:BB", ""))
        advanceTimeBy(100)
        assertEquals(false, (h.manager.state.value as ConnectionState.Ready).tvAsleep)
    }

    @Test
    fun `TV that stays connected in standby stays Ready and marked asleep`() = runTest {
        val h = Harness(this)
        h.startForeground(this)
        h.transport.sessions.single().screen.value = false
        advanceTimeBy(60_000)
        assertEquals(true, (h.manager.state.value as ConnectionState.Ready).tvAsleep)
        h.transport.sessions.single().screen.value = true
        runCurrent()
        assertEquals(false, (h.manager.state.value as ConnectionState.Ready).tvAsleep)
        assertEquals(1, h.transport.hosts.size)
    }

    @Test
    fun `forgetting the TV closes the session`() = runTest {
        val h = Harness(this)
        h.startForeground(this)
        h.repository.edit { it.removing(TV_A) }
        runCurrent()
        assertEquals(ConnectionState.NotConfigured, h.manager.state.value)
        assertTrue(h.transport.sessions.single().closedByApp.isCompleted)
    }
}
