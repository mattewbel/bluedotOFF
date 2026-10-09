package app.bluedotoff

import android.app.Application
import android.os.Parcel
import android.os.TransactionTooLargeException
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class HistoryParcelTest {
    @Test fun homeRemainsCuratedWhenHistoryViewIsAll(){val a=DiscoveredApp("test.app","Test",10000,launcher=true,access=LocationAccess(fine=100));val internal=DiscoveredApp("android","Android",1000,true,false,LocationAccess(fine=200));val m=UiModel(discovered=listOf(internal,a),historyView=HistoryView.ALL);assertEquals(listOf(a),m.appHistory);assertEquals(listOf(internal,a),m.copy(busy=true).historyApps);assertEquals(HistoryView.ALL,m.copy(page=Page.HOME).historyView)}
    @Test fun emptyHistoryReplyHasNoSyntheticTimestamp(){val p=Parcel.obtain();try{p.writeInt(-1);p.setDataPosition(0);assertEquals(LocationAccess(),AppOpsHistory.read(p,"test.app",10000))}finally{p.recycle()}}
    @Test fun malformedIdentityRejected(){val p=Parcel.obtain();try{p.writeInt(1);p.writeInt(1);p.writeString("other.app");p.writeInt(10000);p.writeInt(0);p.setDataPosition(0);try{AppOpsHistory.read(p,"test.app",10000);fail()}catch(_:IllegalStateException){}}finally{p.recycle()}}
    @Test fun oversizedPackageCountRejected(){val p=Parcel.obtain();try{p.writeInt(2);p.setDataPosition(0);try{AppOpsHistory.read(p,"test.app",10000);fail()}catch(_:IllegalStateException){}}finally{p.recycle()}}
    @Test fun realTransactionTooLargeExceptionYieldsNoPartialScan(){var published=false;try{CompletePackageScan.collect(listOf("one.app","two.app")){if(it=="two.app")throw TransactionTooLargeException() else 10000};published=true}catch(_:TransactionTooLargeException){};assertFalse(published)}
    @Test fun historyStatusNeverClaimsUnvalidatedDotCause(){val a=DiscoveredApp("test.app","Test",10000,access=LocationAccess(fine=100));val m=UiModel(gate=Gate.READY,connected=true,discovered=listOf(a));assertEquals("앱 상태 확인 필요",m.historyStatus(a));assertEquals("방금 전",accessAge(100,200));assertEquals("2분 전",accessAge(100,120100))}
    @Test fun knownUnsupportedHistoryRetainsReasonAndBlocksAction(){val a=DiscoveredApp("test.app","Test",10000,access=LocationAccess(fine=100));val s=Snapshot(Identity("test.app",0,10000,listOf("cert"),1,1,1),"Test",listOf("test.app"),true,false,true,false,0x300,0x300,0,0,0,false);val m=UiModel(gate=Gate.READY,connected=true,discovered=listOf(a),candidates=listOf(s));assertFalse(m.canAct(a));assertTrue(m.historyStatus(a).contains("정확한 위치 권한"));assertEquals(1,m.historyApps.size)}
}
