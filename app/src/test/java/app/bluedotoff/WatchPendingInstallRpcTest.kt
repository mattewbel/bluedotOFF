package app.bluedotoff

import android.app.Application
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.*
import app.bluedotoff.adb.ResidentWatchHelper
import app.bluedotoff.adb.WatchProtocol
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class WatchPendingInstallRpcTest {
    @Test fun pendingMonitorDoesNotAuthorizeAnyBinderOperation() {
        val type=ResidentWatchHelper::class.java
        fun field(name:String)=type.getDeclaredField(name).apply{isAccessible=true}
        var reads=0
        val handler=Handler(Looper.getMainLooper())
        field("main").set(null,handler)
        field("appUid").setInt(null,Binder.getCallingUid())
        field("context").set(null,object:ContextWrapper(null){
            override fun getPackageManager():PackageManager { reads++; error("package lookup unavailable") }
        })
        val monitor=type.getDeclaredMethod("monitorInstallOrExit").apply{isAccessible=true}
        val server=field("server").get(null)
        val transact=server.javaClass.getDeclaredMethod("onTransact",Int::class.javaPrimitiveType,
            Parcel::class.java,Parcel::class.java,Int::class.javaPrimitiveType).apply{isAccessible=true}
        try {
            assertEquals(false,monitor.invoke(ResidentWatchHelper))
            assertEquals(1,reads)
            for(code in listOf(WatchProtocol.REQUEST,WatchProtocol.CONFIGURE,WatchProtocol.STOP)){
                val before=reads
                val data=Parcel.obtain();val reply=Parcel.obtain()
                try {
                    data.writeInterfaceToken(WatchProtocol.DESCRIPTOR);data.writeString("{}");data.setDataPosition(0)
                    assertEquals(true,transact.invoke(server,code,data,reply,0))
                    reply.setDataPosition(0)
                    assertThrows(SecurityException::class.java){reply.readException()}
                    assertEquals(before+1,reads)
                } finally { data.recycle();reply.recycle() }
            }
            assertFalse(field("stopping").getBoolean(null))
        } finally { handler.removeCallbacksAndMessages(null) }
    }
}
