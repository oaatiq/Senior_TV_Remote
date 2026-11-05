package app.seniorremote.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.seniorremote.R
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.SavedTv
import app.seniorremote.domain.TvLibrary
import app.seniorremote.ui.RemoteIcons
import app.seniorremote.ui.common.Body
import app.seniorremote.ui.common.Page
import app.seniorremote.ui.common.SecondaryButton
import app.seniorremote.ui.common.TvThumbnail

/**
 * One tap selects a TV. Every saved TV stays listed and selectable even if it cannot be reached.
 * Only the selected TV shows a status, because other TVs are never probed just to label them.
 * Pairing and removal are not offered here; they live behind the family PIN.
 */
@Composable
fun ChooseTvScreen(
    library: TvLibrary,
    connection: ConnectionState,
    canGoBack: Boolean,
    onSelect: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (canGoBack) BackHandler(onBack = onBack)
    Page(stringResource(R.string.choose_tv_title)) {
        Body(stringResource(R.string.choose_tv_question))
        library.tvs.forEach { tv ->
            val isSelected = tv.id == library.selectedId
            TvCard(
                tv = tv,
                isSelected = isSelected,
                status = if (isSelected) stringResource(connection.forTv(tv.id).presentation().label) else null,
                onClick = { onSelect(tv.id) },
            )
        }
        if (canGoBack) SecondaryButton(stringResource(R.string.back_to_remote), onBack)
    }
}

@Composable
private fun TvCard(tv: SavedTv, isSelected: Boolean, status: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val onBackground = MaterialTheme.colorScheme.onBackground
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .clip(shape)
            .border(if (isSelected) 3.dp else 1.5.dp, if (isSelected) MaterialTheme.colorScheme.primary else onBackground.copy(alpha = 0.55f), shape)
            .semantics { selected = isSelected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvThumbnail(tv, size = 56.dp, tint = onBackground)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            // The whole name, however long.
            Text(tv.displayName, style = MaterialTheme.typography.titleLarge, color = onBackground)
            tv.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = onBackground) }
            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = onBackground) }
        }
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            // A tick and a word, so selection does not depend on color.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    RemoteIcons.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
                Text(stringResource(R.string.tv_selected), style = MaterialTheme.typography.labelMedium, color = onBackground)
            }
        }
    }
}
