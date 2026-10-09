package app.bluedotoff.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.nsd.DiscoveryRequest
import android.os.Build
import android.os.PatternMatcher
import java.net.NetworkInterface

/** Discovery supplies a port; the transport itself connects exclusively to loopback. */
class LocalAdbDiscovery(private val context:Context,private val changed:()->Unit) : AutoCloseable {
    companion object { const val PAIR="_adb-tls-pairing._tcp."; const val CONNECT="_adb-tls-connect._tcp." }
    data class Endpoint(val name:String,val port:Int,val network:Long=0,val epoch:Long=0)
    private val manager=runCatching{context.getSystemService(NsdManager::class.java)}.getOrNull()
    private val callbacks=java.util.concurrent.ConcurrentHashMap<String,NsdManager.ServiceInfoCallback>()
    private var endpointEpoch=0L
    private fun network(info:NsdServiceInfo)=if(Build.VERSION.SDK_INT>=37)info.network?.networkHandle?:0L else 0L
    private fun key(type:String,info:NsdServiceInfo)=type+"|"+info.serviceName+"|"+network(info)
    private val found=mutableMapOf<String,Pair<String,Endpoint>>()
    @Volatile private var closed=false
    @Volatile var error:Int?=null;private set
    @Volatile var pickerType:String?=null;private set
    private fun ownAddress(info:NsdServiceInfo):Boolean {
        if(Build.VERSION.SDK_INT<37)return false
        val own=NetworkInterface.getNetworkInterfaces().toList().flatMap{it.inetAddresses.toList()}.toSet()
        return info.hostAddresses.isNotEmpty()&&info.hostAddresses.all{it.isLoopbackAddress||it in own}
    }
    @Synchronized fun candidates(type:String,guid:String?=null):List<Endpoint> = found.values.filter{it.first==type&&(guid==null||it.second.name==guid)}.map{it.second}.distinct()
    private fun audit(stage:String,type:String,code:Int?=null,flags:Long?=null,arrivalNanos:Long=android.os.SystemClock.elapsedRealtimeNanos()){
        if(!app.bluedotoff.BuildConfig.DEBUG)return
        val f=java.io.File(context.noBackupFilesDir,"local-adb-nsd-audit.jsonl")
        if(f.length()>100_000)f.writeText("")
        f.appendText(org.json.JSONObject().put("targetSdk",context.applicationInfo.targetSdkVersion).put("serviceType",type).put("stage",stage).put("callbackNanos",arrivalNanos).put("processedNanos",android.os.SystemClock.elapsedRealtimeNanos()).put("error",code).put("flags",flags).put("broadPermissionGranted",context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==android.content.pm.PackageManager.PERMISSION_GRANTED).toString()+"\n")
    }
    private fun stop(type:String){callbacks.remove(type)?.let{if(Build.VERSION.SDK_INT>=37)runCatching{manager?.unregisterServiceInfoCallback(it)}};synchronized(this){found.entries.removeAll{it.value.first==type}}}
    private fun register(type:String,flags:Long){
        val manager=manager?:return
        if(closed||Build.VERSION.SDK_INT<37)return
        require(type==PAIR||type==CONNECT)
        stop(type);error=null
        val request=DiscoveryRequest.Builder(type).setFlags(flags).apply{
            if(type==CONNECT)context.getSharedPreferences("local-adb",0).getString("pairedGuid",null)?.let{setServiceNameFilter(PatternMatcher(it,PatternMatcher.PATTERN_LITERAL))}
        }.build()
        val cb=object:NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(e:Int){if(callbacks[type]!==this)return;callbacks.remove(type);error=e;audit("failed",type,e,flags);if(pickerType==type)pickerType=null;changed()}
            override fun onServiceInfoCallbackUnregistered(){if(callbacks[type]!==this)return;callbacks.remove(type);synchronized(this@LocalAdbDiscovery){found.entries.removeAll{it.value.first==type}};if(pickerType==type)pickerType=null;changed()}
            override fun onServiceLost(){if(callbacks[type]!==this)return;synchronized(this@LocalAdbDiscovery){found.entries.removeAll{it.value.first==type}};changed()}
            override fun onServiceLost(s:NsdServiceInfo){if(callbacks[type]!==this)return;synchronized(this@LocalAdbDiscovery){found.remove(key(type,s))};audit("service-lost",type,flags=flags);changed()}
            override fun onServiceUpdated(s:NsdServiceInfo){
                val arrival=android.os.SystemClock.elapsedRealtimeNanos()
                if(closed||callbacks[type]!==this||s.port !in 1..65535||!ownAddress(s))return
                synchronized(this@LocalAdbDiscovery){val k=key(type,s);if(k !in found&&found.size>=32)return
                    val old=found[k]?.second
                    found[k]=type to Endpoint(s.serviceName,s.port,network(s),old?.epoch?:++endpointEpoch)}
                if(pickerType==type)pickerType=null
                audit("resolved-own-device",type,flags=flags,arrivalNanos=arrival);changed()
            }
        }
        callbacks[type]=cb
        runCatching{manager.registerServiceInfoCallback(request,context.mainExecutor,cb)}.onFailure{callbacks.remove(type,cb);error=-1;audit("exception",type,-1,flags);pickerType=null;changed()}
    }
    fun start(){
        if(Build.VERSION.SDK_INT<37)return
        val broad=context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==android.content.pm.PackageManager.PERMISSION_GRANTED
        for(type in listOf(PAIR,CONNECT))register(type,if(broad)DiscoveryRequest.FLAG_NO_PICKER else DiscoveryRequest.FLAG_USER_APPROVED_ONLY)
    }
    /** Only an explicit user action opens the system choice. */
    fun choose(type:String){if(Build.VERSION.SDK_INT<37||pickerType!=null||closed)return;pickerType=type;register(type,DiscoveryRequest.FLAG_SHOW_PICKER)}
    override fun close(){closed=true;pickerType=null;callbacks.keys.toList().forEach(::stop);synchronized(this){found.clear()}}
}
