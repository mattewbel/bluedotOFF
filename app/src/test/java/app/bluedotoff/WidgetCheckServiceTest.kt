package app.bluedotoff

import android.app.Application
import android.content.Intent
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import app.bluedotoff.core.*
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Widget refresh without the watch (owner 2026-11-06): only for automatic management with a pairing, and a tap runs right
 *  away or not at all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class WidgetCheckServiceTest {
    private val c get()=ApplicationProvider.getApplicationContext<Application>()
    @Test fun withoutAutomaticManagementTheWidgetOffersNoWirelessRefresh(){
        assertFalse(WidgetCheckService.eligible(c))
        StatusWidget.refresh(c)
        assertFalse(StatusWidget.state(c,false).refreshing)
    }
    /** A cancel after the connection is up stops between apps without an exception (so the lease ends in order and turns
     *  wireless debugging off as usual) and saves none of the partial reads. */
    @Test fun aCancelDuringTheReadsEndsNormallyAndSavesNothing(){
        val fw=Firmware("samsung",37,"90000","fw","id","model","abi")
        val names=listOf("test.read.a","test.read.b","test.read.c")
        val snapshots=names.associateWith{p->Snapshot(Identity(p,0,10129,listOf("signer"),1,1,1),p,listOf(p),true,true,true,false,0x300,0x300,0,4,4,false)}
        val journal=JournalStore(c)
        // Recorded as hidden while the phone shows them visible: a full read would mark them for re-applying.
        names.forEach{p->assertTrue(journal.commit(Entry(snapshots.getValue(p).identity,p,256,true,TargetState.APPLIED,1,fw.key,"other-boot",true,invariantDigest="fixture",settlingVerified=true)))}
        val before=journal.entries()
        var reads=0
        val fake=object:BinderBackend(c){
            override fun liveShellServer()=true
            override fun verify()=fw
            override fun compatibleFirmware()=fw
            override fun read(packageName:String):Snapshot{reads++;return snapshots.getValue(packageName)}
            override fun <T> transact(service:String,descriptor:String,method:String,input:(Parcel)->Unit,output:(Parcel)->T):T=error("Not used")
        }
        assertFalse(WidgetCheckService.readRegistered(c,fake){reads>=1})
        assertEquals(1,reads)
        assertEquals(before,journal.entries())
        assertTrue(WidgetCheckService.readRegistered(c,fake){false})
        assertNotEquals(before,journal.entries())
    }
    private val notice="무선 디버깅을 직접 꺼주세요. 연결이 끝났는지 확인하지 못했어요."
    private fun render(s:StatusWidget.State,heightDp:Int=0)=StatusWidget.views(c,s,heightDp).apply(c,android.widget.FrameLayout(c))
    private fun View.shownText(id:Int)=findViewById<android.widget.TextView>(id).takeIf{it.visibility==View.VISIBLE}?.text?.toString()
    /** Codex follow-up 2: only a lease that really ran settles the notice; a check that never started keeps it. */
    @Test fun aCleanupNoticeStaysUntilACheckReallyClosedTheConnection(){
        Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",1)
        c.getSharedPreferences("widget",0).edit().putLong("checkedAt",1000L).commit()
        StatusWidget.finished(c,false,notice)
        StatusWidget.finished(c,false) // no Wi-Fi, expired request or timeout: no lease ran
        assertEquals(notice,StatusWidget.state(c,false).notice)
        assertEquals(1000L,c.getSharedPreferences("widget",0).getLong("checkedAt",0))
        assertEquals(UiText.text(c,notice),render(StatusWidget.state(c,false).copy(wireless=true)).shownText(R.id.widget_status))
        StatusWidget.finished(c,true,notice) // read everything, but the close was not confirmed again
        assertEquals(notice,StatusWidget.state(c,false).notice)
        StatusWidget.finished(c,true,"") // closed in order
        assertEquals("",StatusWidget.state(c,false).notice)
    }
    @Test fun aNoticeIsResolvedOnceWirelessDebuggingIsOff(){
        Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",1)
        StatusWidget.finished(c,false,notice)
        Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",0)
        assertEquals("",StatusWidget.state(c,false).notice)
        // Turned on again later: the settled notice does not come back, but a new failed cleanup is shown.
        Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",1)
        assertEquals("",StatusWidget.state(c,false).notice)
        StatusWidget.finished(c,false,notice)
        assertEquals(notice,StatusWidget.state(c,false).notice)
    }
    @Test fun aOneLineWidgetAndALostWirelessSetupStillShowTheCleanup(){
        Settings.Global.putInt(c.contentResolver,"adb_wifi_enabled",1)
        StatusWidget.finished(c,false,notice)
        val s=StatusWidget.state(c,false)
        c.resources.configuration.fontScale=2f
        try{
            val oneLine=render(s.copy(wireless=true),heightDp=40)
            assertNull(oneLine.shownText(R.id.widget_status))
            assertEquals(UiText.text(c,"무선 디버깅 끄기"),oneLine.shownText(R.id.widget_title))
        }finally{c.resources.configuration.fontScale=1f}
        val notLive=render(s.copy(wireless=false))
        assertEquals(UiText.text(c,"무선 디버깅 끄기"),notLive.shownText(R.id.widget_title))
        assertEquals(UiText.text(c,notice),notLive.shownText(R.id.widget_status))
    }
    /** Full review 2026-11-07: a background-watch user whose watch stopped (restart, USB debugging off) is told so, as in
     *  1.3.0, instead of getting the wireless refresh; with the watch switched off the same setup gets it. */
    /** Automatic management and a (fake) pairing: what makes the wireless refresh available. */
    private fun automaticManagementWithPairing(){
        org.robolectric.Shadows.shadowOf(c).grantPermissions(app.bluedotoff.adb.WirelessControls.PERMISSION)
        app.bluedotoff.adb.WirelessControls(c).setConsent(true)
        c.getSharedPreferences("local-adb",0).edit().putString("pairedGuid","fake-review-only").commit()
        java.io.File(c.noBackupFilesDir,"local-adb-identity.enc").writeText("fake placeholder, never decrypted")
    }
    /** Owner 2026-11-08: a tap without Wi-Fi says so and starts nothing (it looked broken: "확인 중…" only flickered). */
    @Test fun aTapWithoutWiFiSaysSoAndStartsNothing(){
        automaticManagementWithPairing()
        assertTrue(WidgetCheckService.eligible(c));assertFalse(WifiConnection.connected(c))
        StatusWidget.refresh(c)
        assertEquals(UiText.text(c,"Wi-Fi에 연결해주세요."),org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertNull(org.robolectric.Shadows.shadowOf(c).nextStartedService)
        assertFalse(StatusWidget.state(c,false).refreshing)
    }
    @Test fun aStoppedWatchStillShowsWatchOff(){
        automaticManagementWithPairing()
        BackgroundWatch.set(c,true)
        assertFalse(WidgetCheckService.eligible(c))
        val stopped=StatusWidget.state(c,false)
        assertFalse(stopped.wireless)
        assertEquals(UiText.text(c,"감시 꺼짐"),render(stopped).shownText(R.id.widget_title))
        BackgroundWatch.set(c,false)
        assertTrue(WidgetCheckService.eligible(c))
        assertTrue(StatusWidget.state(c,false).wireless)
    }
    @Test fun anOldRequestIsDroppedAndTouchesNothing(){
        c.getSharedPreferences("widget",0).edit().putLong("checkedAt",1000L).commit()
        val stale=Intent(c,WidgetCheckService::class.java).putExtra("at",SystemClock.elapsedRealtime()-60_000)
        Robolectric.buildService(WidgetCheckService::class.java,stale).create().startCommand(0,1)
        assertEquals(1000L,c.getSharedPreferences("widget",0).getLong("checkedAt",0))
        assertFalse(java.io.File(c.noBackupFilesDir,"system-setting-events.jsonl").exists())
    }
}
