package app.seniorremote.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seniorremote.R
import app.seniorremote.domain.Capability
import app.seniorremote.domain.CapabilityStatus
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.SavedTv
import app.seniorremote.domain.TvCandidate
import app.seniorremote.domain.TvNames
import app.seniorremote.ui.RemoteIcons
import app.seniorremote.ui.common.Body
import app.seniorremote.ui.common.ErrorText
import app.seniorremote.ui.common.Page
import app.seniorremote.ui.common.PrimaryButton
import app.seniorremote.ui.common.QuietButton
import app.seniorremote.ui.common.SecondaryButton
import app.seniorremote.ui.common.SectionTitle

/** First-use screen: no empty, operational-looking remote before setup. */
@Composable
fun WelcomeScreen(onSetUp: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.setup_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.setup_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        PrimaryButton(stringResource(R.string.setup_button), onSetUp)
    }
}

@Composable
fun SetupFlow(viewModel: SetupViewModel, finishLabel: String, onExit: () -> Unit, onFinished: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    when (ui.step) {
        SetupStep.CREATE_PIN -> {
            BackHandler(onBack = onExit)
            CreatePinStep(ui.pinError, viewModel::createPin, onExit)
        }
        SetupStep.DISCOVER -> {
            BackHandler(onBack = onExit)
            DiscoverStep(ui, viewModel, onExit)
        }
        SetupStep.PAIR -> {
            BackHandler(onBack = viewModel::backToDiscovery)
            PairStep(ui, viewModel)
        }
        SetupStep.NAME -> NameStep(ui, viewModel::confirmName)
        SetupStep.VERIFY -> {
            BackHandler(onBack = viewModel::finishChecksLater)
            VerifyStep(ui, viewModel)
        }
        SetupStep.DONE -> DoneStep(ui.capabilities, ui.doneNote, finishLabel, onFinished)
    }
}

@Composable
private fun CreatePinStep(error: String?, onCreate: (String, String) -> Unit, onCancel: () -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    Page("Choose a family PIN") {
        Body("This PIN keeps the remote's settings safe from accidental changes. It is never needed to use the remote itself.")
        PinField("PIN (4 to 8 digits)", pin) { pin = it }
        PinField("Type the PIN again", confirm) { confirm = it }
        error?.let { ErrorText(it) }
        PrimaryButton("Continue", { onCreate(pin, confirm) })
        QuietButton("Cancel", onCancel)
    }
}

@Composable
fun PinField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onChange(input.filter { it.isDigit() }.take(8)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = TextStyle(fontSize = 24.sp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DiscoverStep(ui: SetupUi, viewModel: SetupViewModel, onCancel: () -> Unit) {
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    Page("Find the TV") {
        Body("Switch the TV on. Make sure this phone is connected to the same Wi-Fi network as the TV.")
        if (!ui.networkUsable) {
            ErrorText("This phone is not connected to Wi-Fi. Connect it to the home Wi-Fi network first.")
        }
        SectionTitle("TVs found on this network")
        if (ui.candidates.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Body("Searching…")
            }
            if (ui.searchTimedOut) {
                Body(
                    "No TV found yet. Check that the TV is on and connected to the same Wi-Fi. " +
                        "Some routers block device discovery - you can enter the TV's address instead. " +
                        "On the TV, the address is usually under Settings > Network & Internet > (your network).",
                )
            }
        } else {
            Body("Choose your TV. Check the details if there is more than one with a similar name.")
            ui.candidates.forEach { candidate ->
                val saved = ui.savedTvs.firstOrNull { it.id != ui.tvId && it.isProbably(candidate) }
                CandidateCard(candidate, alreadySavedAs = saved?.displayName) { viewModel.choose(candidate) }
            }
        }
        SecondaryButton("Search again", viewModel::startDiscovery)
        if (!manualOpen) {
            QuietButton("Enter the TV's address instead", { manualOpen = true })
        } else {
            SectionTitle("Enter the TV's address")
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.trim() },
                label = { Text("IP address, e.g. 192.168.1.40") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.chooseManual(address) }),
                textStyle = TextStyle(fontSize = 22.sp),
                modifier = Modifier.fillMaxWidth(),
            )
            ui.manualError?.let { ErrorText(it) }
            PrimaryButton("Connect to this address", { viewModel.chooseManual(address) })
        }
        QuietButton("Cancel setup", onCancel)
    }
}

