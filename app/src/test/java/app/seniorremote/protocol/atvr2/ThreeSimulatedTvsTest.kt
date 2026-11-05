package app.seniorremote.protocol.atvr2

import app.seniorremote.command.CommandDispatcher
import app.seniorremote.connection.ConnectionConfig
import app.seniorremote.connection.ConnectionManager
import app.seniorremote.data.InMemoryCredentialStore
import app.seniorremote.data.InMemoryDeviceRepository
import app.seniorremote.data.TvManager
import app.seniorremote.diagnostics.NoDiagnostics
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.PairingResult
import app.seniorremote.domain.RemoteKey
import app.seniorremote.domain.SavedTv
import app.seniorremote.domain.updateTv
import app.seniorremote.net.SocketProvider
import app.seniorremote.testing.FakeNetwork
import app.seniorremote.testing.RecordingImages
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Three independently paired SIMULATED TVs driven through the real TLS client, connection manager
 * and dispatcher. This shows the routing rules hold over real sockets; it says nothing about how
 * a real TV behaves.
 */
class ThreeSimulatedTvsTest {
    private val sockets = SocketProvider { Socket() }
    private val tvs = mapOf("a" to FakeAndroidTv("TV A"), "b" to FakeAndroidTv("TV B"), "c" to FakeAndroidTv("TV C"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository = InMemoryDeviceRepository()
    private val credentials = InMemoryCredentialStore()
    private val clock = { System.nanoTime() / 1_000_000 }
    private val config = AtvConfig(idleTimeoutMs = 20_000)
    private val connection = ConnectionManager(
        scope = scope,
        repository = repository,
        credentials = credentials,
        transport = AtvTransport(sockets, NoDiagnostics, config),
        network = FakeNetwork(),
        rediscovery = null,
        diagnostics = NoDiagnostics,
        config = ConnectionConfig(rejectionsBeforePairingRequired = 2, stableSessionMs = 0),
        clock = clock,
    )
    private val dispatcher = CommandDispatcher(scope, connection, NoDiagnostics, clock = clock)
    private val manager = TvManager(repository, credentials, RecordingImages(), dispatcher, NoDiagnostics)

    @Before
    fun setUp() = runBlocking<Unit> {
        for ((id, tv) in tvs) {
            val session = AtvPairing(sockets, NoDiagnostics, config.copy(pairingPort = tv.pairingPort)).begin("127.0.0.1")
            val paired = session.submitCode(tv.awaitCode()) as PairingResult.Success
            credentials.save("ref-$id", paired.credentials)
            val saved = SavedTv(id = id, originalName = "TV ${id.uppercase()}", lastKnownHost = "127.0.0.1", port = tv.remotePort, credentialRef = "ref-$id")
            repository.edit { it.adding(saved, select = id == "a") }
        }
        connection.start()
        dispatcher.start()
        connection.onForeground()
        awaitReady("a")
    }

    @After
    fun tearDown() {
        scope.cancel()
        tvs.values.forEach { it.close() }
    }

    private suspend fun awaitReady(id: String) = withTimeout(15_000) {
        connection.state.first { it is ConnectionState.Ready && it.tvId == id }
    }

    private fun keysAt(id: String): List<Int> {
        Thread.sleep(250)
        val out = ArrayList<Pair<Int, Int>>()
        tvs.getValue(id).receivedKeys.drainTo(out)
        return out.map { it.first }
    }

    @Test
    fun `each TV receives only what was pressed while it was selected`() = runBlocking<Unit> {
        dispatcher.tap("a", RemoteKey.UP, clock())
        assertEquals(19, tvs.getValue("a").receivedKeys.poll(5, TimeUnit.SECONDS)?.first)

        manager.select("b")
        awaitReady("b")
        dispatcher.tap("b", RemoteKey.DOWN, clock())
        assertEquals(20, tvs.getValue("b").receivedKeys.poll(5, TimeUnit.SECONDS)?.first)

        manager.select("c")
        awaitReady("c")
        dispatcher.tap("c", RemoteKey.OK, clock())
        assertEquals(23, tvs.getValue("c").receivedKeys.poll(5, TimeUnit.SECONDS)?.first)

        assertEquals(emptyList<Int>(), keysAt("a"))
        assertEquals(emptyList<Int>(), keysAt("b"))
    }

    @Test
    fun `reselecting a paired TV needs no pairing`() = runBlocking<Unit> {
        manager.select("b")
        awaitReady("b")
        manager.select("a")
        awaitReady("a")
        dispatcher.tap("a", RemoteKey.HOME, clock())
        assertEquals(3, tvs.getValue("a").receivedKeys.poll(5, TimeUnit.SECONDS)?.first)
    }

    @Test
    fun `switching during a held button stops repetition and nothing reaches the new TV`() = runBlocking<Unit> {
        dispatcher.holdStart("a", RemoteKey.VOLUME_UP, clock())
        delay(800)
        manager.select("b")
        awaitReady("b")
        delay(800)
        val atA = keysAt("a")
        assertTrue("repetition reached TV A: $atA", atA.size >= 2)
        val countAfterSwitch = keysAt("a").size
        delay(500)
        assertEquals(0, countAfterSwitch + keysAt("a").size)
        assertEquals(emptyList<Int>(), keysAt("b"))
    }

    @Test
    fun `presses made while connecting are dropped, not delivered late to either TV`() = runBlocking<Unit> {
        // Keep the new TV from answering so every press happens before its session is ready.
        tvs.getValue("b").goOffline()
        manager.select("b")
        repeat(10) {
            dispatcher.tap("b", RemoteKey.OK, clock())
            delay(20)
        }
        tvs.getValue("b").goOnline()
        awaitReady("b")
        delay(500)
        assertEquals("no backlog on the new TV", emptyList<Int>(), keysAt("b"))
        assertEquals("nothing may reach the previous TV", emptyList<Int>(), keysAt("a"))
        dispatcher.tap("b", RemoteKey.BACK, clock())
        assertEquals(4, tvs.getValue("b").receivedKeys.poll(5, TimeUnit.SECONDS)?.first)
    }

    @Test
    fun `rapid switching settles on the last choice`() = runBlocking<Unit> {
        repeat(4) {
            manager.select("b")
            manager.select("c")
            manager.select("a")
        }
        manager.select("c")
        awaitReady("c")
        delay(600)
        val state = connection.state.value as ConnectionState.Ready
        assertEquals("c", state.tvId)
        dispatcher.tap("c", RemoteKey.LEFT, clock())
        assertEquals(21, tvs.getValue("c").receivedKeys.poll(5, TimeUnit.SECONDS)?.first)
        assertEquals(emptyList<Int>(), keysAt("a"))
        assertEquals(emptyList<Int>(), keysAt("b"))
    }

    @Test
    fun `pairing removed on one TV does not affect the others`() = runBlocking<Unit> {
        tvs.getValue("b").revokeAllPairings()
        manager.select("b")
        withTimeout(15_000) { connection.state.first { it == ConnectionState.PairingRequired } }

        // A failing TV never blocks choosing another one.
        manager.select("c")
        awaitReady("c")
        dispatcher.tap("c", RemoteKey.RIGHT, clock())
        assertEquals(22, tvs.getValue("c").receivedKeys.poll(5, TimeUnit.SECONDS)?.first)
    }

    @Test
    fun `an unavailable selected TV stays selected and another can be chosen`() = runBlocking<Unit> {
        manager.select("b")
        awaitReady("b")
        tvs.getValue("b").goOffline()
        withTimeout(15_000) { connection.state.first { it !is ConnectionState.Ready } }
        delay(1_000)
        assertEquals("b", repository.library.value.selectedId)

        manager.select("a")
        awaitReady("a")
    }

    @Test
    fun `renaming and removing keep the other configurations working`() = runBlocking<Unit> {
        assertNull(manager.rename("a", "Grandma's TV", ""))
        repository.updateTv("c") { it.copy(subtitle = "Kitchen") }
        manager.remove("b")
        assertNull(credentials.load("ref-b"))
        assertNotNull(credentials.load("ref-a"))
        assertNotNull(credentials.load("ref-c"))

        dispatcher.tap("a", RemoteKey.UP, clock())
        assertEquals(19, tvs.getValue("a").receivedKeys.poll(5, TimeUnit.SECONDS)?.first)
        manager.select("c")
        awaitReady("c")
        dispatcher.tap("c", RemoteKey.DOWN, clock())
        assertEquals(20, tvs.getValue("c").receivedKeys.poll(5, TimeUnit.SECONDS)?.first)
    }
}
