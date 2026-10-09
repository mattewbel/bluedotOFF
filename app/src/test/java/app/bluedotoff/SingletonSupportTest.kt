package app.bluedotoff
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import app.bluedotoff.core.Firmware
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class SingletonSupportTest {
    private val fw=Firmware("samsung",37,"90000","fp","id","fold","abi")
    private fun info()=PackageInfo().apply{packageName="net.daum.android.map";firstInstallTime=1234000;applicationInfo=ApplicationInfo().apply{uid=10502}}
    private fun proof(state:String)=JSONObject().put("state",state).put("fingerprint","fp").put("abiProfile","abi").put("packageName","net.daum.android.map").put("uid",10502).put("firstInstallSeconds",1234).put("signerSha256","cert").put("evidenceId","hardware-aba")
    @Test fun unverifiedProofNeverAllows(){assertFalse(SingletonSupport.evaluate(proof("UNVERIFIED"),fw,info(),listOf("cert"),true))}
    @Test fun pinnedDebugTrialAllowsExactInstallation(){assertTrue(SingletonSupport.evaluate(proof("TRIAL_ONLY"),fw,info(),listOf("cert"),true))}
    @Test fun releaseNeverAllowsTrial(){assertFalse(SingletonSupport.evaluate(proof("TRIAL_ONLY"),fw,info(),listOf("cert"),false))}
    @Test fun changedSignerOrInstallCannotUseTrial(){assertFalse(SingletonSupport.evaluate(proof("TRIAL_ONLY"),fw,info(),listOf("other"),true));assertFalse(SingletonSupport.evaluate(proof("TRIAL_ONLY"),fw,info().apply{firstInstallTime=5678000},listOf("cert"),true))}
    @Test fun verifiedProofIsFirmwareAndAbiScoped(){assertTrue(SingletonSupport.evaluate(proof("VERIFIED"),fw,info(),listOf("cert"),false));assertFalse(SingletonSupport.evaluate(proof("VERIFIED"),fw.copy(fingerprint="other"),info(),listOf("cert"),false));assertFalse(SingletonSupport.evaluate(proof("VERIFIED"),fw.copy(abiProfile="other"),info(),listOf("cert"),true))}
    @Test fun missingEvidenceNeverAllowsVerified(){assertFalse(SingletonSupport.evaluate(proof("VERIFIED").put("evidenceId",""),fw,info(),listOf("cert"),true))}
}
