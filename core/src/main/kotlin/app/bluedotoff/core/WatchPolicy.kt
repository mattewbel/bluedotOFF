package app.bluedotoff.core

/** Opt-in background work may only revisit verified, still-registered hides. Never enroll a new app. */
object WatchPolicy {
    private val states=setOf(TargetState.APPLIED,TargetState.NEEDS_REAPPLY_POLICY,TargetState.NEEDS_REAPPLY_UPDATE)
    fun eligible(e:Entry)=e.enabled&&!e.restoreRequested&&(e.settlingVerified&&e.state in states||retrying(e))
    /** A hide that failed safely, tried again within [WatchRetry]'s limits. */
    fun retrying(e:Entry)=e.enabled&&!e.restoreRequested&&e.state==TargetState.FAILED_SAFE&&e.retryHide
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
        if(fresh.none{it.bit==SENSITIVITY_BIT}&&entries.zip(fresh).none{(e,s)->updated(e,s)}&&entries.none(::retrying))return "stillHidden"
        return null
    }
}

/** Owner 2026-10-31: a hide that failed safely is tried again by the watch, at least [gapMs] after the failure or the
 *  last try and at most [perDay] times in 24 hours, so an app Android keeps resetting cannot become a write loop. The
 *  failure time is the record's own (the journal keeps it); the tries are kept in [store], so a restarted app process
 *  does not get a new budget (GPT rc18 review). Wall-clock times; a clock set back counts as time passed. A success
 *  ends the run of failures. */
class WatchRetry(private val store:Store,private val gapMs:Long=10*60_000L,private val perDay:Int=3) {
    interface Store { fun load(pkg:String):List<Long>; fun save(pkg:String,times:List<Long>) }
    private fun recent(pkg:String,now:Long)=store.load(pkg).filter{it<=now&&now-it<DAY}
    private fun since(e:Entry,tries:List<Long>,now:Long)=(listOf(e.lastVerifiedTime)+tries).filter{it<=now}.maxOrNull()?.let{now-it}?:gapMs
    @Synchronized fun allowed(e:Entry,now:Long)=recent(e.identity.packageName,now).let{it.size<perDay&&since(e,it,now)>=gapMs}
    @Synchronized fun tried(pkg:String,now:Long){store.save(pkg,recent(pkg,now)+now)}
    @Synchronized fun succeeded(pkg:String){if(store.load(pkg).isNotEmpty())store.save(pkg,emptyList())}
    /** When the next try may run (ms from [now]); null when the tries of these 24 hours are used up. */
    @Synchronized fun wait(e:Entry,now:Long):Long?=recent(e.identity.packageName,now).let{if(it.size>=perDay)null else maxOf(0L,gapMs-since(e,it,now))}
    private companion object{const val DAY=86_400_000L}
}

/** D-2a: one event window of the watch helper (the 750 ms check or the 5 s follow-up). Each event restarts the [delay];
 *  a steady stream used to postpone the check forever. The first event after the window is [cap] old hands back its
 *  names to send right away, and keeps them, so every app is still checked a full [delay] after its latest event. */
class EventWindow(val delay:Long,private val cap:Long) {
    private val names=linkedSetOf<String>()
    private var since=-1L
    fun add(pkg:String,now:Long):Set<String> {
        val flush=if(since>=0&&now-since>=cap){since=now;names.toSet()} else emptySet()
        if(since<0)since=now
        names+=pkg
        return flush
    }
    /** The scheduled end of the window: everything collected, window closed. */
    fun take():Set<String>{since=-1L;return names.toSet().also{names.clear()}}
}

/** Bound event bursts and avoid a hot retry loop when Android repeatedly resets a flag or a write fails. */
class WatchCooldown(private val retryMs:Long=30_000) {
    private val failures=mutableMapOf<String,Long>()
    @Synchronized fun allowed(pkg:String,now:Long)=failures[pkg]?.let{now-it>=retryMs}?:true
    @Synchronized fun result(pkg:String,now:Long,ok:Boolean){if(ok)failures.remove(pkg) else failures[pkg]=now}
}
