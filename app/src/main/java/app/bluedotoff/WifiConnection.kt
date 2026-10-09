package app.bluedotoff

import android.content.Context
import android.net.*

/** Includes a connected Wi-Fi under a VPN; Internet validation and SSID are unnecessary. */
object WifiConnection {
    fun connected(context:Context):Boolean=runCatching {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        manager.allNetworks.any{network->manager.getNetworkCapabilities(network)?.let{
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        }==true&&manager.getLinkProperties(network)?.interfaceName!=null}
    }.getOrDefault(false)
    fun observe(context:Context,changed:()->Unit):AutoCloseable {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        val callback=object:ConnectivityManager.NetworkCallback(){
            override fun onAvailable(network:Network)=changed()
            override fun onLost(network:Network)=changed()
            override fun onCapabilitiesChanged(network:Network,capabilities:NetworkCapabilities)=changed()
        }
        manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),callback)
        return AutoCloseable{runCatching{manager.unregisterNetworkCallback(callback)}}
    }
}
