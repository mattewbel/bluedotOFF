package app.bluedotoff

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import app.bluedotoff.core.StartupScanCache

/** Process-only display result. Package changes invalidate even while Setup owns the UI. */
object StartupScanHandoff {
    val cache=StartupScanCache(SystemClock::elapsedRealtime)
    fun observePackages(context:Context) {
        val filter=IntentFilter().apply{
            addAction(Intent.ACTION_PACKAGE_ADDED);addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED);addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(context,object:BroadcastReceiver(){
            override fun onReceive(context:Context,intent:Intent){cache.invalidate()}
        },filter,androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }
}
