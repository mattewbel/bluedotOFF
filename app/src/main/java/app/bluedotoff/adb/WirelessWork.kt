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
        check(!attempted||(completed&&receipt.helperExited&&receipt.streamClosed)){"연결 종료를 확인하지 못했습니다."}
        attempted=true;completed=false;receipt=LocalAdbSession.CloseReceipt()
        val result=block(receipt)
        check(receipt.helperExited&&receipt.streamClosed){"연결 종료를 확인하지 못했습니다."}
        completed=true
        return result
    }
}

/** Keeps adbd stable across bounded helpers; only the final orderly close permits OFF. */
object WirelessWork {
    private val lock=Any()
    fun <T> run(context:Context,userAction:Boolean,knownPort:Int?=null,beforeDisable:()->Unit={},onCleanup:(String)->Unit={},block:(LocalAdbBackend)->T):T =
        lease(context,userAction,knownPort,beforeDisable,onCleanup){it.single(block)}
    fun <T> runBatch(context:Context,userAction:Boolean,onCleanup:(String)->Unit={},block:(BatchSession)->T):T =
        lease(context,userAction,null,{},onCleanup,block)
    class BatchSession internal constructor(private val connection:LocalAdbSession.HelperConnection) {
        private val chain=OrderlyHelperChain()
        internal fun <T> single(block:(LocalAdbBackend)->T):T =
            chain.run{connection.run(it,block)}
        fun <T> run(block:(LocalAdbBackend)->T):T {
            check(ForegroundState.active){"화면을 떠나 남은 앱은 변경하지 않았습니다."}
            return single(block)
        }
    }
    private fun <T> lease(context:Context,userAction:Boolean,knownPort:Int?,beforeDisable:()->Unit,onCleanup:(String)->Unit,block:(BatchSession)->T):T=synchronized(lock) {
        val controls=WirelessControls(context)
        var cleanupNotice=""
        val receipt=LocalAdbSession.CloseReceipt()
        val wasOff=!controls.wireless
        if(wasOff) {
            check(controls.mayStart(userAction&&ForegroundState.active)){"무선 디버깅을 켜주세요."}
            check(controls.enableWirelessDebugging()){"무선 디버깅을 켜지 못했습니다. 설정에서 직접 켜주세요."}
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
            check(latch.await(15,TimeUnit.SECONDS)){"연결을 찾지 못했습니다. 무선 디버깅의 네트워크 허용과 연결 정보를 확인해주세요."}
            return selected?:error("연결을 확인하지 못했습니다.")
        }
        fun isConnectFailure(error:Throwable)=(error as? LocalAdbStageException)?.state==app.bluedotoff.core.LocalAdbState.ADB_CONNECTION_FAILED
        try {
            var port=if(knownPort!=null&&!wasOff)knownPort else discoverPort(null)
            val connect={LocalAdbSession.withConnection(context,port,receipt){nothingOpened=false;block(BatchSession(it))}}
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
                if(safeToDisable) {
                    runCatching{beforeDisable()}
                    if(!runCatching{controls.disableWirelessDebugging()}.getOrDefault(false))
                        cleanupNotice="설정은 완료되었습니다. 무선 디버깅을 직접 꺼주세요."
                } else cleanupNotice="무선 디버깅을 직접 꺼주세요. 연결 종료를 확인하지 못했습니다."
            }
            // Diagnostic only (no personal data): why the lease could not confirm an orderly close.
            if(cleanupNotice.isNotEmpty()||failure!=null)android.util.Log.w("bluedotOFF","wireless lease: helperExited=${receipt.helperExited} socketClosed=${receipt.socketClosed} completed=$completed nothingOpened=$nothingOpened failure=${failure?.javaClass?.simpleName}: ${failure?.message?.take(160)} notice=${cleanupNotice.isNotEmpty()}")
            runCatching{onCleanup(cleanupNotice)}
        }
    }
}
