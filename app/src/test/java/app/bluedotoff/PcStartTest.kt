package app.bluedotoff

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.adb.PcStart
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 1.2.1 "PC로 시작": the script the PC runs, and where the app writes it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class PcStartTest {
    private val c get()=ApplicationProvider.getApplicationContext<Application>()
    /** Only the shell user starts the same helper as the wireless path; no network port, LF only (sh on the phone). */
    @Test fun theScriptStartsOnlyTheWatchHelperAsShell(){
        val s=PcStart.SCRIPT
        assertTrue(s.startsWith("#!/system/bin/sh\n"));assertFalse(s.contains('\r'));assertTrue(s.endsWith("\n"))
        assertTrue(s.contains("if [ \"$(id -u)\" != 2000 ]; then"))
        assertTrue(s.contains("pm path --user 0 app.bluedotoff"))
        assertTrue(s.contains("CLASSPATH=\"\$APK\" nohup setsid app_process /system/bin app.bluedotoff.adb.ResidentWatchHelper \"\$APK\""))
        for(word in listOf("tcpip","pair","connect","settings put","pm grant","appops"))assertFalse(word,s.contains(word))
        // Run inside the platform-tools folder: PowerShell needs .\ for a program in the current folder, the Mac shell ./
        assertEquals(".\\adb shell sh /sdcard/Android/data/${c.packageName}/files/start.sh",PcStart.WINDOWS)
        assertEquals("./adb shell sh /sdcard/Android/data/${c.packageName}/files/start.sh",PcStart.MAC)
    }
    @Test fun prepareWritesTheScriptIntoTheAppFolderAndRepairsIt(){
        assertTrue(PcStart.prepare(c))
        val file=File(c.getExternalFilesDir(null),"start.sh")
        assertEquals(PcStart.SCRIPT,file.readText())
        file.writeText("changed");assertTrue(PcStart.prepare(c));assertEquals(PcStart.SCRIPT,file.readText())
    }
}
