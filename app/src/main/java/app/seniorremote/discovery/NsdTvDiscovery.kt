package app.seniorremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import app.seniorremote.diagnostics.Diagnostics
import app.seniorremote.domain.TvCandidate
import app.seniorremote.domain.TvDiscovery
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * mDNS discovery of Android TV Remote Service v2 endpoints ("_androidtvremote2._tcp").
 * Resolves services one at a time because older NsdManager versions reject parallel resolves.
 */
class NsdTvDiscovery(
    private val context: Context,
    private val diagnostics: Diagnostics,
) : TvDiscovery {

    override fun discover(): Flow<List<TvCandidate>> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = runCatching { wifi?.createMulticastLock("tv-discovery")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        val found = LinkedHashMap<String, TvCandidate>()
        val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        fun emitAll() {
            trySend(found.values.toList())
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                diagnostics.log("discovery", "Discovery could not start (error $errorCode)")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                toResolve.trySend(info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val removed = synchronized(found) { found.remove(info.serviceName) }
                if (removed != null) synchronized(found) { emitAll() }
            }
        }

        launch {
            for (info in toResolve) {
                val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { resolve(nsd, info) } ?: continue
                val candidate = toCandidate(resolved) ?: continue
                synchronized(found) {
                    found[candidate.serviceName] = candidate
                    emitAll()
                }
            }
        }

        trySend(emptyList())
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: RuntimeException) {
            diagnostics.log("discovery", "Discovery unavailable: ${e.javaClass.simpleName}")
            close()
        }

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            toResolve.close()
            runCatching { lock?.release() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? =
        suspendCancellableCoroutine { cont ->
            try {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (cont.isActive) cont.resume(serviceInfo)
                    }
                })
            } catch (e: RuntimeException) {
                if (cont.isActive) cont.resume(null)
            }
        }

    @Suppress("DEPRECATION")
    private fun toCandidate(info: NsdServiceInfo): TvCandidate? {
        val address: InetAddress = pickAddress(info) ?: return null
        val attributes = runCatching { info.attributes }.getOrNull().orEmpty()
        val deviceId = attributes["bt"]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
        val host = address.hostAddress ?: return null
        val details = buildString {
            append("Address ").append(host)
            deviceId?.let { append(" · ID …").append(it.takeLast(5)) }
        }
        return TvCandidate(
            serviceName = info.serviceName,
            displayName = info.serviceName,
            host = host,
            port = info.port,
            deviceId = deviceId,
            details = details,
        )
    }

    @Suppress("DEPRECATION")
    private fun pickAddress(info: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= 34) {
            val addresses = info.hostAddresses
            return addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull()
        }
        return info.host
    }

    private companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp."
        const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
