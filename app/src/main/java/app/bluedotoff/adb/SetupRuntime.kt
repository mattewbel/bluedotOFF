package app.bluedotoff.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import app.bluedotoff.BootSession
import app.bluedotoff.JournalStore
import app.bluedotoff.core.Reconcile

/** Fixed capabilities only. Test doubles cannot be supplied by an Intent or product UI. */
internal interface SetupDiscoveryAccess:AutoCloseable {
    val error:Int?
    fun candidates(type:String,guid:String?=null):List<LocalAdbDiscovery.Endpoint>
    fun choose(type:String)
}
internal interface SetupRuntime {
    fun initializeOnboarding() {}
    val finalCheckResolved:Boolean get()=true
    fun completeFinalCheck() {}
    fun verifyConnection(port:Int,started:()->Unit,current:()->Boolean)=verifyScan(port,started,current)
    val managementChoiceResolved:Boolean get()=true
    fun skipManagement() {}
    fun enableManagement(port:Int):Unit=throw UnsupportedOperationException()
    val batteryChoiceRequired:Boolean get()=false
    val batteryChoiceResolved:Boolean get()=true
    fun skipBatteryChoice() {}
    val wifiConnected:Boolean get()=true
    val autoBlocked:Boolean get()=false
    fun foregroundUse() {}
    fun keepPairingAlive(required:Boolean):Boolean=true
    val intentionalExit:Boolean get()=false
    fun observeWifi(changed:()->Unit):AutoCloseable?=null
    val developer:Boolean
    val wireless:Boolean
    val paired:Boolean
    val supported:Boolean
    val notifications:Boolean
    val broad:Boolean
    val localNetworkPermissionRequired:Boolean get()=false
    val busy:Boolean
    fun discover(changed:()->Unit):SetupDiscoveryAccess
    fun pair(port:Int,code:ByteArray,current:()->Boolean)
    fun verify(port:Int,started:()->Unit)
    fun verifyScan(port:Int,started:()->Unit,current:()->Boolean):Unit {if(!current())throw java.util.concurrent.CancellationException();verify(port,started)}
    fun verifyScanProgress(port:Int,started:()->Unit,current:()->Boolean,progress:(app.bluedotoff.core.ScanProgress)->Unit)=verifyScan(port,started,current)
    fun cancelPairing()
    fun debugReadOnly(port:Int,interrupt:Boolean):Unit=throw UnsupportedOperationException()
    fun debugBatchReadOnly():Unit=throw UnsupportedOperationException()
    /** 1.2.1 PC start: the user's choice, and whether a verified helper started from the PC is attached. */
    val pcStart:Boolean get()=false
    val pcHelper:Boolean get()=false
    fun choosePcStart(on:Boolean) {}
}
internal class AndroidSetupRuntime(private val context:Context,private var wirelessSetup:Boolean=false):SetupRuntime {
    override fun keepPairingAlive(required:Boolean):Boolean {
        if(required)return if(app.bluedotoff.ForegroundState.active)PairingLifetimeService.ensure(context) else PairingLifetimeService.isRequested
        PairingLifetimeService.stop(context);return true
    }
    @Volatile private var ending=false
    private val ui get()=context.getSharedPreferences("ui-state",0)
    override fun initializeOnboarding(){
        // Existing paired users have completed the old flow; fresh sessions record
        // false before pairing. Clearing app data also clears this choice.
        if(!ui.contains("finalCheckResolved"))check(ui.edit().putBoolean("finalCheckResolved",paired&&app.bluedotoff.ScopePreferences(context).resolved).commit())
        if(!ui.contains("managementChoiceResolved"))check(ui.edit().putBoolean("managementChoiceResolved",paired||WirelessControls(context).consent).commit())
    }
    override val finalCheckResolved get()=ui.getBoolean("finalCheckResolved",false)
    override val batteryChoiceRequired get()=app.bluedotoff.BackgroundWatch.enabled(context)
    // Existing users are not sent through onboarding again. Fresh installs persist false
    // for finalCheckResolved before pairing; only consent or an explicit skip resolves this step.
    override val batteryChoiceResolved get()=ui.getBoolean("batteryChoiceResolved",finalCheckResolved)||app.bluedotoff.BatteryOptimization.unrestricted(context)==true
    override fun skipBatteryChoice(){check(ui.edit().putBoolean("batteryChoiceResolved",true).commit())}
    override fun completeFinalCheck(){check(ui.edit().putBoolean("finalCheckResolved",true).commit())}
    override fun verifyConnection(port:Int,started:()->Unit,current:()->Boolean){
        if(!current())throw java.util.concurrent.CancellationException()
        WirelessWork.run(context,true,port,beforeDisable={ending=true}){backend->
            started();backend.verify();JournalStore(context).entries()
            if(!current())throw java.util.concurrent.CancellationException()
        }
    }
    override val managementChoiceResolved get()=ui.getBoolean("managementChoiceResolved",false)||WirelessControls(context).consent
    override fun skipManagement(){check(ui.edit().putBoolean("managementChoiceResolved",true).commit())}
    override fun enableManagement(port:Int){
        check(app.bluedotoff.ForegroundState.active&&developer&&wifiConnected&&paired&&wireless&&!busy)
        val controls=WirelessControls(context)
        controls.setConsent(true) // Only the explicit final-step action calls this.
        try {
            WirelessWork.run(context,true,port,beforeDisable={ending=true},onCleanup={notice->
                if(notice.isNotBlank())ui.edit().putString("setupNotice",notice).apply()
            }){backend->if(!controls.granted)backend.grantSelfAutomaticPermission()}
            check(controls.granted)
            skipManagement()
        }catch(error:Exception){controls.setConsent(false);throw error}
    }
    override val intentionalExit get()=ending
    override fun observeWifi(changed:()->Unit)=app.bluedotoff.WifiConnection.observe(context,changed)
    override val wifiConnected get()=app.bluedotoff.WifiConnection.connected(context)
    // PC start never uses wireless debugging, so setup does not turn it on there (Codex R2).
    override fun foregroundUse(){ending=false;if(busy||pcStart)return;val controls=WirelessControls(context);if(controls.mayStart(app.bluedotoff.ForegroundState.active))controls.enableWirelessDebugging()}
    override val developer get()=Settings.Global.getInt(context.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1
    override val wireless get()=Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0)==1
    override val autoBlocked get()=AutoBlocker.suspected&&!wireless
    override val paired get()=context.getSharedPreferences("local-adb",0).getString("pairedGuid",null)!=null&&AdbHostIdentity(context).exists
    override val supported get()=app.bluedotoff.DeviceSupport.basic()
    override val notifications get()=(Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
    override val broad get()=Build.VERSION.SDK_INT>=37&&context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==PackageManager.PERMISSION_GRANTED
    override val localNetworkPermissionRequired get()=Build.VERSION.SDK_INT>=37
    override val busy get()=LocalAdbSession.busy
    override fun discover(changed:()->Unit):SetupDiscoveryAccess {
        val discovery=LocalAdbDiscovery(context,changed).also{it.start()}
        return object:SetupDiscoveryAccess {
            override val error get()=discovery.error
            override fun candidates(type:String,guid:String?)=discovery.candidates(type,guid)
            override fun choose(type:String)=discovery.choose(type)
            override fun close()=discovery.close()
        }
    }
    override fun pair(port:Int,code:ByteArray,current:()->Boolean){LocalAdbSession.pair(context,port,code,current)}
    override fun verify(port:Int,started:()->Unit)=verifyScan(port,started){true}
    override fun verifyScan(port:Int,started:()->Unit,current:()->Boolean)=verifyScanProgress(port,started,current){}
    override fun verifyScanProgress(port:Int,started:()->Unit,current:()->Boolean,progress:(app.bluedotoff.core.ScanProgress)->Unit){
        val cache=app.bluedotoff.StartupScanHandoff.cache
        val revision=cache.revision()
        val valid={current()&&cache.revision()==revision}
        // Publish only after WirelessWork confirms helper EXIT/socket close.
        val prepared=WirelessWork.run(context,true,port,beforeDisable={ending=true},onCleanup={notice->
            if(notice.isNotBlank())ui.edit().putString("setupNotice",notice).apply()
        }){backend->
            started()
            val store=JournalStore(context);val boot=BootSession.current(context)
            app.bluedotoff.core.StartupScan.read(backend::verify,store::entries,
                {capture->backend.discover(checkCancelled={if(!valid())throw java.util.concurrent.CancellationException()},onSnapshot=capture,onProgress={done,total->progress(app.bluedotoff.core.ScanProgress(done,total))})},
                // An uninstalled registered app reads as absent (MISSING_TARGET), as on Home; it must not fail setup (Fold7 2026-10-20).
                {pkg->try{backend.read(pkg)}catch(_:android.content.pm.PackageManager.NameNotFoundException){null}},{snapshots,firmware->
                    check(store.transform{e->if(!valid())throw java.util.concurrent.CancellationException();Reconcile.read(e,snapshots[e.identity.packageName],firmware.key,boot,snapshots)})
                    store.entries()
                },boot,valid)
        }
        if(!valid())throw java.util.concurrent.CancellationException()
        check(cache.publish(revision,prepared)){"앱 설치 상태가 바뀌었어요. 다시 확인해주세요."}
    }
    override fun cancelPairing()=LocalAdbSession.cancelPairing()
    override val pcStart get()=!wirelessSetup&&PcStart.chosen(context)
    override val pcHelper get()=WatchClient.available(context)
    override fun choosePcStart(on:Boolean){wirelessSetup=false;PcStart.choose(context,on)}
    override fun debugReadOnly(port:Int,interrupt:Boolean) {
        check(app.bluedotoff.BuildConfig.DEBUG)
        if(interrupt)LocalAdbSession.interruptionProof(context,port)
        LocalAdbSession.proof(context,port)
    }
    override fun debugBatchReadOnly() {
        check(app.bluedotoff.BuildConfig.DEBUG)
        var before:app.bluedotoff.core.Snapshot?=null
        WirelessWork.runBatch(context,true){session->
            repeat(7){session.run{backend->
                backend.verify()
                val current=backend.read("com.google.android.apps.maps")
                before?.let{check(it.invariantEquals(current,it.bit))}
                before=current
            }}
        }
    }
}
