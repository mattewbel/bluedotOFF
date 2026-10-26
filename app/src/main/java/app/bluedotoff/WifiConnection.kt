package app.bluedotoff

import android.content.Context
import android.net.*

/** Includes a connected Wi-Fi under a VPN; Internet validation and SSID are unnecessary. */
object WifiConnection {
    private fun wifi(capabilities:NetworkCapabilities?,link:LinkProperties?)=capabilities?.let{
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
    }==true&&link?.interfaceName!=null
    fun connected(context:Context):Boolean=runCatching {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        manager.allNetworks.any{wifi(manager.getNetworkCapabilities(it),manager.getLinkProperties(it))}
    }.getOrDefault(false)
    fun observe(context:Context,changed:(connected:Boolean)->Unit):AutoCloseable {
        val manager=context.getSystemService(ConnectivityManager::class.java)
        // One Wi-Fi connection sends several capability updates (validation, signal): report only a real connect or
        // disconnect, so Home does not start two or three app checks in a row (owner 2026-10-21).
        var last=connected(context)
        // Getters inside a callback can still return the old values (Android NetworkCallback docs; Codex 2026-10-22): what a
        // callback said about its network wins, and a lost network stays gone even while the getters still list it.
        val capabilities=HashMap<Network,NetworkCapabilities>();val links=HashMap<Network,LinkProperties>();val lost=HashSet<Network>()
        val report={
            val now=runCatching{(manager.allNetworks.toSet()+capabilities.keys-lost).any{wifi(capabilities[it]?:manager.getNetworkCapabilities(it),links[it]?:manager.getLinkProperties(it))}}.getOrDefault(false)
            if(now!=last){last=now;changed(now)}
        }
        val callback=object:ConnectivityManager.NetworkCallback(){
            override fun onAvailable(network:Network){lost-=network;report()}
            override fun onLost(network:Network){lost+=network;capabilities-=network;links-=network;report()}
            override fun onCapabilitiesChanged(network:Network,networkCapabilities:NetworkCapabilities){capabilities[network]=networkCapabilities;report()}
            // The interface name can arrive after the capabilities; without this the connect was never reported (Codex 2026-10-22).
            override fun onLinkPropertiesChanged(network:Network,linkProperties:LinkProperties){links[network]=linkProperties;report()}
        }
        manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),callback)
        return AutoCloseable{runCatching{manager.unregisterNetworkCallback(callback)}}
    }
}
