package app.seniorremote.ui.setup

import app.seniorremote.domain.Capability
import app.seniorremote.domain.CapabilityStatus
import app.seniorremote.domain.RemoteKey

enum class Answer { YES, NO, SKIP }

/**
 * One guided check. A capability is only marked supported when the administrator confirms the
 * TV visibly reacted - successful transmission alone proves nothing.
 */
data class CapabilityTest(
    val id: String,
    val capability: Capability,
    val title: String,
    val instruction: String,
    val sendLabel: String,
    val keys: List<RemoteKey>,
    val gapMs: Long,
    val question: String,
    val isWake: Boolean = false,
    /** Sent to the TV's active text field instead of pressing keys. */
    val text: String? = null,
)

val capabilityTests = listOf(
    CapabilityTest(
        id = "nav_vertical",
        capability = Capability.NAVIGATION,
        title = "Up and Down",
        instruction = "Show the TV's home screen. Tap the button below: the phone sends Down, then Up.",
        sendLabel = "Send Down, then Up",
        keys = listOf(RemoteKey.DOWN, RemoteKey.UP),
        gapMs = 900,
        question = "Did the highlight on the TV move down and then back up?",
    ),
    CapabilityTest(
        id = "nav_horizontal",
        capability = Capability.NAVIGATION,
        title = "Left and Right",
        instruction = "Tap the button below: the phone sends Right, then Left.",
        sendLabel = "Send Right, then Left",
        keys = listOf(RemoteKey.RIGHT, RemoteKey.LEFT),
        gapMs = 900,
        question = "Did the highlight move right and then back left?",
    ),
    CapabilityTest(
        id = "ok",
        capability = Capability.OK,
        title = "OK",
        instruction = "Using the TV's own remote or the Up/Down test, highlight something harmless such as Settings or an app. Then tap the button below.",
        sendLabel = "Send OK",
        keys = listOf(RemoteKey.OK),
        gapMs = 0,
        question = "Did the highlighted item open?",
    ),
    CapabilityTest(
        id = "back",
        capability = Capability.BACK,
        title = "Back",
        instruction = "Tap the button below while the item you opened is showing.",
        sendLabel = "Send Back",
        keys = listOf(RemoteKey.BACK),
        gapMs = 0,
        question = "Did the TV go back to the previous screen?",
    ),
    CapabilityTest(
        id = "home",
        capability = Capability.HOME,
        title = "Home",
        instruction = "Open any app or menu on the TV, then tap the button below.",
        sendLabel = "Send Home",
        keys = listOf(RemoteKey.HOME),
        gapMs = 0,
        question = "Did the TV show its home screen?",
    ),
    CapabilityTest(
        id = "volume",
        capability = Capability.VOLUME,
        title = "Volume",
        instruction = "Tap the button below: the phone sends Volume up, then Volume down. If the TV uses a sound bar, watch the sound bar too.",
        sendLabel = "Send Volume up, then down",
        keys = listOf(RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN),
        gapMs = 1_200,
        question = "Did the volume go up and then back down?",
    ),
    CapabilityTest(
        id = "mute",
        capability = Capability.MUTE,
        title = "Mute",
        instruction = "Tap the button below: the phone sends Mute, waits two seconds, and sends Mute again to switch sound back on.",
        sendLabel = "Send Mute twice",
        keys = listOf(RemoteKey.MUTE, RemoteKey.MUTE),
        gapMs = 2_000,
        question = "Did the TV mute and then unmute?",
    ),
    CapabilityTest(
        id = "text",
        capability = Capability.TEXT_ENTRY,
        title = "Type text",
        instruction = "On the TV, open a search screen and select the search box. Leave any on-screen keyboard open. Tap the button below: the phone sends the word TEST to the box, without searching. Search boxes differ between apps, so also try the apps the senior uses.",
        sendLabel = "Send the word TEST",
        keys = emptyList(),
        gapMs = 0,
        question = "Did TEST appear in the box?",
        text = "TEST",
    ),
    CapabilityTest(
        id = "standby",
        capability = Capability.POWER_STANDBY,
        title = "Power: standby",
        instruction = "This switches the TV to standby (screen off). Tap the button below.",
        sendLabel = "Send Power",
        keys = listOf(RemoteKey.POWER),
        gapMs = 0,
        question = "Did the TV switch off into standby?",
    ),
    CapabilityTest(
        id = "wake",
        capability = Capability.POWER_WAKE,
        title = "Power: wake up",
        instruction = "Leave the TV in standby for at least 30 seconds. Then tap the button below. The phone reconnects to the TV and, if it can, sends Power. Repeat this test after an overnight standby before relying on it.",
        sendLabel = "Try to wake the TV",
        keys = listOf(RemoteKey.POWER),
        gapMs = 0,
        question = "Did the TV switch back on?",
        isWake = true,
    ),
)

/**
 * Combines answers into capability statuses. Any "No" makes a capability unsupported; it is
 * supported only if every related check was confirmed. Capabilities whose checks were all
 * skipped keep their previous status.
 */
fun aggregateCapabilities(
    answers: Map<String, Answer>,
    previous: Map<Capability, CapabilityStatus>,
): Map<Capability, CapabilityStatus> = Capability.entries.associateWith { capability ->
    val related = capabilityTests.filter { it.capability == capability }.map { answers[it.id] }
    when {
        related.all { it == null || it == Answer.SKIP } -> previous[capability] ?: CapabilityStatus.NOT_VERIFIED
        related.any { it == Answer.NO } -> CapabilityStatus.UNSUPPORTED
        related.all { it == Answer.YES } -> CapabilityStatus.SUPPORTED_TESTED
        else -> CapabilityStatus.NOT_VERIFIED
    }
}

fun CapabilityStatus.adminText(): String = when (this) {
    CapabilityStatus.SUPPORTED_TESTED -> "Tested - works"
    CapabilityStatus.UNSUPPORTED -> "Does not work - hidden from the remote"
    CapabilityStatus.NOT_VERIFIED -> "Not tested yet"
}

fun Capability.adminName(): String = when (this) {
    Capability.NAVIGATION -> "Arrows (Up/Down/Left/Right)"
    Capability.OK -> "OK"
    Capability.BACK -> "Back"
    Capability.HOME -> "Home"
    Capability.VOLUME -> "Volume up/down"
    Capability.MUTE -> "Mute"
    Capability.POWER_STANDBY -> "Power: switch to standby"
    Capability.POWER_WAKE -> "Power: wake from standby"
    Capability.TEXT_ENTRY -> "Type text"
}
