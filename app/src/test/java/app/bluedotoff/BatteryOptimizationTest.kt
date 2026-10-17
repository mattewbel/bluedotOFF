package app.bluedotoff

import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BatteryOptimizationTest {
    private fun app()=RuntimeEnvironment.getApplication()

    @Test fun requestIntentTargetsOnlyThisPackage(){
        val context=app()
        val intent=BatteryOptimization.requestIntent(context)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,intent.action)
        assertEquals(Uri.fromParts("package",context.packageName,null),intent.data)
    }

    @Test fun openingRequestDoesNotPretendApprovalWasGranted(){
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val pm=shadowOf(activity.getSystemService(PowerManager::class.java)!!)
        pm.setIgnoringBatteryOptimizations(activity.packageName,false)
        shadowOf(activity.getSystemService(ActivityManager::class.java)!!).setBackgroundRestricted(false)

        assertTrue(BatteryOptimization.open(activity))
        val started=shadowOf(activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,started.action)
        assertEquals(Uri.fromParts("package",activity.packageName,null),started.data)
        assertFalse(BatteryOptimization.unrestricted(activity) ?: true)
    }

    @Test fun deniedSettingsLaunchAlsoLeavesStateUnapproved(){
        val context=app()
        shadowOf(context.getSystemService(PowerManager::class.java)!!).setIgnoringBatteryOptimizations(context.packageName,false)
        shadowOf(context.getSystemService(ActivityManager::class.java)!!).setBackgroundRestricted(false)
        val denied=object:ContextWrapper(context){override fun startActivity(intent:android.content.Intent){throw ActivityNotFoundException("not available")}}

        assertFalse(BatteryOptimization.open(denied))
        assertFalse(BatteryOptimization.unrestricted(context) ?: true)
    }

    @Test fun allowlistedAppFallsBackToAppInfoEvenIfBackgroundRestricted(){
        for(restricted in listOf(false,true)){
            val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
            shadowOf(activity.getSystemService(PowerManager::class.java)!!).setIgnoringBatteryOptimizations(activity.packageName,true)
            shadowOf(activity.getSystemService(ActivityManager::class.java)!!).setBackgroundRestricted(restricted)

            assertTrue(BatteryOptimization.open(activity))
            val started=shadowOf(activity).nextStartedActivity
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,started.action)
            assertEquals(Uri.fromParts("package",activity.packageName,null),started.data)
            activity.finish()
        }
    }

    @Test fun explicitOffAlwaysOpensAppInfoAndLeavesSystemStateUnchanged(){
        for(allowlisted in listOf(false,true)){
            val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
            shadowOf(activity.getSystemService(PowerManager::class.java)!!).setIgnoringBatteryOptimizations(activity.packageName,allowlisted)
            shadowOf(activity.getSystemService(ActivityManager::class.java)!!).setBackgroundRestricted(false)
            val before=BatteryOptimization.unrestricted(activity)

            assertTrue(BatteryOptimization.open(activity,enable=false))
            val started=shadowOf(activity).nextStartedActivity
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,started.action)
            assertEquals(Uri.fromParts("package",activity.packageName,null),started.data)
            assertEquals(before,BatteryOptimization.unrestricted(activity))
            val toast=ShadowToast.getTextOfLatestToast()
            assertTrue("Toast should identify Battery",toast.contains("배터리")||toast.contains("Battery"))
            assertTrue("Toast should identify Optimized",toast.contains("최적화")||toast.contains("Optimized"))
            activity.finish()
        }
    }

    @Test fun unrestrictedRequiresAllowlistingAndNoBackgroundRestriction(){
        val context=app()
        val pm=shadowOf(context.getSystemService(PowerManager::class.java)!!)
        val am=shadowOf(context.getSystemService(ActivityManager::class.java)!!)
        for(allowlisted in listOf(false,true))for(restricted in listOf(false,true)){
            pm.setIgnoringBatteryOptimizations(context.packageName,allowlisted)
            am.setBackgroundRestricted(restricted)
            assertEquals(allowlisted&&!restricted,BatteryOptimization.unrestricted(context))
        }
    }
}
