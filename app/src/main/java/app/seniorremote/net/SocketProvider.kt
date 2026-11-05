package app.seniorremote.net

import java.io.Closeable
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Creates unconnected sockets. On Android the sockets are bound to the home Wi-Fi/Ethernet
 * network, so control traffic still reaches the TV when Wi-Fi has no internet access and the
 * system default network is mobile data.
 */
fun interface SocketProvider {
    fun create(): Socket
}

/**
 * Runs blocking socket I/O on the IO dispatcher and closes [resource] if the caller is
 * cancelled, which is the only reliable way to unblock java.net socket calls.
 */
internal suspend fun <T> closeOnCancel(resource: Closeable, block: () -> T): T = coroutineScope {
    val work = async(Dispatchers.IO) { block() }
    try {
        work.await()
    } catch (e: CancellationException) {
        resource.closeQuietly()
        throw e
    }
}

internal fun Closeable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}
