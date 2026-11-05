package app.seniorremote.ui.setup

import app.seniorremote.data.AdminPinStore
import app.seniorremote.data.TvManager
import app.seniorremote.diagnostics.Diagnostics
import app.seniorremote.domain.Capability
import app.seniorremote.domain.CapabilityStatus
import app.seniorremote.domain.ConnectionController
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.CredentialStore
import app.seniorremote.domain.DeviceRepository
import app.seniorremote.domain.NameProblem
import app.seniorremote.domain.PairingException
import app.seniorremote.domain.PairingFailure
import app.seniorremote.domain.PairingResult
import app.seniorremote.domain.PairingSession
import app.seniorremote.domain.RemoteCommandDispatcher
import app.seniorremote.domain.SavedTv
import app.seniorremote.domain.TvCandidate
import app.seniorremote.domain.TvCredentials
import app.seniorremote.domain.TvDiscovery
import app.seniorremote.domain.TvNames
import app.seniorremote.domain.TvPairing
import app.seniorremote.domain.updateTv
import app.seniorremote.net.NetworkStatusSource
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class SetupStep { CREATE_PIN, DISCOVER, PAIR, NAME, VERIFY, DONE }

sealed interface SetupEntry {
    /** First TV on this phone. */
    data object FirstTv : SetupEntry

    /** Another TV, from Family settings. */
    data object AddTv : SetupEntry

    /** Check the controls of a saved TV, which must already be selected. */
    data class Retest(val tvId: String, val onlyTextEntry: Boolean = false) : SetupEntry

    /** Renew the pairing of a saved TV. Its name and other TVs are untouched. */
    data class Repair(val tvId: String) : SetupEntry
}

data class PairTarget(
    val host: String,
    val port: Int,
    val displayName: String?,
    val serviceName: String?,
    val deviceId: String?,
)

sealed interface PairPhase {
    data object Connecting : PairPhase
    data class EnterCode(val error: String? = null) : PairPhase
    data object Submitting : PairPhase
    data class Failed(val message: String) : PairPhase
}

sealed interface TestPhase {
    data object Ready : TestPhase
    data object Sending : TestPhase
    data object Asking : TestPhase
    data object WakeWaiting : TestPhase
    data class Note(val message: String) : TestPhase
}

data class SetupUi(
    val step: SetupStep,
    val pinError: String? = null,
    /** The TV this flow is working on, once it has one. */
    val tvId: String? = null,
    val tv: SavedTv? = null,
    val savedTvs: List<SavedTv> = emptyList(),
    val nameError: String? = null,
    /** Extra line on the final screen. */
    val doneNote: String? = null,
    val networkUsable: Boolean = true,
    val candidates: List<TvCandidate> = emptyList(),
    val searchTimedOut: Boolean = false,
    val manualError: String? = null,
    val target: PairTarget? = null,
    val pairPhase: PairPhase = PairPhase.Connecting,
    val testIndex: Int = 0,
    val testPhase: TestPhase = TestPhase.Ready,
    val answers: Map<String, Answer> = emptyMap(),
    val connection: ConnectionState = ConnectionState.NotConfigured,
) {
    val capabilities: Map<Capability, CapabilityStatus> get() = tv?.capabilities.orEmpty()
}

/**
 * Administrator setup: PIN, discovery, pairing, guided capability checks. Lives exactly as long
 * as the setup flow is on screen; [dispose] stops discovery and any open pairing session.
 */
