package app.seniorremote.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.seniorremote.data.RemoteSettings
import app.seniorremote.data.SettingsStore
import app.seniorremote.domain.Capability
import app.seniorremote.domain.CapabilityStatus
import app.seniorremote.domain.CommandFeedback
import app.seniorremote.domain.ConnectionController
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.DeviceRepository
import app.seniorremote.domain.RemoteCommandDispatcher
import app.seniorremote.domain.RemoteKey
import app.seniorremote.domain.SavedTv
import app.seniorremote.domain.TvLibrary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Exposes connection state and accepts presses. Holds no connection logic of its own. */
class RemoteViewModel(
    private val connection: ConnectionController,
    private val dispatcher: RemoteCommandDispatcher,
    repository: DeviceRepository,
    settingsStore: SettingsStore,
    private val clock: () -> Long,
) : ViewModel() {

    val state: StateFlow<ConnectionState> = connection.state
    val library: StateFlow<TvLibrary> = repository.library
    val settings: StateFlow<RemoteSettings> = settingsStore.settings

    /** Brief explanation after a press that could not be delivered. The status area also persists it. */
    private val _feedback = MutableStateFlow<CommandFeedback?>(null)
    val feedback: StateFlow<CommandFeedback?> = _feedback.asStateFlow()
    private var clearFeedback: Job? = null

    init {
        viewModelScope.launch {
            dispatcher.feedback.collect(::show)
        }
        viewModelScope.launch {
            connection.state.collect { if (it is ConnectionState.Ready) _feedback.value = null }
        }
    }

    /** Shows a brief message. Repeated ones refresh it rather than stacking. */
    fun show(item: CommandFeedback) {
        _feedback.value = item
        clearFeedback?.cancel()
        clearFeedback = viewModelScope.launch {
            delay(FEEDBACK_MS)
            _feedback.value = null
        }
    }

    /** Why Type text cannot open right now, or null if it can. */
    fun textEntryBlock(tv: SavedTv, state: ConnectionState): CommandFeedback? {
        val session = (state as? ConnectionState.Ready)?.let { connection.sessionFor(it.generation, tv.id) }
        return when {
            tv.status(Capability.TEXT_ENTRY) == CapabilityStatus.UNSUPPORTED -> CommandFeedback.TextEntryUnavailable
            session?.textEntrySupported == false -> CommandFeedback.TextEntryUnavailable
            tv.status(Capability.TEXT_ENTRY) == CapabilityStatus.NOT_VERIFIED -> CommandFeedback.TextEntryNotTested
            state !is ConnectionState.Ready || state.tvAsleep -> CommandFeedback.NotReady(state)
            else -> null
        }
    }

    fun keyDown(tvId: String, key: RemoteKey) = dispatcher.holdStart(tvId, key, clock())
    fun keyUp(key: RemoteKey) = dispatcher.holdEnd(key)
    fun accessibilityPress(tvId: String, key: RemoteKey) = dispatcher.tap(tvId, key, clock())
    fun tryAgain() = connection.requestRecovery("Try again pressed")

    override fun onCleared() {
        dispatcher.cancelAll()
    }

    private companion object {
        const val FEEDBACK_MS = 3_000L
    }
}
