package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class BatchProgressTest {
    @Test fun progressDoesNotClaimCompletionBeforeCleanup(){
        assertEquals(50,BatchProgress(35,70).percent)
        assertEquals(99,BatchProgress(70,70,BatchPhase.CLOSING).percent)
        assertEquals(100,BatchProgress(70,70,BatchPhase.FINISHED).percent)
    }
    @Test fun countAndElapsedBothBoundTheNextTarget(){
        assertTrue(BatchWindow.next(11,54000))
        assertFalse(BatchWindow.next(12,100))
        assertFalse(BatchWindow.next(1,55000))
        assertTrue(BatchWindow.next(0,55000))
    }
    @Test fun uncertainOwnershipStopsFollowingTargetsAcrossConnections(){
        val runner=TargetBatch.Runner()
        val first=runner.run("first"){throw OwnershipChangedException()}
        var called=false
        val next=runner.run("next"){called=true;error("must not be called in a new window")}
        assertFalse(first.success);assertFalse(next.success);assertFalse(called)
    }
    @Test fun seventyTargetsKeepEveryFreshCheckWaitAndDurableOriginal(){
        val current=linkedMapOf<String,Snapshot>()
        repeat(70){n->val pkg="test.app$n";current[pkg]=Snapshot(Identity(pkg,0,10000+n,listOf("signer"),1,1,1),pkg,listOf(pkg),true,true,true,false,0x300,0x300,0,4,4,false)}
        val entries=linkedMapOf<String,Entry>()
        val waits=mutableListOf<Long>();var clock=0L;var writes=0
        val port=object:TargetPort {
            override fun compatibleFirmware()=Firmware("samsung",37,"90000","firmware","id","model","abi")
            override fun read(packageName:String):Snapshot{clock+=100;return current.getValue(packageName)}
            override fun writeFineBit(packageName:String,originalBit:Int){
                assertEquals(TargetState.PENDING_HIDE,entries.getValue(packageName).state)
                assertEquals(256,entries.getValue(packageName).originalBit)
                writes++;current[packageName]=current.getValue(packageName).copy(fineFlags=0x200)
            }
        }
        val journal=object:Journal {
            override fun entries()=entries.values.toList()
            override fun commit(entry:Entry):Boolean{entries[entry.identity.packageName]=entry;return true}
        }
        val engine=SafetyEngine(port,journal,{clock},{"boot"},wait={waits+=it;clock+=it})
        val runner=TargetBatch.Runner();val durations=mutableListOf<Long>()
        val names=current.keys.toList();var cursor=0
        while(cursor<names.size){val start=clock;var count=0
            while(cursor<names.size&&BatchWindow.next(count,clock-start)){
                assertTrue(runner.run(names[cursor++],engine::userHide).success);count++
            }
            durations+=clock-start
        }
        assertEquals(70,writes);assertEquals(70,waits.count{it==500L});assertEquals(70,waits.count{it==1000L})
        assertTrue(clock>120000);assertTrue(durations.size>1);assertTrue(durations.all{it<120000})
        assertTrue(entries.values.all{it.state==TargetState.APPLIED&&it.settlingVerified&&it.originalBit==256})
    }
}
