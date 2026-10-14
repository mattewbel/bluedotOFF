package app.bluedotoff.adb

/** Fixed Binder and startup surface. No arbitrary shell command or external socket. */
object WatchProtocol {
    const val AUTHORITY="app.bluedotoff.watch"
    const val DESCRIPTOR="app.bluedotoff.watch.v1"
    const val REQUEST=1
    const val CONFIGURE=2
    const val STOP=3
    const val MAX_REQUEST=65_536
    const val MAX_RESPONSE=524_288
    fun launch(apk:String):String {
        app.bluedotoff.core.HelperLaunch.command(apk)
        return "CLASSPATH=$apk nohup setsid app_process /system/bin app.bluedotoff.adb.ResidentWatchHelper $apk </dev/null >/dev/null 2>&1 &"
    }
}
