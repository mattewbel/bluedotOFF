package app.bluedotoff

import android.content.Context
import android.content.pm.PackageInfo
import app.bluedotoff.core.Firmware
import org.json.JSONObject

/** Proof is firmware-scoped. A debug trial can authorize only its pinned installed target. */
object SingletonSupport {
    fun allowed(context:Context,firmware:Firmware,info:PackageInfo,signers:List<String>?=null):Boolean {
        val proof=try{context.assets.open("singleton-proof.json").bufferedReader().use{JSONObject(it.readText())}}catch(_:Exception){return false}
        return evaluate(proof,firmware,info,signers,BuildConfig.DEBUG)
    }
    internal fun evaluate(proof:JSONObject,firmware:Firmware,info:PackageInfo,signers:List<String>?,debug:Boolean):Boolean {
        // A verified proof also holds after a firmware update (1.2.0, R-2); the fresh sole-peer check still runs per write.
        if(proof.optString("state")!="VERIFIED"&&(proof.optString("fingerprint")!=firmware.fingerprint || proof.optString("abiProfile")!=firmware.abiProfile))return false
        return when(proof.optString("state")) {
            "VERIFIED" -> proof.optString("evidenceId").isNotBlank()
            "TRIAL_ONLY" -> debug && proof.optString("signerSha256").isNotBlank() && info.packageName==proof.optString("packageName") &&
                info.applicationInfo?.uid==proof.optInt("uid",-1) && info.firstInstallTime/1000==proof.optLong("firstInstallSeconds",-1) &&
                (signers==null || signers==listOf(proof.optString("signerSha256")))
            else -> false
        }
    }
}
