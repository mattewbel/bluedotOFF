package app.bluedotoff.core

/** Read-only readiness and bounded notification parsing; never authorizes a target write. */
object LocalAdbPolicy {
    fun readiness(developer:Boolean,wireless:Boolean,guid:Boolean,key:Boolean,pairing:Boolean,ports:List<Int>):LocalAdbState = when {
        !developer -> LocalAdbState.DEVELOPER_OPTIONS_OFF
        !wireless -> LocalAdbState.WIRELESS_DEBUGGING_OFF
        pairing -> LocalAdbState.PAIRING_IN_PROGRESS
        !guid || !key -> LocalAdbState.NOT_PAIRED
        ports.distinct().size!=1 || ports.any{it !in 1..65535} -> LocalAdbState.ADB_DISCOVERY_FAILED
        else -> LocalAdbState.ADB_CONNECTED
    }
    class PairReply(val port:Int?,val code:ByteArray):AutoCloseable { override fun close(){code.fill(0)} }
    fun reply(input:CharSequence):PairReply? {
        if(input.length !in 6..12)return null
        val separator=input.indices.firstOrNull{input[it]==' '}
        val start:Int
        val port:Int?
        if(separator!=null){
            if(separator !in 1..5 || input.length-separator-1!=6)return null
            var value=0
            for(i in 0 until separator){if(input[i] !in '0'..'9')return null;value=value*10+(input[i]-'0')}
            if(value !in 1..65535)return null
            port=value;start=separator+1
        }else {port=null;start=0}
        if(input.length-start!=6 || (start until input.length).any{input[it] !in '0'..'9'})return null
        return PairReply(port,ByteArray(6){input[start+it].code.toByte()})
    }
    fun validateShell(uid:Int,selinux:String){require(uid==2000&&selinux=="u:r:shell:s0"){"SHELL_IDENTITY_INVALID"}}
    /** History and the SystemUI check may be unverified after a patch (1.2.0); only the calls the helper makes must fit. */
    fun validateProfile(abi:Boolean,fingerprint:String,expected:String){require(abi&&fingerprint==expected){"COMPATIBILITY_FAILED"}}
}
