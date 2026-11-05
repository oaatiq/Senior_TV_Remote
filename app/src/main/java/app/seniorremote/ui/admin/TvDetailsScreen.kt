package app.seniorremote.ui.admin

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seniorremote.domain.Capability
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.NameProblem
import app.seniorremote.domain.SavedTv
import app.seniorremote.ui.common.Body
import app.seniorremote.ui.common.ErrorText
import app.seniorremote.ui.common.Page
import app.seniorremote.ui.common.PrimaryButton
import app.seniorremote.ui.common.QuietButton
import app.seniorremote.ui.common.SecondaryButton
import app.seniorremote.ui.common.SectionTitle
import app.seniorremote.ui.common.TvThumbnail
import app.seniorremote.ui.setup.TvNameFields
import app.seniorremote.ui.setup.adminName
import app.seniorremote.ui.setup.adminText
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ManagedTvCard(tv: SavedTv, isSelected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val onBackground = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .border(1.5.dp, onBackground.copy(alpha = 0.55f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvThumbnail(tv, size = 40.dp, tint = onBackground)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tv.displayName, style = MaterialTheme.typography.titleMedium, color = onBackground)
            tv.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = onBackground) }
            if (isSelected) Text("Selected on the remote", style = MaterialTheme.typography.bodySmall, color = onBackground)
        }
    }
}

/** Protected details of one TV. Nothing here affects any other saved TV. */
@Composable
fun TvDetailsScreen(
    viewModel: AdminViewModel,
    tvId: String,
    onBack: () -> Unit,
    onRetest: () -> Unit,
    onTestText: () -> Unit,
    onRepair: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val library by viewModel.library.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val tv = library.find(tvId) ?: return
    val isSelected = library.selectedId == tvId

    var name by rememberSaveable(tvId) { mutableStateOf(tv.displayName) }
    var subtitle by rememberSaveable(tvId) { mutableStateOf(tv.subtitle.orEmpty()) }
    var nameMessage by rememberSaveable(tvId) { mutableStateOf<String?>(null) }
    var nameSaved by rememberSaveable(tvId) { mutableStateOf(false) }
    var confirmRemove by rememberSaveable(tvId) { mutableStateOf(false) }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.choosePicture(tvId, uri)
    }

    Page(tv.displayName) {
        SecondaryButton("Back to Manage TVs", onBack)

        SectionTitle("Name")
        Body("Shown on the remote, the TV chooser and here. Changing it does not affect the pairing.")
        TvNameFields(
            name,
            {
                name = it
                nameMessage = null
            },
            subtitle,
            {
                subtitle = it
                nameMessage = null
            },
        )
        nameMessage?.let { if (nameSaved) Body(it) else ErrorText(it) }
        PrimaryButton("Save name", {
            viewModel.rename(tvId, name, subtitle) { problem ->
                nameSaved = problem == null
                nameMessage = when (problem) {
                    null -> "Saved."
                    NameProblem.NEEDS_SUBTITLE -> "Another TV has this name. Add a short description so they can be told apart."
                    NameProblem.SAME_AS_ANOTHER_TV -> "Another TV has this name and description. Please change one of them."
                }
            }
        })
        Body("Original device name: ${tv.originalName ?: "Not available"}")

        HorizontalDivider()
        SectionTitle("Picture")
        Body("Optional. Shown instead of the TV icon on the remote and in the chooser.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvThumbnail(tv, size = 64.dp, tint = MaterialTheme.colorScheme.onBackground)
            TextButton(
                onClick = { pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Choose picture") }
            if (tv.imageFile != null) {
                TextButton(onClick = { viewModel.removePicture(tvId) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Remove picture")
                }
            }
        }

        HorizontalDivider()
        SectionTitle("Connection")
        Body("Address: ${tv.lastKnownHost} (port ${tv.port})")
        Body("Protocol: Android TV Remote Service v2")
        Body(
            "Last connected: " + (tv.lastConnectedAtEpochMs?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            } ?: "never"),
        )
        if (isSelected) {
            val shown = connection.takeUnless { it is ConnectionState.Ready && it.tvId != tvId } ?: ConnectionState.Connecting
            Body("Connection: ${shown.adminText()}")
            SecondaryButton("Reconnect now", viewModel::reconnectNow)
        } else {
            Body("Connection: not selected, so the phone is not connected to this TV.")
        }

        HorizontalDivider()
        SectionTitle("Buttons")
        Capability.entries.forEach { capability ->
            Column {
                Text(capability.adminName(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                Text(tv.status(capability).adminText(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        Body("Buttons that do not work are hidden. The Power button appears only after standby was tested successfully.")
        if (!isSelected) Body("Testing selects this TV on the remote.")
        SecondaryButton("Test the buttons", onRetest)
        SecondaryButton("Test text entry only", onTestText)

        HorizontalDivider()
        SectionTitle("Pairing")
        Body("Re-pair if the remote asks for a family member, for example after the pairing was removed on the TV. The name and tested buttons are kept.")
        SecondaryButton("Re-pair this TV", onRepair)

        HorizontalDivider()
        SectionTitle("Remove")
        Body("Removes this TV, its pairing, its picture and its test results from this phone. Other TVs are not affected.")
        SecondaryButton("Remove this TV", { confirmRemove = true })
        QuietButton("Back to Manage TVs", onBack)
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${tv.displayName}?") },
            text = { Text("To use this TV again, a family member will have to pair it again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    viewModel.removeTv(tvId, onBack)
                }) { Text("Remove TV") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}