@Composable
private fun CandidateCard(candidate: TvCandidate, alreadySavedAs: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (alreadySavedAs == null) 1f else 0.4f)), shape)
            .clickable(enabled = alreadySavedAs == null, role = Role.Button, onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(16.dp),
    ) {
        Text(candidate.displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(candidate.details, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        alreadySavedAs?.let {
            Text("Already added as $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

private fun SavedTv.isProbably(candidate: TvCandidate): Boolean =
    lastKnownHost == candidate.host || (serviceName != null && serviceName == candidate.serviceName)

/**
 * Names are free-form. The name from setup is suggested; a family member may change it. A
 * description is only required when another TV already has the same name.
 */
@Composable
private fun NameStep(ui: SetupUi, onConfirm: (String, String) -> Unit) {
    val tv = ui.tv ?: return
    var name by rememberSaveable(tv.id) { mutableStateOf(tv.displayName) }
    var subtitle by rememberSaveable(tv.id) { mutableStateOf(tv.subtitle.orEmpty()) }
    Page("Name this TV") {
        Body("This name appears on the remote. Keep it, or type any name the senior will recognize.")
        TvNameFields(name, { name = it }, subtitle, { subtitle = it })
        ui.nameError?.let { ErrorText(it) }
        PrimaryButton("Continue", { onConfirm(name, subtitle) })
    }
}

@Composable
fun TvNameFields(name: String, onName: (String) -> Unit, subtitle: String, onSubtitle: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = { onName(it.take(TvNames.MAX_LENGTH)) },
        label = { Text("TV name") },
        textStyle = TextStyle(fontSize = 22.sp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = subtitle,
        onValueChange = { onSubtitle(it.take(TvNames.MAX_LENGTH)) },
        label = { Text("Short description (optional)") },
        supportingText = { Text("Helps tell two similar TVs apart.") },
        singleLine = true,
        textStyle = TextStyle(fontSize = 20.sp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PairStep(ui: SetupUi, viewModel: SetupViewModel) {
    val name = ui.target?.displayName ?: ui.target?.host ?: "the TV"
    Page("Connect to $name") {
        when (val phase = ui.pairPhase) {
            PairPhase.Connecting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Body("Contacting the TV…")
                }
            }
            is PairPhase.EnterCode -> CodeEntry(phase.error, viewModel::submitCode)
            PairPhase.Submitting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Body("Checking the code…")
                }
            }
            is PairPhase.Failed -> {
                ErrorText(phase.message)
                PrimaryButton("Try again", viewModel::retryPairing)
            }
        }
        QuietButton("Choose a different TV", viewModel::backToDiscovery)
    }
}

@Composable
private fun CodeEntry(error: String?, onSubmit: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    Body("Look at the TV screen. It now shows a code with 6 characters (numbers and letters A to F). Type that code here.")
    OutlinedTextField(
        value = code,
        onValueChange = { input -> code = input.filter { it.isLetterOrDigit() }.uppercase().take(6) },
        label = { Text("Code shown on the TV") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit(code) }),
        textStyle = TextStyle(fontSize = 32.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { ErrorText(it) }
    PrimaryButton("Connect", { onSubmit(code) }, enabled = code.length == 6)
}

@Composable
private fun VerifyStep(ui: SetupUi, viewModel: SetupViewModel) {
    val test = viewModel.currentTest() ?: return
    val ready = ui.connection is ConnectionState.Ready
    Page("Test the buttons (${ui.testIndex + 1} of ${viewModel.testCount})") {
        Body("Each check sends a real command. Only buttons you confirm here are shown as working; buttons that do not work are hidden from the remote.")
        ConnectionLine(ui.connection)
        SectionTitle(test.title)
        Body(test.instruction)
        when (val phase = ui.testPhase) {
            TestPhase.Ready -> PrimaryButton(test.sendLabel, viewModel::sendTest, enabled = ready || test.isWake)
            TestPhase.Sending -> Body("Sending…")
            TestPhase.WakeWaiting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Body("Reconnecting to the TV…")
            }
            TestPhase.Asking -> {
                Text(test.question, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                PrimaryButton("Yes, it worked", { viewModel.answer(Answer.YES) })
                SecondaryButton("No, nothing happened", { viewModel.answer(Answer.NO) })
                QuietButton("Send again", viewModel::sendTest)
            }
            is TestPhase.Note -> {
                Body(phase.message)
                PrimaryButton("Continue", viewModel::continueAfterNote)
            }
        }
        if (ui.testPhase != TestPhase.Asking) {
            QuietButton("Skip this check", { viewModel.answer(Answer.SKIP) })
        }
        QuietButton("Finish checks later", viewModel::finishChecksLater)
    }
}

@Composable
private fun ConnectionLine(state: ConnectionState) {
    val (text, icon) = when (state) {
        is ConnectionState.Ready -> "Connected to the TV" to RemoteIcons.CheckCircle
        ConnectionState.PairingRequired -> "The TV refused the connection. Go back and pair again." to RemoteIcons.Warning
        ConnectionState.NetworkUnavailable -> "This phone is not on Wi-Fi." to RemoteIcons.WifiOff
        ConnectionState.TvUnreachable -> "The TV cannot be reached right now. Still trying…" to RemoteIcons.Warning
        ConnectionState.TvOff -> "The TV went to standby and left the network." to RemoteIcons.Power
        else -> "Connecting to the TV…" to RemoteIcons.Sync
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { },
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun DoneStep(capabilities: Map<Capability, CapabilityStatus>, note: String?, finishLabel: String, onFinished: () -> Unit) {
    BackHandler(onBack = onFinished)
    Page(stringResource(R.string.done_title)) {
        Body(stringResource(R.string.done_body))
        note?.let { Body(it) }
        val untested = capabilities.filterValues { it == CapabilityStatus.NOT_VERIFIED }.keys
        val unsupported = capabilities.filterValues { it == CapabilityStatus.UNSUPPORTED }.keys
        if (unsupported.isNotEmpty()) {
            Body("Hidden because they did not work: " + unsupported.joinToString { it.adminName() } + ".")
        }
        if (untested.isNotEmpty()) {
            Body("Not tested yet: " + untested.joinToString { it.adminName() } + ". You can test them later in Family settings.")
        }
        PrimaryButton(finishLabel, onFinished)
    }
}
