package app.bluedotoff
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import app.bluedotoff.core.*
import java.io.File

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],application=Application::class)
class SharedGroupJournalTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val file get()=File(context.noBackupFilesDir,"original-bit-journal.json")
    @Before fun clean(){file.delete();File(file.path+".new").delete()}
    private fun entries():List<Entry>{val ids=listOf("test.phone","test.provider").map{Identity(it,0,10129,listOf("signer"),1,1,1)};return ids.map{Entry(it,it.packageName,256,true,TargetState.PENDING_HIDE,1,"fw","boot",false,groupIdentities=ids)}}
    @Test fun groupAtomicCommitKeepsLegacyRecordAndRoundTripsAllMemberPins(){val store=JournalStore(context);val legacy=entries().first().copy(identity=Identity("legacy.app",0,10001,listOf("old"),1,1,1),groupIdentities=emptyList());assertTrue(store.commit(legacy));assertTrue(store.commitGroup(entries()));assertEquals(3,JournalStore(context).entries().size);assertEquals(legacy,store.entries().first());assertEquals(entries(),store.entries().drop(1));val text=file.readText();assertEquals(1,org.json.JSONObject(text).getInt("schema"));assertEquals(BinderBackend.digest(org.json.JSONObject(text).getString("payload").toByteArray()),org.json.JSONObject(text).getString("checksum"))}
    @Test fun incompleteGroupNeverOverwritesTheJournal(){val store=JournalStore(context);assertTrue(store.commitGroup(entries()));val before=file.readBytes();assertFalse(store.commitGroup(entries().take(1)));assertArrayEquals(before,file.readBytes())}
    @Test fun memberPinsCannotBeDroppedOrReorderedSilently(){val store=JournalStore(context);val bad=entries().map{it.copy(groupIdentities=it.groupIdentities.reversed())};assertFalse(store.commitGroup(bad));assertFalse(file.exists())}
    @Test fun corruptedGroupJournalCannotBecomeAnEmptyRecord(){val store=JournalStore(context);assertTrue(store.commitGroup(entries()));file.writeText("{\"schema\":1,\"payload\":\"[]\",\"checksum\":\"bad\"}");assertTrue(runCatching{store.entries()}.isFailure);assertFalse(store.commitGroup(entries()))}
    @Test fun incomingCallFocusLossPreventsNewTargetsEvenWhileScreenIsVisible(){ForegroundState.started();ForegroundState.resumed();try{assertTrue(ForegroundState.canStartTargets);ForegroundState.paused();assertTrue(ForegroundState.active);assertFalse(ForegroundState.canStartTargets)}finally{ForegroundState.stopped()}}
}
