package app.seniorremote.text

import app.seniorremote.diagnostics.Diagnostics
import app.seniorremote.domain.ConnectionController
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.DeviceRepository
import app.seniorremote.domain.SendOutcome
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TextEntryUi(
    val tvName: String,
    val draft: String = "",
    /** The bound TV has a usable session right now. */
    val connected: Boolean = false,
    val sending: Boolean = false,
    /** The last send may or may not have reached the TV. */
    val uncertain: Boolean = false,
) {
    val canSend: Boolean get() = draft.isNotBlank() && connected && !sending
}

/**
 * One text-entry session, bound to TV [tvId] from the moment it opens.
 *
 * The draft lives only in this object, so it is gone on cancel, success, TV switch or process
 * death, and it is never written to any log or store. Nothing is ever sent except by [send], and
 * nothing is queued: if the connection drops, the draft waits on screen for another explicit tap.
 */
class TextEntryController(
    private val tvId: String,
    private val repository: DeviceRepository,
    private val connection: ConnectionController,
    private val diagnostics: Diagnostics,
    private val onSent: () -> Unit,
    private val onClosed: () -> Unit,
    context: CoroutineContext = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + context)
    private val _ui = MutableStateFlow(TextEntryUi(tvName = repository.library.value.find(tvId)?.displayName.orEmpty()))
    val ui: StateFlow<TextEntryUi> = _ui.asStateFlow()

    init {
        scope.launch {
            repository.library.collect { library ->
                val tv = library.find(tvId)
                if (tv == null || library.selectedId != tvId) {
                    // Never redirected: the draft belongs to the TV it was opened for.
                    close()
                    return@collect
                }
                _ui.update { it.copy(tvName = tv.displayName) }
            }
        }
        scope.launch {
            connection.state.collect { state ->
                val usable = state is ConnectionState.Ready && state.tvId == tvId && !state.tvAsleep
                _ui.update { it.copy(connected = usable) }
            }
        }
    }

    fun edit(text: String) {
        // A line break could submit a search on the TV, so a pasted one becomes a space.
        val clean = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').take(MAX_LENGTH)
        _ui.update { it.copy(draft = clean, uncertain = false) }
    }

    fun send() {
        val ui = _ui.value
        if (!ui.canSend) return
        val state = connection.state.value as? ConnectionState.Ready ?: return
        if (state.tvId != tvId) return
        val session = connection.sessionFor(state.generation, tvId) ?: return
        _ui.update { it.copy(sending = true, uncertain = false) }
        scope.launch {
            val outcome = session.sendText(ui.draft)
            val now = connection.state.value
            val sameSession = now is ConnectionState.Ready && now.generation == state.generation && now.tvId == tvId
            if (outcome == SendOutcome.Transmitted && sameSession) {
                diagnostics.log("text", "Text transmitted, ${ui.draft.length} characters (TV gives no confirmation)")
                _ui.update { it.copy(draft = "", sending = false) }
                onSent()
            } else {
                // It may have arrived, so it is not retried; the user checks the TV and decides.
                diagnostics.log("text", "Delivery of ${ui.draft.length} characters could not be confirmed")
                if (outcome is SendOutcome.Failed) connection.reportSendFailure(state.generation, outcome.detail)
                _ui.update { it.copy(sending = false, uncertain = true) }
            }
        }
    }

    fun dispose() {
        _ui.update { it.copy(draft = "") }
        scope.cancel()
    }

    private fun close() {
        _ui.update { it.copy(draft = "") }
        onClosed()
    }

    companion object {
        /** Keywords and short phrases; long enough for any search, bounded for one message. */
        const val MAX_LENGTH = 500
    }
}
