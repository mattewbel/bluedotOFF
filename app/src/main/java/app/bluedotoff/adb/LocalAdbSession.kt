package app.bluedotoff.adb

import android.content.Context
import android.os.SystemClock
import java.io.File
import org.json.JSONObject

/** Shared ownership across Activity and notification; no concurrent pairing/helper sessions. */
object LocalAdbSession {
    private val lock=Any()
    class CloseReceipt {
        @Volatile var helperExited=false;internal set
        @Volatile var streamClosed=false;internal set
        @Volatile var socketClosed=false;internal set
        /** The authenticated connection was kept open (all helpers exited) for the next task on a visible screen. */
        @Volatile var kept=false;internal set
    }
    // Android shows "무선 디버깅 연결됨" for every new ADB connection, so a visible main screen keeps one authenticated
    // connection between tasks. Only an idle connection whose helpers all exited is kept; closeHeld() ends it when
    // the app is left, before wireless debugging is turned off.
    private var held:LocalAdbConnection?=null
    private var heldPort=-1
    val holdsConnection get()=synchronized(lock){held!=null}
    fun closeHeld()=synchronized(lock){check(!busy);held?.let{runCatching{it.close()}};held=null;heldPort=-1}
    @Volatile var busy=false; private set
    @Volatile var generation=0L; private set
    @Volatile private var pairingHandle=0L
    private fun <T> stage(state:app.bluedotoff.core.LocalAdbState,block:()->T):T=try{block()}catch(e:HelperOperationRejectedException){throw e}catch(e:Exception){throw LocalAdbStageException(state,e)}
    private fun open(port:Int,key:AdbHostIdentity.Material)=stage(app.bluedotoff.core.LocalAdbState.ADB_CONNECTION_FAILED){NativeAdb.open(port,key.privatePem,key.certificatePem)}
    fun cancelPairing(){val h=pairingHandle;if(h!=0L)NativeAdb.close(h)}
    fun <T> exclusive(block:()->T):T = synchronized(lock) {
        check(!busy){"Local ADB session already active"};busy=true;generation++
        try{block()}finally{busy=false}
    }
    fun pair(context:Context,port:Int,code:ByteArray,current:()->Boolean={true}):String=exclusive {
        try{check(current()){"Pairing preparation expired"};AdbHostIdentity(context).loadOrCreate().use{key->
            val h=NativeAdb.open(port,key.privatePem,key.certificatePem)
            pairingHandle=h
            val started=SystemClock.elapsedRealtime()
            try{NativeAdb.pair(h,code,key.publicKey).also{guid->check(current()){"Pairing preparation cancelled"};check(context.getSharedPreferences("local-adb",0).edit().putString("pairedGuid",guid).commit());File(context.noBackupFilesDir,"local-adb-pair-result.json").writeText(JSONObject().put("success",true).put("targetSdk",context.applicationInfo.targetSdkVersion).put("time",System.currentTimeMillis()).put("pairingMs",SystemClock.elapsedRealtime()-started).put("socketDestination","127.0.0.1").put("targetMetadataWrites",0).toString())}}finally{pairingHandle=0L;NativeAdb.close(h)}
        }}finally{code.fill(0)}
    }
    fun <T> withBackend(context:Context,port:Int,receipt:CloseReceipt=CloseReceipt(),block:(LocalAdbBackend)->T):T =
        withConnection(context,port,receipt){it.run(CloseReceipt(),block)}

