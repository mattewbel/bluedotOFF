package app.bluedotoff

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import app.bluedotoff.core.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class StartupScanIntegrationTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val firmware=Firmware("samsung",37,"90000","fp","id","fold","profile")
    private val snapshot=Snapshot(Identity("sample.app",0,10001,listOf("signer"),1,2,3),"Sample",listOf("sample.app"),true,true,true,false,512,768,0,0,0,false)
    private val entry=Entry(snapshot.identity,"Sample",256,true,TargetState.APPLIED,10,firmware.key,"boot",true,invariantDigest=snapshot.invariantDigest(),settlingVerified=true)
    private val result=DiscoveryResult(listOf(DiscoveredApp("sample.app","Sample",10001)),true,ScanMetrics(installed=1))
    private fun collect(store:JournalStore,capture:Snapshot=snapshot)=StartupScan.read({firmware},store::entries,
        {emit->emit(capture);result},{error("Registered snapshot read twice")},
        {s,f->check(store.transform{Reconcile.read(it,s[it.identity.packageName],f.key,"boot")});store.entries()},"boot"){true}
    @Before fun reset(){StartupScanHandoff.cache.invalidate();File(context.noBackupFilesDir,"original-bit-journal.json").delete()}
    @After fun end(){StartupScanHandoff.cache.invalidate()}
    @Test fun realJournalReconcilePreservesOriginalAndDoesNotReadRegisteredTargetTwice(){
        val store=JournalStore(context);assertTrue(store.commit(entry));val scan=collect(store)
        assertEquals(listOf(entry),scan.entries);assertEquals(listOf(entry),store.entries())
        val cache=StartupScanHandoff.cache;assertTrue(cache.publish(cache.revision(),scan))
        assertNotNull(cache.take("fp","boot",store.entries()));assertNull(cache.take("fp","boot",store.entries()))
    }
    @Test fun osOnReadUpdatesStateWithoutReplacingOriginalOrMakingAnApplyRecord(){
        val store=JournalStore(context);assertTrue(store.commit(entry));val scan=collect(store,snapshot.copy(fineFlags=768))
        val saved=JournalStore(context).entries().single()
        assertEquals(256,saved.originalBit);assertEquals(TargetState.NEEDS_REAPPLY_POLICY,saved.state)
        assertEquals(saved,scan.entries.single());assertEquals(10L,saved.lastVerifiedTime)
    }
    @Test fun corruptedJournalFailsBeforeAnyDiscoveryAndRemainsUntouched(){
        val file=File(context.noBackupFilesDir,"original-bit-journal.json");file.parentFile!!.mkdirs();file.writeText("corrupt")
        var scanned=false
        try{StartupScan.read({firmware},JournalStore(context)::entries,{scanned=true;result},{snapshot},{_,_->emptyList()},"boot"){true};fail("Expected corruption")}
        catch(_:Exception){}
        assertFalse(scanned);assertEquals("corrupt",file.readText());assertNull(StartupScanHandoff.cache.take("fp","boot",emptyList()))
    }
    @Test fun packageAdditionWhileOnlySetupIsPresentInvalidatesReceipt(){
        StartupScanHandoff.observePackages(context)
        val cache=StartupScanHandoff.cache;val scan=PreparedStartupScan(firmware,"boot",emptyList(),emptyList(),result)
        assertTrue(cache.publish(cache.revision(),scan))
        context.sendBroadcast(Intent(Intent.ACTION_PACKAGE_ADDED,Uri.parse("package:new.application")))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(cache.take("fp","boot",emptyList()))
    }
}
