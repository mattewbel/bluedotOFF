package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class LifecycleBatchTest {
    private val fw=Firmware("samsung",37,"90000","fp","id","Galaxy","abi")
    private val data=mutableMapOf<String,Snapshot>()
    private val entries=mutableMapOf<String,Entry>()
    private val writes=mutableListOf<Pair<String,Int>>()
    private val journal=object:Journal{override fun entries()=entries.values.toList();override fun commit(entry:Entry):Boolean{entries[entry.identity.packageName]=entry;return true}}
    private val port=object:TargetPort{
        override fun compatibleFirmware()=fw
        override fun read(packageName:String)=data[packageName]?:throw SafetyException("Missing package")
        override fun writeFineBit(packageName:String,originalBit:Int){writes.add(packageName to originalBit);val s=read(packageName);data[packageName]=s.copy(fineFlags=(s.fineFlags and 256.inv()) or originalBit)}
    }
    private val engine=SafetyEngine(port,journal,{200},{"boot"},wait={})
    private fun seed(pkg:String="test.app",bit:Int=256):Entry {
        val id=Identity(pkg,0,10000+data.size,listOf("cert"),1,1,1)
        data[pkg]=Snapshot(id,pkg,listOf(pkg),true,true,true,false,0x200 or bit,0x300,0,0,0,false)
        return engine.userHide(pkg)
    }
    private fun current(e:Entry)=Reconcile.read(e,data[e.identity.packageName],fw.key,"boot")
    @Test fun aliveUpdateMarkerDoesNotWrite(){val e=seed();writes.clear();assertEquals(TargetState.NEEDS_REAPPLY_UPDATE,current(Reconcile.update(e)).state);assertTrue(writes.isEmpty())}
    @Test fun missedUpdateSameSignerDetected(){val e=seed();data["test.app"]=data.getValue("test.app").let{it.copy(identity=it.identity.copy(versionCode=2,lastUpdateTime=2))};assertEquals(TargetState.NEEDS_REAPPLY_UPDATE,current(e).state)}
    @Test fun updateWithOtherPermissionFlagsNeedsReapplyStillNoWrite(){val e=seed();writes.clear();val s=data.getValue("test.app");data["test.app"]=s.copy(identity=s.identity.copy(versionCode=2),fineFlags=s.fineFlags or 0x10);assertEquals(TargetState.NEEDS_REAPPLY_UPDATE,current(e).state);assertTrue(writes.isEmpty())}
    @Test fun reinstallDifferentUidInactive(){val e=seed();val s=data.getValue("test.app");data["test.app"]=s.copy(identity=s.identity.copy(uid=11000,firstInstallTime=2));assertEquals(TargetState.INACTIVE_INSTALL,current(e).state)}
    @Test fun signerChangeInactive(){val e=seed();val s=data.getValue("test.app");data["test.app"]=s.copy(identity=s.identity.copy(signers=listOf("different")));assertEquals(TargetState.INACTIVE_INSTALL,current(e).state)}
    @Test fun missingPackageRetainsOriginal(){val e=seed();data.clear();assertEquals(TargetState.MISSING_TARGET,current(e).state);assertEquals(256,current(e).originalBit)}
    @Test fun alreadyOffAfterUpdateNoSetterButSettled(){val e=seed();val s=data.getValue("test.app");data["test.app"]=s.copy(identity=s.identity.copy(versionCode=2,lastUpdateTime=2));entries["test.app"]=Reconcile.update(e);writes.clear();val applied=engine.userHide("test.app");assertEquals(TargetState.APPLIED,applied.state);assertTrue(applied.settlingVerified);assertEquals(256,applied.originalBit);assertTrue(writes.isEmpty())}
    @Test fun onAfterUpdateExplicitReapplyWritesOnlyBit(){val e=seed();val s=data.getValue("test.app");data["test.app"]=s.copy(identity=s.identity.copy(versionCode=2,lastUpdateTime=2),fineFlags=0x300);entries["test.app"]=Reconcile.update(e);writes.clear();assertEquals(TargetState.APPLIED,engine.userHide("test.app").state);assertEquals(listOf("test.app" to 0),writes);assertEquals(0x200,data.getValue("test.app").fineFlags)}
    @Test fun duplicateUpdateEventsIdempotent(){val e=seed();assertEquals(Reconcile.update(e),Reconcile.update(Reconcile.update(e)))}
    @Test fun batchThreeIndependentSuccess(){listOf("a.app","b.app","c.app").forEach{seed(it)};writes.clear();val results=TargetBatch.run(listOf("a.app","b.app","c.app"),engine::userRestore);assertTrue(results.all{it.success});assertEquals(3,writes.size);assertTrue(data.values.all{it.fineFlags==0x300&&it.coarseFlags==0x300})}
    @Test fun staleIdentityFailsOnlyThatTarget(){listOf("a.app","b.app","c.app").forEach{seed(it)};data["b.app"]=data.getValue("b.app").let{it.copy(identity=it.identity.copy(uid=13000))};writes.clear();val r=TargetBatch.run(listOf("a.app","b.app","c.app"),engine::userRestore);assertEquals(listOf(true,false,true),r.map{it.success});assertEquals(listOf("a.app","c.app"),writes.map{it.first})}
    @Test fun sharedUidChangeFailsOnlyThatTarget(){seed("a.app");seed("b.app");data["a.app"]=data.getValue("a.app").copy(peers=listOf("a.app","shared.app"));writes.clear();val r=TargetBatch.run(listOf("a.app","b.app"),engine::userRestore);assertEquals(listOf(false,true),r.map{it.success});assertEquals(listOf("b.app" to 256),writes)}
    @Test fun missingDoesNotBlockOthersOrEraseJournal(){seed("a.app");seed("b.app");data.remove("a.app");writes.clear();val r=TargetBatch.run(listOf("a.app","b.app"),engine::userRestore);assertEquals(listOf(false,true),r.map{it.success});assertEquals(256,entries.getValue("a.app").originalBit)}
    @Test fun originalOnOffMixtureAndAlreadyOriginal(){seed("a.app",0);seed("b.app");seed("c.app");data["c.app"]=data.getValue("c.app").copy(fineFlags=0x300);writes.clear();val r=TargetBatch.run(listOf("a.app","b.app","c.app"),engine::userRestore);assertTrue(r.all{it.success});assertEquals(listOf("b.app" to 256),writes);assertEquals(0x200,data.getValue("a.app").fineFlags);assertTrue(data.values.all{it.coarseFlags==0x300})}
    @Test fun uncertainRollbackStopsLaterTargetsWithoutBatchRollback(){val e=seed("a.app");val called=mutableListOf<String>();val r=TargetBatch.run(listOf("a.app","b.app")){pkg->called.add(pkg);e.copy(state=TargetState.FAILED_ROLLBACK,error="복원 확인 필요")};assertEquals(listOf("a.app"),called);assertTrue(r.none{it.success});assertTrue(r[1].error.contains("변경하지 않았습니다"))}
    @Test fun disconnectedSessionStopsFurtherActions(){val called=mutableListOf<String>();val r=TargetBatch.run(listOf("a.app","b.app")){pkg->called.add(pkg);throw IllegalStateException("Shizuku stopped")};assertEquals(listOf("a.app"),called);assertEquals(2,r.size);assertTrue(r.none{it.success})}
}
