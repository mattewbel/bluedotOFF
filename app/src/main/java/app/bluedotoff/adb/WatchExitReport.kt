package app.bluedotoff.adb

import android.os.Binder
import android.os.IBinder
import android.os.Parcel

internal enum class WatchExitReason {
    INSTALL_LOOKUP_UNAVAILABLE, INSTALL_CHECK_FAILED, APP_REQUESTED_STOP, CONTACT_REFUSED,
    APP_REPLACED, APP_REMOVED, WATCH_LOOP_EXCEPTION, WATCH_LOOP_ENDED, STARTUP_EXCEPTION, RUNTIME_LINKAGE_ERROR,
    // The one-way callback encodes ordinals. Append new reasons after all previously shipped ones.
    INSTALL_LOOKUP_DENIED, INSTALL_UID_PACKAGES_UNAVAILABLE, INSTALL_IDENTITY_MISMATCH
}

/** One-way hint to the already authenticated app. No lookup of a replacement app, reply or privileged operation. */
internal object WatchExitReport {
    private const val DESCRIPTOR = "app.bluedotoff.watch.exit.v1"
    const val EXTRA = "exitReport"

    fun receiver(record: (WatchExitReason) -> Unit): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != IBinder.FIRST_CALL_TRANSACTION || flags and IBinder.FLAG_ONEWAY == 0 ||
                Binder.getCallingUid() != 2000) return false
            data.enforceInterface(DESCRIPTOR)
            val reason = WatchExitReason.entries.getOrNull(data.readInt()) ?: return false
            if (data.dataAvail() != 0) return false
            record(reason)
            return true
        }
    }

    fun send(endpoint: IBinder?, reason: WatchExitReason) {
        if (endpoint == null) return
        runCatching {
            val parcel = Parcel.obtain()
            try {
                parcel.writeInterfaceToken(DESCRIPTOR)
                parcel.writeInt(reason.ordinal)
                endpoint.transact(IBinder.FIRST_CALL_TRANSACTION, parcel, null, IBinder.FLAG_ONEWAY)
            } finally {
                parcel.recycle()
            }
        }
    }
}
