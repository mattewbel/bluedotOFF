package app.bluedotoff

import android.app.Application
import android.net.*
import android.os.Build
import android.os.Looper
import android.provider.Settings
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Codex review 2026-10-22: the scan finished by the setup screen clears an earlier "최근 확인 실패". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SetupScanClearsFailureTest {
    @Test fun aSuccessfulSetupScanMustClearTheEarlierFailureMarker(){
        val c=RuntimeEnvironment.getApplication()
        val controller=Robolectric.buildActivity(MainActivity::class.java).create()
        val getter=MainActivity::class.java.getDeclaredMethod("getModel").apply{isAccessible=true}
        val setter=MainActivity::class.java.getDeclaredMethod("setModel",UiModel::class.java).apply{isAccessible=true}
        val sdk=Build.VERSION.SDK_INT;val manufacturer=Build.MANUFACTURER;val abis=Build.SUPPORTED_ABIS
        val identityFile=File(c.noBackupFilesDir,"local-adb-identity.enc")
        try{
            // Only the early prerequisite branch is simulated; no Android 37 API or privileged setter is called.
            ReflectionHelpers.setStaticField(Build.VERSION::class.java,"SDK_INT",37)
            ReflectionHelpers.setStaticField(Build::class.java,"MANUFACTURER","samsung")
            ReflectionHelpers.setStaticField(Build::class.java,"SUPPORTED_ABIS",arrayOf("arm64-v8a"))
            Settings.Global.putInt(c.contentResolver,Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,1)
            Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",1)
            c.getSharedPreferences("ui-state",0).edit().putBoolean("welcomed",true).commit()
            c.getSharedPreferences("local-adb",0).edit().putString("pairedGuid","synthetic-guid").commit()
            identityFile.writeText("synthetic existence marker, never decrypted")
            ScopePreferences(c).select(ApplicationScope.ALL)
            val cm=c.getSystemService(ConnectivityManager::class.java);val n=checkNotNull(cm.activeNetwork)
            val cap=NetworkCapabilities()
            NetworkCapabilities::class.java.getMethod("addTransportType",Int::class.javaPrimitiveType).invoke(cap,NetworkCapabilities.TRANSPORT_WIFI)
            NetworkCapabilities::class.java.getMethod("addCapability",Int::class.javaPrimitiveType).invoke(cap,NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
            shadowOf(cm).setNetworkCapabilities(n,cap);shadowOf(cm).setLinkProperties(n,LinkProperties().apply{interfaceName="wlan0"})
            val fw=Firmware("samsung",37,"90000",Build.FINGERPRINT,"id","fold","profile")
            val id=Identity("demo.map",0,10792,listOf("demo"),1,1,1)
            val snapshot=Snapshot(id,"Map",listOf(id.packageName),true,true,true,false,512,768,0,4,4,false)
            val entry=Entry(id,"Map",256,true,TargetState.APPLIED,1,fw.key,BootSession.current(c),true,settlingVerified=true)
            assertTrue(JournalStore(c).commit(entry))
            val scan=PreparedStartupScan(fw,BootSession.current(c),listOf(entry),listOf(snapshot),DiscoveryResult(listOf(DiscoveredApp(id.packageName,"Map",id.uid)),true,ScanMetrics()))
            val cache=StartupScanHandoff.cache;assertTrue(cache.publish(cache.revision(),scan))
            setter.invoke(controller.get(),UiModel(gate=Gate.READY,entries=listOf(entry),lastCheckFailed=true))
            val refresh=MainActivity::class.java.declaredMethods.single{it.name=="refresh"}.apply{isAccessible=true}
            val auto=refresh.parameterTypes[1].enumConstants.single{it.toString()=="AUTO"}
            refresh.invoke(controller.get(),false,auto,emptySet<String>())
            val until=System.nanoTime()+5_000_000_000L
            while((getter.invoke(controller.get()) as UiModel).lastCheckedAt==0L&&System.nanoTime()<until){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
            val model=getter.invoke(controller.get()) as UiModel
            assertTrue("The real prepared-scan handoff was not reached: ${model.compatibility}",model.lastCheckedAt>0L)
            assertEquals("앱 확인 완료",model.compatibility)
            assertFalse("Setup succeeded but Home keeps saying the latest check failed",model.lastCheckFailed)
        }finally{
            ReflectionHelpers.setStaticField(Build.VERSION::class.java,"SDK_INT",sdk)
            ReflectionHelpers.setStaticField(Build::class.java,"MANUFACTURER",manufacturer)
            ReflectionHelpers.setStaticField(Build::class.java,"SUPPORTED_ABIS",abis)
            identityFile.delete();StartupScanHandoff.cache.invalidate();controller.destroy()
        }
    }
}
