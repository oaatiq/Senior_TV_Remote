package app.seniorremote.protocol.atvr2

import app.seniorremote.domain.RemoteKey

/**
 * Settings for the Android TV Remote Service v2 adapter. Timeouts are starting values to be
 * tuned against the target TV.
 */
data class AtvConfig(
    val pairingPort: Int = 6467,
    val defaultRemotePort: Int = 6466,
    val connectTimeoutMs: Int = 3_000,
    val tlsHandshakeTimeoutMs: Int = 4_000,
    /** Time allowed after TLS for the TV's configure / set-active exchange. */
    val sessionSetupTimeoutMs: Long = 5_000,
    /** The TV pings connected remotes; silence for this long is treated as a lost session. */
    val idleTimeoutMs: Int = 20_000,
    /**
     * Shorter limit once the TV reports standby. Many TVs shut their network down seconds later,
     * and this bounds how long the phone can believe a dead session is alive.
     */
    val standbyIdleTimeoutMs: Int = 12_000,
    val sendTimeoutMs: Long = 2_000,
    val pairingStepTimeoutMs: Int = 10_000,
    val pairingServiceName: String = "androidtvremote2",
    val clientName: String = "Senior TV Remote",
    val deviceModel: String = "Senior TV Remote",
    val deviceVendor: String = "SeniorRemote",
    val packageName: String = "androidtv-remote",
    val appVersion: String = "1.0.0",
    /** Feature bitmask announced in RemoteConfigure / RemoteSetActive (ping, key, ime, power, volume, app link). */
    val featureMask: Int = 622,
) {
    companion object {
        /** Feature bit for text input. */
        const val FEATURE_IME = 4
    }
}

/** Android KeyEvent key codes injected through RemoteKeyInject. */
internal object AtvKeyCodes {
    fun of(key: RemoteKey): Int = when (key) {
        RemoteKey.UP -> 19 // KEYCODE_DPAD_UP
        RemoteKey.DOWN -> 20 // KEYCODE_DPAD_DOWN
        RemoteKey.LEFT -> 21 // KEYCODE_DPAD_LEFT
        RemoteKey.RIGHT -> 22 // KEYCODE_DPAD_RIGHT
        RemoteKey.OK -> 23 // KEYCODE_DPAD_CENTER
        RemoteKey.BACK -> 4 // KEYCODE_BACK
        RemoteKey.HOME -> 3 // KEYCODE_HOME
        RemoteKey.VOLUME_UP -> 24 // KEYCODE_VOLUME_UP
        RemoteKey.VOLUME_DOWN -> 25 // KEYCODE_VOLUME_DOWN
        RemoteKey.MUTE -> 164 // KEYCODE_VOLUME_MUTE
        RemoteKey.POWER -> 26 // KEYCODE_POWER (toggle)
    }
}
