package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class StartupScanTest {
    private val firmware=Firmware("samsung",37,"90000","fp","id","fold","proof")
    private val snapshot=Snapshot(Identity("example.app",0,10001,listOf("signer"),1,2,3),"App",listOf("example.app"),true,true,true,false,512,768,0,0,0,false)
    private val entry=Entry(snapshot.identity,"App",256,true,TargetState.APPLIED,10,firmware.key,"boot",true,invariantDigest=snapshot.invariantDigest(),settlingVerified=true)
    private val result=DiscoveryResult(listOf(DiscoveredApp("example.app","App",10001)),true,ScanMetrics(installed=1))
    private fun prepared()=PreparedStartupScan(firmware,"boot",listOf(entry),listOf(snapshot),result)
    private fun fails(block:()->Unit){try{block();fail("Expected rejection")}catch(_:IllegalStateException){}catch(_:java.util.concurrent.CancellationException){}}
    @Test fun firmwareFailurePrecedesAnyPackageOrPermissionRead(){
        var reads=0
        fails{StartupScan.read({firmware.copy(oneUi="80000")},{reads++;listOf(entry)},{reads++;result},{reads++;snapshot},{_,_->reads++;listOf(entry)},"boot"){true}}
        assertEquals(0,reads)
    }
    @Test fun capturedRegisteredSnapshotIsReusedAndOriginalOnBitIsPreserved(){
        val calls=mutableListOf<String>()
        val scan=StartupScan.read({calls+="profile";firmware},{calls+="journal";listOf(entry)},
            {capture->calls+="scan";capture(snapshot);result},{error("Duplicate target read")},
            {snapshots,fw->calls+="reconcile";listOf(Reconcile.read(entry,snapshots["example.app"],fw.key,"boot"))},"boot"){true}
        assertEquals(listOf("profile","journal","scan","reconcile"),calls)
        assertEquals(256,scan.entries.single().originalBit);assertEquals(TargetState.APPLIED,scan.entries.single().state)
        assertEquals(listOf(snapshot),scan.snapshots)
    }
    @Test fun freshEmptyJournalDoesNotAdoptOffAppsOrInventOriginalValues(){
        val scan=StartupScan.read({firmware},{emptyList()},{capture->capture(snapshot);result},{error("Unexpected target read")},{s,_->assertTrue(s.isEmpty());emptyList()},"boot"){true}
        assertTrue(scan.entries.isEmpty());assertTrue(scan.snapshots.isEmpty());assertEquals(result,scan.discovery)
    }
    @Test fun uncapturedRegisteredTargetGetsExactlyOneFallbackRead(){
        var reads=0
        val scan=StartupScan.read({firmware},{listOf(entry)},{result},{reads++;snapshot},{s,_->assertEquals(snapshot,s["example.app"]);listOf(entry)},"boot"){true}
        assertEquals(1,reads);assertEquals(listOf(snapshot),scan.snapshots)
    }
    @Test fun partialScanFailureCannotReconcileOrReturnReady(){
        var reconciled=false
        fails{StartupScan.read({firmware},{listOf(entry)},{capture->capture(snapshot);error("socket lost")},{snapshot},{_,_->reconciled=true;listOf(entry)},"boot"){true}}
        assertFalse(reconciled)
    }
    @Test fun cancellationAfterFullScanCannotCommitJournal(){
        var active=true;var reconciled=false
        fails{StartupScan.read({firmware},{listOf(entry)},{capture->capture(snapshot);active=false;result},{snapshot},{_,_->reconciled=true;listOf(entry)},"boot"){active}}
        assertFalse(reconciled)
    }
    @Test fun storageFailureCannotProducePreparedResult(){
        fails{StartupScan.read({firmware},{listOf(entry)},{result},{snapshot},{_,_->error("storage corrupt")},"boot"){true}}
    }
    @Test fun observedOnBitIsReapplyRequiredWithoutChangingOriginalBit(){
        val changed=snapshot.copy(fineFlags=768)
        val scan=StartupScan.read({firmware},{listOf(entry)},{capture->capture(changed);result},{error("Duplicate read")},{s,fw->listOf(Reconcile.read(entry,s["example.app"],fw.key,"boot"))},"boot"){true}
        assertEquals(TargetState.NEEDS_REAPPLY_POLICY,scan.entries.single().state);assertEquals(256,scan.entries.single().originalBit)
    }
    @Test fun ordinaryRefreshCanReconcileRemovedRegisteredTarget(){
        val scan=StartupScan.read({firmware},{listOf(entry)},{result},{null},{s,fw->listOf(Reconcile.read(entry,s["example.app"],fw.key,"boot"))},"boot"){true}
        assertEquals(TargetState.MISSING_TARGET,scan.entries.single().state);assertEquals(256,scan.entries.single().originalBit);assertTrue(scan.snapshots.isEmpty())
    }
    @Test fun completedReceiptCanBeConsumedOnlyOnce(){
        val cache=StartupScanCache{100};assertTrue(cache.publish(cache.revision(),prepared()))
        assertEquals(prepared(),cache.take("fp","boot",listOf(entry)));assertNull(cache.take("fp","boot",listOf(entry)))
    }
    @Test fun installationChangeRejectsLatePublication(){
        val cache=StartupScanCache{100};val revision=cache.revision();cache.invalidate()
        assertFalse(cache.publish(revision,prepared()));assertNull(cache.take("fp","boot",listOf(entry)))
    }
    @Test fun installationChangeInvalidatesPublishedReceipt(){
        val cache=StartupScanCache{100};cache.publish(cache.revision(),prepared());cache.invalidate()
        assertNull(cache.take("fp","boot",listOf(entry)))
    }
    @Test fun expiredReceiptFallsBackToNormalReads(){
        var clock=100L;val cache=StartupScanCache{clock};cache.publish(cache.revision(),prepared());clock+=60_000
        assertNull(cache.take("fp","boot",listOf(entry)))
    }
    @Test fun clockRollbackCannotExtendReceipt(){
        var clock=100L;val cache=StartupScanCache{clock};cache.publish(cache.revision(),prepared());clock=90
        assertNull(cache.take("fp","boot",listOf(entry)))
    }
    @Test fun changedFirmwareOrBootCannotReuseReceipt(){
        for((fp,boot) in listOf("changed" to "boot","fp" to "new-boot")){
            val cache=StartupScanCache{100};cache.publish(cache.revision(),prepared());assertNull(cache.take(fp,boot,listOf(entry)))
        }
    }
    @Test fun modifiedJournalCannotBeOverwrittenByOldDisplayReceipt(){
        val cache=StartupScanCache{100};cache.publish(cache.revision(),prepared())
        assertNull(cache.take("fp","boot",listOf(entry.copy(state=TargetState.NEEDS_REVIEW))))
    }
    @Test fun incompatibleProofCannotBePublished(){
        val cache=StartupScanCache{100};assertFalse(cache.publish(cache.revision(),prepared().copy(firmware=firmware.copy(abiProfile=""))))
    }
}
