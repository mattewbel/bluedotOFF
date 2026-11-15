package app.bluedotoff

import android.content.pm.PackageManager
import app.bluedotoff.core.*

/** Local package visibility cannot establish absence. Full state comes from the backend read. */
object LocalEntryEvents {
    fun installedIdentity(pm:PackageManager, entry:Entry):Identity? {
        val pkg=entry.identity.packageName
        val info=try { pm.getPackageInfo(pkg,PackageManager.GET_SIGNING_CERTIFICATES) }
            catch (_:PackageManager.NameNotFoundException) { return null }
            catch (_:SecurityException) { return null }
        val uid=info.applicationInfo?.uid ?: return null
        val signers=BinderBackend.signers(info)
        if(signers.isNullOrEmpty())return null
        return Identity(pkg,entry.identity.userId,uid,signers,info.firstInstallTime,info.longVersionCode,info.lastUpdateTime)
    }
    fun read(e:Entry,fingerprint:String,boot:String,localIdentity:()->Identity?):Entry {
        if(!e.enabled || e.state in listOf(TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW,TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE,TargetState.PENDING_SHOW,TargetState.MISSING_TARGET))return e
        // Package metadata cannot overturn a backend permission rejection; the next full read can.
        if(e.state==TargetState.UNSUPPORTED_STATE){
            val identity=localIdentity()?:return e
            return if(!e.identity.sameInstall(identity))e.copy(state=TargetState.INACTIVE_INSTALL,enabled=false) else e
        }
        if(e.firmware.substringBefore('|')!=fingerprint)return e.copy(state=TargetState.NEEDS_RECHECK_FIRMWARE)
        if(e.bootSession!=boot && BootEvents.hasBootEvidence(e,boot))return Reconcile.boot(e)
        val identity=localIdentity()?:return e
        if(!e.identity.sameInstall(identity))return e.copy(state=TargetState.INACTIVE_INSTALL,enabled=false)
        return Reconcile.observedUpdate(e,identity)
    }
}
