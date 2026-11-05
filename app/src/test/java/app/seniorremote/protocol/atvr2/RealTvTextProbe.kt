package app.seniorremote.protocol.atvr2

import app.seniorremote.diagnostics.Diagnostics
import app.seniorremote.domain.PairingResult
import app.seniorremote.domain.RemoteKey
import app.seniorremote.domain.TransportResult
import app.seniorremote.net.SocketProvider
import java.io.File
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual tool, skipped in normal test runs: drives a REAL TV through the app's own protocol code
 * to find out how it treats text. Pairs as a separate client, then runs commands.
 *
 *   gradlew :app:testDebugUnitTest --tests "*RealTvTextProbe*" -PtvProbeHost=192.168.1.40
 *
 * Results go to build/tv-probe.log. Write one command at a time to build/tv-probe.cmd:
 *   CODE <code shown on the TV>     TEXT <text to send>     KEY <RemoteKey name>     QUIT
 */
class RealTvTextProbe {
    @Test
    fun probe() = runBlocking<Unit> {
        val host = System.getProperty("tvprobe.host").orEmpty()
        assumeTrue("set -PtvProbeHost to run against a real TV", host.isNotEmpty())
        val log = File("build/tv-probe.log").apply { writeText("") }
        val commands = File("build/tv-probe.cmd").apply { delete() }
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val record = { line: String -> synchronized(log) { log.appendText("${time.format(Date())} $line\n") } }
        val diagnostics = object : Diagnostics {
            override fun log(category: String, message: String) = record("[$category] $message")
        }

        suspend fun nextCommand(): String {
            while (true) {
                if (commands.exists()) {
                    val text = commands.readText(Charsets.UTF_8)
                    commands.delete()
                    return text
                }
                delay(200)
            }
        }

        val sockets = SocketProvider { Socket() }
        val config = AtvConfig(clientName = "Senior TV Remote (test)")
        val pairing = AtvPairing(sockets, diagnostics, config).begin(host)
        record("WAITING FOR CODE")
        val code = nextCommand().removePrefix("CODE ").trim()
        val paired = pairing.submitCode(code)
        if (paired !is PairingResult.Success) {
            record("PAIRING FAILED: $paired")
            return@runBlocking
        }
        val connected = AtvTransport(sockets, diagnostics, config).connect(host, config.defaultRemotePort, paired.credentials)
        if (connected !is TransportResult.Connected) {
            record("CONNECT FAILED: $connected")
            return@runBlocking
        }
        val session = connected.session
        record("CONNECTED textEntrySupported=${session.textEntrySupported}")
        while (true) {
            val command = nextCommand()
            when {
                command.startsWith("TEXT ") -> record("sent text, outcome ${session.sendText(command.removePrefix("TEXT "))}")
                command.startsWith("KEY ") -> record("sent key, outcome ${session.send(RemoteKey.valueOf(command.removePrefix("KEY ").trim()))}")
                command.trim() == "QUIT" -> break
            }
        }
        session.close()
        record("DONE")
    }
}
