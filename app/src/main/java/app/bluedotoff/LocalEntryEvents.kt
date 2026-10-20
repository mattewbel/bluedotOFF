package app.bluedotoff

import app.bluedotoff.core.*

/** Local package visibility cannot establish absence. Full state comes from the backend read. */
object LocalEntryEvents {
    fun read(e:Entry,fingerprint:String,boot:String,localIdentity:()->Identity?):Entry {
        if(!e.enabled || e.state in listOf(TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW,TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE,TargetState.PENDING_SHOW,TargetState.MISSING_TARGET))return e
        if(e.firmware.substringBefore('|')!=fingerprint)return e.copy(state=TargetState.NEEDS_RECHECK_FIRMWARE)
        if(e.bootSession!=boot)return Reconcile.boot(e)
        val identity=localIdentity()?:return e
        if(!e.identity.sameInstall(identity))return e.copy(state=TargetState.INACTIVE_INSTALL,enabled=false)
        if(identity.versionCode!=e.identity.versionCode || identity.lastUpdateTime!=e.identity.lastUpdateTime)return Reconcile.update(e)
        return e
    }
}
