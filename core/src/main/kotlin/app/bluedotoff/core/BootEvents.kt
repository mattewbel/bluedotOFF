package app.bluedotoff.core

/** Android15+ redelivers BOOT_COMPLETED after force-stop without rebooting the device. */
object BootEvents {
    fun fromBroadcast(entry:Entry,currentBoot:String):Entry {
        val counted=Regex("count:[0-9]+")
        return if(counted.matches(entry.bootSession)&&counted.matches(currentBoot)&&entry.bootSession==currentBoot)entry
        else Reconcile.boot(entry) // Unknown/fallback boot identity remains conservative.
    }
}
