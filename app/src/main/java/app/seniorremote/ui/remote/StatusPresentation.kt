package app.seniorremote.ui.remote

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import app.seniorremote.R
import app.seniorremote.domain.CommandFeedback
import app.seniorremote.domain.ConnectionState
import app.seniorremote.ui.RemoteIcons

enum class StatusTone { READY, BUSY, PROBLEM }

/** Senior-facing presentation of a connection state. [detail] is shown only when it adds something. */
data class StatusPresentation(
    @StringRes val label: Int,
    @StringRes val detail: Int?,
    val icon: ImageVector,
    val tone: StatusTone,
    val offerTryAgain: Boolean,
)

fun ConnectionState.presentation(wakeUnsupported: Boolean = false): StatusPresentation = when (this) {
    is ConnectionState.Ready -> when {
        !tvAsleep -> StatusPresentation(R.string.status_ready, null, RemoteIcons.CheckCircle, StatusTone.READY, false)
        wakeUnsupported -> StatusPresentation(R.string.status_tv_off, R.string.detail_tv_off_own_remote, RemoteIcons.Power, StatusTone.BUSY, false)
        else -> StatusPresentation(R.string.status_tv_off, R.string.detail_tv_off_press_power, RemoteIcons.Power, StatusTone.BUSY, false)
    }
    ConnectionState.TvOff ->
        StatusPresentation(R.string.status_tv_off, R.string.detail_tv_off_own_remote, RemoteIcons.Power, StatusTone.BUSY, true)
    ConnectionState.Connecting, ConnectionState.Suspended, ConnectionState.NotConfigured ->
        StatusPresentation(R.string.status_connecting, null, RemoteIcons.Sync, StatusTone.BUSY, false)
    ConnectionState.Recovering ->
        StatusPresentation(R.string.status_reconnecting, null, RemoteIcons.Sync, StatusTone.BUSY, false)
    ConnectionState.NetworkUnavailable ->
        StatusPresentation(R.string.status_check_wifi, R.string.detail_check_wifi, RemoteIcons.WifiOff, StatusTone.PROBLEM, true)
    ConnectionState.TvUnreachable ->
        StatusPresentation(R.string.status_tv_unavailable, R.string.detail_tv_unavailable, RemoteIcons.Warning, StatusTone.PROBLEM, true)
    ConnectionState.PairingRequired ->
        StatusPresentation(R.string.status_pairing_required, R.string.detail_pairing_required, RemoteIcons.Person, StatusTone.PROBLEM, true)
    is ConnectionState.Unsupported ->
        StatusPresentation(R.string.status_unsupported, R.string.detail_unsupported, RemoteIcons.Warning, StatusTone.PROBLEM, false)
}

/**
 * The state as it applies to [tvId]. In the moment after choosing another TV the connection
 * manager may still report the previous TV's session; that must never look usable.
 */
fun ConnectionState.forTv(tvId: String): ConnectionState =
    if (this is ConnectionState.Ready && this.tvId != tvId) ConnectionState.Connecting else this

@StringRes
fun CommandFeedback.message(): Int = when (this) {
    CommandFeedback.SendFailed -> R.string.feedback_send_failed
    CommandFeedback.TextSent -> R.string.text_sent
    CommandFeedback.TextEntryUnavailable -> R.string.text_unavailable
    CommandFeedback.TextEntryNotTested -> R.string.text_not_tested
    is CommandFeedback.NotReady -> when (state) {
        ConnectionState.NetworkUnavailable -> R.string.feedback_check_wifi
        ConnectionState.TvUnreachable -> R.string.feedback_tv_unavailable
        ConnectionState.TvOff -> R.string.feedback_tv_off
        ConnectionState.PairingRequired, is ConnectionState.Unsupported -> R.string.feedback_pairing_required
        else -> R.string.feedback_connecting
    }
}

/** Every label/detail the status area can show, used to reserve space so nothing below it moves. */
val allStatusLabels = listOf(
    R.string.status_ready, R.string.status_connecting, R.string.status_reconnecting, R.string.status_check_wifi,
    R.string.status_tv_unavailable, R.string.status_tv_off, R.string.status_pairing_required, R.string.status_unsupported,
)

val allStatusDetails = listOf(
    R.string.detail_check_wifi, R.string.detail_tv_unavailable, R.string.detail_tv_off_press_power,
    R.string.detail_tv_off_own_remote, R.string.detail_pairing_required, R.string.detail_unsupported,
    R.string.feedback_connecting, R.string.feedback_check_wifi, R.string.feedback_tv_unavailable, R.string.feedback_tv_off,
    R.string.feedback_pairing_required, R.string.feedback_send_failed,
    R.string.text_sent, R.string.text_unavailable, R.string.text_not_tested,
)
