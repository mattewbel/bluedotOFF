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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class JournalStoreTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val file get()=File(context.noBackupFilesDir,"original-bit-journal.json")
    private val entry=Entry(Identity("test.location",0,10792,listOf("cert"),1,1,1),"Test",256,true,TargetState.PENDING_HIDE,2,"fp","boot",false)
    @Before fun clean(){file.delete();File(file.path+".bak").delete();File(file.path+".new").delete()}
    /** The store reuses its last parsed list; a changed file stamp must force a real parse that yields the same entries. */
    /** Same size and timestamp but different bytes (e.g. a damaged checksum) must still be rejected, not served from cache. */
    @Test fun corruptionWithTheSameStampIsNotServedFromCache(){
        val store=JournalStore(context);assertTrue(store.commit(entry));store.entries()
        val text=file.readText();val stamp=file.lastModified()
        val i=text.indexOf("\"checksum\":\"")+12;val flipped=text.substring(0,i)+(if(text[i]=='a')'b' else 'a')+text.substring(i+1)
        file.writeText(flipped);file.setLastModified(stamp);assertEquals(text.length,file.readText().length)
        try{JournalStore(context).entries();fail("A damaged journal must not be served from cache")}catch(_:IllegalStateException){}
    }
    /** An unfinished "표시 켜기" must still read as one after the app restarts (value before OFF, state PENDING_SHOW). */
    @Test fun pendingShowSurvivesARestart(){
        val show=entry.copy(originalBit=0,state=TargetState.PENDING_SHOW)
        assertTrue(JournalStore(context).commit(show))
        // Different stored bytes (a trailing space; the checksum covers only the payload) force a real parse, as after a restart.
        file.appendText(" ")
        val reread=JournalStore(context).entries()
        assertEquals(TargetState.PENDING_SHOW,reread.single().state);assertEquals(0,reread.single().originalBit);assertTrue(reread.single().enabled)
    }
    @Test fun cachedEntriesMatchAFreshParseOfTheStoredBytes(){
        val member=Identity("test.peer",0,10792,listOf("cert"),1,1,1)
        val rich=listOf(entry.copy(error="오류",invariantDigest="b".repeat(64),settlingVerified=true,sharedUserId="test.shared"),
            entry.copy(identity=member,groupIdentities=listOf(entry.identity,member).sortedBy{it.packageName},state=TargetState.APPLIED))
        val store=JournalStore(context);rich.forEach{assertTrue(store.commit(it))}
        val cached=store.entries()
        assertTrue(file.setLastModified(file.lastModified()-5000))
        assertEquals(cached,JournalStore(context).entries())
    }
    @Test fun reusedEntryTextKeepsThePayloadInStandardJsonArrayForm(){
        val store=JournalStore(context);(0..4).forEach{n->assertTrue(store.commit(entry.copy(identity=entry.identity.copy(packageName="test.app$n"))))}
        assertTrue(store.commit(entry.copy(identity=entry.identity.copy(packageName="test.app2"),state=TargetState.APPLIED)))
        val payload=org.json.JSONObject(file.readText()).getString("payload")
        assertEquals(org.json.JSONArray(payload).toString(),payload);assertEquals(5,JournalStore(context).entries().size)
    }
    @Test fun durableRoundTripAcrossNewStoreInstance(){assertTrue(JournalStore(context).commit(entry));assertEquals(listOf(entry),JournalStore(context).entries())}
    @Test fun singletonNamespaceAndReviewStatePersist(){val e=entry.copy(sharedUserId="test.shared",state=TargetState.NEEDS_REVIEW);assertTrue(JournalStore(context).commit(e));assertEquals(e,JournalStore(context).entries().single())}
    @Test fun test4JournalWithoutNamespaceRetainsOriginalAndIdentity(){JournalStore(context).commit(entry);val root=org.json.JSONObject(file.readText());val payload=org.json.JSONArray(root.getString("payload"));payload.getJSONObject(0).remove("sharedUserId");val text=payload.toString();file.writeText(root.put("payload",text).put("checksum",BinderBackend.digest(text.toByteArray())).toString());assertEquals(entry,JournalStore(context).entries().single())}
    @Test fun metadataSnapshotIsNotAStoredRestoreValue(){JournalStore(context).commit(entry);assertTrue(file.readText().contains("originalBit"));assertFalse(file.readText().contains("fineFlags"));assertFalse(file.readText().contains("coarseFlags"))}
    @Test fun corruptedJournalNeverLooksLikeEmptyFreshRegistration(){JournalStore(context).commit(entry);file.writeText("{\"schema\":1,\"payload\":\"[]\",\"checksum\":\"invalid\"}");try{JournalStore(context).entries();fail("Corruption must stop writes")}catch(_:Exception){};assertFalse(JournalStore(context).commit(entry))}
    @Test fun bootMarkerIsDurableWithoutNotificationPermission(){JournalStore(context).commit(entry.copy(state=TargetState.APPLIED));assertTrue(JournalStore(context).transform(Reconcile::boot));assertEquals(TargetState.NEEDS_REAPPLY_BOOT,JournalStore(context).entries().single().state);ReapplyNotification.show(context,null);assertEquals(256,JournalStore(context).entries().single().originalBit)}
    @Test fun updateMarkerPreservesOriginalInstallIdentity(){JournalStore(context).commit(entry);JournalStore(context).transform(Reconcile::update);val result=JournalStore(context).entries().single();assertEquals(entry.identity,result.identity);assertEquals(256,result.originalBit)}
    @Test fun repeatedCommitsPublishTheLatestVerifiedState(){val store=JournalStore(context);listOf(TargetState.PENDING_HIDE,TargetState.APPLIED,TargetState.PENDING_RESTORE,TargetState.NOT_APPLIED).forEach{state->assertTrue(store.commit(entry.copy(state=state)));assertEquals(state,JournalStore(context).entries().single().state)}}
    @Test fun interruptedTemporaryFileCannotReplacePublishedJournal(){JournalStore(context).commit(entry);File(file.path+".new").writeText("incomplete");assertEquals(listOf(entry),JournalStore(context).entries())}
    @Test fun verificationDigestRoundTripsWithoutStoredFullFlags(){val e=entry.copy(invariantDigest="a".repeat(64));assertTrue(JournalStore(context).commit(e));assertEquals(e,JournalStore(context).entries().single());assertFalse(file.readText().contains("coarseFlags"))}
}
