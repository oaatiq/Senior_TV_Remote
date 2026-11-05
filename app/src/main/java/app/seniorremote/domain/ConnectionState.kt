package app.seniorremote.domain

/** Connection states, owned exclusively by the connection manager. */
sealed interface ConnectionState {
    /** No TV is selected. Setup or the chooser is shown instead of the remote. */
    data object NotConfigured : ConnectionState

    /** App is not visible; no session is being maintained. Never shown to the senior. */
    data object Suspended : ConnectionState

    /** Establishing a session after launch or return to the foreground. */
    data object Connecting : ConnectionState

    /**
     * A usable, fully established session exists with TV [tvId]. [tvAsleep] means the TV itself
     * reported that its screen is off; some TVs stay connected like this, others leave the network.
     */
    data class Ready(val generation: Long, val tvId: String, val tvAsleep: Boolean = false) : ConnectionState

    /** A previously ready session was lost and is being re-established. */
    data object Recovering : ConnectionState

    /** The phone has no Wi-Fi/Ethernet network. */
    data object NetworkUnavailable : ConnectionState

    /** The TV has not answered for a while. Retries continue in the background. */
    data object TvUnreachable : ConnectionState

    /**
     * The TV reported standby and then left the network. Only used when it told us so ourselves:
     * otherwise an unreachable TV is [TvUnreachable], because the reason is not known.
     */
    data object TvOff : ConnectionState

    /** The TV refused our credentials, or identity could not be safely confirmed. */
    data object PairingRequired : ConnectionState

    /** The device cannot be controlled with this app. */
    data class Unsupported(val reason: String) : ConnectionState
}
