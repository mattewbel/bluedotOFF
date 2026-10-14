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
    /** A fresh read must match the recorded install. An app update is allowed; reinstall/UID/signature change is not. */
    fun mayReapply(entries:List<Entry>,fresh:List<Snapshot>,firmware:Firmware,boot:String):Boolean =
        entries.isNotEmpty()&&entries.size==fresh.size&&entries.zip(fresh).all{(e,s)->
            eligible(e)&&e.identity.sameInstall(s.identity)&&e.bootSession==boot&&e.firmware==firmware.key&&
                ScopePolicy.includes(s,s.applicationScope)&&s.fineGranted&&s.fineDeclared&&
                (e.state==TargetState.NEEDS_REAPPLY_UPDATE||e.identity.versionCode!=s.identity.versionCode||e.identity.lastUpdateTime!=s.identity.lastUpdateTime||
                    e.invariantDigest.isNotBlank()&&e.invariantDigest==s.invariantDigest())
        }&&fresh.any{it.bit==SENSITIVITY_BIT}
}

/** Bound event bursts and avoid a hot retry loop when Android repeatedly resets a flag or a write fails. */
class WatchCooldown(private val retryMs:Long=30_000) {
    private val failures=mutableMapOf<String,Long>()
    @Synchronized fun allowed(pkg:String,now:Long)=failures[pkg]?.let{now-it>=retryMs}?:true
    @Synchronized fun result(pkg:String,now:Long,ok:Boolean){if(ok)failures.remove(pkg) else failures[pkg]=now}
}
