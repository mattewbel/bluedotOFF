package app.bluedotoff

import android.content.Context
import app.bluedotoff.core.*

/** Scope changes are durable only after every excluded registered target is restored. */
class ScopePreferences(context:Context) {
    private val context=context.applicationContext
    private val prefs=context.getSharedPreferences("application-scope",Context.MODE_PRIVATE)
    // Keep the legacy fallback for upgrades that already have registrations
    // but predate scope preferences. First setup still requires explicit choice.
    val current get()=ApplicationScope.fromStoredLevel(prefs.getInt("level",ApplicationScope.GROUPS.level))
    val resolved get()=prefs.getBoolean("resolved",false)
    val requested get()=prefs.getInt("requested",0).takeIf{it in 1..3}?.let(ApplicationScope::fromStoredLevel)
    val pending get()=prefs.getInt("pending",0).takeIf{it in 1..3}?.let(ApplicationScope::fromLevel)
    fun selectInitial(scope:ApplicationScope):Boolean {
        // An update can still have legacy registrations. Let the normal migration
        // preview restore them before reducing the effective admission policy.
        if(scope.level<current.level&&JournalStore(context).entries().any{it.enabled})
            return prefs.edit().putBoolean("resolved",true).putInt("requested",scope.level).commit()
        return select(scope)
    }
    fun select(scope:ApplicationScope):Boolean=prefs.edit().putInt("level",ApplicationScope.fromStoredLevel(scope.level).level).putBoolean("resolved",true).remove("pending").remove("targets").remove("requested").commit()
    fun beginRestore(scope:ApplicationScope,targets:Set<String>):Boolean {
        require(scope.level<current.level&&targets.isNotEmpty())
        return prefs.edit().putInt("pending",scope.level).putStringSet("targets",targets).remove("requested").commit()
    }
    fun finishRestore(outcome:BatchOutcome):Boolean {
        val target=pending?:return false
        val names=prefs.getStringSet("targets",emptySet())!!.toSet()
        if(outcome.hide||names.isEmpty()||names.any{p->outcome.results.none{it.packageName==p&&it.success}}||
            names.any{p->outcome.entries.lastOrNull{it.identity.packageName==p}?.let{!it.enabled&&it.state==TargetState.NOT_APPLIED}!=true})return false
        return select(target)
    }
}
