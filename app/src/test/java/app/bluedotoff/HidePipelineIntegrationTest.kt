package app.bluedotoff

import android.app.Application
import app.bluedotoff.adb.OrderlyHelperChain
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class HidePipelineIntegrationTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val file get()=File(context.noBackupFilesDir,"original-bit-journal.json")
    @Before fun clean(){file.delete();File(file.path+".new").delete()}
    private class Device(val store:Journal,val count:Int=74) {
        var clock=0L;var active=true
        var disconnected=false
        var onImmediate:(String)->Unit={}
        val snapshots=linkedMapOf<String,Snapshot>()
        val writes=mutableListOf<Pair<String,Int>>()
        val firmware=Firmware("samsung",37,"90000","firmware","id","model","verified")
        val names get()=snapshots.keys.toList()
        init{repeat(count){n->val pkg="test.app$n";snapshots[pkg]=Snapshot(Identity(pkg,0,10000+n,listOf("signer"),1,1,1),pkg,listOf(pkg),true,true,true,false,0x300,0x300,0,4,4,false)}}
        val port=object:TargetPort {
            override fun compatibleFirmware()=firmware
            override fun read(packageName:String):Snapshot{check(!disconnected);return snapshots.getValue(packageName)}
            override fun writeFineBit(packageName:String,originalBit:Int){
                val persisted=store.entries().single{it.identity.packageName==packageName}
                assertTrue(persisted.state in listOf(TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE))
                assertEquals(256,persisted.originalBit)
                writes+=packageName to originalBit
                snapshots[packageName]=snapshots.getValue(packageName).copy(fineFlags=0x200 or originalBit)
            }
            override fun writeFineBitChecked(expected:Snapshot,wanted:Int){val fresh=read(expected.identity.packageName);LocalWriteGuard.validate(fresh,LocalWriteGuard.identityDigest(expected.identity),expected.invariantDigest(),expected.fineFlags,wanted);writeFineBit(expected.identity.packageName,wanted)}
        }
        fun engine()=SafetyEngine(port,store,{clock},{"boot"},wait={clock+=it},observe={phase,snapshot,_->if(phase=="immediate")onImmediate(snapshot.identity.packageName)})
    }
    @Test fun realJournalSeventyFourAppsDrainBeforeSevenOrderlyHelperClosuresAndHundredPercent() {
        val store=JournalStore(context);val device=Device(store);val chain=OrderlyHelperChain()
        var cursor=0;var completed=0;var closures=0;val progress=mutableListOf<BatchProgress>()
        while(cursor<74){
            chain.run{receipt->
                val window=device.engine().userHideWindow(device.names.drop(cursor).take(12),elapsed={device.clock},onResult={r->
                    assertTrue(r.success);assertEquals(r.entry,JournalStore(context).entries().single{it.identity.packageName==r.packageName})
                    completed++;progress+=BatchProgress(completed,74,BatchPhase.CHECKING)
                })
                assertFalse(window.stopped);cursor+=window.started
                assertTrue(store.entries().none{it.state==TargetState.PENDING_HIDE})
                progress+=BatchProgress(completed,74,BatchPhase.CLOSING)
                receipt.helperExited=true;receipt.streamClosed=true;closures++
                assertFalse(receipt.socketClosed)
            }
        }
        assertEquals(7,closures);assertEquals(74,completed);assertTrue(progress.all{it.percent<=99})
        assertEquals(100,BatchProgress(74,74,BatchPhase.FINISHED).percent)
        val durable=JournalStore(context).entries();assertEquals(74,durable.size)
        assertTrue(durable.all{it.settlingVerified&&it.originalBit==256&&it.state==TargetState.APPLIED})
        val restoreChain=OrderlyHelperChain();var restored=0;var restoreClosures=0
        while(restored<74){
            restoreChain.run{receipt->
                val window=device.engine().userRestoreWindow(device.names.drop(restored).take(12),elapsed={device.clock})
                assertFalse(window.stopped);assertTrue(window.results.all{it.success});restored+=window.started
                assertTrue(JournalStore(context).entries().none{it.state==TargetState.PENDING_RESTORE})
                receipt.helperExited=true;receipt.streamClosed=true;restoreClosures++
                assertFalse(receipt.socketClosed)
            }
        }
        assertEquals(74,restored);assertEquals(7,restoreClosures)
        assertTrue(device.snapshots.values.all{it.bit==256});assertTrue(JournalStore(context).entries().none{it.enabled})
    }
    @Test fun processDeathWithFourPendingTargetsPreservesSchemaAndNeverRunsRecoverySetters() {
        val store=JournalStore(context);val device=Device(store,8)
        device.onImmediate={pkg->if(pkg==device.names[3])throw SimulatedProcessDeath()}
        try{device.engine().userHideWindow(device.names,elapsed={device.clock});fail("simulated death must escape")}catch(_:SimulatedProcessDeath){}
        val recovered=JournalStore(context).entries();assertEquals(4,recovered.size)
        assertTrue(recovered.all{it.state==TargetState.PENDING_HIDE&&it.originalBit==256&&!it.settlingVerified})
        val before=device.writes.toList()
        assertTrue(store.transform{e->Reconcile.read(Reconcile.boot(e),device.snapshots[e.identity.packageName],device.firmware.key,"new-boot")})
        assertTrue(JournalStore(context).entries().all{it.state==TargetState.FAILED_SAFE})
        assertEquals(before,device.writes)
        val root=org.json.JSONObject(file.readText());assertEquals(1,root.getInt("schema"))
        assertEquals(BinderBackend.digest(root.getString("payload").toByteArray()),root.getString("checksum"))
    }
    @Test fun foregroundExitDrainPublishesOnlyStartedResultsAndPreservesSkippedApps() {
        val store=JournalStore(context);val device=Device(store,8)
        device.onImmediate={pkg->if(pkg==device.names[2])device.active=false}
        val result=device.engine().userHideWindow(device.names,canStart={device.active},elapsed={device.clock})
        assertTrue(result.stopped);assertEquals(3,result.started);assertTrue(result.results.all{it.success})
        val all=result.results+device.names.drop(result.started).map{BatchResult(it,error="작업을 멈춰서 이 앱은 바꾸지 않았어요.")}
        assertEquals(8,all.size);assertEquals(3,all.count{it.success});assertEquals(3,store.entries().size)
        assertTrue(store.entries().all{it.settlingVerified});assertTrue(device.names.drop(3).all{device.snapshots[it]!!.bit==256})
    }
    @Test fun corruptionDuringAdmissionKeepsPendingFileEvidenceAndBlocksLaterMutations() {
        val store=JournalStore(context);val device=Device(store,8)
        device.onImmediate={file.writeText("{\"schema\":1,\"payload\":\"[]\",\"checksum\":\"bad\"}")}
        val result=device.engine().userHideWindow(device.names,elapsed={device.clock})
        assertTrue(result.stopped);assertTrue(result.results.none{it.success});assertEquals(1,device.writes.size)
        assertTrue(runCatching{JournalStore(context).entries()}.isFailure)
        assertEquals(256,result.results.first().entry!!.originalBit)
    }
    @Test fun falseFinalSuccessCannotEraseDurablePendingOnDisconnectedTransport() {
        val store=JournalStore(context);val device=Device(store,8)
        device.onImmediate={pkg->if(pkg==device.names[3])device.disconnected=true}
        val result=device.engine().userHideWindow(device.names,elapsed={device.clock})
        assertTrue(result.stopped);assertTrue(result.results.none{it.success});assertEquals(4,device.writes.size)
        val saved=JournalStore(context).entries();assertEquals(4,saved.size)
        assertTrue(saved.all{it.state==TargetState.FAILED_ROLLBACK&&it.originalBit==256&&!it.settlingVerified})
    }
    private class SimulatedProcessDeath:Error("process terminated")
}
