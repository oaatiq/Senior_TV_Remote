package app.seniorremote.protocol.atvr2

import app.seniorremote.command.CommandDispatcher
import app.seniorremote.connection.ConnectionConfig
import app.seniorremote.connection.ConnectionManager
import app.seniorremote.data.InMemoryCredentialStore
import app.seniorremote.data.InMemoryDeviceRepository
import app.seniorremote.diagnostics.DiagnosticsLog
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.PairingResult
import app.seniorremote.domain.RemoteKey
import app.seniorremote.domain.SavedTv
import app.seniorremote.net.SocketProvider
import app.seniorremote.testing.FakeNetwork
import java.io.File
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole client stack (pairing, connection manager, dispatcher, real TLS transport) against the
 * SIMULATED TV. Also writes the sanitized diagnostic log to build/reports so the documentation
 * can show real output. This is not evidence of compatibility with a real TV.
 */
class EndToEndSimulatedTvTest {

    @Test
    fun `pair, control, recover from a dropped connection, and detect revoked pairing`() = runBlocking {
        val log = DiagnosticsLog()
        val tv = FakeAndroidTv("Living Room TV")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val sockets = SocketProvider { Socket() }
            val atv = AtvConfig(pairingPort = tv.pairingPort, idleTimeoutMs = 20_000)
            val repository = InMemoryDeviceRepository()
            val credentials = InMemoryCredentialStore()

            // Administrator pairing.
            val session = AtvPairing(sockets, log, atv).begin("127.0.0.1")
            val code = tv.awaitCode()
            log.log("setup", "Administrator typed code=$code")
            val paired = session.submitCode(code) as PairingResult.Success
            credentials.save("ref1", paired.credentials)
            repository.edit {
                it.adding(SavedTv(id = "tv1", originalName = "Living Room TV", lastKnownHost = "127.0.0.1", port = tv.remotePort, credentialRef = "ref1"), select = true)
            }

            val clock = { System.nanoTime() / 1_000_000 }
            val manager = ConnectionManager(
                scope = scope,
                repository = repository,
                credentials = credentials,
                transport = AtvTransport(sockets, log, atv),
                network = FakeNetwork(),
                rediscovery = null,
                diagnostics = log,
                config = ConnectionConfig(rejectionsBeforePairingRequired = 2),
                clock = clock,
            )
            val dispatcher = CommandDispatcher(scope, manager, log, clock = clock)
            manager.start()
            dispatcher.start()
            manager.onForeground()

            withTimeout(15_000) { manager.state.first { it is ConnectionState.Ready } }
            dispatcher.tap("tv1", RemoteKey.DOWN, clock())
            dispatcher.tap("tv1", RemoteKey.OK, clock())
            assertEquals(20, tv.receivedKeys.poll(5, TimeUnit.SECONDS)?.first)
            assertEquals(23, tv.receivedKeys.poll(5, TimeUnit.SECONDS)?.first)

            // The TV drops the connection (e.g. TV restart): automatic recovery.
            val before = manager.state.value as ConnectionState.Ready
            tv.dropConnections()
            withTimeout(15_000) {
                manager.state.first { it is ConnectionState.Ready && it.generation > before.generation }
            }
            dispatcher.tap("tv1", RemoteKey.HOME, clock())
            assertEquals(3, tv.receivedKeys.poll(5, TimeUnit.SECONDS)?.first)

            // Pairing removed on the TV: administrator-required state, no false Ready.
            tv.revokeAllPairings()
            tv.dropConnections()
            withTimeout(20_000) { manager.state.first { it == ConnectionState.PairingRequired } }

            val exported = log.export()
            assertFalse("pairing code leaked", exported.contains(code))
            assertTrue(exported.contains("code=<redacted>"))
            File("build/reports").mkdirs()
            File("build/reports/diagnostic-example.log").writeText(exported)
        } finally {
            scope.cancel()
            tv.close()
        }
    }
}