    /** Authentication is leased to foreground work only: kept between tasks while a main screen is visible, never for a
     *  background service. */
    internal fun <T> withConnection(context:Context,port:Int,receipt:CloseReceipt,keep:Boolean=false,block:(HelperConnection)->T):T=exclusive {
        requirePaired(context)
        val reuse=held?.takeIf{heldPort==port};if(held!=null&&reuse==null)closeHeldLocked();held=null;heldPort=-1
        if(reuse!=null){
            // A connection that died meanwhile fails at the first helper start, before anything ran: reconnect then.
            var started=false
            android.util.Log.i("bluedotOFF","connection: reusing the kept connection")
            try{return@exclusive useAuthenticated(context,reuse,receipt,"reused",keep,port,{started=it},block)}
            catch(e:Exception){if(started)throw e;android.util.Log.i("bluedotOFF","connection: kept connection unusable, reconnecting");receipt.helperExited=false;receipt.streamClosed=false;receipt.socketClosed=false;receipt.kept=false}
        }
        val id=java.util.UUID.randomUUID().toString()
        val start=SystemClock.elapsedRealtime()
        val event=JSONObject().put("connectionId",id).put("time",System.currentTimeMillis()).put("port",port).put("targetSdk",context.applicationInfo.targetSdkVersion).put("transport","local-adb").put("socketDestination","127.0.0.1").put("pairedKeyReused",true)
        try {
            AdbHostIdentity(context).loadOrCreate().use{key->
                LocalAdbConnection(open(port,key)).let{c->
                    try{stage(app.bluedotoff.core.LocalAdbState.ADB_AUTHENTICATION_FAILED){c.authenticate()}}catch(e:Exception){runCatching{c.close()};throw e};event.put("reconnectMs",SystemClock.elapsedRealtime()-start)
                    useAuthenticated(context,c,receipt,id,keep,port,{},block).also{event.put("completed",true)}
                }
            }
        }catch(e:Exception){event.put("completed",false).put("errorClass",e.javaClass.simpleName);throw e}
        finally {
            event.put("closed",receipt.socketClosed).put("helperExited",receipt.helperExited).put("totalMs",SystemClock.elapsedRealtime()-start)
            appendEvent(context,"local-adb-connections.jsonl",event)
        }
    }

    private fun closeHeldLocked(){held?.let{runCatching{it.close()}}}
    internal fun <T> useAuthenticated(context:Context,c:LocalAdbConnection,receipt:CloseReceipt,id:String,keep:Boolean=false,port:Int=-1,onStarted:(Boolean)->Unit={},block:(HelperConnection)->T):T {
        check(busy)
        val helpers=HelperConnection(context,c,id)
        var ok=false
        try{return block(helpers).also{ok=true}}finally{
            onStarted(helpers.anyStarted)
            receipt.helperExited=helpers.orderly
            receipt.streamClosed=helpers.orderly
            if(keep&&ok&&helpers.orderly){held=c;heldPort=port;receipt.kept=true}
            // This final physical close must happen before WirelessWork considers automatic OFF.
            else runCatching{c.close();receipt.socketClosed=true}
        }
    }

