package app.bluedotoff

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import app.bluedotoff.core.*
import java.io.File
import java.security.MessageDigest

/** Only updatePermissionFlags(FINE, mask=256, value=0/originalBit) mutates system state. */
abstract class BinderBackend(private val context: Context) : TargetPort {
    companion object {
        const val SHIZUKU = "moe.shizuku.privileged.api"
        const val FINE = "android.permission.ACCESS_FINE_LOCATION"
        const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
        const val BACKGROUND = "android.permission.ACCESS_BACKGROUND_LOCATION"
        // Read-only MATCH_STATIC_SHARED_AND_SDK_LIBRARIES, verified from this firmware.
        // Not exposed in compileSdk public stubs; never used in the permission setter.
        private const val MATCH_SHARED_LIBRARIES = 67108864L
        private const val PM = "android.content.pm.IPackageManager"
        private const val PERMISSION = "android.permission.IPermissionManager"
        private const val OPS = "com.android.internal.app.IAppOpsService"
        fun ownership(pkg:String,uid:Int,peers:List<String>?,flags:Int,critical:Boolean,sharedUserId:String?)=UidOwnership(pkg,uid,peers,sharedUserId,flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT!=0,critical)
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private val validationStart = SystemClock.elapsedRealtime()
    private val codes = java.util.concurrent.ConcurrentHashMap<String, Int>()
    var historyAvailable: Boolean = false; private set
    protected open val applicationScope:ApplicationScope get()=ApplicationScope.GROUPS
    abstract fun liveShellServer():Boolean
    @Synchronized private fun record(event: JSONObject) {
        val file = File(context.noBackupFilesDir,"validation-events.jsonl")
        event.put("time",System.currentTimeMillis()).put("elapsedMs",SystemClock.elapsedRealtime()-validationStart)
        // Diagnostics only (never read for safety): a storage error here must not fail a permission read or write.
        try{if(file.exists() && file.length()>1_000_000) file.writeText("");file.appendText(event.toString()+"\n")}catch(e:java.io.IOException){android.util.Log.w("bluedotOFF","diagnostic log skipped: ${e.javaClass.simpleName}")}
    }
    open fun recordVerification(phase:String,s:Snapshot,wrote:Boolean) {
        record(JSONObject().put("phase",phase).put("package",s.identity.packageName).put("uid",s.identity.uid).put("peers",org.json.JSONArray(s.peers)).put("signers",org.json.JSONArray(s.identity.signers)).put("versionCode",s.identity.versionCode).put("firstInstallTime",s.identity.firstInstallTime).put("lastUpdateTime",s.identity.lastUpdateTime).put("fineGranted",s.fineGranted).put("coarseGranted",s.coarseGranted).put("backgroundGranted",s.backgroundGranted).put("fineFlags",s.fineFlags).put("coarseFlags",s.coarseFlags).put("backgroundFlags",s.backgroundFlags).put("fineMode",s.fineMode).put("coarseMode",s.coarseMode).put("sharedUserId",s.sharedUserId?:JSONObject.NULL).put("uidClass",s.ownership.uidClass.name).put("eligibilityReason",s.rejectionReason()).put("setterPreviouslyCalled",wrote).put("transport","local-adb").put("firmware",compatibleFirmware().key))
    }
    protected abstract fun <T> transact(service:String,descriptor:String,method:String,input:(Parcel)->Unit,output:(Parcel)->T):T
    abstract fun verify():Firmware
    abstract override fun compatibleFirmware():Firmware
    fun allPackages(): List<String> = transact("package", PM, "getAllPackages", {}, { it.createStringArrayList()?.sorted() ?: error("앱 목록 응답 오류") })
    /** Display-only: re-reads location access times for an already discovered list, reusing the same getters as
     * discover(). A package that cannot be read keeps its previous values. No safety state or setter is involved. */
    fun refreshHistory(apps:List<DiscoveredApp>,checkCancelled:()->Unit={}):List<DiscoveredApp> {
        verifyHistory()
        return apps.map{a->checkCancelled();try{
            val parsed=transact("appops",OPS,"getOpsForPackage",{it.writeInt(a.uid);it.writeString(a.packageName);it.writeIntArray(intArrayOf(0,1))},{AppOpsHistory.readWithEvidence(it,a.packageName,a.uid)})
            val access=parsed.access
            val hint=if(access.fine!=null)try{HistoryPolicy.fineHint(flags(a.packageName,FINE))}catch(_:Exception){FineIndicatorHint.UNKNOWN}else FineIndicatorHint.UNKNOWN
            a.copy(access=access,indicatorHint=hint,hasRelevantOps=parsed.hasLocationOps)
        }catch(error:Exception){if(error is kotlinx.coroutines.CancellationException)throw error;a}}
    }
    /** Global shell-visible names, one small user-0 PackageInfo response per name.
     * No signer, permissions or permission flags in discovery. A successful null response
     * means unavailable in this scope, never sole UID ownership: every represented UID
     * has its authoritative peer names checked before publishing. Errors abort the pass.
     */
    fun discover(checkCancelled:()->Unit = {}, onInitial: (DiscoveryResult)->Unit = {}, onSnapshot:(Snapshot)->Unit = {},onProgress:(Int,Int)->Unit={_,_->}): DiscoveryResult {
        val firmware=compatibleFirmware()
        val begin=SystemClock.elapsedRealtime()
        val names=allPackages()
        val allInfo=CompletePackageScan.collectAvailable(names) { pkg -> checkCancelled();packageInfo(pkg,PackageManager.MATCH_APEX.toLong() or MATCH_SHARED_LIBRARIES) }
        val applications=CompletePackageScan.applicationNames(allInfo.map{(pkg,info)->CompletePackageScan.Kind(pkg,info.applicationInfo?.uid,info.isApex)})
        val infos=allInfo.filter{it.first in applications && it.second.applicationInfo!!.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED!=0}
        val scanMs=SystemClock.elapsedRealtime()-begin
        val mapStart=SystemClock.elapsedRealtime()
        val uidMap=CompletePackageScan.confirmedUidMap(names.toSet(),infos.map{(pkg,info)->pkg to (info.applicationInfo?.uid?:error("앱 정보를 확인하지 못했습니다."))}) { uid -> checkCancelled();packagesForUid(uid) }
        val mapMs=SystemClock.elapsedRealtime()-mapStart
        // Launcher visibility affects display only, never the UID map or write authorization.
        val launcher=try { context.packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER),0).map{it.activityInfo.packageName}.toSet() } catch(_:Exception) { emptySet() }
        val basic=infos.map{(pkg,info)->
            val app=info.applicationInfo!!
            val label=try{app.loadLabel(context.packageManager).toString()}catch(_:Exception){pkg}
            val block=ScopePolicy.ownershipBlock(ownership(pkg,app.uid,uidMap[app.uid],app.flags,isCritical(pkg),info.sharedUserId),SingletonSupport.allowed(context,firmware,info),applicationScope)?.message.orEmpty()
            DiscoveredApp(pkg,label,app.uid,app.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)!=0,pkg in launcher,block=block,internalService=app.flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT!=0 || isCritical(pkg),uidClass=ownership(pkg,app.uid,uidMap[app.uid],app.flags,isCritical(pkg),info.sharedUserId).uidClass,sharedUserId=info.sharedUserId,uidPeers=uidMap[app.uid]?:emptyList())
        }
        historyAvailable=false
        val initial=DiscoveryResult(DiscoveryPolicy.sort(basic,false),false,ScanMetrics(infos.size,basic.count{it.visibleCandidate},lightweightMs=scanMs,uidMapMs=mapMs))
        onInitial(initial)
        val historyStart=SystemClock.elapsedRealtime()
        val enriched=try {
            verifyHistory()
            basic.map{a->checkCancelled();val parsed=transact("appops",OPS,"getOpsForPackage",{it.writeInt(a.uid);it.writeString(a.packageName);it.writeIntArray(intArrayOf(0,1))},{AppOpsHistory.readWithEvidence(it,a.packageName,a.uid)})
                val access=parsed.access
                val hint=if(access.fine!=null)try{HistoryPolicy.fineHint(flags(a.packageName,FINE))}catch(_:Exception){FineIndicatorHint.UNKNOWN}else FineIndicatorHint.UNKNOWN
                a.copy(access=access,indicatorHint=hint,hasRelevantOps=parsed.hasLocationOps)
            }.also{historyAvailable=true}
        }catch(error:Exception){if(error is kotlinx.coroutines.CancellationException)throw error;record(JSONObject().put("phase","discovery_history_error").put("error",error.javaClass.simpleName));basic}
        val historyMs=SystemClock.elapsedRealtime()-historyStart
        // Display-only read phase. Uses existing allowlisted PackageInfo/flags/raw-mode getters.
        // No Snapshot safety predicate or setter is changed by this classification.
        val platform=IndicatorPlatform.read(context)
        record(JSONObject().put("phase","indicator_platform").put("supported",platform.supported).put("exemptions",org.json.JSONArray(platform.exemptions.toList().sorted())))
        if(enriched.isNotEmpty())onProgress(0,enriched.size)
        val classified=enriched.mapIndexed{index,a->
            checkCancelled()
            val classifiedApp=try {
                val info=packageInfo(a.packageName)?:error("앱 정보 변경")
                val requested=info.requestedPermissions?:emptyArray()
                val fineIndex=requested.indexOf(FINE)
                val declared=fineIndex>=0
                val hasLocation=declared||COARSE in requested
                val granted=declared&&((info.requestedPermissionsFlags?.getOrNull(fineIndex)?:0) and PackageInfo.REQUESTED_PERMISSION_GRANTED)!=0
                val facts=IndicatorFacts(platform.supported,a.packageName !in platform.exemptions,declared,granted,
                    if(declared)flags(a.packageName,FINE)else 0,if(declared)mode(a.packageName,a.uid,1)else 0)
                val relevant=hasLocation||a.hasRelevantOps||a.access.latest!=null
                val snapshot=if((a.block.isEmpty()||applicationScope==ApplicationScope.ALL)&&relevant)read(a.packageName).also(onSnapshot)else null
                val block=if(a.block.isNotEmpty())a.block else snapshot?.rejectionReason()?:""
                a.copy(indicator=IndicatorClassifier.classify(facts),hasLocationDeclaration=hasLocation,block=block,check=if(block.isNotEmpty())CandidateCheck.BLOCKED else if(relevant&&platform.supported)CandidateCheck.AVAILABLE else CandidateCheck.NEEDS_CHECK)
            }catch(error:Exception){if(error is kotlinx.coroutines.CancellationException)throw error;a.copy(indicator=IndicatorAssessment(),check=CandidateCheck.NEEDS_CHECK)}
            onProgress(index+1,enriched.size)
            classifiedApp
        }
        if(BuildConfig.DEBUG)File(context.noBackupFilesDir,"indicator-discovery.json").writeText(JSONObject()
            .put("profileSupported",platform.supported).put("exemptions",org.json.JSONArray(platform.exemptions.toList().sorted()))
            .put("apps",org.json.JSONArray(classified.map{a->JSONObject().put("package",a.packageName).put("label",a.label).put("uid",a.uid).put("launcher",a.launcher).put("preinstalled",a.preinstalled).put("hasLocation",a.hasLocationDeclaration?:JSONObject.NULL).put("indicator",a.indicator.eligibility.name).put("branch",a.indicator.sensitivityBranch?:JSONObject.NULL).put("canCheck",a.canCheck).put("block",a.block)})).toString())
        val sortStart=SystemClock.elapsedRealtime()
        val sorted=DiscoveryPolicy.sort(classified,historyAvailable)
        val sortMs=SystemClock.elapsedRealtime()-sortStart
        val metrics=initial.metrics.copy(candidates=sorted.count{it.visibleCandidate},historyEntries=sorted.count{it.access.latest!=null},historyMs=historyMs,sortMs=sortMs)
        record(JSONObject().put("phase","discovery_metrics").put("installed",infos.size).put("totalPackageNames",names.size).put("unavailableInUser0",names.size-allInfo.size).put("apexModules",allInfo.count{it.second.isApex}).put("uidGroups",uidMap.size).put("candidates",metrics.candidates).put("historyEntries",metrics.historyEntries).put("lightweightMs",scanMs).put("uidMapMs",mapMs).put("historyMs",historyMs).put("sortMs",sortMs))
        return DiscoveryResult(sorted,historyAvailable,metrics)
    }
    private fun isCritical(pkg:String) = pkg in setOf(context.packageName,SHIZUKU,"android","com.android.systemui","com.android.permissioncontroller","com.google.android.permissioncontroller","com.samsung.android.knox.containercore","com.android.phone","com.android.providers.settings")
    fun recordUiMetric(phase:String,ms:Long) { record(JSONObject().put("phase",phase).put("durationMs",ms)) }
    private fun verifyHistory() {
        val expected=sortedMapOf<String,String>();var code:Int?=null
        context.assets.open("reference-history.tsv").bufferedReader().useLines{lines->lines.forEach{line->val p=line.removePrefix("\uFEFF").split('\t');if(p.size==3&&p[0]=="M")expected[p[1]]=p[2];if(p.size==3&&p[0]=="T")code=p[2].toInt()}}
        check(expected.size==7 && code!=null){"사용 기록 형식을 확인하지 못했습니다."}
        val classes=expected.keys.map{it.substringBefore("->")}.toSet()+"Lcom/android/internal/app/IAppOpsService\$Stub;"
        val observed=DexAbi.inspect(File("/system/framework/framework.jar"),classes)
        check(expected.all{(k,v)->observed.methods[k]==v} && observed.transactions["Lcom/android/internal/app/IAppOpsService\$Stub;->TRANSACTION_getOpsForPackage:I"]==code){"사용 기록 형식 변경"}
        codes["$OPS:getOpsForPackage"]=code!!
    }
    private fun packageInfo(pkg: String, requestFlags: Long = (PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES).toLong()): PackageInfo? = transact("package", PM, "getPackageInfo", { it.writeString(pkg); it.writeLong(requestFlags); it.writeInt(0) }, { it.readTypedObject(PackageInfo.CREATOR) })
    private fun packagesForUid(uid: Int): List<String> = transact("package", PM, "getPackagesForUid", { it.writeInt(uid) }, { it.createStringArray()?.sorted()?.toList() ?: error("앱 묶음 정보를 확인하지 못했습니다.") })
    private fun flags(pkg: String, permission: String): Int = transact("permissionmgr", PERMISSION, "getPermissionFlags", { it.writeString(pkg); it.writeString(permission); it.writeString("default:0"); it.writeInt(0) }, { it.readInt() })
    private fun mode(pkg: String, uid: Int, op: Int): Int = transact("appops", OPS, "checkOperationRaw", { it.writeInt(op); it.writeInt(uid); it.writeString(pkg); it.writeString(null) }, { it.readInt() })
    override fun read(packageName: String): Snapshot = app.bluedotoff.Perf.time("read"){readOnce(packageName)}
    private fun readOnce(packageName: String): Snapshot {
        val firmware=compatibleFirmware()
        require(packageName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")))
        val info = packageInfo(packageName) ?: throw PackageManager.NameNotFoundException(packageName)
        val app = info.applicationInfo ?: error("앱 정보 없음")
        val signers = info.signingInfo?.apkContentsSigners?.map { digest(it.toByteArray()) }?.sorted() ?: error("서명 조회 불가")
        check(signers.isNotEmpty())
        fun declared(p: String) = info.requestedPermissions?.contains(p) == true
        fun grant(p: String): Boolean { val i = info.requestedPermissions?.indexOf(p) ?: -1; return i >= 0 && (info.requestedPermissionsFlags!![i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }
        val identity = Identity(packageName, UidPolicy.userId(app.uid), app.uid, signers, info.firstInstallTime, info.longVersionCode, info.lastUpdateTime)
        val critical = UidPolicy.appId(app.uid) < Process.FIRST_APPLICATION_UID || (app.flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT) != 0 || isCritical(packageName)
        val label = try { app.loadLabel(context.packageManager).toString() } catch (_: Exception) { packageName }
        return Snapshot(identity, label, packagesForUid(app.uid), declared(FINE), grant(FINE), grant(COARSE), grant(BACKGROUND), flags(packageName,FINE), flags(packageName,COARSE), if (declared(BACKGROUND)) flags(packageName,BACKGROUND) else 0, mode(packageName,app.uid,1), mode(packageName,app.uid,0), critical,sharedUserId=info.sharedUserId,persistent=app.flags and android.content.pm.ApplicationInfo.FLAG_PERSISTENT!=0,criticalPackage=isCritical(packageName),singletonVerified=SingletonSupport.allowed(context,firmware,info,signers),coarseDeclared=declared(COARSE),applicationScope=applicationScope)
    }
    override fun writeFineBit(packageName: String, originalBit: Int) {
        require(originalBit == 0 || originalBit == SENSITIVITY_BIT)
        compatibleFirmware()
        record(JSONObject().put("phase","setter_request").put("package",packageName).put("permission",FINE).put("mask",SENSITIVITY_BIT).put("value",originalBit).put("user",0))
        transact("permissionmgr", PERMISSION, "updatePermissionFlags", {
            it.writeString(packageName); it.writeString(FINE); it.writeInt(SENSITIVITY_BIT); it.writeInt(originalBit); it.writeInt(1); it.writeString("default:0"); it.writeInt(0)
        }, {})
    }
}
