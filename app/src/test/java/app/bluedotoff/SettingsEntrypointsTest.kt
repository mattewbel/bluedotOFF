package app.bluedotoff

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SettingsEntrypointsTest {
    private fun register(activity:Activity,action:String,exported:Boolean=true){
        val intent=Intent(action).setPackage("com.android.settings")
        val info=ResolveInfo().apply{activityInfo=ActivityInfo().apply{
            packageName="com.android.settings";name="com.android.settings.PublicTestActivity"
            this.exported=exported;enabled=true;applicationInfo=ApplicationInfo().apply{enabled=true}
        }}
        shadowOf(activity.packageManager).addResolveInfoForIntent(intent,info)
    }
    @Test fun firmwareVersionLookalikeIsNotADeveloperOptionsShortcut(){
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try{val a=c.get();register(a,Settings.ACTION_DEVICE_INFO_SETTINGS)
            val lookalike=Intent().setComponent(android.content.ComponentName("com.android.settings","com.android.settings.Settings\$FirmwareVersionActivity"))
            shadowOf(a.packageManager).addResolveInfoForIntent(lookalike,ResolveInfo().apply{activityInfo=ActivityInfo().apply{
                packageName="com.android.settings";name="com.android.settings.Settings\$FirmwareVersionActivity"
                exported=true;enabled=true;applicationInfo=ApplicationInfo().apply{enabled=true}
            }})
            SettingsEntrypoints.softwareInformation(a)
            val opened=shadowOf(a).nextStartedActivity
            assertEquals(Settings.ACTION_DEVICE_INFO_SETTINGS,opened.action)
            assertNull(opened.component);assertEquals("com.android.settings",opened.`package`)
            assertFalse(opened.hasExtra(":settings:show_fragment"))
            assertEquals("software_info",opened.getStringExtra(":settings:fragment_args_key"))
            assertEquals("software_info",opened.getBundleExtra(":settings:show_fragment_args")!!.getString(":settings:fragment_args_key"))
            // Nothing covers the highlighted row (owner 2026-10-22).
            assertEquals(0,org.robolectric.shadows.ShadowToast.shownToastCount())
        }finally{c.pause().stop().destroy()}
    }
    @Test fun inaccessibleDirectScreenFallsBackWithoutForcingAFragment(){
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try{val a=c.get();register(a,"com.samsung.settings.SOFTWARE_INFO_SETTINGS",false);register(a,Settings.ACTION_DEVICE_INFO_SETTINGS)
            SettingsEntrypoints.softwareInformation(a)
            assertEquals(Settings.ACTION_DEVICE_INFO_SETTINGS,shadowOf(a).nextStartedActivity.action)
        }finally{c.pause().stop().destroy()}
    }
}
