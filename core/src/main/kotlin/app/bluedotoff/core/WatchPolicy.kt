package app.bluedotoff.core

/** Opt-in background work may only revisit verified, still-registered hides. Never enroll a new app. */
object WatchPolicy {
    private val states=setOf(TargetState.APPLIED,TargetState.NEEDS_REAPPLY_POLICY,TargetState.NEEDS_REAPPLY_UPDATE)
    fun eligible(e:Entry)=e.enabled&&e.settlingVerified&&e.state in states
    fun units(entries:List<Entry>,packages:Set<String>):List<List<Entry>> {
        val byName=entries.associateBy{it.identity.packageName}
        return entries.filter{eligible(it)&&(it.identity.packageName in packages||it.groupIdentities.any{id->id.packageName in packages})}
            .mapNotNull{e->
                val ids=e.groupIdentities
                if(ids.isEmpty())listOf(e) else ids.mapNotNull{id->byName[id.packageName]?.takeIf{eligible(it)&&it.groupIdentities==ids&&it.identity==id}}
                    .takeIf{it.size==ids.size}
            }.distinctBy{it.map{e->e.identity.packageName}.sorted()}
    }
    /** A fresh read must match the recorded install. An app update is allowed; reinstall/UID/signature change is not.
     *  A group uses the same rule as the manual group write: one usable member, companions pinned but never granted. */
    fun mayReapply(entries:List<Entry>,fresh:List<Snapshot>,firmware:Firmware,boot:String)=skipReason(entries,fresh,firmware,boot)==null
    private fun updated(e:Entry,s:Snapshot)=e.state==TargetState.NEEDS_REAPPLY_UPDATE||e.identity.versionCode!=s.identity.versionCode||e.identity.lastUpdateTime!=s.identity.lastUpdateTime
    /** Why the watch leaves this unit alone (null = go ahead). Logged without changing anything. */
    fun skipReason(entries:List<Entry>,fresh:List<Snapshot>,firmware:Firmware,boot:String):String? {
        if(entries.isEmpty()||entries.size!=fresh.size)return "members"
        for((e,s) in entries.zip(fresh)) when {
            !eligible(e)->return "notEligible"
            !e.identity.sameInstall(s.identity)->return "install"
            e.bootSession!=boot->return "boot"
            e.firmware!=firmware.key->return "firmware"
            !ScopePolicy.includes(s,s.applicationScope)->return "scope"
            entries.size==1&&!(s.fineGranted&&s.fineDeclared)->return "permission"
            !updated(e,s)&&!(e.invariantDigest.isNotBlank()&&e.invariantDigest==s.invariantDigest())->return "otherPermissionState"
        }
        if(entries.size>1&&runCatching{SharedGroupPolicy.validate(fresh)}.isFailure)return "group"
        // Not unhidden and not updated: nothing to do. An app update that kept the hide is still settled (read-only checks,
        // no write) so its record, "재적용 필요" and the update notice do not linger until the app is opened (2026-10-17).
        if(fresh.none{it.bit==SENSITIVITY_BIT}&&entries.zip(fresh).none{(e,s)->updated(e,s)})return "stillHidden"
        return null
    }
}

/** Bound event bursts and avoid a hot retry loop when Android repeatedly resets a flag or a write fails. */
class WatchCooldown(private val retryMs:Long=30_000) {
    private val failures=mutableMapOf<String,Long>()
    @Synchronized fun allowed(pkg:String,now:Long)=failures[pkg]?.let{now-it>=retryMs}?:true
    @Synchronized fun result(pkg:String,now:Long,ok:Boolean){if(ok)failures.remove(pkg) else failures[pkg]=now}
}
