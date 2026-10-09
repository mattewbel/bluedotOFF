package app.bluedotoff.core

import java.security.MessageDigest

/** Additional helper preflight; the existing SafetyEngine remains the journal/write owner. */
object LocalWriteGuard {
    fun identityDigest(identity:Identity):String=MessageDigest.getInstance("SHA-256").digest(listOf(identity.packageName,identity.userId,identity.uid,identity.signers.joinToString(","),identity.firstInstallTime,identity.versionCode,identity.lastUpdateTime).joinToString("|").toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
    fun validate(fresh:Snapshot,identity:String,invariant:String,flags:Int,wanted:Int){
        require(wanted==0||wanted==SENSITIVITY_BIT){"Only FINE sensitivity bit is permitted"}
        require(identity.matches(Regex("[0-9a-f]{64}"))&&invariant.matches(Regex("[0-9a-f]{64}")))
        if(fresh.peers!=listOf(fresh.identity.packageName)||ScopePolicy.ownershipBlock(fresh.ownership,fresh.singletonVerified,fresh.applicationScope)!=null)throw OwnershipChangedException()
        if(!fresh.safe()||fresh.identity.userId!=0||fresh.identity.signers.isEmpty()||identityDigest(fresh.identity)!=identity||fresh.invariantDigest()!=invariant||fresh.fineFlags!=flags)throw SafetyException("Fresh helper target preflight failed")
    }
}
