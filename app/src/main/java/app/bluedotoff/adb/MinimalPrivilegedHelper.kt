package app.bluedotoff.adb

import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.content.pm.PackageInfo
import android.util.Base64
import app.bluedotoff.core.DexAbi
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject

/** Bounded stdin/stdout helper. No exported IPC, arbitrary Binder operation or shell execution. */
object MinimalPrivilegedHelper {
    private const val MAGIC=0x42443037
    private const val PM="android.content.pm.IPackageManager"
    private const val PERMISSION="android.permission.IPermissionManager"
    private const val OPS="com.android.internal.app.IAppOpsService"
    private val services=mapOf("package" to PM,"permissionmgr" to PERMISSION,"appops" to OPS)
    private val codes=mutableMapOf<String,Int>()
    private var verified=false
    private var deviceProfile:app.bluedotoff.core.Firmware?=null
    private lateinit var apk:File
    private val helperContext:android.content.Context by lazy {
        if(android.os.Looper.getMainLooper()==null){
            @Suppress("DEPRECATION")
            android.os.Looper.prepareMainLooper()
        }
        val c=Class.forName("android.app.ActivityThread")
        val thread=c.getMethod("systemMain").invoke(null)
        (c.getMethod("getSystemContext").invoke(thread) as android.content.Context).createPackageContext("app.bluedotoff",android.content.Context.CONTEXT_IGNORE_SECURITY)
    }
    private fun binder(service:String):IBinder {
        val b=Class.forName("android.os.ServiceManager").getMethod("getService",String::class.java).invoke(null,service) as? IBinder ?: error("Helper service unavailable")
        check(b.interfaceDescriptor==services[service]){"Helper descriptor mismatch"};return b
    }
    private fun property(key:String)=Class.forName("android.os.SystemProperties").getMethod("get",String::class.java,String::class.java).invoke(null,key,"") as String
    private fun context()=File("/proc/self/attr/current").readText().trim().trimEnd('\u0000')
    private fun health():JSONObject {
        app.bluedotoff.core.LocalAdbPolicy.validateShell(Process.myUid(),context())
        check(Build.MANUFACTURER.equals("samsung",true)&&Build.VERSION.SDK_INT>=37&&Build.TYPE=="user"&&(property("ro.build.version.oneui").toIntOrNull()?:0)>=90000){"Helper device profile invalid"}
        val descriptors=JSONObject();services.forEach{(service,_)->descriptors.put(service,binder(service).interfaceDescriptor)}
        return JSONObject().put("pid",Process.myPid()).put("uid",Process.myUid()).put("selinux",context()).put("fingerprint",Build.FINGERPRINT).put("sdk",Build.VERSION.SDK_INT).put("oneUi",property("ro.build.version.oneui")).put("manufacturer",Build.MANUFACTURER).put("buildType",Build.TYPE).put("descriptors",descriptors).put("writeEnabled",app.bluedotoff.BuildConfig.LOCAL_WRITES_ENABLED).put("transport","direct-shell-binder")
    }
    private fun asset(name:String):String=ZipFile(apk).use{z->z.getInputStream(z.getEntry("assets/$name")?:error("Helper profile missing")).bufferedReader().use{it.readText()}}
    private fun verify():JSONObject {
        verified=false
        val h=health()
        val methods=sortedMapOf<String,String>();val transactions=sortedMapOf<String,Int>()
        asset("reference-abi.tsv").lineSequence().forEach{l->val p=l.removePrefix("\uFEFF").split('\t');if(p.size==3){if(p[0]=="M")methods[p[1]]=p[2];if(p[0]=="T")transactions[p[1]]=p[2].toInt()}}
        check(methods.size==12&&transactions.size==6){"Helper ABI profile invalid"}
        val classes=setOf("Landroid/permission/IPermissionManager\$Stub;","Landroid/permission/IPermissionManager\$Stub\$Proxy;","Landroid/content/pm/IPackageManager\$Stub;","Landroid/content/pm/IPackageManager\$Stub\$Proxy;","Lcom/android/internal/app/IAppOpsService\$Stub;","Lcom/android/internal/app/IAppOpsService\$Stub\$Proxy;")
        val observed=DexAbi.inspect(File("/system/framework/framework.jar"),classes)
        check(observed.methods.filterKeys{!it.contains("->getOpsForPackage(")}==methods){"Helper unverified firmware ABI"}
        check(transactions.all{(k,v)->observed.transactions[k]==v}){"Helper transaction mismatch"}
        val hm=sortedMapOf<String,String>();var history:Int?=null
        asset("reference-history.tsv").lineSequence().forEach{l->val p=l.removePrefix("\uFEFF").split('\t');if(p.size==3){if(p[0]=="M")hm[p[1]]=p[2];if(p[0]=="T")history=p[2].toInt()}}
        val ho=DexAbi.inspect(File("/system/framework/framework.jar"),hm.keys.map{it.substringBefore("->")}.toSet()+"Lcom/android/internal/app/IAppOpsService\$Stub;")
        check(hm.size==7&&hm.all{(k,v)->ho.methods[k]==v}&&ho.transactions["Lcom/android/internal/app/IAppOpsService\$Stub;->TRANSACTION_getOpsForPackage:I"]==history){"Helper history ABI mismatch"}
        codes.clear();transactions.forEach{(k,v)->codes[k.substringAfter("TRANSACTION_").substringBefore(':')]=v};codes["getOpsForPackage"]=history!!
        val self=packageInfo("app.bluedotoff")?:error("Helper installed identity unavailable")
        check(self.applicationInfo!!.sourceDir==apk.path&&self.applicationInfo!!.uid>=10000){"Helper installed identity mismatch"}
        for(permission in listOf("android.permission.GRANT_RUNTIME_PERMISSIONS","android.permission.REVOKE_RUNTIME_PERMISSIONS","android.permission.GET_APP_OPS_STATS")){
            check(helperContext.checkPermission(permission,Process.myPid(),Process.myUid())==android.content.pm.PackageManager.PERMISSION_GRANTED){"Helper shell permission unavailable"}
        }
        val peers=parcelRead("package","getPackagesForUid",{it.writeInt(self.applicationInfo!!.uid)},{it.createStringArray()?.sorted()?.toList()})
        check(peers==listOf("app.bluedotoff")){"Helper authoritative app UID peers mismatch"}
        val first=parcelRead("permissionmgr","getPermissionFlags",{it.writeString("app.bluedotoff");it.writeString("android.permission.ACCESS_FINE_LOCATION");it.writeString("default:0");it.writeInt(0)},{it.readInt()})
        check(first>=0&&first==parcelRead("permissionmgr","getPermissionFlags",{it.writeString("app.bluedotoff");it.writeString("android.permission.ACCESS_FINE_LOCATION");it.writeString("default:0");it.writeInt(0)},{it.readInt()})){"Helper getter validation failed"}
        val country=Class.forName("com.samsung.android.feature.SemCscFeature").let{c->c.getMethod("getString",String::class.java,String::class.java).invoke(c.getMethod("getInstance").invoke(null),"CountryISO","") as String}
        // US CSC adds another SystemUI location path, so the app warns instead of blocking (country is returned below).
        check(country.matches(Regex("[A-Z]{2}"))){"Helper CSC policy unsupported"}
        val indicator=sortedMapOf<String,String>()
        asset("reference-indicator.tsv").lineSequence().forEach{l->val p=l.removePrefix("\uFEFF").split('\t');if(p.size==3&&p[0]=="M")indicator[p[1]]=p[2]}
        val systemUi=packageInfo("com.android.systemui")?.applicationInfo?.sourceDir?:error("Helper SystemUI identity unavailable")
        check(indicator.size==5&&DexAbi.inspect(File(systemUi),setOf("Lcom/android/systemui/ScRune;","Lcom/android/systemui/privacy/AppOpsPrivacyItemMonitor;")).methods==indicator){"Helper indicator ABI mismatch"}
        val profileId=java.security.MessageDigest.getInstance("SHA-256").digest((methods.entries+indicator.entries).joinToString("\n"){"${it.key}=${it.value}"}.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        verified=true
        deviceProfile=app.bluedotoff.core.Firmware(Build.MANUFACTURER,Build.VERSION.SDK_INT,property("ro.build.version.oneui"),Build.FINGERPRINT,Build.ID,Build.MODEL,profileId)
        return h.put("abiVerified",true).put("historyAbiVerified",true).put("indicatorAbiVerified",true).put("country",country).put("profileId",profileId).put("installedUid",self.applicationInfo!!.uid).put("shellPermissionChecks",true).put("authoritativeSelfUid",true).put("stableGetter",true)
    }
    private fun <T> parcelRead(service:String,method:String,input:(Parcel)->Unit,output:(Parcel)->T):T {
        val d=Parcel.obtain();val r=Parcel.obtain()
        try{d.writeInterfaceToken(services.getValue(service));input(d);check(binder(service).transact(codes.getValue(method),d,r,0));r.readException();val value=output(r);check(r.dataAvail()==0);return value}finally{d.recycle();r.recycle()}
    }
    private fun packageInfo(name:String):PackageInfo? {
        val data=Parcel.obtain();val reply=Parcel.obtain()
        try{data.writeInterfaceToken(PM);data.writeString(name);data.writeLong(134221824L);data.writeInt(0);check(binder("package").transact(codes.getValue("getPackageInfo"),data,reply,0));reply.readException();return reply.readTypedObject(PackageInfo.CREATOR)}finally{data.recycle();reply.recycle()}
    }
    private fun pkg(s:String):String {require(s.length<=255&&s.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*"))){"Helper package invalid"};return s}
    private fun uid(n:Int):Int {require(n in 0..19999){"Helper user 0 UID required"};return n}
    /** Uses precisely the existing test.6 snapshot/ownership/eligibility implementation. */
    private class DirectSnapshotBackend:app.bluedotoff.BinderBackend(helperContext){
        override fun liveShellServer():Boolean=Process.myUid()==2000&&context()=="u:r:shell:s0"
        override fun verify():app.bluedotoff.core.Firmware {check(verified&&liveShellServer());return deviceProfile?:error("Helper profile unavailable")}
        override fun compatibleFirmware()=verify()
        override fun writeFineBit(packageName:String,originalBit:Int):Unit=error("Only typed helper narrow setter is allowed")
        override fun <T> transact(service:String,descriptor:String,method:String,input:(Parcel)->Unit,output:(Parcel)->T):T {
            check(descriptor==services[service]&&method in setOf("getPackageInfo","getPackagesForUid","getPermissionFlags","checkOperationRaw"))
            return parcelRead(service,method,input,output)
        }
    }
    private fun updateFine(r:JSONObject):JSONObject {
        check(app.bluedotoff.BuildConfig.LOCAL_WRITES_ENABLED){"Read-only acceptance build"}
        check(verified&&Process.myUid()==2000&&context()=="u:r:shell:s0")
        val name=pkg(r.getString("package"));val wanted=r.getInt("value")
        val scope=app.bluedotoff.core.ApplicationScope.fromLevel(r.getInt("scope"))
        val fresh=DirectSnapshotBackend().read(name).copy(applicationScope=scope)
        app.bluedotoff.core.LocalWriteGuard.validate(fresh,r.getString("identityDigest"),r.getString("invariantDigest"),r.getInt("fineFlags"),wanted)
        val code=codes.getValue("updatePermissionFlags")
        if(app.bluedotoff.BuildConfig.DEBUG)android.util.Log.i("BlueDotLocalWriteAudit","setter_enter package=$name uid=${fresh.identity.uid} mask=256 value=$wanted tx=$code before=${fresh.fineFlags}")
        applyFineMask(name,wanted)
        if(app.bluedotoff.BuildConfig.DEBUG)android.util.Log.i("BlueDotLocalWriteAudit","setter_return package=$name mask=256 value=$wanted tx=$code")
        return JSONObject().put("method","updatePermissionFlags").put("descriptor",PERMISSION).put("transaction",code).put("package",name).put("mask",256).put("value",wanted).put("user",0).put("beforeFineFlags",fresh.fineFlags)
    }
    private fun selfPermission(grant:Boolean):JSONObject {
        check(verified&&Process.myUid()==2000&&context()=="u:r:shell:s0")
        val self=packageInfo("app.bluedotoff")?:error("Self identity unavailable")
        check(self.applicationInfo!!.sourceDir==apk.path&&self.sharedUserId==null)
        check(parcelRead("package","getPackagesForUid",{it.writeInt(self.applicationInfo!!.uid)},{it.createStringArray()?.toList()})==listOf("app.bluedotoff"))
        check(self.requestedPermissions?.contains("android.permission.WRITE_SECURE_SETTINGS")==true)
        // Fixed argv, never a shell string, package parameter, permission parameter, or settings command.
        val process=if(grant)ProcessBuilder("/system/bin/pm","grant","--user","0","app.bluedotoff","android.permission.WRITE_SECURE_SETTINGS").redirectErrorStream(true).start()
            else ProcessBuilder("/system/bin/pm","revoke","--user","0","app.bluedotoff","android.permission.WRITE_SECURE_SETTINGS").redirectErrorStream(true).start()
        try {
            val finished=process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)
            if(!finished)process.destroyForcibly()
            check(finished&&process.exitValue()==0){"Fixed self permission operation failed"}
        } finally {
            process.inputStream.close();process.outputStream.close();process.errorStream.close()
        }
        val granted=helperContext.checkPermission("android.permission.WRITE_SECURE_SETTINGS",-1,self.applicationInfo!!.uid)==android.content.pm.PackageManager.PERMISSION_GRANTED
        check(granted==grant)
        if(app.bluedotoff.BuildConfig.DEBUG)android.util.Log.i("BlueDotSelfPermissionAudit","category=SELF_PERMISSION operation=${if(grant)"grant" else "revoke"} package=app.bluedotoff success=true")
        return JSONObject().put("granted",granted)
    }
    private fun updateShared(r:JSONObject):JSONObject {
        check(app.bluedotoff.BuildConfig.LOCAL_WRITES_ENABLED&&verified&&Process.myUid()==2000&&context()=="u:r:shell:s0")
        val wanted=r.getInt("value");require(wanted==0||wanted==256)
        val members=r.getJSONArray("members");require(members.length() in 2..app.bluedotoff.core.SharedGroupPolicy.MAX_MEMBERS)
        val requests=(0 until members.length()).map{members.getJSONObject(it)}
        val names=requests.map{require(it.keys().asSequence().toSet()==setOf("package","identityDigest","invariantDigest","fineFlags"));pkg(it.getString("package"))}
        require(names.distinct()==names&&names.sorted()==names)
        val scope=app.bluedotoff.core.ApplicationScope.fromLevel(r.getInt("scope"))
        val fresh=names.map{DirectSnapshotBackend().read(it).copy(applicationScope=scope)}
        app.bluedotoff.core.SharedGroupPolicy.validate(fresh)
        fresh.zip(requests).forEach{(s,q)->
            require(q.getString("identityDigest").matches(Regex("[0-9a-f]{64}"))&&q.getString("invariantDigest").matches(Regex("[0-9a-f]{64}")))
            check(app.bluedotoff.core.LocalWriteGuard.identityDigest(s.identity)==q.getString("identityDigest")&&s.invariantDigest()==q.getString("invariantDigest")&&s.fineFlags==q.getInt("fineFlags")){"Fresh group helper preflight failed"}
        }
        val name=app.bluedotoff.core.SharedGroupPolicy.representative(fresh).identity.packageName
        // Exactly one UID-scoped FINE mask=256 write; never a setter per peer.
        applyFineMask(name,wanted)
        return JSONObject().put("method","updatePermissionFlags").put("descriptor",PERMISSION).put("package",name).put("mask",256).put("value",wanted).put("user",0).put("members",org.json.JSONArray(names))
    }
    private fun applyFineMask(name:String,wanted:Int){
        check(verified&&Process.myUid()==2000&&context()=="u:r:shell:s0")
        require(wanted==0||wanted==256)
        parcelRead("permissionmgr","updatePermissionFlags",{it.writeString(name);it.writeString("android.permission.ACCESS_FINE_LOCATION");it.writeInt(256);it.writeInt(wanted);it.writeInt(1);it.writeString("default:0");it.writeInt(0)},{Unit})
    }
    private fun request(r:JSONObject):JSONObject {
        val op=r.getString("op")
        val allowed=when(op){"HEALTH","PROFILE","GET_PACKAGES","GRANT_SELF_WIRELESS_PERMISSION","REVOKE_SELF_WIRELESS_PERMISSION"->setOf("op");"GET_PACKAGE_INFO"->setOf("op","package","flags");"GET_UID_PEERS"->setOf("op","uid");"GET_PERMISSION_FLAGS"->setOf("op","package","permission");"GET_RAW_APP_OPS"->setOf("op","package","uid","locationOp");"GET_LOCATION_HISTORY"->setOf("op","package","uid");"UPDATE_SHARED_FINE_SENSITIVITY_BIT"->setOf("op","members","value","scope");"UPDATE_FINE_SENSITIVITY_BIT"->setOf("op","package","identityDigest","invariantDigest","fineFlags","value","scope");else->error("Helper operation not allowed")}
        require(r.keys().asSequence().toSet()==allowed){"Helper request schema invalid"}
        if(op=="HEALTH")return health()
        if(op=="PROFILE")return verify()
        check(verified){"Helper requires fresh ABI verification"}
        if(op=="GRANT_SELF_WIRELESS_PERMISSION")return selfPermission(true)
        if(op=="REVOKE_SELF_WIRELESS_PERMISSION")return selfPermission(false)
        if(op=="UPDATE_FINE_SENSITIVITY_BIT")return updateFine(r)
        if(op=="UPDATE_SHARED_FINE_SENSITIVITY_BIT")return updateShared(r)
        val service=when(op){"GET_PACKAGES","GET_PACKAGE_INFO","GET_UID_PEERS"->"package";"GET_PERMISSION_FLAGS"->"permissionmgr";else->"appops"}
        val method=when(op){"GET_PACKAGES"->"getAllPackages";"GET_PACKAGE_INFO"->"getPackageInfo";"GET_UID_PEERS"->"getPackagesForUid";"GET_PERMISSION_FLAGS"->"getPermissionFlags";"GET_RAW_APP_OPS"->"checkOperationRaw";else->"getOpsForPackage"}
        val data=Parcel.obtain();val reply=Parcel.obtain()
        try {
            data.writeInterfaceToken(services.getValue(service))
            when(op){
                "GET_PACKAGES"->Unit
                "GET_PACKAGE_INFO"->{val flags=r.getLong("flags");require(flags==134221824L||flags==1140850688L){"Helper package flags invalid"};data.writeString(pkg(r.getString("package")));data.writeLong(flags);data.writeInt(0)}
                "GET_UID_PEERS"->data.writeInt(uid(r.getInt("uid")))
                "GET_PERMISSION_FLAGS"->{val permission=r.getString("permission");require(permission in setOf("FINE","COARSE","BACKGROUND"));data.writeString(pkg(r.getString("package")));data.writeString("android.permission.ACCESS_${permission}_LOCATION");data.writeString("default:0");data.writeInt(0)}
                "GET_RAW_APP_OPS"->{val loc=r.getInt("locationOp");require(loc in 0..1);data.writeInt(loc);data.writeInt(uid(r.getInt("uid")));data.writeString(pkg(r.getString("package")));data.writeString(null)}
                "GET_LOCATION_HISTORY"->{data.writeInt(uid(r.getInt("uid")));data.writeString(pkg(r.getString("package")));data.writeIntArray(intArrayOf(0,1))}
            }
            check(binder(service).transact(codes.getValue(method),data,reply,0)){"Helper Binder request failed"}
            check(reply.dataSize()<=4_000_000){"Helper response too large"}
            // Retain Android's existing typed Parcel format; no dumpsys/pm/service-call parsing.
            return JSONObject().put("parcel",Base64.encodeToString(reply.marshall(),Base64.NO_WRAP)).put("method",method).put("descriptor",services.getValue(service))
        }finally{data.recycle();reply.recycle()}
    }
    @JvmStatic fun main(args:Array<String>){
        check(args.size==1&&args[0].matches(Regex("/data/app/[A-Za-z0-9_~=/+.-]+/base\\.apk"))&&System.getenv("CLASSPATH")==args[0]){"Helper classpath invalid"}
        apk=File(args[0]);check(apk.isFile&&apk.canRead()){"Helper APK unavailable"}
        val started=android.os.SystemClock.elapsedRealtime()
        val lastRequest=java.util.concurrent.atomic.AtomicLong(started)
        val watchdog=java.util.Timer(true);watchdog.scheduleAtFixedRate(object:java.util.TimerTask(){override fun run(){val now=android.os.SystemClock.elapsedRealtime();if(now-started>120000||now-lastRequest.get()>10000)System.exit(70)}},1000,1000)
        val input=DataInputStream(System.`in`);val output=DataOutputStream(System.out)
        try{
            while(true){
                val magic=try{input.readInt()}catch(_:java.io.EOFException){break};check(magic==MAGIC){"Helper frame magic invalid"}
                val n=input.readInt();check(n in 1..65536){"Helper request size invalid"};val bytes=ByteArray(n);input.readFully(bytes)
                lastRequest.set(android.os.SystemClock.elapsedRealtime())
                var operation=""
                val response=try{val parsed=JSONObject(String(bytes,Charsets.UTF_8));operation=parsed.optString("op");JSONObject().put("ok",true).put("result",request(parsed))}catch(e:Exception){
                    val reason=when {e.message=="SHELL_IDENTITY_INVALID"->"SHELL_IDENTITY_INVALID";operation in setOf("HEALTH","PROFILE")->"COMPATIBILITY_FAILED";operation in setOf("UPDATE_FINE_SENSITIVITY_BIT","UPDATE_SHARED_FINE_SENSITIVITY_BIT")->"HELPER_OPERATION_REJECTED";else->"HELPER_PROTOCOL_FAILED"}
                    JSONObject().put("ok",false).put("error",reason)
                }
                val encoded=response.toString().toByteArray(Charsets.UTF_8);check(encoded.size<=6_000_000)
                output.writeInt(MAGIC);output.writeInt(encoded.size);output.write(encoded);output.flush()
            }
        }finally{watchdog.cancel()}
    }
}
