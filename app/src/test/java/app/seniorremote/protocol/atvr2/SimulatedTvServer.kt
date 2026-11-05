package app.seniorremote.protocol.atvr2

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual tool, skipped in normal test runs: serves two SIMULATED TVs so the installed app can be
 * driven through setup, switching and use on the Android emulator. Events, including the pairing
 * code "shown on the TV", go to build/sim-tv.log.
 *
 *   gradlew :app:testDebugUnitTest --tests "*SimulatedTvServer*" -PsimtvMinutes=20
 *
 * TV 1 listens on the standard ports 6466/6467: in the app use the address 10.0.2.2.
 * TV 2 listens on 7466/7467. To reach it from the emulator as address 127.0.0.1, run
 *   adb -s emulator-5554 reverse tcp:6466 tcp:7466
 *   adb -s emulator-5554 reverse tcp:6467 tcp:7467
 *
 * While it runs, write a command to build/sim-tv.cmd, for example "1 offline":
 *   offline - TV unplugged (nothing answers)     online - power restored
 *   drop    - TV restart (connections dropped)   revoke - pairing removed on the TV
 */
class SimulatedTvServer {
    @Test
    fun serve() {
        val minutes = System.getProperty("simtv.minutes")?.toLongOrNull() ?: 0
        assumeTrue("set -PsimtvMinutes to run the simulated TVs", minutes > 0)
        val log = File("build/sim-tv.log").apply { writeText("") }
        val commands = File("build/sim-tv.cmd").apply { delete() }
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val record = { line: String -> synchronized(log) { log.appendText("${time.format(Date())} $line\n") } }
        val tvs = mapOf(
            "1" to FakeAndroidTv("Simulated TV", 6467, 6466, 5_000, onEvent = { record("TV1 $it") }),
            "2" to FakeAndroidTv("Simulated TV", 7467, 7466, 5_000, onEvent = { record("TV2 $it") }),
        )
        record("LISTENING")
        val end = System.currentTimeMillis() + minutes * 60_000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(300)
            if (!commands.exists()) continue
            val parts = commands.readText().trim().split(' ')
            commands.delete()
            val tv = tvs[parts.first()] ?: continue
            record("COMMAND ${parts.joinToString(" ")}")
            when (parts.getOrNull(1)) {
                "offline" -> tv.goOffline()
                "online" -> tv.goOnline()
                "drop" -> tv.dropConnections()
                "revoke" -> {
                    tv.revokeAllPairings()
                    tv.dropConnections()
                }
            }
        }
        tvs.values.forEach { it.close() }
    }
}
