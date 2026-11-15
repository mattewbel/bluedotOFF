package app.bluedotoff.core

/** Android15+ redelivers BOOT_COMPLETED after force-stop without rebooting the device. */
object BootEvents {
    private val counted=Regex("count:[0-9]+")
    fun hasBootEvidence(entry:Entry,currentBoot:String):Boolean {
        // A wall-clock estimate can move without a reboot; it cannot release a manual safety hold.
        return !entry.manualHold || counted.matches(entry.bootSession)&&counted.matches(currentBoot)
    }
    fun fromBroadcast(entry:Entry,currentBoot:String):Entry {
        val known=counted.matches(entry.bootSession)&&counted.matches(currentBoot)
        if(known&&entry.bootSession==currentBoot)return entry
        return if(hasBootEvidence(entry,currentBoot))Reconcile.boot(entry) else entry
    }
}
