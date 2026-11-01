package app.bluedotoff

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/** 1.3.0 "what bluedotOFF did": only confirmed automatic results count, from a start date that does not move. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class ReapplyStatsTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private fun result(pkg:String,error:String="")=BatchResult(pkg,Entry(Identity(pkg,0,12345,listOf("signer"),1,1,1),pkg,256,true,TargetState.APPLIED,1,"firmware","boot",true),error)

    @Test fun startDateIsKeptAndOnlyAutomaticSuccessesCount(){
        val since=ReapplyStats.since(context)
        val history=ReapplyHistoryStore(context)
        history.record(result("test.map"),ReapplySource.BACKGROUND,1000,rewrote=true)
        history.record(result("test.map"),ReapplySource.APP_OPEN,2000,rewrote=true)
        history.record(result("test.map"),ReapplySource.RESTART,3000,rewrote=true)
        history.record(result("test.map"),ReapplySource.MANUAL,4000,rewrote=true)
        // A check that found the hide still in place (e.g. an update that kept it) is not a reapply (GPT review 2026-10-29).
        history.record(result("test.map"),ReapplySource.BACKGROUND,4500)
        history.record(result("test.map","no connection"),ReapplySource.BACKGROUND,5000,rewrote=true)
        assertEquals(3,ReapplyStats.count(context))
        assertEquals(since,ReapplyStats.since(context))
        assertEquals(listOf(true,false,true,true,true,true),history.entries().map{it.rewrote})
    }
    @Test fun mostFrequentAppIgnoresManualAndFailedRowsAndTiesGoToTheNewest(){
        fun row(pkg:String,at:Long,source:ReapplySource=ReapplySource.BACKGROUND,ok:Boolean=true,rewrote:Boolean=true)=ReapplyHistoryItem("$pkg$at",pkg,"label-$pkg",at,ok,source,rewrote)
        assertNull(ReapplyStats.top(listOf(row("a",1,ReapplySource.MANUAL),row("b",2,ok=false),row("c",3,rewrote=false))))
        assertEquals("label-a",ReapplyStats.top(listOf(row("a",1),row("a",2),row("b",3),row("b",4,ReapplySource.MANUAL))))
        assertEquals("label-b",ReapplyStats.top(listOf(row("a",1),row("b",2))))
    }
    @Test fun installDayIsDayOneAndMilestonesAreSaidOnce(){
        val zone=ZoneId.of("UTC")
        val installed=context.packageManager.getPackageInfo(context.packageName,0).firstInstallTime
        assertEquals(1L,ReapplyStats.days(context,installed,zone))
        assertEquals(100L,ReapplyStats.days(context,installed+99L*86_400_000L,zone))
        assertNull(ReapplyStats.nextMilestone(context,99))
        assertEquals(100,ReapplyStats.nextMilestone(context,150))
        assertNull(ReapplyStats.nextMilestone(context,150))
        assertEquals(365,ReapplyStats.nextMilestone(context,400))
    }
    /** The watch's wiring (WatchClient): an app counts only when the engine reported a write for it. An update that kept
     *  the hide is a successful check with no write (GPT review 2026-10-29); one that undid it is written again. */
    @Test fun theWatchCountsOnlyAppsItWroteAgain(){
        fun run(flagsAfterUpdate:Int):Int{
            val fw=Firmware("samsung",37,"90000","fixture","b","m","abi")
            val id=Identity("test.map",0,10123,listOf("signer"),100,1,100)
            val before=Snapshot(id,"Map",listOf(id.packageName),true,true,true,false,0x300,0x300,0,0,0,false)
            var saved=Entry(id,"Map",256,true,TargetState.NEEDS_REAPPLY_UPDATE,200,fw.key,"boot",true,invariantDigest=before.invariantDigest(),settlingVerified=true)
            var flags=flagsAfterUpdate
            val journal=object:Journal{
                override fun entries()=listOf(saved)
                override fun commit(entry:Entry):Boolean{saved=entry;return true}
                override fun commitGroup(entries:List<Entry>):Boolean{saved=entries.single();return true}
            }
            val port=object:TargetPort{
                override fun compatibleFirmware()=fw
                override fun read(packageName:String)=before.copy(identity=id.copy(versionCode=2,lastUpdateTime=900),fineFlags=flags)
                override fun writeFineBit(packageName:String,originalBit:Int){flags=(flags and SENSITIVITY_BIT.inv()) or originalBit}
            }
            val rewrote=HashSet<String>();val countBefore=ReapplyStats.count(context);var results=0
            WatchEngine(port,journal,{300},{"boot"},wait={},observe={_,s,wrote->if(wrote)rewrote+=s.identity.packageName}).reapply(setOf(id.packageName),{true},{},{r->
                assertTrue(r.error,r.success);results++
                ReapplyHistoryStore(context).record(r,ReapplySource.BACKGROUND,rewrote=r.packageName in rewrote)
            })
            assertEquals(1,results)
            return ReapplyStats.count(context)-countBefore
        }
        assertEquals(0,run(0x200))
        assertEquals(1,run(0x300))
    }
}
