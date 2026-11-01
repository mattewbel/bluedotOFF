package app.bluedotoff

import android.app.Application
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class LocationRetirementNoticeTest {
    private val c get()=RuntimeEnvironment.getApplication()
    private val e=Entry(Identity("test.map",0,10123,listOf("cert"),1,1,1),"Map",256,false,TargetState.NOT_APPLIED,2,"fw","boot",true,locationRetired=true)
    @Test fun archivedEntryRoundTripsAndShowsOnceWithoutCountingAsUnresolved(){
        val store=JournalStore(c);assertTrue(store.commit(e));assertEquals(e,store.entries().single())
        val model=UiModel(entries=store.entries())
        assertEquals(0,model.otherCheckCount);assertEquals(0,model.reapplyCount);assertTrue(model.visibleEntries.isEmpty())
        assertEquals("Map: 업데이트 후 위치 권한을 요청하지 않아 관리 목록에서 제외했어요.",LocationRetirementNotice.take(c,store.entries()))
        assertNull(LocationRetirementNotice.take(c,JournalStore(c).entries()))
    }
    @Test fun ordinaryFailuresDoNotProduceNoticeAndMultipleRetirementsUseOneNotice(){
        assertNull(LocationRetirementNotice.take(c,listOf(e.copy(enabled=true,locationRetired=false,state=TargetState.UNSUPPORTED_STATE))))
        val second=e.copy(identity=e.identity.copy(packageName="test.other"),label="Other")
        assertEquals("업데이트 후 위치 권한을 요청하지 않는 앱 2개를 관리 목록에서 제외했어요.",LocationRetirementNotice.take(c,listOf(e,second)))
        assertNull(LocationRetirementNotice.take(c,listOf(e,second)))
    }
}
