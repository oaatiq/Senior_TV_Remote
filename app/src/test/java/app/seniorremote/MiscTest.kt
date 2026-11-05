package app.seniorremote

import app.seniorremote.data.CredentialCodec
import app.seniorremote.diagnostics.DiagnosticsLog
import app.seniorremote.diagnostics.Metric
import app.seniorremote.diagnostics.PerformanceTracker
import app.seniorremote.diagnostics.Redactor
import app.seniorremote.domain.Capability
import app.seniorremote.domain.CapabilityStatus
import app.seniorremote.domain.RemoteKey
import app.seniorremote.domain.TvCredentials
import app.seniorremote.testing.testTv
import app.seniorremote.ui.setup.Answer
import app.seniorremote.ui.setup.aggregateCapabilities
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionTest {
    @Test
    fun `pairing codes, secrets and keys never reach the log`() {
        val log = DiagnosticsLog()
        log.log("pairing", "user typed code=4F2A9C and secret: 0a1b2c3d4e5f60718293a4b5c6d7e8f9")
        log.log("tls", "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----")
        log.log("tls", "blob MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7")
        val text = log.export()
        assertFalse(text.contains("4F2A9C"))
        assertFalse(text.contains("0a1b2c3d4e5f"))
        assertFalse(text.contains("MIIEvQIBADAN"))
        assertTrue(text.contains("code=<redacted>"))
    }

    @Test
    fun `addresses are truncated`() {
        assertEquals("Connect to 192.168.1.x failed", Redactor.sanitize("Connect to 192.168.1.47 failed"))
        assertEquals("bt xx:xx:xx:xx:EE:FF", Redactor.sanitize("bt AA:BB:CC:DD:EE:FF"))
        assertEquals("host <ipv6>", Redactor.sanitize("host fe80:0:0:0:1c2d:3e4f:5a6b:7c8d"))
    }

    @Test
    fun `ordinary messages are unchanged`() {
        val text = "Connecting -> Ready#3 (AttemptSucceeded)"
        assertEquals(text, Redactor.sanitize(text))
    }
}

class CapabilityTest {
    private val none = Capability.entries.associateWith { CapabilityStatus.NOT_VERIFIED }

    @Test
    fun `capability is supported only when every related check is confirmed`() {
        val result = aggregateCapabilities(mapOf("nav_vertical" to Answer.YES, "nav_horizontal" to Answer.YES, "ok" to Answer.NO), none)
        assertEquals(CapabilityStatus.SUPPORTED_TESTED, result[Capability.NAVIGATION])
        assertEquals(CapabilityStatus.UNSUPPORTED, result[Capability.OK])
        assertEquals(CapabilityStatus.NOT_VERIFIED, result[Capability.VOLUME])
    }

    @Test
    fun `partially confirmed or skipped checks are not supported`() {
        val result = aggregateCapabilities(mapOf("nav_vertical" to Answer.YES, "nav_horizontal" to Answer.SKIP), none)
        assertEquals(CapabilityStatus.NOT_VERIFIED, result[Capability.NAVIGATION])
    }

    @Test
    fun `skipping during a re-test keeps earlier results`() {
        val previous = none + (Capability.MUTE to CapabilityStatus.SUPPORTED_TESTED)
        val result = aggregateCapabilities(mapOf("mute" to Answer.SKIP), previous)
        assertEquals(CapabilityStatus.SUPPORTED_TESTED, result[Capability.MUTE])
    }

    @Test
    fun `text entry is its own capability per TV`() {
        assertEquals(CapabilityStatus.SUPPORTED_TESTED, aggregateCapabilities(mapOf("text" to Answer.YES), none)[Capability.TEXT_ENTRY])
        assertEquals(CapabilityStatus.UNSUPPORTED, aggregateCapabilities(mapOf("text" to Answer.NO), none)[Capability.TEXT_ENTRY])
        assertEquals(CapabilityStatus.NOT_VERIFIED, testTv().status(Capability.TEXT_ENTRY))
    }

    @Test
    fun `unsupported controls are hidden and power needs a verified standby`() {
        val tv = testTv().copy(capabilities = none + (Capability.VOLUME to CapabilityStatus.UNSUPPORTED))
        assertFalse(tv.showsKey(RemoteKey.VOLUME_UP))
        assertTrue(tv.showsKey(RemoteKey.UP))
        assertFalse(tv.showsKey(RemoteKey.POWER))
        val withPower = tv.copy(capabilities = tv.capabilities + (Capability.POWER_STANDBY to CapabilityStatus.SUPPORTED_TESTED))
        assertTrue(withPower.showsKey(RemoteKey.POWER))
    }
}

class StorageAndMetricsTest {
    @Test
    fun `credential encoding round trips and hides contents from toString`() {
        val original = TvCredentials(ByteArray(300) { it.toByte() }, ByteArray(40) { 7 }, ByteArray(32) { 9 })
        val decoded = CredentialCodec.decode(CredentialCodec.encode(original))
        assertArrayEquals(original.clientPrivateKeyPkcs8, decoded.clientPrivateKeyPkcs8)
        assertArrayEquals(original.clientCertificateDer, decoded.clientCertificateDer)
        assertArrayEquals(original.serverPublicKeySha256, decoded.serverPublicKeySha256)
        assertEquals("TvCredentials(<redacted>)", decoded.toString())
    }

    @Test
    fun `performance summary reports percentiles and abandoned trials`() {
        val tracker = PerformanceTracker()
        (1..100).forEach { tracker.record(Metric.FOREGROUND_TO_READY, it * 30L) }
        tracker.markForeground(0)
        tracker.abandonPending()
        val summary = tracker.summaries.value.single { it.metric == Metric.FOREGROUND_TO_READY }
        assertEquals(100, summary.count)
        assertEquals(1_500L, summary.medianMs)
        assertEquals(2_850L, summary.p95Ms)
        assertEquals(66, summary.withinTarget)
        assertEquals(1, summary.abandoned)
    }

    @Test
    fun `ready marks close all pending trials`() {
        val tracker = PerformanceTracker()
        tracker.markColdLaunch(0)
        tracker.markRecoveryStart(500)
        tracker.markReady(1_200)
        val byMetric = tracker.summaries.value.associateBy { it.metric }
        assertEquals(1_200L, byMetric.getValue(Metric.COLD_LAUNCH_TO_READY).maxMs)
        assertEquals(700L, byMetric.getValue(Metric.RECOVERY_TO_READY).maxMs)
        assertEquals(0, byMetric.getValue(Metric.FOREGROUND_TO_READY).count)
    }

    @Test
    fun `recovery time restarts when the TV or network becomes reachable again`() {
        val tracker = PerformanceTracker()
        tracker.markRecoveryStart(0) // session lost
        tracker.markRecoveryStart(1_000) // later loss signal does not move the start
        tracker.markRecoveryStart(30_000, reachableAgain = true) // TV announced itself after 30 s off
        tracker.markReady(30_800)
        val recovery = tracker.summaries.value.single { it.metric == Metric.RECOVERY_TO_READY }
        assertEquals(800L, recovery.maxMs)
    }
}
