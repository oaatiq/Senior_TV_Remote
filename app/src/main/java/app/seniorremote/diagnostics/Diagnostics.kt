package app.seniorremote.diagnostics

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local, sanitized event log for the administrator. Never uploaded anywhere. */
interface Diagnostics {
    fun log(category: String, message: String)
}

object NoDiagnostics : Diagnostics {
    override fun log(category: String, message: String) = Unit
}

data class DiagEvent(val epochMs: Long, val category: String, val message: String) {
    fun format(): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(epochMs))
        return "$time [$category] $message"
    }
}

class DiagnosticsLog(
    private val capacity: Int = 500,
    private val file: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : Diagnostics {
    private val buffer = ArrayDeque<DiagEvent>()
    private val _events = MutableStateFlow<List<DiagEvent>>(emptyList())
    val events: StateFlow<List<DiagEvent>> = _events.asStateFlow()

    init {
        file?.takeIf { it.exists() }?.let { restore(it) }
    }

    override fun log(category: String, message: String) {
        val event = DiagEvent(clock(), category, Redactor.sanitize(message))
        synchronized(buffer) {
            buffer.addLast(event)
            while (buffer.size > capacity) buffer.removeFirst()
            _events.value = buffer.toList()
        }
    }

    fun export(): String = events.value.joinToString("\n") { it.format() }

    fun clear() {
        synchronized(buffer) {
            buffer.clear()
            _events.value = emptyList()
        }
        file?.delete()
    }

    /** Writes the current buffer so it survives process death. Call from a background thread. */
    fun persist() {
        val target = file ?: return
        val lines = events.value.map { "${it.epochMs}\t${it.category}\t${it.message.replace('\n', ' ')}" }
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.path + ".tmp")
            tmp.writeText(lines.joinToString("\n"))
            tmp.renameTo(target) || run { target.delete(); tmp.renameTo(target) }
        }
    }

    private fun restore(source: File) {
        val restored = runCatching {
            source.readLines().mapNotNull { line ->
                val parts = line.split('\t', limit = 3)
                val epoch = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                if (parts.size < 3) return@mapNotNull null
                DiagEvent(epoch, parts[1], Redactor.sanitize(parts[2]))
            }
        }.getOrDefault(emptyList())
        synchronized(buffer) {
            buffer.addAll(restored.takeLast(capacity))
            _events.value = buffer.toList()
        }
    }
}

/**
 * Removes secrets and reduces identifying detail before anything is written to the log.
 * Pairing codes, key material and long hex/base64 blobs are dropped; addresses are truncated.
 */
object Redactor {
    private val labelled = Regex("""(?i)\b(code|secret|pin|password|token|key)\b(\s*[=:]\s*)(\S+)""")
    private val pem = Regex("""-----BEGIN [^-]+-----[\s\S]*?-----END [^-]+-----""")
    private val longHex = Regex("""\b[0-9A-Fa-f]{16,}\b""")
    private val base64 = Regex("""[A-Za-z0-9+/]{40,}={0,2}""")
    private val mac = Regex("""\b([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})\b""")
    private val ipv4 = Regex("""\b(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\b""")
    private val ipv6 = Regex("""\b(?:[0-9A-Fa-f]{1,4}:){2,7}[0-9A-Fa-f]{1,4}\b""")

    fun sanitize(text: String): String {
        var out = pem.replace(text, "<pem redacted>")
        out = labelled.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}<redacted>" }
        out = mac.replace(out) { "xx:xx:xx:xx:" + it.value.takeLast(5) }
        out = longHex.replace(out, "<hex redacted>")
        out = base64.replace(out, "<blob redacted>")
        out = ipv6.replace(out, "<ipv6>")
        out = ipv4.replace(out) { "${it.groupValues[1]}.${it.groupValues[2]}.${it.groupValues[3]}.x" }
        return out
    }
}
