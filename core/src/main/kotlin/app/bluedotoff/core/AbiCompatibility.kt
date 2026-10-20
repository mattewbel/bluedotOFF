package app.bluedotoff.core

/** R-2 (1.2.0): a Galaxy security patch may rebuild framework.jar, which changes the bytecode hashes of the verified
 *  reference even when nothing the helper calls changed. The helper then still runs when every proxy method it calls
 *  keeps its exact AIDL signature and every transaction field still exists; it uses the installed framework's codes,
 *  never the reference's. A changed signature or a missing field (e.g. a new Android version) still stops it. */
object AbiCompatibility {
    data class Check(val exact:Boolean,val codes:Map<String,Int>)
    /** [reference]/[observed]: signature -> bytecode hash; codes: `...Stub;->TRANSACTION_name:I` -> code. Null = incompatible. */
    fun framework(reference:Map<String,String>,referenceCodes:Map<String,Int>,observed:Map<String,String>,observedCodes:Map<String,Int>):Check? {
        val calls=reference.keys.filter{it.contains("\$Stub\$Proxy;->")}
        if(calls.isEmpty()||referenceCodes.isEmpty()||!observed.keys.containsAll(calls)||!observedCodes.keys.containsAll(referenceCodes.keys))return null
        val codes=referenceCodes.keys.associate{it.substringAfter("TRANSACTION_").substringBefore(':') to observedCodes.getValue(it)}
        // Two calls on one interface can never share a code; a duplicate means the fields were not read correctly.
        if(referenceCodes.keys.groupBy{it.substringBefore("->")}.values.any{g->g.map{observedCodes.getValue(it)}.distinct().size!=g.size})return null
        return Check(observed==reference&&referenceCodes.all{(k,v)->observedCodes[k]==v},codes)
    }
}
