package app.bluedotoff.adb

import android.content.Context
import android.os.Build
import android.os.Parcel
import android.util.Base64
import app.bluedotoff.BinderBackend
import app.bluedotoff.core.Firmware
import app.bluedotoff.core.Snapshot
import org.json.JSONObject

/** Reuses test.6 discovery/snapshot/History logic, replacing only its privileged transaction transport. */
class LocalAdbBackend(private val context:Context,private val connection:LocalAdbConnection,private val proof:JSONObject,private val sessionId:String=java.util.UUID.randomUUID().toString()):BinderBackend(context) {
    override val applicationScope get()=app.bluedotoff.ScopePreferences(context).current
    override val indicatorMethodsVerified get()=proof.getBoolean("indicatorAbiVerified")
    private val generation=LocalAdbSession.generation
    private var live=true
    private fun ready(){check(live&&generation==LocalAdbSession.generation&&LocalAdbSession.busy){"ADB session generation changed"};app.bluedotoff.core.LocalAdbPolicy.validateShell(proof.getInt("uid"),proof.getString("selinux"));app.bluedotoff.core.LocalAdbPolicy.validateProfile(proof.getBoolean("abiVerified"),proof.getBoolean("historyAbiVerified"),proof.getBoolean("indicatorAbiVerified"),proof.getString("fingerprint"),Build.FINGERPRINT)}
    fun grantSelfAutomaticPermission(){ready();check(app.bluedotoff.ForegroundState.active&&WirelessControls(context).consent);audit(JSONObject().put("category","SELF_PERMISSION").put("phase","self_grant_attempt"));check(connection.exchange(JSONObject().put("op","GRANT_SELF_WIRELESS_PERMISSION")).getBoolean("granted"));audit(JSONObject().put("category","SELF_PERMISSION").put("phase","self_grant_verified"))}
    fun revokeSelfAutomaticPermission(){ready();check(!WirelessControls(context).consent);audit(JSONObject().put("category","SELF_PERMISSION").put("phase","self_revoke_attempt"));check(!connection.exchange(JSONObject().put("op","REVOKE_SELF_WIRELESS_PERMISSION")).getBoolean("granted"));audit(JSONObject().put("category","SELF_PERMISSION").put("phase","self_revoke_verified"))}
    override fun verify():Firmware {ready();return Firmware(Build.MANUFACTURER,Build.VERSION.SDK_INT,proof.getString("oneUi"),Build.FINGERPRINT,Build.ID,Build.MODEL,proof.getString("profileId"))}
    override fun compatibleFirmware():Firmware=verify()
    override fun liveShellServer():Boolean=live&&generation==LocalAdbSession.generation&&android.provider.Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0)==1
    override fun recordVerification(phase:String,s:Snapshot,wrote:Boolean){
        check(!wrote||app.bluedotoff.BuildConfig.LOCAL_WRITES_ENABLED){"Read-only backend cannot record a setter"}
        super.recordVerification(phase,s,wrote)
        audit(JSONObject().put("phase",phase).put("package",s.identity.packageName).put("uid",s.identity.uid).put("fineFlags",s.fineFlags).put("coarseFlags",s.coarseFlags).put("setterPreviouslyCalled",wrote))
    }
    private fun audit(event:JSONObject){
        if(!event.has("category"))event.put("category","TARGET_METADATA")
        val file=java.io.File(context.noBackupFilesDir,"local-adb-operation-events.jsonl")
        if(file.length()>1_000_000)file.writeText("")
        file.appendText(event.put("time",System.currentTimeMillis()).put("transport","local-adb").put("sessionId",sessionId).put("helperPid",proof.getInt("pid")).put("generation",generation).put("shellUid",proof.getInt("uid")).put("selinux",proof.getString("selinux")).put("profileId",proof.getString("profileId")).toString()+"\n")
    }
    override fun writeFineBit(packageName:String,originalBit:Int):Unit=error("Phase 7 read-only acceptance: target writes disabled")
    /** Debug acceptance only: actual socket close, getter failure, zero target mutation. */
    internal fun interruptionReadOnly():JSONObject {
        check(app.bluedotoff.BuildConfig.DEBUG);ready()
        val before=read("net.daum.android.map")
        connection.close()
        val failure=runCatching{read("net.daum.android.map")}.exceptionOrNull()
        check(failure!=null&&!liveShellServer()){"Closed wire cannot remain ready"}
        return JSONObject().put("targetMetadataWrites",0).put("actualSocketClosed",true).put("getterFailed",true).put("backendNotReady",true).put("originalBit",before.bit).put("shellUid",proof.getInt("uid")).put("helperPid",proof.getInt("pid")).put("sessionId",sessionId).put("errorClass",failure.javaClass.simpleName)
    }
    override fun writeFineBitChecked(expected:Snapshot,wanted:Int){
        check(app.bluedotoff.BuildConfig.LOCAL_WRITES_ENABLED){"Read-only acceptance build"};ready()
        val fresh=read(expected.identity.packageName)
        if(expected.peers!=fresh.peers||expected.sharedUserId!=fresh.sharedUserId)throw app.bluedotoff.core.OwnershipChangedException()
        if(!expected.invariantEquals(fresh,expected.bit))throw app.bluedotoff.core.SafetyException("Fresh local preflight changed")
        val identity=app.bluedotoff.core.LocalWriteGuard.identityDigest(expected.identity)
        val invariant=expected.invariantDigest()
        app.bluedotoff.core.LocalWriteGuard.validate(fresh,identity,invariant,expected.fineFlags,wanted)
        val r=JSONObject().put("op","UPDATE_FINE_SENSITIVITY_BIT").put("package",expected.identity.packageName).put("identityDigest",identity).put("invariantDigest",invariant).put("fineFlags",expected.fineFlags).put("value",wanted).put("scope",expected.applicationScope.level)
        audit(JSONObject().put("phase","setter_attempt").put("operation",r.getString("op")).put("package",expected.identity.packageName).put("permission",FINE).put("mask",256).put("value",wanted).put("user",0).put("beforeFineFlags",fresh.fineFlags))
        try{
            val result=connection.exchange(r)
            check(result.getString("method")=="updatePermissionFlags"&&result.getString("descriptor")=="android.permission.IPermissionManager"&&result.getInt("mask")==256&&result.getInt("value")==wanted&&result.getInt("user")==0&&result.getString("package")==expected.identity.packageName&&result.getInt("beforeFineFlags")==fresh.fineFlags)
            audit(result.put("phase","setter_return"))
        }catch(e:Exception){if(e !is HelperOperationRejectedException)live=false;throw e}
    }
    override fun writeFineGroupChecked(expected:List<Snapshot>,wanted:Int){
        check(app.bluedotoff.BuildConfig.LOCAL_WRITES_ENABLED);ready()
        require(wanted==0||wanted==256)
        app.bluedotoff.core.SharedGroupPolicy.validate(expected)
        val fresh=expected.map{read(it.identity.packageName)}
        if(!app.bluedotoff.core.SharedGroupPolicy.ownership(expected,fresh))throw app.bluedotoff.core.OwnershipChangedException()
        if(!app.bluedotoff.core.SharedGroupPolicy.same(expected,fresh,expected.first().bit))throw app.bluedotoff.core.SafetyException("공유 앱의 쓰기 직전 상태가 바뀌었습니다.")
        val members=org.json.JSONArray(expected.map{s->JSONObject().put("package",s.identity.packageName).put("identityDigest",app.bluedotoff.core.LocalWriteGuard.identityDigest(s.identity)).put("invariantDigest",s.invariantDigest()).put("fineFlags",s.fineFlags)})
        val request=JSONObject().put("op","UPDATE_SHARED_FINE_SENSITIVITY_BIT").put("members",members).put("value",wanted).put("scope",expected.first().applicationScope.level)
        val representative=app.bluedotoff.core.SharedGroupPolicy.representative(expected).identity.packageName
        audit(JSONObject().put("phase","setter_attempt").put("operation",request.getString("op")).put("package",representative).put("members",org.json.JSONArray(expected.map{it.identity.packageName})).put("permission",FINE).put("mask",256).put("value",wanted).put("user",0))
        try{
            val reply=connection.exchange(request)
            check(reply.getString("method")=="updatePermissionFlags"&&reply.getString("descriptor")=="android.permission.IPermissionManager"&&reply.getInt("mask")==256&&reply.getInt("value")==wanted&&reply.getInt("user")==0&&reply.getString("package")==representative&&reply.getJSONArray("members").toString()==org.json.JSONArray(expected.map{it.identity.packageName}).toString())
            audit(reply.put("phase","setter_return"))
        }catch(e:Exception){if(e !is HelperOperationRejectedException)live=false;throw e}
    }
    override fun <T> transact(service:String,descriptor:String,method:String,input:(Parcel)->Unit,output:(Parcel)->T):T {
        ready()
        val data=Parcel.obtain();val reply=Parcel.obtain()
        try{
            input(data);data.setDataPosition(0)
            val request=when(method){
                "getAllPackages"->JSONObject().put("op","GET_PACKAGES")
                "getPackageInfo"->JSONObject().put("op","GET_PACKAGE_INFO").put("package",data.readString()).put("flags",data.readLong()).also{check(data.readInt()==0)}
                "getPackagesForUid"->JSONObject().put("op","GET_UID_PEERS").put("uid",data.readInt())
                "getPermissionFlags"->JSONObject().put("op","GET_PERMISSION_FLAGS").put("package",data.readString()).put("permission",when(data.readString()){FINE->"FINE";COARSE->"COARSE";BACKGROUND->"BACKGROUND";else->error("Unknown location permission")}).also{check(data.readString()=="default:0"&&data.readInt()==0)}
                "checkOperationRaw"->{val op=data.readInt();val uid=data.readInt();val pkg=data.readString();check(data.readString()==null);JSONObject().put("op","GET_RAW_APP_OPS").put("locationOp",op).put("uid",uid).put("package",pkg)}
                "getOpsForPackage"->{val uid=data.readInt();val pkg=data.readString();check(data.createIntArray()!!.contentEquals(intArrayOf(0,1)));JSONObject().put("op","GET_LOCATION_HISTORY").put("uid",uid).put("package",pkg)}
                else->error("Local backend operation not allowed")
            }
            check(data.dataAvail()==0)
            val result=try{connection.exchange(request)}catch(e:Exception){if(e !is HelperOperationRejectedException)live=false;throw e}
            check(result.getString("method")==method&&result.getString("descriptor")==descriptor)
            val bytes=Base64.decode(result.getString("parcel"),Base64.NO_WRAP);check(bytes.size<=4_000_000)
            reply.unmarshall(bytes,0,bytes.size);reply.setDataPosition(0);reply.readException()
            val parsed=output(reply);check(reply.dataAvail()==0){"Unexpected local helper Parcel reply"};return parsed
        }catch(e:Exception){if(e !is HelperOperationRejectedException)live=false;throw e}finally{reply.recycle();data.recycle()}
    }
}
