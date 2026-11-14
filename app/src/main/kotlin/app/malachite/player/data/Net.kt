package app.malachite.player.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.malachite.player.MalachiteApp
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Is there an internet connection – so that a search without one says so at once instead of
 * spinning until every service has timed out, and starts by itself when the connection is back.
 */
object Net {
    private fun manager(): ConnectivityManager? =
        MalachiteApp.instance.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** True when a network with internet access is active. If the system cannot tell – true (just try). */
    fun online(): Boolean = runCatching {
        val cm = manager() ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    /** Returns when there is a connection (at once if there is one already). Can be cancelled. */
    suspend fun awaitOnline() {
        if (online()) return
        val cm = manager() ?: return
        suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { cm.unregisterNetworkCallback(this) }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
            if (!registered) { if (cont.isActive) cont.resume(Unit); return@suspendCancellableCoroutine }
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
        }
    }

    /**
     * Runs [block] when there is a connection. While it waits, [waiting] is told so (the screen
     * shows "no internet, the search will start by itself").
     */
    suspend fun <T> whenOnline(waiting: (Boolean) -> Unit, block: suspend () -> T): T {
        if (!online()) {
            waiting(true)
            try { awaitOnline() } finally { waiting(false) }
        }
        return block()
    }
}
