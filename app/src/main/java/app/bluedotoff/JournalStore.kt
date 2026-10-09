package app.bluedotoff

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.bluedotoff.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Checked fsync + atomic rename, then exact reread. Corruption never means an empty journal. */
class JournalStore(context: Context) : Journal {
    private val base = File(context.noBackupFilesDir, "original-bit-journal.json")
    companion object { private val lock = Any() }
    override fun entries(): List<Entry> = synchronized(lock) { read() }
    private fun read(): List<Entry> {
        if (!base.exists()) return emptyList()
        val root = base.bufferedReader().use { JSONObject(it.readText()) }
        check(root.getInt("schema") == 1) { "복원 기록 형식을 확인할 수 없습니다. 변경을 차단했습니다." }
        val payload = root.getString("payload")
        check(BinderBackend.digest(payload.toByteArray()) == root.getString("checksum")) { "복원 기록이 손상되어 변경을 차단했습니다." }
        val items = JSONArray(payload)
        return (0 until items.length()).map { index ->
            val o = items.getJSONObject(index); val signers = o.getJSONArray("signers")
            Entry(Identity(o.getString("packageName"), o.getInt("userId"), o.getInt("uid"), (0 until signers.length()).map { signers.getString(it) }, o.getLong("firstInstallTime"), o.getLong("versionCode"), o.getLong("lastUpdateTime")), o.getString("label"), o.getInt("originalBit"), o.getBoolean("enabled"), TargetState.valueOf(o.getString("state")), o.getLong("verifiedAt"), o.getString("firmware"), o.getString("boot"), o.getBoolean("changedByUs"), o.optString("error"), o.optString("invariantDigest"), o.optBoolean("settlingVerified",false),if(o.isNull("sharedUserId"))null else o.optString("sharedUserId").takeIf{it.isNotBlank()},o.optJSONArray("groupIdentities")?.let{group->(0 until group.length()).map{n->val g=group.getJSONObject(n);val ss=g.getJSONArray("signers");Identity(g.getString("packageName"),g.getInt("userId"),g.getInt("uid"),(0 until ss.length()).map{ss.getString(it)},g.getLong("firstInstallTime"),g.getLong("versionCode"),g.getLong("lastUpdateTime"))}}?:emptyList())
        }
    }
    override fun commit(entry: Entry): Boolean = synchronized(lock) {
        try {
            val all = read().filterNot { it.identity.packageName == entry.identity.packageName && it.identity.userId == entry.identity.userId } + entry
            write(all)
        } catch (_: Exception) { false }
    }
    override fun commitGroup(entries:List<Entry>):Boolean=synchronized(lock){try{
        require(entries.size in 2..SharedGroupPolicy.MAX_MEMBERS&&entries.map{it.identity.packageName}.distinct().size==entries.size)
        require(entries.all{it.groupIdentities==entries.first().groupIdentities}&&entries.map{it.identity}.sortedBy{it.packageName}==entries.first().groupIdentities)
        val names=entries.map{it.identity.packageName}.toSet();write(read().filterNot{it.identity.userId==0&&it.identity.packageName in names}+entries)
    }catch(_:Exception){false}}
    fun transform(action: (Entry) -> Entry): Boolean = synchronized(lock) { try { write(read().map(action)) } catch (_: Exception) { false } }
    private fun write(items: List<Entry>): Boolean {
        val array = JSONArray()
        for (e in items) array.put(JSONObject().apply {
            put("packageName", e.identity.packageName); put("userId", e.identity.userId); put("uid", e.identity.uid); put("signers", JSONArray(e.identity.signers)); put("firstInstallTime", e.identity.firstInstallTime); put("versionCode", e.identity.versionCode); put("lastUpdateTime", e.identity.lastUpdateTime)
            put("label", e.label); put("originalBit", e.originalBit); put("enabled", e.enabled); put("state", e.state.name); put("verifiedAt", e.lastVerifiedTime); put("firmware", e.firmware); put("boot", e.bootSession); put("changedByUs", e.changedByUs); put("error", e.error)
            put("invariantDigest",e.invariantDigest)
            if(e.groupIdentities.isNotEmpty())put("groupIdentities",JSONArray(e.groupIdentities.map{id->JSONObject().put("packageName",id.packageName).put("userId",id.userId).put("uid",id.uid).put("signers",JSONArray(id.signers)).put("firstInstallTime",id.firstInstallTime).put("versionCode",id.versionCode).put("lastUpdateTime",id.lastUpdateTime)}))
            put("settlingVerified",e.settlingVerified);put("sharedUserId",e.sharedUserId?:JSONObject.NULL)
        })
        val payload = array.toString()
        val root = JSONObject().put("schema", 1).put("payload", payload).put("checksum", BinderBackend.digest(payload.toByteArray())).toString()
        base.parentFile!!.mkdirs()
        val next=File(base.path+".new")
        FileOutputStream(next).use { it.write(root.toByteArray());it.flush();it.fd.sync() }
        // No non-atomic fallback: unsupported filesystems stop before any permission write.
        Files.move(next.toPath(),base.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        check(read()==items) { "복원 기록 저장 확인 실패" }
        return true
    }
}

object BootSession {
    fun current(context: Context): String {
        val count = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        // BOOT_COUNT is read-only; fallback wall-clock boot estimate is conservative and rounded.
        return if (count >= 0) "count:$count" else "estimate:${(System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60000}"
    }
}
