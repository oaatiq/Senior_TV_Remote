package app.seniorremote.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks Wi-Fi/Ethernet networks regardless of which network is the system default, and
 * provides sockets bound to the current one. Home Wi-Fi without internet access is common for
 * a TV-only setup, and Android may then route default traffic over mobile data.
 */
class AndroidNetworkMonitor(context: Context) : NetworkStatusSource, SocketProvider {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val available = LinkedHashMap<Network, Unit>()

    @Volatile
    private var current: Network? = null

    private val _status = MutableStateFlow(NetworkStatus(usable = false, networkId = null))
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(available) { available[network] = Unit }
            publish()
        }

        override fun onLost(network: Network) {
            synchronized(available) { available.remove(network) }
            publish()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (e: RuntimeException) {
            // Without network callbacks, fall back to "usable" with unbound sockets.
            _status.value = NetworkStatus(usable = true, networkId = null)
        }
    }

    override fun create(): Socket {
        val network = current
        return network?.socketFactory?.createSocket() ?: Socket()
    }

    private fun publish() {
        val network = synchronized(available) { available.keys.lastOrNull() }
        current = network
        _status.value = NetworkStatus(usable = network != null, networkId = network?.networkHandle)
    }
}
