package app.seniorremote.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seniorremote.R
import app.seniorremote.domain.SavedTv
import app.seniorremote.text.TextEntryController
import app.seniorremote.ui.common.Body
import app.seniorremote.ui.common.ErrorText
import app.seniorremote.ui.common.PrimaryButton
import app.seniorremote.ui.common.QuietButton
import app.seniorremote.ui.common.TvThumbnail

/**
 * The phone's normal keyboard, for one phrase, sent to one TV on an explicit tap. The buttons sit
 * outside the scrolling part, so Send to TV stays visible above the keyboard.
 */
@Composable
fun TextEntryScreen(controller: TextEntryController, tv: SavedTv, onCancel: () -> Unit) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.type_text),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvThumbnail(tv, size = 32.dp, tint = MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(ui.tvName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    tv.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground) }
                }
            }
            Body(stringResource(R.string.type_text_instruction))
            OutlinedTextField(
                value = ui.draft,
                onValueChange = controller::edit,
                label = { Text(stringResource(R.string.text_to_send)) },
                maxLines = 4,
                // Done closes the keyboard; there is no Enter key that could reach the TV.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                textStyle = TextStyle(fontSize = 24.sp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .focusRequester(focus),
            )
            Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                when {
                    ui.uncertain -> ErrorText(stringResource(R.string.text_not_confirmed))
                    !ui.connected -> ErrorText(stringResource(R.string.text_waiting_for_tv))
                }
            }
        }
        Spacer(Modifier.padding(top = 8.dp))
        PrimaryButton(stringResource(R.string.send_to_tv), controller::send, enabled = ui.canSend)
        QuietButton(stringResource(R.string.cancel), onCancel, Modifier.align(Alignment.CenterHorizontally))
    }
}