class SetupViewModel(
    private val entry: SetupEntry,
    private val adminPin: AdminPinStore,
    private val discovery: TvDiscovery,
    private val pairing: TvPairing,
    private val repository: DeviceRepository,
    private val credentials: CredentialStore,
    private val tvManager: TvManager,
    private val connection: ConnectionController,
    private val dispatcher: RemoteCommandDispatcher,
    network: NetworkStatusSource,
    private val diagnostics: Diagnostics,
    private val clock: () -> Long,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val tests: List<CapabilityTest> =
        if ((entry as? SetupEntry.Retest)?.onlyTextEntry == true) capabilityTests.filter { it.text != null } else capabilityTests

    val testCount: Int get() = tests.size

    private val _ui = MutableStateFlow(
        SetupUi(
            step = when {
                entry is SetupEntry.Retest -> SetupStep.VERIFY
                entry is SetupEntry.Repair -> SetupStep.PAIR
                adminPin.hasPin -> SetupStep.DISCOVER
                else -> SetupStep.CREATE_PIN
            },
            tvId = (entry as? SetupEntry.Retest)?.tvId ?: (entry as? SetupEntry.Repair)?.tvId,
        ),
    )
    val ui: StateFlow<SetupUi> = _ui.asStateFlow()

    private var discoveryJob: Job? = null
    private var pairJob: Job? = null
    private var testJob: Job? = null
    private var session: PairingSession? = null

    init {
        scope.launch { network.status.collect { s -> _ui.update { it.copy(networkUsable = s.usable) } } }
        scope.launch { connection.state.collect { s -> _ui.update { it.copy(connection = s) } } }
        scope.launch {
            repository.library.collect { library ->
                _ui.update { ui -> ui.copy(savedTvs = library.tvs, tv = ui.tvId?.let(library::find)) }
            }
        }
        if (_ui.value.step == SetupStep.DISCOVER) startDiscovery()
        if (entry is SetupEntry.Repair) {
            repository.library.value.find(entry.tvId)?.let { tv ->
                startPairing(PairTarget(tv.lastKnownHost, tv.port, tv.displayName, tv.serviceName, tv.deviceId))
            }
        }
    }

    // ---- PIN ----

    fun createPin(pin: String, confirm: String) {
        when {
            !AdminPinStore.isValidFormat(pin) -> _ui.update { it.copy(pinError = "Use 4 to 8 digits.") }
            pin != confirm -> _ui.update { it.copy(pinError = "The two PINs are different. Please type them again.") }
            else -> {
                adminPin.setPin(pin)
                diagnostics.log("setup", "Family PIN created")
                _ui.update { it.copy(pinError = null, step = SetupStep.DISCOVER) }
                startDiscovery()
            }
        }
    }

    // ---- Discovery ----

    fun startDiscovery() {
        discoveryJob?.cancel()
        _ui.update { it.copy(candidates = emptyList(), searchTimedOut = false, manualError = null) }
        discoveryJob = scope.launch {
            launch {
                delay(SEARCH_HINT_MS)
                _ui.update { it.copy(searchTimedOut = true) }
            }
            discovery.discover()
                .catch { diagnostics.log("setup", "Discovery stopped: ${it.javaClass.simpleName}") }
                .collect { list -> _ui.update { it.copy(candidates = list) } }
        }
    }

    private fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }

    fun choose(candidate: TvCandidate) {
        startPairing(PairTarget(candidate.host, candidate.port, candidate.displayName, candidate.serviceName, candidate.deviceId))
    }

    fun chooseManual(address: String) {
        val host = address.trim()
        if (!isPlausibleHost(host)) {
            _ui.update { it.copy(manualError = "Enter the TV's IP address, for example 192.168.1.40.") }
            return
        }
        startPairing(PairTarget(host, DEFAULT_REMOTE_PORT, null, null, null))
    }

    // ---- Pairing ----

    private fun startPairing(target: PairTarget, error: String? = null) {
        stopDiscovery()
        session?.cancel()
        session = null
        pairJob?.cancel()
        _ui.update { it.copy(step = SetupStep.PAIR, target = target, pairPhase = PairPhase.Connecting, manualError = null) }
        pairJob = scope.launch {
            try {
                session = pairing.begin(target.host)
                _ui.update { it.copy(pairPhase = PairPhase.EnterCode(error)) }
            } catch (e: PairingException) {
                _ui.update { it.copy(pairPhase = PairPhase.Failed(e.reason.message())) }
            }
        }
    }

    fun retryPairing() {
        _ui.value.target?.let { startPairing(it) }
    }

    fun backToDiscovery() {
        pairJob?.cancel()
        session?.cancel()
        session = null
        _ui.update { it.copy(step = SetupStep.DISCOVER, target = null) }
        startDiscovery()
    }

    fun submitCode(code: String) {
        val current = session ?: return
        val target = _ui.value.target ?: return
        val normalized = code.filterNot { it.isWhitespace() }.uppercase()
        if (!CODE_PATTERN.matches(normalized)) {
            _ui.update { it.copy(pairPhase = PairPhase.EnterCode("The code has 6 characters: numbers 0-9 and letters A-F.")) }
            return
        }
        _ui.update { it.copy(pairPhase = PairPhase.Submitting) }
        pairJob = scope.launch {
            when (val result = current.submitCode(normalized)) {
                is PairingResult.Success -> {
                    session = null
                    onPaired(target, current.serverName, result.credentials)
                }
                PairingResult.WrongCode ->
                    // The TV ends the attempt after a wrong code; start a new one with a new code.
                    startPairing(target, "That code did not match. The TV now shows a new code - please type the new one.")
                is PairingResult.Failed -> _ui.update { it.copy(pairPhase = PairPhase.Failed(result.reason.message())) }
            }
        }
    }

    /**
     * Credentials are stored only now, after the TV accepted the code. The TV's key decides
     * whether this is a new TV or one already saved, whatever it is called.
     */
    private suspend fun onPaired(target: PairTarget, serverName: String?, paired: TvCredentials) {
        val identity = paired.serverPublicKeySha256.joinToString("") { "%02x".format(it) }
        val library = repository.library.value
        val repairing = (entry as? SetupEntry.Repair)?.let { library.find(it.tvId) }
        val alreadySaved = library.tvs.firstOrNull { it.identityKey == identity && it.id != repairing?.id }
        when {
            repairing != null && alreadySaved != null -> _ui.update {
                it.copy(pairPhase = PairPhase.Failed("That is a different TV, already saved as \"${alreadySaved.displayName}\". Nothing was changed."))
            }
            repairing != null -> {
                renewPairing(repairing, target, identity, paired)
                _ui.update { it.copy(step = SetupStep.DONE, doneNote = "The pairing with this TV was renewed.") }
            }
            alreadySaved != null -> {
                renewPairing(alreadySaved, target, identity, paired)
                _ui.update {
                    it.copy(
                        step = SetupStep.DONE,
                        tvId = alreadySaved.id,
                        tv = alreadySaved,
                        doneNote = "This TV was already saved as \"${alreadySaved.displayName}\", so no new TV was added. Its pairing was renewed.",
                    )
                }
            }
            else -> {
                val tv = addTv(target, serverName, identity, paired)
                _ui.update { it.copy(step = SetupStep.NAME, tvId = tv.id, tv = tv) }
            }
        }
    }

    private suspend fun addTv(target: PairTarget, serverName: String?, identity: String, paired: TvCredentials): SavedTv {
        val saved = repository.library.value.tvs
        val original = TvNames.clean(target.displayName ?: serverName)
        val fallback = if (original == null) TvNames.fallback(saved) else null
        val shown = original ?: requireNotNull(fallback)
        val tv = SavedTv(
            id = "tv_" + newId(),
            originalName = original,
            customName = fallback,
            // A taken name gets a subtitle straight away, so the two are never identical.
            subtitle = if (TvNames.isDuplicate(shown, saved)) TvNames.defaultSubtitle(shown, saved) else null,
            deviceId = target.deviceId,
            serviceName = target.serviceName,
            identityKey = identity,
            lastKnownHost = target.host,
            port = target.port,
            credentialRef = saveCredentials(paired),
        )
        // The new TV becomes the target: its controls are checked next.
        dispatcher.cancelAll()
        repository.edit { it.adding(tv, select = true) }
        diagnostics.log("setup", "Paired and saved TV \"${tv.displayName}\"")
        return tv
    }

    private suspend fun renewPairing(tv: SavedTv, target: PairTarget, identity: String, paired: TvCredentials) {
        val ref = saveCredentials(paired)
        // Test results describe the old device if the key changed (a replaced or reset TV).
        val sameDevice = tv.identityKey == null || tv.identityKey == identity
        repository.updateTv(tv.id) {
            it.copy(
                credentialRef = ref,
                identityKey = identity,
                lastKnownHost = target.host,
                port = target.port,
                capabilities = if (sameDevice) it.capabilities else SavedTv(lastKnownHost = "", port = 0, credentialRef = "").capabilities,
            )
        }
        credentials.delete(tv.credentialRef)
        diagnostics.log("setup", "Renewed pairing of TV \"${tv.displayName}\"")
    }

    private suspend fun saveCredentials(paired: TvCredentials): String {
        val ref = "tv_" + newId()
        credentials.save(ref, paired)
        return ref
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)

    // ---- Name ----

    fun confirmName(name: String, subtitle: String) {
        val id = _ui.value.tvId ?: return
        scope.launch {
            when (tvManager.rename(id, name, subtitle)) {
                null -> _ui.update {
                    it.copy(nameError = null, step = SetupStep.VERIFY, testIndex = 0, testPhase = TestPhase.Ready, answers = emptyMap())
                }
                NameProblem.NEEDS_SUBTITLE -> _ui.update {
                    it.copy(nameError = "Another TV has this name. Add a short description so they can be told apart.")
                }
                NameProblem.SAME_AS_ANOTHER_TV -> _ui.update {
                    it.copy(nameError = "Another TV has this name and description. Please change one of them.")
                }
            }
        }
    }

    // ---- Capability checks ----

    fun currentTest(): CapabilityTest? = tests.getOrNull(_ui.value.testIndex)

    fun sendTest() {
        val test = currentTest() ?: return
        val tvId = _ui.value.tvId ?: return
        testJob?.cancel()
        testJob = scope.launch {
            if (test.isWake) {
                _ui.update { it.copy(testPhase = TestPhase.WakeWaiting) }
                connection.requestRecovery("wake test")
                val ready = withTimeoutOrNull(WAKE_WAIT_MS) {
                    connection.state.first { it is ConnectionState.Ready && it.tvId == tvId }
                }
                if (ready == null) {
                    diagnostics.log("setup", "Wake test: no session could be established while the TV was in standby")
                    record(test, Answer.NO)
                    _ui.update {
                        it.copy(
                            testPhase = TestPhase.Note(
                                "The phone could not connect to the TV while it was in standby, so this app cannot switch the TV on. " +
                                    "The Power button will still be shown if standby worked, but waking must be done with the TV's own remote.",
                            ),
                        )
                    }
                    return@launch
                }
            } else {
                _ui.update { it.copy(testPhase = TestPhase.Sending) }
            }
            test.text?.let { text ->
                val ready = connection.state.value as? ConnectionState.Ready
                val session = ready?.takeIf { it.tvId == tvId }?.let { connection.sessionFor(it.generation, tvId) }
                if (session?.textEntrySupported == false) {
                    record(test, Answer.NO)
                    _ui.update { it.copy(testPhase = TestPhase.Note("This TV reports that it does not accept text from a phone.")) }
                    return@launch
                }
                session?.sendText(text)
            }
            test.keys.forEachIndexed { index, key ->
                if (index > 0) delay(test.gapMs)
                dispatcher.tap(tvId, key, clock())
            }
            diagnostics.log("setup", "Check \"${test.title}\" sent; waiting for the administrator's answer")
            _ui.update { it.copy(testPhase = TestPhase.Asking) }
        }
    }

    fun answer(answer: Answer) {
        val test = currentTest() ?: return
        testJob?.cancel()
        scope.launch {
            record(test, answer)
            advance()
        }
    }

    fun continueAfterNote() = advance()

    private suspend fun record(test: CapabilityTest, answer: Answer) {
        val answers = _ui.value.answers + (test.id to answer)
        _ui.update { it.copy(answers = answers) }
        _ui.value.tvId?.let { id ->
            repository.updateTv(id) { tv -> tv.copy(capabilities = aggregateCapabilities(answers, tv.capabilities)) }
        }
        diagnostics.log("setup", "Check \"${test.title}\": $answer")
    }

    private fun advance() {
        var next = _ui.value.testIndex + 1
        val answers = _ui.value.answers
        // Waking can only be tested after standby was confirmed in this run.
        if (tests.getOrNull(next)?.isWake == true && answers["standby"] != Answer.YES) next++
        if (next >= tests.size) {
            _ui.update { it.copy(step = SetupStep.DONE, testIndex = next, testPhase = TestPhase.Ready) }
        } else {
            _ui.update { it.copy(testIndex = next, testPhase = TestPhase.Ready) }
        }
    }

    fun finishChecksLater() {
        testJob?.cancel()
        _ui.update { it.copy(step = SetupStep.DONE) }
    }

    fun dispose() {
        stopDiscovery()
        session?.cancel()
        scope.cancel()
    }

    private fun PairingFailure.message(): String = when (this) {
        PairingFailure.UNREACHABLE ->
            "The phone could not reach this TV. Check that the TV is switched on and that the phone is on the same Wi-Fi network as the TV."
        PairingFailure.REJECTED -> "The TV refused the pairing request. Try again, and accept any prompt shown on the TV."
        PairingFailure.PROTOCOL_ERROR ->
            "This TV did not respond the way the app expects. It may not support the Android TV remote service this app uses."
        PairingFailure.TIMEOUT -> "The TV did not answer in time. Check that it is on and try again."
    }

    private companion object {
        const val DEFAULT_REMOTE_PORT = 6466
        const val SEARCH_HINT_MS = 10_000L
        const val WAKE_WAIT_MS = 20_000L
        val CODE_PATTERN = Regex("^[0-9A-F]{6}$")
        val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$""")
        val HOSTNAME = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\.[A-Za-z0-9-]{1,63})*$""")

        fun isPlausibleHost(host: String) = IPV4.matches(host) || (host.any { it.isLetter() } && HOSTNAME.matches(host))
    }
}
