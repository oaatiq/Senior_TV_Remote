package app.seniorremote.ui.remote

import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seniorremote.R
import app.seniorremote.domain.Capability
import app.seniorremote.domain.CapabilityStatus
import app.seniorremote.domain.CommandFeedback
import app.seniorremote.domain.ConnectionState
import app.seniorremote.domain.RemoteKey
import app.seniorremote.domain.SavedTv
import app.seniorremote.ui.RemoteIcons
import app.seniorremote.ui.common.TvThumbnail
import app.seniorremote.ui.theme.LocalRemoteColors

@Composable
fun RemoteScreen(
    viewModel: RemoteViewModel,
    onChooseTv: () -> Unit,
    onTypeText: () -> Unit,
    onOpenSettings: () -> Unit,
    onFirstFrame: () -> Unit,
) {
    val rawState by viewModel.state.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val feedback by viewModel.feedback.collectAsStateWithLifecycle()
    val view = LocalView.current
    val tv = library.selected ?: return
    val state = rawState.forTv(tv.id)
    // This TV cannot be woken from the phone, so Power must not suggest that it can.
    val wakeUnsupported = tv.status(Capability.POWER_WAKE) == CapabilityStatus.UNSUPPORTED
    val presentation = state.presentation(wakeUnsupported)
    val ready = state is ConnectionState.Ready && !state.tvAsleep
    val powerReady = state is ConnectionState.Ready && (!state.tvAsleep || !wakeUnsupported)

    LaunchedEffect(Unit) {
        withFrameNanos { }
        onFirstFrame()
    }

    DisposableEffect(settings.keepScreenOn) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 60) }.getOrNull() }
    DisposableEffect(tone) { onDispose { tone?.release() } }

    val actions = KeyActions(
        ready = ready,
        powerReady = powerReady,
        powerTurnsOffOnly = wakeUnsupported,
        onDown = { key ->
            if (settings.haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (settings.buttonSounds) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
            viewModel.keyDown(tv.id, key)
        },
        onUp = viewModel::keyUp,
        onAccessibilityPress = { key -> viewModel.accessibilityPress(tv.id, key) },
    )

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        val metrics = metricsFor(maxHeight, hasPower = tv.showsKey(RemoteKey.POWER))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(metrics.gap),
        ) {
            TvSelector(tv, library.tvs, onChooseTv)
            StatusArea(presentation, feedback)
            if (tv.showsKey(RemoteKey.POWER)) {
                PowerButton(actions, metrics)
            }
            DPad(tv, actions, metrics)
            NavigationRow(tv, actions, metrics)
            VolumeRow(tv, actions, metrics)
            // Below the main controls, whose sizes do not depend on it; small phones scroll instead.
            val textBlock = viewModel.textEntryBlock(tv, state)
            TypeTextButton(
                available = textBlock == null,
                onClick = { if (textBlock == null) onTypeText() else viewModel.show(textBlock) },
            )
            BottomRow(
                showTryAgain = presentation.offerTryAgain,
                onTryAgain = viewModel::tryAgain,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

private data class Metrics(val cell: Dp, val row: Dp, val gap: Dp)

/** Sizes the d-pad to the space left at normal font size. Larger fonts scroll instead. */
private fun metricsFor(height: Dp, hasPower: Boolean): Metrics {
    val tight = height < 720.dp
    val row = if (tight) 60.dp else 68.dp
    val gap = if (tight) 8.dp else 12.dp
    val selector = 76.dp
    val status = 88.dp
    val bottomRow = 56.dp
    val padding = 24.dp
    val rows = if (hasPower) 3 else 2
    val gaps = if (hasPower) 8 else 7
    val cell = (height - selector - status - bottomRow - padding - row * rows - gap * gaps) / 3
    return Metrics(cell = cell.coerceIn(68.dp, 92.dp), row = row, gap = gap)
}

private class KeyActions(
    val ready: Boolean,
    val powerReady: Boolean,
    val powerTurnsOffOnly: Boolean,
    val onDown: (RemoteKey) -> Unit,
    val onUp: (RemoteKey) -> Unit,
    val onAccessibilityPress: (RemoteKey) -> Unit,
)

/** Shows which TV the buttons control. Always usable, whatever the connection is doing. */
@Composable
private fun TvSelector(tv: SavedTv, allTvs: List<SavedTv>, onClick: () -> Unit) {
    val colors = LocalRemoteColors.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(colors.statusBackground)
            .border(1.5.dp, colors.onStatusBackground.copy(alpha = 0.55f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvThumbnail(tv, size = 36.dp, tint = colors.onStatusBackground)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            // Sized for the tallest saved TV, so switching never moves the controls below.
            allTvs.forEach { other ->
                Box(Modifier.alpha(0f).clearAndSetSemantics { }) { TvLabel(other) }
            }
            TvLabel(tv)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.change_tv),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onStatusBackground,
            )
            Icon(RemoteIcons.Down, contentDescription = null, tint = colors.onStatusBackground, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun TvLabel(tv: SavedTv) {
    val color = LocalRemoteColors.current.onStatusBackground
    Column {
        Text(
            tv.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        tv.subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Compact status. Its height is fixed for the tallest message, so the controls never move. */
@Composable
private fun StatusArea(presentation: StatusPresentation, feedback: CommandFeedback?) {
    val colors = LocalRemoteColors.current
    val toneColor = when (presentation.tone) {
        StatusTone.READY -> colors.statusReady
        StatusTone.BUSY -> colors.statusBusy
        StatusTone.PROBLEM -> colors.statusProblem
    }
    val shape = RoundedCornerShape(14.dp)
    val detail = feedback?.message() ?: presentation.detail
    val labelStyle = MaterialTheme.typography.titleMedium
    val detailStyle = MaterialTheme.typography.bodyMedium
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (feedback != null) 3.dp else 1.5.dp, toneColor, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Shape differs per state (tick, arrows, warning, Wi-Fi), so status is not color-only.
        Icon(presentation.icon, contentDescription = null, tint = toneColor, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Column(Modifier.alpha(0f).clearAndSetSemantics { }) {
                Box { allStatusLabels.forEach { Text(stringResource(it), style = labelStyle) } }
                Box { allStatusDetails.forEach { Text(stringResource(it), style = detailStyle) } }
            }
            Column {
                Text(stringResource(presentation.label), style = labelStyle, color = MaterialTheme.colorScheme.onBackground)
                detail?.let {
                    Text(stringResource(it), style = detailStyle, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
    }
}

@Composable
private fun PowerButton(actions: KeyActions, metrics: Metrics) {
    val colors = LocalRemoteColors.current
    KeyButton(
        key = RemoteKey.POWER,
        actions = actions,
        available = actions.powerReady,
        description = if (actions.powerTurnsOffOnly) stringResource(R.string.cd_power_off) else null,
        icon = RemoteIcons.Power,
        label = stringResource(if (actions.powerTurnsOffOnly) R.string.key_power_off else R.string.key_power),
        container = colors.power,
        content = colors.onPower,
        shape = RoundedCornerShape(50),
        modifier = Modifier.widthIn(min = 180.dp).heightIn(min = metrics.row),
        iconSize = 30.dp,
    )
}

@Composable
private fun DPad(tv: SavedTv, actions: KeyActions, metrics: Metrics) {
    val colors = LocalRemoteColors.current
    val showArrows = tv.showsKey(RemoteKey.UP)
    val showOk = tv.showsKey(RemoteKey.OK)
    val arrowShape = RoundedCornerShape(22.dp)

    @Composable
    fun Arrow(key: RemoteKey, icon: ImageVector) {
        if (showArrows) {
            KeyButton(
                key = key,
                actions = actions,
                icon = icon,
                shape = arrowShape,
                modifier = Modifier.size(metrics.cell),
                iconSize = metrics.cell * 0.6f,
            )
        } else {
            Spacer(Modifier.size(metrics.cell))
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(metrics.gap), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
            Spacer(Modifier.size(metrics.cell))
            Arrow(RemoteKey.UP, RemoteIcons.Up)
            Spacer(Modifier.size(metrics.cell))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
            Arrow(RemoteKey.LEFT, RemoteIcons.Left)
            if (showOk) {
                KeyButton(
                    key = RemoteKey.OK,
                    actions = actions,
                    label = stringResource(R.string.key_ok),
                    container = colors.okButton,
                    content = colors.onOkButton,
                    shape = CircleShape,
                    modifier = Modifier.size(metrics.cell),
                    labelStyle = TextStyle(fontSize = 28.sp),
                )
            } else {
                Spacer(Modifier.size(metrics.cell))
            }
            Arrow(RemoteKey.RIGHT, RemoteIcons.Right)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
            Spacer(Modifier.size(metrics.cell))
            Arrow(RemoteKey.DOWN, RemoteIcons.Down)
            Spacer(Modifier.size(metrics.cell))
        }
    }
}

@Composable
private fun NavigationRow(tv: SavedTv, actions: KeyActions, metrics: Metrics) {
    val shape = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
        if (tv.showsKey(RemoteKey.BACK)) {
            KeyButton(
                key = RemoteKey.BACK,
                actions = actions,
                icon = RemoteIcons.Back,
                label = stringResource(R.string.key_back),
                shape = shape,
                modifier = Modifier.weight(1f).heightIn(min = metrics.row),
                iconSize = 28.dp,
            )
        }
        if (tv.showsKey(RemoteKey.HOME)) {
            KeyButton(
                key = RemoteKey.HOME,
                actions = actions,
                icon = RemoteIcons.Home,
                label = stringResource(R.string.key_home),
                shape = shape,
                modifier = Modifier.weight(1f).heightIn(min = metrics.row),
                iconSize = 28.dp,
            )
        }
    }
}

@Composable
private fun VolumeRow(tv: SavedTv, actions: KeyActions, metrics: Metrics) {
    val shape = RoundedCornerShape(18.dp)
    val showVolume = tv.showsKey(RemoteKey.VOLUME_UP)
    val showMute = tv.showsKey(RemoteKey.MUTE)
    if (!showVolume && !showMute) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.gap)) {
        if (showVolume) {
            KeyButton(
                key = RemoteKey.VOLUME_DOWN,
                actions = actions,
                icon = RemoteIcons.VolumeDown,
                label = stringResource(R.string.label_vol_down),
                shape = shape,
                modifier = Modifier.weight(1f).heightIn(min = metrics.row),
                iconSize = 28.dp,
            )
        }
        if (showMute) {
            KeyButton(
                key = RemoteKey.MUTE,
                actions = actions,
                icon = RemoteIcons.Mute,
                label = stringResource(R.string.key_mute),
                shape = shape,
                modifier = Modifier.weight(1f).heightIn(min = metrics.row),
                iconSize = 28.dp,
            )
        }
        if (showVolume) {
            KeyButton(
                key = RemoteKey.VOLUME_UP,
                actions = actions,
                icon = RemoteIcons.VolumeUp,
                label = stringResource(R.string.label_vol_up),
                shape = shape,
                modifier = Modifier.weight(1f).heightIn(min = metrics.row),
                iconSize = 28.dp,
            )
        }
    }
}

/** Secondary to the remote controls. Always tappable, so an unavailable state can explain itself. */
@Composable
private fun TypeTextButton(available: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .alpha(if (available) 1f else 0.6f),
    ) {
        Icon(RemoteIcons.Keyboard, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.type_text), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun BottomRow(showTryAgain: Boolean, onTryAgain: () -> Unit, onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Always laid out (invisible when not offered) so the screen never shifts.
        OutlinedButton(
            onClick = onTryAgain,
            enabled = showTryAgain,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .alpha(if (showTryAgain) 1f else 0f)
                .then(if (showTryAgain) Modifier else Modifier.clearAndSetSemantics { }),
        ) {
            Text(stringResource(R.string.try_again), style = MaterialTheme.typography.labelLarge)
        }
        TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(RemoteIcons.Settings, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.family_settings), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun KeyButton(
    key: RemoteKey,
    actions: KeyActions,
    shape: Shape,
    modifier: Modifier,
    icon: ImageVector? = null,
    label: String? = null,
    container: Color = LocalRemoteColors.current.button,
    content: Color = LocalRemoteColors.current.onButton,
    iconSize: Dp = 36.dp,
    labelStyle: TextStyle? = null,
    available: Boolean = actions.ready,
    description: String? = null,
) {
    val colors = LocalRemoteColors.current
    RemoteButton(
        description = description ?: stringResource(key.descriptionRes()),
        available = available,
        unavailableDescription = stringResource(R.string.cd_unavailable_suffix).trimStart(',', ' '),
        container = container,
        content = content,
        pressedContainer = colors.pressed,
        pressedContent = colors.onPressed,
        shape = shape,
        onDown = { actions.onDown(key) },
        onUp = { actions.onUp(key) },
        onAccessibilityClick = { actions.onAccessibilityPress(key) },
        modifier = modifier,
        icon = icon,
        label = label,
        iconSize = iconSize,
        labelStyle = labelStyle,
    )
}

private fun RemoteKey.descriptionRes(): Int = when (this) {
    RemoteKey.UP -> R.string.key_up
    RemoteKey.DOWN -> R.string.key_down
    RemoteKey.LEFT -> R.string.key_left
    RemoteKey.RIGHT -> R.string.key_right
    RemoteKey.OK -> R.string.key_ok
    RemoteKey.BACK -> R.string.key_back
    RemoteKey.HOME -> R.string.key_home
    RemoteKey.VOLUME_UP -> R.string.key_volume_up
    RemoteKey.VOLUME_DOWN -> R.string.key_volume_down
    RemoteKey.MUTE -> R.string.key_mute
    RemoteKey.POWER -> R.string.key_power
}