    internal class HelperConnection(private val context:Context,private val c:LocalAdbConnection,private val connectionId:String) {
        private var attempted=false
        private var failed=false
        internal var orderly=false;private set
        internal var anyStarted=false;private set
        fun <T> run(receipt:CloseReceipt,block:(LocalAdbBackend)->T):T {
            check(busy&&!failed&&(!attempted||orderly)){"Previous helper did not finish safely"}
            attempted=true;orderly=false;generation++
            val id=java.util.UUID.randomUUID().toString()
            val start=SystemClock.elapsedRealtime()
            val event=JSONObject().put("sessionId",id).put("connectionId",connectionId).put("generation",generation).put("time",System.currentTimeMillis()).put("transport","local-adb")
            var started=false
            try {
                stage(app.bluedotoff.core.LocalAdbState.HELPER_START_FAILED){c.startHelper(context.applicationInfo.sourceDir)};started=true;anyStarted=true
                val profile=stage(app.bluedotoff.core.LocalAdbState.HELPER_PROTOCOL_FAILED){c.exchange(JSONObject().put("op","PROFILE"))};rememberRegion(context,profile)
                event.put("helperStartupAndProfileMs",SystemClock.elapsedRealtime()-start).put("helperPid",profile.getInt("pid")).put("shellUid",profile.getInt("uid")).put("selinux",profile.getString("selinux")).put("profileId",profile.getString("profileId"))
                val operationStart=SystemClock.elapsedRealtime();app.bluedotoff.Perf.reset()
                val result=block(LocalAdbBackend(context,c,profile,id))
                event.put("batchMs",SystemClock.elapsedRealtime()-operationStart)
                c.finishHelper();receipt.helperExited=true;receipt.streamClosed=true
                orderly=true;event.put("completed",true)
                return result
            }catch(e:Exception){failed=true;event.put("completed",false).put("errorClass",e.javaClass.simpleName).put("causeClass",e.cause?.javaClass?.simpleName?:JSONObject.NULL);if(app.bluedotoff.BuildConfig.DEBUG)event.put("causeDetail",e.cause?.message?:JSONObject.NULL);throw e}
            finally {
                // Invalidate this backend even while the authenticated transport still has an owner.
                generation++
                if(started&&!receipt.streamClosed)runCatching{c.finishHelper();receipt.helperExited=true;receipt.streamClosed=true}.onFailure{event.put("helperCloseError",it.javaClass.simpleName)}
                event.put("helperExited",receipt.helperExited).put("streamClosed",receipt.streamClosed).put("closed",false).put("totalMs",SystemClock.elapsedRealtime()-start)
                appendEvent(context,"local-adb-batches.jsonl",event)
                // Timing only (no package names), readable over USB for speed measurements.
                android.util.Log.i("bluedotOFF","perf: ${app.bluedotoff.Perf.summary()}")
                android.util.Log.i("bluedotOFF","helper: startup=${event.optLong("helperStartupAndProfileMs",-1)}ms work=${event.optLong("batchMs",-1)}ms total=${event.optLong("totalMs",-1)}ms completed=${event.optBoolean("completed")}")
            }
        }
    }
    /** US CSC models are allowed with a warning on Home; see MinimalPrivilegedHelper.verify(). */
    // Also kept for "진단 정보 복사" (One UI version and country only; nothing about the user's apps).
    /** True once a helper confirmed this device's compatibility in this app process (for "진단 정보 복사"). */
    @Volatile var compatibilityConfirmed=false;private set
    internal fun rememberRegion(context:Context,profile:JSONObject){if(profile.optBoolean("abiVerified"))compatibilityConfirmed=true;context.getSharedPreferences("ui-state",0).edit().putBoolean("usModel",profile.optString("country")=="US").putString("deviceOneUi",profile.optString("oneUi")).putString("deviceCountry",profile.optString("country")).apply()}
    private fun appendEvent(context:Context,name:String,event:JSONObject){
        val f=File(context.noBackupFilesDir,name)
        // Diagnostics only: never let a storage error replace the result of the work it describes.
        try{if(f.length()>1_000_000)f.writeText("");f.appendText(event.toString()+"\n")}catch(e:java.io.IOException){android.util.Log.w("bluedotOFF","diagnostic log skipped: ${e.javaClass.simpleName}")}
    }
    private fun requirePaired(context:Context){
        check(android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1){"DEVELOPER_OPTIONS_OFF"}
        check(android.provider.Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0)==1){"WIRELESS_DEBUGGING_OFF"}
        check(context.getSharedPreferences("local-adb",0).getString("pairedGuid",null)!=null&&AdbHostIdentity(context).exists){"NOT_PAIRED"}
    }
    fun interruptionProof(context:Context,port:Int):JSONObject {
        check(app.bluedotoff.BuildConfig.DEBUG)
        var observed:JSONObject?=null
        val failure=runCatching{withBackend(context,port){backend->observed=backend.interruptionReadOnly()}}.exceptionOrNull()
        // This diagnostic deliberately closes the socket before helper teardown. Only an
        // already verified read-only interruption result may survive that expected error.
        val result=observed?:throw checkNotNull(failure)
        File(context.noBackupFilesDir,"local-adb-interruption-proof.json").writeText(result.toString())
        return result
    }
    fun proof(context:Context,port:Int):JSONObject=exclusive {
        requirePaired(context)
        val start=SystemClock.elapsedRealtime()
        AdbHostIdentity(context).loadOrCreate().use{key->
            LocalAdbConnection(open(port,key)).use{c->
                stage(app.bluedotoff.core.LocalAdbState.ADB_AUTHENTICATION_FAILED){c.authenticate()};val authenticated=SystemClock.elapsedRealtime()
                stage(app.bluedotoff.core.LocalAdbState.HELPER_START_FAILED){c.startHelper(context.applicationInfo.sourceDir)}
                val health=stage(app.bluedotoff.core.LocalAdbState.HELPER_PROTOCOL_FAILED){c.exchange(JSONObject().put("op","HEALTH"))};val helper=SystemClock.elapsedRealtime()
                val profile=stage(app.bluedotoff.core.LocalAdbState.HELPER_PROTOCOL_FAILED){c.exchange(JSONObject().put("op","PROFILE"))};rememberRegion(context,profile)
                val packages=c.exchange(JSONObject().put("op","GET_PACKAGES"))
                val target="net.daum.android.map"
                val info=c.exchange(JSONObject().put("op","GET_PACKAGE_INFO").put("package",target).put("flags",134221824L))
                val p=android.os.Parcel.obtain();val bytes=android.util.Base64.decode(info.getString("parcel"),android.util.Base64.NO_WRAP)
                val pi=try{p.unmarshall(bytes,0,bytes.size);p.setDataPosition(0);p.readException();p.readTypedObject(android.content.pm.PackageInfo.CREATOR)?:error("Target unavailable")}finally{p.recycle()}
                val uid=pi.applicationInfo!!.uid
                val peers=c.exchange(JSONObject().put("op","GET_UID_PEERS").put("uid",uid))
                val flags=c.exchange(JSONObject().put("op","GET_PERMISSION_FLAGS").put("package",target).put("permission","FINE"))
                val mode=c.exchange(JSONObject().put("op","GET_RAW_APP_OPS").put("package",target).put("uid",uid).put("locationOp",1))
                val history=c.exchange(JSONObject().put("op","GET_LOCATION_HISTORY").put("package",target).put("uid",uid))
                val backend=LocalAdbBackend(context,c,profile)
                val firmware=backend.verify()
                val journal=app.bluedotoff.JournalStore(context)
                val bootSession=app.bluedotoff.BootSession.current(context)
                val bootCount=android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT,-1)
                val entries=journal.entries()
                val current=entries.associate{it.identity.packageName to backend.read(it.identity.packageName)}
                val reconciled=entries.map{app.bluedotoff.core.Reconcile.read(it,current[it.identity.packageName],firmware.key,app.bluedotoff.BootSession.current(context),current)}
                check(journal.transform{old->reconciled.single{it.identity.packageName==old.identity.packageName}}){"Read-only journal reconcile failed"}
                val discoveryStart=SystemClock.elapsedRealtime()
                val discovery=backend.discover()
                check(discovery.historyAvailable){"Existing History unavailable"}
                val summary=org.json.JSONArray(discovery.apps.map{a->JSONObject().put("package",a.packageName).put("uid",a.uid).put("block",a.block).put("launcher",a.launcher).put("fineAccess",a.access.fine?:JSONObject.NULL).put("coarseAccess",a.access.coarse?:JSONObject.NULL).put("historyReason",app.bluedotoff.core.HistoryPolicy.displayReason(a,entries.filter{it.enabled}.map{it.identity.packageName}.toSet()).name)})
                JSONObject().put("time",System.currentTimeMillis()).put("transport","local-adb").put("generation",generation).put("health",health).put("profile",profile).put("packages",packages).put("target",target).put("uid",uid).put("identity",info).put("peers",peers).put("fineFlags",flags).put("rawFineMode",mode).put("history",history).put("metadataWrites",0)
                    .put("journal",org.json.JSONArray(reconciled.map{e->JSONObject().put("package",e.identity.packageName).put("originalBit",e.originalBit).put("enabled",e.enabled).put("state",e.state.name)}))
                    .put("discovery",summary).put("historyAvailable",true).put("discoveryMs",SystemClock.elapsedRealtime()-discoveryStart)
                    .put("bootSession",bootSession).put("appBootCount",bootCount)
                    .put("reconnectMs",authenticated-start).put("helperStartupMs",helper-authenticated).put("totalMs",SystemClock.elapsedRealtime()-start)
                    .also{File(context.noBackupFilesDir,"local-adb-proof.json").writeText(it.toString())}
            }
        }
    }
}
