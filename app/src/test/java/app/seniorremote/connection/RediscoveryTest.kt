package app.seniorremote.connection

import app.seniorremote.domain.TvCandidate
import app.seniorremote.domain.TvDiscovery
import app.seniorremote.testing.testTv
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RediscoveryTest {
    private val sameTvNewAddress = TvCandidate("Living room TV", "Living room TV", "10.0.0.9", 6466, "AA:BB", "")
    private val otherTv = TvCandidate("Bedroom TV", "Bedroom TV", "10.0.0.20", 6466, "CC:DD", "")
    private val sameTvOldAddress = TvCandidate("Living room TV", "Living room TV", "10.0.0.5", 6466, "AA:BB", "")
    private val discovery = TvDiscovery { flowOf(listOf(otherTv, sameTvOldAddress, sameTvNewAddress)) }

    @Test
    fun `TV set up from discovery only considers matching candidates at other addresses`() = runTest {
        val result = DiscoveryRediscovery(discovery).candidatesFor(testTv(), 1_000)
        assertEquals(listOf(sameTvNewAddress), result)
    }

    @Test
    fun `TV added by address considers every other candidate`() = runTest {
        val manual = testTv().copy(serviceName = null, deviceId = null)
        val result = DiscoveryRediscovery(discovery).candidatesFor(manual, 1_000)
        assertEquals(listOf(otherTv, sameTvNewAddress), result)
    }
}
