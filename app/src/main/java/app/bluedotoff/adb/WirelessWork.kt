package app.bluedotoff.adb

import android.content.Context
import app.bluedotoff.ForegroundState
import app.bluedotoff.core.WirelessManagementPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A failed/uncertain chunk permanently stops this lease, even if its caller catches the error. */
internal class OrderlyHelperChain {
    var receipt=LocalAdbSession.CloseReceipt();private set
    private var attempted=false
    private var completed=false
    fun <T> run(block:(LocalAdbSession.CloseReceipt)->T):T {
        check(!attempted||(completed&&receipt.helperExited&&receipt.streamClosed)){"연결이 끝났는지 확인하지 못했어요."}
        attempted=true;completed=false;receipt=LocalAdbSession.CloseReceipt()
        val result=block(receipt)
        check(receipt.helperExited&&receipt.streamClosed){"연결이 끝났는지 확인하지 못했어요."}
        completed=true
        return result
    }
}

/** Keeps adbd stable across bounded helpers; only the final orderly close permits OFF. */
object WirelessWork {
    private val lock=Any()
    /** True while this app holds a lease; its own wireless ON/OFF must not look like a user-made connection change. */
    @Volatile var leasing=false;private set
    private fun prefs(context:Context)=context.getSharedPreferences("wireless-management",0)
    /** Main screens keep wireless debugging on while bluedotOFF is visible (one "connected" notice instead of one per task).
     *  The setup flow never holds: it relies on the lease turning wireless debugging off. */
    fun held(context:Context)=prefs(context).getBoolean("heldOn",false)
    private fun setHeld(context:Context,on:Boolean){prefs(context).edit().putBoolean("heldOn",on).commit()}
    /** Turns a held wireless debugging off once no bluedotOFF screen is visible (called after leaving, and at process start
     *  in case the process died while holding). Never touches a lease in progress or a connection the user made. */
    fun release(context:Context)=Thread{releaseIfHidden(context)}.start()
    /** Blocking form for [WirelessReleaseJob]: waits for a lease in progress, then releases only if no screen is visible. */
    fun releaseIfHidden(context:Context)=synchronized(lock){if(!ForegroundState.active)releaseLocked(context)}
    /** Same cleanup right away (e.g. the user turns automatic management off while the app is open). */
    fun releaseNow(context:Context)=synchronized(lock){releaseLocked(context)}
    private fun releaseLocked(context:Context){
        // The kept ADB connection is closed for every user (with or without automatic management); OFF follows only
        // after that physical close, and only for wireless debugging this app turned on itself.
        runCatching{LocalAdbSession.closeHeld()}
        if(!held(context))return
        val controls=WirelessControls(context)
        if(controls.consent&&controls.granted&&controls.wireless)runCatching{controls.disableWirelessDebugging()}
        setHeld(context,false)
        android.util.Log.i("bluedotOFF","lease: released held wireless debugging")
    }
    fun <T> run(context:Context,userAction:Boolean,knownPort:Int?=null,beforeDisable:()->Unit={},onCleanup:(String)->Unit={},hold:Boolean=false,forceWireless:Boolean=false,block:(LocalAdbBackend)->T):T =
        lease(context,userAction,knownPort,beforeDisable,onCleanup,hold,forceWireless){it.single(block)}
    fun <T> runBatch(context:Context,userAction:Boolean,onCleanup:(String)->Unit={},hold:Boolean=false,block:(BatchSession)->T):T =
        lease(context,userAction,null,{},onCleanup,hold,false,block)
    class BatchSession internal constructor(private val connection:LocalAdbSession.HelperConnection?,private val resident:WatchClient.Session?=null) {
        private val chain=OrderlyHelperChain()
        internal fun <T> single(block:(LocalAdbBackend)->T):T =
            if(resident!=null)resident.run(block) else chain.run{connection!!.run(it,block)}
        fun <T> run(block:(LocalAdbBackend)->T):T {
            check(ForegroundState.active){"화면을 떠나서 남은 앱은 바꾸지 않았어요."}
            return single(block)
        }
    }
    private fun <T> lease(context:Context,userAction:Boolean,knownPort:Int?,beforeDisable:()->Unit,onCleanup:(String)->Unit,hold:Boolean,forceWireless:Boolean,block:(BatchSession)->T):T=synchronized(lock) {
        if(!forceWireless&&WatchClient.available(context)){onCleanup("");return@synchronized WatchClient.exclusive(context){block(BatchSession(null,it))}}
        // Cleared on every exit, including a refused start before the connection try block (GPT review 2026-10-11).
        leasing=true
        try{leaseLocked(context,userAction,knownPort,beforeDisable,onCleanup,hold,block)}finally{leasing=false}
    }
    private fun <T> leaseLocked(context:Context,userAction:Boolean,knownPort:Int?,beforeDisable:()->Unit,onCleanup:(String)->Unit,hold:Boolean,block:(BatchSession)->T):T {
        val controls=WirelessControls(context)
        var cleanupNotice=""
        val receipt=LocalAdbSession.CloseReceipt()
        val leaseStart=android.os.SystemClock.elapsedRealtime()
        val wasOff=!controls.wireless
        if(wasOff) {
            check(controls.mayStart(userAction&&ForegroundState.active)){"무선 디버깅을 켜주세요."}
            check(controls.enableWirelessDebugging()){if(AutoBlocker.suspected)AutoBlocker.MESSAGE else "무선 디버깅을 켜지 못했어요. 설정에서 직접 켜주세요."}
        }
        val observers=mutableListOf<LocalAdbDiscovery>()
        var completed=false
        var failure:Throwable?=null
        // True until a socket to adbd exists. Failures before that point changed nothing that needs closing.
        var nothingOpened=true
        fun discoverPort(exclude:Int?):Int {
            val latch=CountDownLatch(1)
            val guid=context.getSharedPreferences("local-adb",0).getString("pairedGuid",null)
            var selected:Int?=null
            lateinit var observer:LocalAdbDiscovery
            observer=LocalAdbDiscovery(context){
                observer.candidates(LocalAdbDiscovery.CONNECT,guid).singleOrNull()?.port?.takeIf{it!=exclude}?.let{selected=it;latch.countDown()}
            }
            observers+=observer;observer.start()
            check(latch.await(15,TimeUnit.SECONDS)){"연결을 찾지 못했어요. 무선 디버깅의 네트워크 허용과 연결 정보를 확인해주세요."}
            return selected?:error("연결을 확인하지 못했어요.")
        }
        fun isConnectFailure(error:Throwable)=(error as? LocalAdbStageException)?.state==app.bluedotoff.core.LocalAdbState.ADB_CONNECTION_FAILED
        return try {
            var port=if(knownPort!=null&&!wasOff)knownPort else discoverPort(null)
            android.util.Log.i("bluedotOFF","lease: ready to connect after ${android.os.SystemClock.elapsedRealtime()-leaseStart}ms (wireless was ${if(wasOff)"off" else "on"})")
            val connect={LocalAdbSession.withConnection(context,port,receipt,keep=hold&&ForegroundState.active){nothingOpened=false;block(BatchSession(it))}}
            try{connect()}catch(error:Exception){
                // Right after wireless debugging is re-enabled, discovery can still report the previous adbd's port.
                // The socket was never opened, so finding the fresh port and trying once more changes nothing else.
                if(!isConnectFailure(error))throw error
                port=discoverPort(port)
                connect()
            }.also{completed=true}
        } catch(error:Throwable) {
            failure=error;throw error
        } finally {
            observers.forEach{runCatching{it.close()}}
            if(controls.consent&&controls.granted) {
                val safeToDisable=WirelessManagementPolicy.mayDisable(controls.consent,controls.granted,receipt.helperExited,receipt.socketClosed,completed)||
                    (nothingOpened&&(failure==null||isConnectFailure(failure!!)||failure is IllegalStateException))
                // A clean finish on a visible main screen keeps wireless debugging on for the next task; release() turns it off later.
                if(receipt.kept||safeToDisable&&hold&&completed&&failure==null&&ForegroundState.active)setHeld(context,true)
                else if(safeToDisable) {
                    setHeld(context,false)
                    runCatching{beforeDisable()}
                    if(!runCatching{controls.disableWirelessDebugging()}.getOrDefault(false))
                        cleanupNotice="설정은 끝났어요. 무선 디버깅을 직접 꺼주세요."
                } else cleanupNotice="무선 디버깅을 직접 꺼주세요. 연결이 끝났는지 확인하지 못했어요."
            }
            // Diagnostic only (no personal data): why the lease could not confirm an orderly close.
            if(cleanupNotice.isNotEmpty()||failure!=null)android.util.Log.w("bluedotOFF","wireless lease: helperExited=${receipt.helperExited} socketClosed=${receipt.socketClosed} completed=$completed nothingOpened=$nothingOpened failure=${failure?.javaClass?.simpleName}: ${failure?.message?.take(160)} notice=${cleanupNotice.isNotEmpty()}")
            android.util.Log.i("bluedotOFF","lease: total ${android.os.SystemClock.elapsedRealtime()-leaseStart}ms completed=$completed")
            runCatching{onCleanup(cleanupNotice)}
        }
    }
}
