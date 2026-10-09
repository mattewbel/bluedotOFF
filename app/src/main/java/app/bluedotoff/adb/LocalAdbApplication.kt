package app.bluedotoff.adb

/** Local variant deliberately does not initialize a Shizuku provider or application class. */
class LocalAdbApplication:android.app.Application(){
    override fun onCreate(){
        super.onCreate()
        app.bluedotoff.StartupScanHandoff.observePackages(this)
        val filter=android.content.IntentFilter().apply{addAction(android.content.Intent.ACTION_PACKAGE_REPLACED);addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);addDataScheme("package")}
        androidx.core.content.ContextCompat.registerReceiver(this,app.bluedotoff.LifecycleReceiver(),filter,androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }
}
