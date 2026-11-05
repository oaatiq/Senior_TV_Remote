package app.seniorremote.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seniorremote.domain.ConnectionState
import app.seniorremote.ui.common.Body
import app.seniorremote.ui.common.ErrorText
import app.seniorremote.ui.common.Page
import app.seniorremote.ui.common.PrimaryButton
import app.seniorremote.ui.common.QuietButton
import app.seniorremote.ui.common.SecondaryButton
import app.seniorremote.ui.common.SectionTitle
import app.seniorremote.ui.setup.PinField

@Composable
fun AdminScreen(
    viewModel: AdminViewModel,
    onClose: () -> Unit,
    onOpenTv: (String) -> Unit,
    onAddTv: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val library by viewModel.library.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val performance by viewModel.performance.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var changingPin by rememberSaveable { mutableStateOf(false) }

    Page("Family settings") {
        PrimaryButton("Back to the remote", onClose)

        SectionTitle("Manage TVs")
        Body("Each TV has its own pairing and tested buttons. Tap a TV to rename, test, re-pair or remove it.")
        library.tvs.forEach { tv ->
            ManagedTvCard(tv, isSelected = tv.id == library.selectedId, onClick = { onOpenTv(tv.id) })
        }
        SecondaryButton("Add and pair a TV", onAddTv)

        HorizontalDivider()
        SectionTitle("Remote options")
        SettingSwitch("Vibrate when a button is pressed", settings.haptics, viewModel::setHaptics)
        SettingSwitch("Button sounds", settings.buttonSounds, viewModel::setSounds)
        SettingSwitch("Keep the phone screen on while the remote is open", settings.keepScreenOn, viewModel::setKeepScreenOn)

        HorizontalDivider()
        SectionTitle("Measured performance (this phone)")
        performance.forEach { Body(it.format()) }

        HorizontalDivider()
        SectionTitle("Diagnostics")
        Body("Stored only on this phone. Pairing codes, keys and full addresses are never recorded.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(viewModel.exportLog())) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Copy log")
            }
            TextButton(onClick = viewModel::clearDiagnostics, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Clear log")
            }
        }
        SelectionContainer {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                val recent = events.takeLast(120).reversed()
                if (recent.isEmpty()) Text("No events yet.", style = MaterialTheme.typography.bodySmall)
                recent.forEach {
                    Text(
                        it.format(),
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider()
        SectionTitle("Family PIN")
        if (changingPin) {
            ChangePin(viewModel) { changingPin = false }
        } else {
            SecondaryButton("Change PIN", { changingPin = true })
        }

        QuietButton("Back to the remote", onClose)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ChangePin(viewModel: AdminViewModel, onDone: () -> Unit) {
    var current by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.semantics(mergeDescendants = false) { }) {
        PinField("Current PIN", current) { current = it }
        PinField("New PIN", new) { new = it }
        PinField("New PIN again", confirm) { confirm = it }
        error?.let { ErrorText(it) }
        PrimaryButton("Save new PIN", {
            error = viewModel.changePin(current, new, confirm)
            if (error == null) onDone()
        })
        QuietButton("Cancel", onDone)
    }
}

/** Entry gate for family settings. Routine remote use never shows this. */
@Composable
fun PinGateDialog(verify: (String) -> Boolean, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    var pin by rememberSaveable { mutableStateOf("") }
    var wrong by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Family settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Enter the family PIN.", style = MaterialTheme.typography.bodyLarge)
                PinField("PIN", pin) {
                    pin = it
                    wrong = false
                }
                if (wrong) ErrorText("That PIN is not correct.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (verify(pin)) onUnlocked() else {
                    wrong = true
                    pin = ""
                }
            }) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun ConnectionState.adminText(): String = when (this) {
    is ConnectionState.Ready -> "Ready (session #$generation)" + if (tvAsleep) ", TV screen off" else ""
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Recovering -> "Reconnecting after a lost session"
    ConnectionState.NetworkUnavailable -> "No Wi-Fi/Ethernet on this phone"
    ConnectionState.TvUnreachable -> "TV not answering (retrying in the background)"
    ConnectionState.TvOff -> "TV went to standby and left the network (retrying in the background)"
    ConnectionState.PairingRequired -> "TV refused this phone's pairing, or a different device answered. Re-pair this TV."
    is ConnectionState.Unsupported -> "Unsupported: $reason"
    ConnectionState.Suspended -> "Paused (app in background)"
    ConnectionState.NotConfigured -> "No TV selected"
}
