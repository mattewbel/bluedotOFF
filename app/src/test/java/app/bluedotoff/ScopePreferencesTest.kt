package app.bluedotoff

import android.app.Application
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class ScopePreferencesTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val prefs get()=ScopePreferences(context)
    private val names=listOf("test.routines","test.systemui")
    private fun entries(enabled:Boolean)=names.map{p->Entry(Identity(p,0,10048,listOf("cert"),1,1,1),p,256,enabled,if(enabled)TargetState.APPLIED else TargetState.NOT_APPLIED,1,"fw","boot",enabled,settlingVerified=true)}
    private fun outcome(success:Set<String> = names.toSet(),hide:Boolean=false,enabled:Boolean=false)=BatchOutcome(hide,names.map{p->BatchResult(p,entries(enabled).single{it.identity.packageName==p},if(p in success)"" else "Disconnected")},entries(enabled),emptyList())
    @Before fun reset(){context.getSharedPreferences("application-scope",0).edit().clear().commit()}
    @Test fun dataClearRequiresANewChoiceAndDoesNotInferOriginals(){assertFalse(prefs.resolved);assertEquals(ApplicationScope.ALL,prefs.current);assertTrue(prefs.selectInitial(ApplicationScope.NORMAL));assertTrue(prefs.resolved);assertEquals(ApplicationScope.NORMAL,prefs.current)}
    @Test fun partialFailureOrInterruptedProcessKeepsOldScopeAndPendingTarget(){
        prefs.select(ApplicationScope.ALL);prefs.beginRestore(ApplicationScope.NORMAL,names.toSet())
        assertFalse(prefs.finishRestore(outcome(success=setOf(names[0]))));assertEquals(ApplicationScope.ALL,ScopePreferences(context).current);assertEquals(ApplicationScope.NORMAL,ScopePreferences(context).pending)
        assertFalse(prefs.finishRestore(outcome(enabled=true)));assertFalse(prefs.finishRestore(outcome(hide=true)))
    }
    @Test fun onlyAllDurablyRestoredTargetsCommitTheLowerScope(){prefs.select(ApplicationScope.ALL);prefs.beginRestore(ApplicationScope.NORMAL,names.toSet());assertTrue(prefs.finishRestore(outcome()));assertEquals(ApplicationScope.NORMAL,prefs.current);assertNull(prefs.pending)}
    @Test fun changingScopeNeverWritesPermissionMetadataOrRegistersApps(){assertTrue(prefs.select(ApplicationScope.ALL));assertTrue(JournalStore(context).entries().isEmpty());assertTrue(prefs.select(ApplicationScope.NORMAL));assertTrue(JournalStore(context).entries().isEmpty())}
    @Test fun legacyRegistrationsWithoutScopePrefsStillRequireDowngradePreview(){
        val store=JournalStore(context)
        entries(true).forEach{assertTrue(store.commit(it))}
        val before=store.entries()
        assertEquals(ApplicationScope.ALL,prefs.current)
        assertTrue(prefs.selectInitial(ApplicationScope.NORMAL))
        assertEquals(ApplicationScope.ALL,prefs.current)
        assertEquals(ApplicationScope.NORMAL,prefs.requested)
        assertEquals(before,store.entries())
    }
    @Test fun bothOldExtendedLevelsBecomeAllWithoutChangingJournalOrPrefs(){
        val raw=context.getSharedPreferences("application-scope",0)
        for(level in listOf(2,3)){
            raw.edit().putInt("level",level).putBoolean("resolved",true).commit()
            assertEquals(ApplicationScope.ALL,prefs.current)
            assertEquals(level,raw.getInt("level",0))
            assertTrue(JournalStore(context).entries().isEmpty())
        }
    }
    @Test fun oldInterruptedMiddleRestoreStillRequiresEverySavedTarget(){
        val raw=context.getSharedPreferences("application-scope",0)
        raw.edit().putInt("level",3).putInt("pending",2).putStringSet("targets",names.toSet()).commit()
        assertEquals(ApplicationScope.GROUPS,prefs.pending)
        assertFalse(prefs.finishRestore(outcome(success=setOf(names[0]))))
        assertEquals(ApplicationScope.ALL,prefs.current);assertEquals(ApplicationScope.GROUPS,prefs.pending)
        assertTrue(prefs.finishRestore(outcome()));assertEquals(ApplicationScope.ALL,prefs.current)
        assertEquals(3,raw.getInt("level",0));assertNull(prefs.pending)
    }
}
