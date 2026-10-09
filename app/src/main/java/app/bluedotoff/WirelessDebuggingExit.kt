package app.bluedotoff

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** User-operated Settings fallback; no Settings mutation or privileged command. */
object WirelessDebuggingExit {
    fun intent(context:Context):Intent {
        val direct=Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
        return if(direct.resolveActivity(context.packageManager)!=null)direct else
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key","toggle_adb_wireless")
                .putExtra(":settings:show_fragment_args",Bundle().apply{putString(":settings:fragment_args_key","toggle_adb_wireless")})
    }
    fun open(context:Context){runCatching{context.startActivity(intent(context))}.onFailure{context.startActivity(Intent(Settings.ACTION_SETTINGS))}}
}
