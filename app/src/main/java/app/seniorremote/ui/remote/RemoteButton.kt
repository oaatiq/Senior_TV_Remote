package app.seniorremote.ui.remote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A remote button that reports finger-down and finger-up separately (for hold-to-repeat) and
 * shows its pressed state on the same frame as the touch. Screen-reader activation performs a
 * single press through [onAccessibilityClick].
 *
 * When [available] is false the button stays where it is and still reacts (so the press can
 * explain why nothing happens), but is drawn dimmed with a dashed-looking outline.
 */
@Composable
fun RemoteButton(
    description: String,
    available: Boolean,
    unavailableDescription: String,
    container: Color,
    content: Color,
    pressedContainer: Color,
    pressedContent: Color,
    shape: Shape,
    onDown: () -> Unit,
    onUp: () -> Unit,
    onAccessibilityClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    label: String? = null,
    iconSize: Dp = 36.dp,
    labelStyle: TextStyle? = null,
) {
    var pressed by remember { mutableStateOf(false) }
    val currentDown by rememberUpdatedState(onDown)
    val currentUp by rememberUpdatedState(onUp)
    val currentClick by rememberUpdatedState(onAccessibilityClick)

    val background = if (pressed) pressedContainer else container
    val foreground = if (pressed) pressedContent else content

    Box(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(
                BorderStroke(if (available) 0.dp else 2.dp, if (available) Color.Transparent else content.copy(alpha = 0.5f)),
                shape,
            )
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
                if (!available) stateDescription = unavailableDescription
                onClick(label = description) {
                    currentClick()
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    currentDown()
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        // Runs on release, on touch cancellation and when the button leaves
                        // the composition, so repetition can never be left running.
                        pressed = false
                        currentUp()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.alpha(if (available || pressed) 1f else 0.55f),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(iconSize))
            }
            if (label != null) {
                Text(
                    text = label,
                    color = foreground,
                    style = labelStyle?.let { MaterialTheme.typography.labelLarge.merge(it) } ?: MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
