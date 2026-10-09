package app.bluedotoff.core

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Injected wire-loss model. No Android target metadata is touched by these tests. */
class LocalTransportRecoveryTest {
    private val firmware=Firmware("samsung",37,"90000","fp","id","Fold7","verified")
    private val original=Snapshot(Identity("test.location",0,10502,listOf("signer"),1,1,1),"Test",listOf("test.location"),true,true,true,false,0x300,0x300,0,4,4,false)
    private class Store:Journal {
        var entry:Entry?=null
        override fun entries()=listOfNotNull(entry)
        override fun commit(entry:Entry):Boolean{this.entry=entry;return true}
    }
    @Test fun disconnectBeforeSetterLeavesPendingAndReadonlyRecoveryCannotPromiseApplied(){
        val store=Store();var reads=0;var lost=true;var writes=0
        val port=object:TargetPort {
            override fun compatibleFirmware()=firmware
            override fun read(packageName:String):Snapshot{if(lost&&++reads==2)throw IOException("wire closed");return original}
            override fun writeFineBit(packageName:String,originalBit:Int){writes++}
        }
        try{SafetyEngine(port,store,{0},{"count:54"},wait={}).userHide("test.location");fail()}catch(_:IOException){}
        assertEquals(0,writes);assertEquals(TargetState.PENDING_HIDE,store.entry!!.state)
        lost=false
        val recovered=Reconcile.read(store.entry!!,port.read("test.location"),firmware.key,"count:54")
        assertEquals(TargetState.FAILED_SAFE,recovered.state);assertEquals(256,recovered.originalBit);assertEquals(0,writes)
    }
    @Test fun lostAcknowledgmentAfterMutationNeverRetriesAndReconnectKeepsStickyReview(){
        val store=Store();var current=original;var lost=false;val writes=mutableListOf<Int>()
        val port=object:TargetPort {
            override fun compatibleFirmware()=firmware
            override fun read(packageName:String):Snapshot{if(lost)throw IOException("wire closed");return current}
            override fun writeFineBit(packageName:String,originalBit:Int){writes+=originalBit;current=current.copy(fineFlags=(current.fineFlags and 256.inv()) or originalBit);lost=true;throw IOException("acknowledgment lost")}
        }
        val engine=SafetyEngine(port,store,{0},{"count:54"},wait={})
        assertEquals(TargetState.FAILED_ROLLBACK,engine.userHide("test.location").state)
        assertEquals(listOf(0),writes);assertEquals(0x200,current.fineFlags)
        lost=false
        val recovered=Reconcile.read(store.entry!!,port.read("test.location"),firmware.key,"count:54")
        assertEquals(TargetState.FAILED_ROLLBACK,recovered.state);assertEquals(256,recovered.originalBit)
        try{engine.userRestore("test.location");fail()}catch(_:SafetyException){}
        assertEquals(listOf(0),writes)
    }
    @Test fun reconnectAfterPendingRestoreAlreadyOriginalClosesJournalWithoutSetter(){
        val pending=Entry(original.identity,"Test",256,true,TargetState.PENDING_RESTORE,0,firmware.key,"count:54",true,invariantDigest=original.invariantDigest())
        val recovered=Reconcile.read(pending,original,firmware.key,"count:54")
        assertEquals(TargetState.NOT_APPLIED,recovered.state);assertFalse(recovered.enabled);assertFalse(recovered.changedByUs)
    }
}
