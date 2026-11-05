package app.seniorremote.ui.admin

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.seniorremote.AppContainer
import app.seniorremote.data.AdminPinStore
import app.seniorremote.data.RemoteSettings
import app.seniorremote.diagnostics.DiagEvent
import app.seniorremote.diagnostics.MetricSummary
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.NameProblem
import app.seniorremote.domain.TvLibrary
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AdminViewModel(private val container: AppContainer) : ViewModel() {
    val library: StateFlow<TvLibrary> = container.repository.library
    val connection: StateFlow<ConnectionState> = container.connection.state
    val settings: StateFlow<RemoteSettings> = container.settings.settings
    val events: StateFlow<List<DiagEvent>> = container.diagnostics.events
    val performance: StateFlow<List<MetricSummary>> = container.performance.summaries

    fun setHaptics(enabled: Boolean) = container.settings.update { it.copy(haptics = enabled) }
    fun setSounds(enabled: Boolean) = container.settings.update { it.copy(buttonSounds = enabled) }
    fun setKeepScreenOn(enabled: Boolean) = container.settings.update { it.copy(keepScreenOn = enabled) }

    /** Returns an error message, or null when the PIN was changed. */
    fun changePin(current: String, new: String, confirm: String): String? = when {
        !container.adminPin.verify(current) -> "The current PIN is not correct."
        !AdminPinStore.isValidFormat(new) -> "Use 4 to 8 digits for the new PIN."
        new != confirm -> "The two new PINs are different."
        else -> {
            container.adminPin.setPin(new)
            container.diagnostics.log("admin", "Family PIN changed")
            null
        }
    }

    fun reconnectNow() = container.connection.requestRecovery("administrator asked to reconnect")

    /** Reports back with the problem, or null once saved. Pairing is not affected by names. */
    fun rename(tvId: String, name: String, subtitle: String, onResult: (NameProblem?) -> Unit) {
        viewModelScope.launch { onResult(container.tvManager.rename(tvId, name, subtitle)) }
    }

    fun choosePicture(tvId: String, uri: Uri) {
        viewModelScope.launch {
            container.images.import(tvId, uri)?.let { container.tvManager.setImage(tvId, it) }
        }
    }

    fun removePicture(tvId: String) {
        viewModelScope.launch { container.tvManager.setImage(tvId, null) }
    }

    fun removeTv(tvId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            container.tvManager.remove(tvId)
            onDone()
        }
    }

    fun exportLog(): String = buildString {
        appendLine("Senior TV Remote diagnostics (sanitized: no codes, keys or full addresses)")
        appendLine()
        appendLine("Measured performance:")
        performance.value.forEach { appendLine("  " + it.format()) }
        appendLine()
        append(container.diagnostics.export())
    }

    fun clearDiagnostics() {
        container.diagnostics.clear()
        container.performance.clear()
    }
}
