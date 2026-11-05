package app.seniorremote.net

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether a local network (Wi-Fi or Ethernet) is available. [networkId] changes when the phone
 * moves to a different network object, e.g. after Wi-Fi is switched off and on again.
 */
data class NetworkStatus(val usable: Boolean, val networkId: Long?)

interface NetworkStatusSource {
    val status: StateFlow<NetworkStatus>
}
