package app.seniorremote

import android.content.Context
import android.os.SystemClock
import app.seniorremote.command.CommandDispatcher
import app.seniorremote.connection.ConnectionManager
import app.seniorremote.connection.DiscoveryRediscovery
import app.seniorremote.data.AdminPinStore
import app.seniorremote.data.AndroidTvImageStore
import app.seniorremote.data.KeystoreCredentialStore
import app.seniorremote.data.PrefsDeviceRepository
import app.seniorremote.data.SettingsStore
import app.seniorremote.data.TvManager
import app.seniorremote.diagnostics.DiagnosticsLog
import app.seniorremote.diagnostics.PerformanceTracker
import app.seniorremote.discovery.NsdTvDiscovery
import app.seniorremote.net.AndroidNetworkMonitor
import app.seniorremote.protocol.atvr2.AtvConfig
import app.seniorremote.protocol.atvr2.AtvPairing
import app.seniorremote.protocol.atvr2.AtvTransport
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual dependency wiring. One instance per process, owned by [SeniorRemoteApp]. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val elapsedClock: () -> Long = SystemClock::elapsedRealtime

    val diagnostics = DiagnosticsLog(file = File(context.filesDir, "diagnostics.log"))
    val performance = PerformanceTracker(file = File(context.filesDir, "performance.txt"))
    val network = AndroidNetworkMonitor(context)
    val repository = PrefsDeviceRepository(context)
    val credentials = KeystoreCredentialStore(context)
    val settings = SettingsStore(context)
    val adminPin = AdminPinStore(context)
    val images = AndroidTvImageStore(context)

    private val atvConfig = AtvConfig()
    val discovery = NsdTvDiscovery(context, diagnostics)
    val pairing = AtvPairing(network, diagnostics, atvConfig)
    private val transport = AtvTransport(network, diagnostics, atvConfig)

    val connection = ConnectionManager(
        scope = appScope,
        repository = repository,
        credentials = credentials,
        transport = transport,
        network = network,
        rediscovery = DiscoveryRediscovery(discovery),
        announcements = discovery,
        diagnostics = diagnostics,
        perf = performance,
        clock = elapsedClock,
    )

    val dispatcher = CommandDispatcher(
        scope = appScope,
        connection = connection,
        diagnostics = diagnostics,
        perf = performance,
        clock = elapsedClock,
    )

    val tvManager = TvManager(repository, credentials, images, dispatcher, diagnostics)

    fun start() {
        network.start()
        connection.start()
        dispatcher.start()
        // Save new timing samples shortly after they arrive, so a force-stop does not lose them.
        appScope.launch {
            performance.summaries.drop(1).collectLatest {
                delay(2_000)
                withContext(Dispatchers.IO) { performance.persist() }
            }
        }
    }

    fun persistDiagnostics() {
        appScope.launch(Dispatchers.IO) {
            diagnostics.persist()
            performance.persist()
        }
    }
}
