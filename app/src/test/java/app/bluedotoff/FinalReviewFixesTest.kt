package app.bluedotoff

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.*
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.*
import app.bluedotoff.core.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** Codex final 1.2.0 review (2026-10-20, `artifacts/review-120-final-20261020/`): its R1-R3 probes, now expecting the fix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class FinalReviewFixesTest {
    private val c get()=ApplicationProvider.getApplicationContext<Application>().also{it.applicationInfo.sourceDir="/data/app/~~fixture/bluedot/base.apk"}
    private val fw=Firmware("samsung",37,"90000","fp","id","model","verified")
    private val id=Identity("demo.location",0,10050,listOf("fixture"),1,1,1)
    private fun entry(state:TargetState)=Entry(id,"Fixture",256,true,state,1,fw.key,"count:1",true,invariantDigest="fixture",settlingVerified=true)
    private class Server:Binder(){
        var requests=0
        init{attachInterface(null,WatchProtocol.DESCRIPTOR)}
        override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean{data.enforceInterface(WatchProtocol.DESCRIPTOR);data.readString()
            if(code==WatchProtocol.REQUEST){requests++;throw SecurityException("No real target")}
            reply!!.writeNoException();reply.writeString(JSONObject().put("configured",true).toString());return true}
    }
    private fun attach(server:Server){
        Settings.Global.putInt(c.contentResolver,Settings.Global.ADB_ENABLED,1);BackgroundWatch.set(c,true)
        val p=JSONObject().put("pid",100).put("uid",2000).put("selinux","u:r:shell:s0").put("abiVerified",true).put("fingerprint",Build.FINGERPRINT).put("oneUi","90000")
            .put("profileId","fixture").put("watchProtocol",1).put("watchId","fixture-id").put("appUpdatedAt",c.packageManager.getPackageInfo(c.packageName,0).lastUpdateTime)
        assertTrue(WatchClient.attach(c,server,p,c.applicationInfo.sourceDir,BuildConfig.VERSION_CODE.toLong()))
    }
    private fun wifi(){
        val cm=c.getSystemService(ConnectivityManager::class.java);val network=checkNotNull(cm.activeNetwork);val caps=NetworkCapabilities()
        NetworkCapabilities::class.java.getMethod("addTransportType",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.TRANSPORT_WIFI)
        NetworkCapabilities::class.java.getMethod("addCapability",Int::class.javaPrimitiveType).invoke(caps,NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        shadowOf(cm).setNetworkCapabilities(network,caps);shadowOf(cm).setLinkProperties(network,LinkProperties().apply{interfaceName="wlan0"});assertTrue(WifiConnection.connected(c))
    }
    @After fun clean(){while(ForegroundState.active)ForegroundState.stopped();BackgroundWatch.set(c,false);WatchClient.stopNow(c);Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",0)}

    /** R1: an interrupted shared-group restore followed by a firmware update keeps its PENDING marker (as single apps do),
     *  so app-open auto re-apply never hides the group again; only SharedGroupEngine.recover settles it. */
    @Test fun anInterruptedGroupKeepsItsMarkerAcrossAFirmwareUpdate(){
        val names=listOf("demo.alpha","demo.beta")
        val snapshots=names.mapIndexed{i,p->Snapshot(Identity(p,0,10129,listOf("fixture$i"),1,1,1),p,names,i==0,i==0,i==0,false,0x300,0x300,0,4,4,false,sharedUserId="demo.shared",coarseDeclared=i==0)}.associateBy{it.identity.packageName}
        val identities=names.map{snapshots.getValue(it).identity};val records=linkedMapOf<String,Entry>();val writes=mutableListOf<Int>()
        for(state in listOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE,TargetState.PENDING_SHOW)){
            val pending=names.map{p->val s=snapshots.getValue(p);Entry(s.identity,p,256,true,state,1,"old-firmware","count:1",true,invariantDigest=s.invariantDigest(),sharedUserId="demo.shared",groupIdentities=identities)}
            val updated=pending.map{Reconcile.read(it,snapshots[it.identity.packageName],fw.key,"count:2",snapshots)}
            assertEquals(pending,updated);assertTrue(UiModel(entries=updated).reapplyTargetPackages.isEmpty())
            if(state==TargetState.PENDING_RESTORE)updated.forEach{records[it.identity.packageName]=it}
        }
        val journal=object:Journal{override fun entries()=records.values.toList();override fun commit(entry:Entry)=error("Whole group required");override fun commitGroup(entries:List<Entry>):Boolean{entries.forEach{records[it.identity.packageName]=it};return true}}
        val port=object:TargetPort{override fun compatibleFirmware()=fw;override fun read(packageName:String)=snapshots.getValue(packageName)
            override fun writeFineBit(packageName:String,originalBit:Int)=error("Whole group required");override fun writeFineGroupChecked(expected:List<Snapshot>,wanted:Int){writes+=wanted}}
        try{SharedGroupEngine(port,journal,{2L},{"count:2"},wait={}).execute(names,true){true};fail("An interrupted group must not be hidden")}catch(_:SafetyException){}
        assertTrue(writes.isEmpty());assertTrue(records.values.all{it.state==TargetState.PENDING_RESTORE})
        // A registered group on the new firmware is still re-applied as before.
        val applied=names.map{p->val s=snapshots.getValue(p);Entry(s.identity,p,256,true,TargetState.APPLIED,1,"old-firmware","count:2",true,invariantDigest=s.invariantDigest(),sharedUserId="demo.shared",groupIdentities=identities,settlingVerified=true)}
        assertTrue(applied.map{Reconcile.read(it,snapshots[it.identity.packageName],fw.key,"count:2",snapshots)}.all{it.state==TargetState.NEEDS_RECHECK_FIRMWARE})
    }
    /** R2: a failed write of the diagnostic setting log no longer fails the switch, so the lease keeps its cleanup. */
    @Test fun aFailedSettingLogDoesNotFailTheWirelessSwitch(){
        wifi();ForegroundState.started();Settings.Global.putInt(c.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,1)
        val controls=WirelessControls(c);controls.setConsent(true);shadowOf(c).grantPermissions(WirelessControls.PERMISSION)
        c.getSharedPreferences("local-adb",0).edit().putString("pairedGuid","fixture-guid").commit();File(c.noBackupFilesDir,"local-adb-identity.enc").writeText("synthetic placeholder, never loaded")
        assertTrue(File(c.noBackupFilesDir,"system-setting-events.jsonl").mkdir())
        assertTrue(controls.enableWirelessDebugging());assertTrue(controls.wireless)
        assertTrue(controls.disableWirelessDebugging());assertFalse(controls.wireless)
    }
    /** R3: turning "re-apply when opened" off while the restart run starts the watch stops it before any app is read. */
    @Test fun aRestartRunStopsWhenAutoReapplyIsTurnedOffMidway(){
        shadowOf(c).grantPermissions(WirelessControls.PERMISSION);WirelessControls(c).setConsent(true)
        val server=Server();attach(server)
        assertTrue(JournalStore(c).commit(entry(TargetState.NEEDS_REAPPLY_BOOT)))
        assertTrue(RestartRecoveryJob.eligible(c))
        val sdk=Build.VERSION.SDK_INT;val maker=Build.MANUFACTURER
        org.robolectric.util.ReflectionHelpers.setStaticField(Build.VERSION::class.java,"SDK_INT",37);org.robolectric.shadows.ShadowBuild.setManufacturer("samsung")
        var checks=0
        val notStopped:()->Boolean={checks++;if(checks==2)c.getSharedPreferences("ui-state",0).edit().putBoolean("autoReapply",false).commit();true}
        try{
            val service=org.robolectric.Robolectric.buildService(RestartRecoveryJob::class.java).create().get()
            RestartRecoveryJob::class.java.declaredMethods.single{it.name=="recover"}.apply{isAccessible=true}.invoke(service,c,notStopped)
            assertTrue(checks>=2);assertEquals(0,server.requests)
            assertEquals(TargetState.NEEDS_REAPPLY_BOOT,JournalStore(c).entries().single().state)
        }finally{
            org.robolectric.util.ReflectionHelpers.setStaticField(Build.VERSION::class.java,"SDK_INT",sdk);org.robolectric.shadows.ShadowBuild.setManufacturer(maker)
            c.getSharedPreferences("ui-state",0).edit().remove("autoReapply").commit()
        }
    }
}
