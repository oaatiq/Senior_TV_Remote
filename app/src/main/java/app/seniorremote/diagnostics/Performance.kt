package app.seniorremote.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Acceptance metrics the app can measure itself (spec section 14). Times are elapsed-realtime ms. */
enum class Metric(val label: String, val targetMs: Long) {
    LAUNCH_TO_REMOTE_VISIBLE("Launch to remote visible", 500),
    COLD_LAUNCH_TO_READY("Cold launch to Ready", 3_000),
    FOREGROUND_TO_READY("Foreground reopen to Ready", 2_000),
    /** From session loss, restarted when the network or the TV reappears. */
    RECOVERY_TO_READY("Recovery to Ready", 5_000),

    /** Lower bound for press-to-TV-response: time until the bytes were written to the socket. */
    PRESS_TO_TRANSMIT("Press to command transmitted", 200),
}

data class MetricSummary(
    val metric: Metric,
    val count: Int,
    val abandoned: Int,
    val medianMs: Long?,
    val p95Ms: Long?,
    val maxMs: Long?,
    val withinTarget: Int,
) {
    fun format(): String = if (count == 0) {
        "${metric.label}: no samples" + if (abandoned > 0) " ($abandoned abandoned)" else ""
    } else {
        "${metric.label}: n=$count, median=${medianMs}ms, p95=${p95Ms}ms, max=${maxMs}ms, " +
            "within ${metric.targetMs}ms: $withinTarget/$count" +
            if (abandoned > 0) ", abandoned=$abandoned" else ""
    }
}

/**
 * Records timing trials on the real phone so the administrator can report measured
 * performance (including slow and abandoned trials, not only averages).
 */
class PerformanceTracker(
    private val file: File? = null,
    private val maxSamplesPerMetric: Int = 200,
) {
    private val samples = mutableMapOf<Metric, ArrayDeque<Long>>()
    private val abandoned = mutableMapOf<Metric, Int>()
    private var coldStartAt: Long? = null
    private var foregroundAt: Long? = null
    private var recoveryAt: Long? = null

    private val _summaries = MutableStateFlow<List<MetricSummary>>(emptyList())
    val summaries: StateFlow<List<MetricSummary>> = _summaries.asStateFlow()

    init {
        file?.takeIf { it.exists() }?.let { restore(it) }
        publish()
    }

    @Synchronized
    fun markColdLaunch(processStartElapsedMs: Long) {
        coldStartAt = processStartElapsedMs
    }

    @Synchronized
    fun markForeground(nowMs: Long) {
        if (coldStartAt == null) foregroundAt = nowMs
    }

    /**
     * Starts a recovery trial at the loss of a session. A later [reachableAgain] signal (network
     * restored, TV announced itself) restarts the clock, so the sample measures recovery after
     * the TV became reachable rather than how long the TV was off.
     */
    @Synchronized
    fun markRecoveryStart(nowMs: Long, reachableAgain: Boolean = false) {
        if (recoveryAt == null || reachableAgain) recoveryAt = nowMs
    }

    @Synchronized
    fun markReady(nowMs: Long) {
        coldStartAt?.let { add(Metric.COLD_LAUNCH_TO_READY, nowMs - it) }
        foregroundAt?.let { add(Metric.FOREGROUND_TO_READY, nowMs - it) }
        recoveryAt?.let { add(Metric.RECOVERY_TO_READY, nowMs - it) }
        coldStartAt = null
        foregroundAt = null
        recoveryAt = null
        publish()
    }

    /**
     * Pending trials can no longer finish normally (app left the foreground, or the connection
     * needs the administrator): count them as abandoned rather than as slow samples.
     */
    @Synchronized
    fun abandonPending() {
        coldStartAt?.let { abandon(Metric.COLD_LAUNCH_TO_READY) }
        foregroundAt?.let { abandon(Metric.FOREGROUND_TO_READY) }
        recoveryAt?.let { abandon(Metric.RECOVERY_TO_READY) }
        coldStartAt = null
        foregroundAt = null
        recoveryAt = null
        publish()
    }

    @Synchronized
    fun record(metric: Metric, ms: Long) {
        add(metric, ms)
        publish()
    }

    @Synchronized
    fun clear() {
        samples.clear()
        abandoned.clear()
        file?.delete()
        publish()
    }

    @Synchronized
    fun persist() {
        val target = file ?: return
        val text = buildString {
            for ((metric, values) in samples) appendLine("${metric.name}\t${values.joinToString(",")}")
            for ((metric, count) in abandoned) appendLine("${metric.name}\tabandoned=$count")
        }
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(text)
        }
    }

    private fun add(metric: Metric, ms: Long) {
        if (ms < 0) return
        val list = samples.getOrPut(metric) { ArrayDeque() }
        list.addLast(ms)
        while (list.size > maxSamplesPerMetric) list.removeFirst()
    }

    private fun abandon(metric: Metric) {
        abandoned[metric] = (abandoned[metric] ?: 0) + 1
    }

    private fun publish() {
        _summaries.value = Metric.entries.map { summarize(it) }
    }

    private fun summarize(metric: Metric): MetricSummary {
        val values = samples[metric]?.sorted().orEmpty()
        return MetricSummary(
            metric = metric,
            count = values.size,
            abandoned = abandoned[metric] ?: 0,
            medianMs = percentile(values, 50.0),
            p95Ms = percentile(values, 95.0),
            maxMs = values.lastOrNull(),
            withinTarget = values.count { it <= metric.targetMs },
        )
    }

    private fun restore(source: File) {
        runCatching {
            source.readLines().forEach { line ->
                val (name, data) = line.split('\t', limit = 2).takeIf { it.size == 2 } ?: return@forEach
                val metric = Metric.entries.firstOrNull { it.name == name } ?: return@forEach
                if (data.startsWith("abandoned=")) {
                    abandoned[metric] = data.removePrefix("abandoned=").toIntOrNull() ?: 0
                } else {
                    data.split(',').mapNotNull { it.toLongOrNull() }.forEach { add(metric, it) }
                }
            }
        }
    }

    companion object {
        /** Nearest-rank percentile of an ascending list. */
        fun percentile(sorted: List<Long>, p: Double): Long? {
            if (sorted.isEmpty()) return null
            val rank = kotlin.math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
