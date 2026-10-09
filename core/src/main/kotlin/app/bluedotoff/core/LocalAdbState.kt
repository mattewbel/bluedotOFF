package app.bluedotoff.core

enum class LocalAdbState {
    DEVELOPER_OPTIONS_OFF,WIRELESS_DEBUGGING_OFF,NOT_PAIRED,PAIRING_IN_PROGRESS,
    PAIRING_FAILED,ADB_DISCOVERY_FAILED,ADB_CONNECTED,SHELL_IDENTITY_INVALID,
    HELPER_START_FAILED,HELPER_PROTOCOL_FAILED,COMPATIBILITY_FAILED,KEY_CORRUPT,AUTHORIZATION_REVOKED,
    ADB_CONNECTION_FAILED,ADB_AUTHENTICATION_FAILED
}

/** A reply belongs to a single short-lived preparation; taking it invalidates duplicates. */
class PairingLease(private val now:()->Long) {
    private var generation=0L
    private var deadline=0L
    private var prepared=false
    @Synchronized fun begin():Long {generation++;deadline=now()+300000;prepared=true;return generation}
    @Synchronized fun take(token:Long,code:ByteArray):Boolean {
        val valid=prepared&&token==generation&&now()<deadline&&code.size==6&&code.all{it in 48..57}
        if(valid)prepared=false
        return valid
    }
    @Synchronized fun current(token:Long):Boolean=token==generation&&now()<deadline
    @Synchronized fun cancel(){generation++;prepared=false;deadline=0}
}
