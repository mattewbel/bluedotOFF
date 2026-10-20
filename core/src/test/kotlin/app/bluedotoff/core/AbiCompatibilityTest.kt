package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class AbiCompatibilityTest {
    private val set="Landroid/permission/IPermissionManager\$Stub\$Proxy;->updatePermissionFlags(Ljava/lang/String;Ljava/lang/String;IIZLjava/lang/String;I)V"
    private val get="Landroid/permission/IPermissionManager\$Stub\$Proxy;->getPermissionFlags(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)I"
    private val dispatch="Landroid/permission/IPermissionManager\$Stub;->onTransact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z"
    private val methods=mapOf(set to "a",get to "b",dispatch to "c")
    private val codes=mapOf("Landroid/permission/IPermissionManager\$Stub;->TRANSACTION_updatePermissionFlags:I" to 8,"Landroid/permission/IPermissionManager\$Stub;->TRANSACTION_getPermissionFlags:I" to 7)

    @Test fun theVerifiedFirmwareIsExactWithItsOwnCodes() {
        val check=AbiCompatibility.framework(methods,codes,methods,codes)!!
        assertTrue(check.exact);assertEquals(mapOf("updatePermissionFlags" to 8,"getPermissionFlags" to 7),check.codes)
    }
    @Test fun aPatchThatRebuildsTheCodeOrAddsMethodsStillRunsWithTheInstalledCodes() {
        // New bytecode everywhere and a method inserted before ours: codes shift by one.
        val check=AbiCompatibility.framework(methods,codes,mapOf(set to "x",get to "y",dispatch to "z"),codes.mapValues{it.value+1})!!
        assertFalse(check.exact);assertEquals(mapOf("updatePermissionFlags" to 9,"getPermissionFlags" to 8),check.codes)
    }
    @Test fun aChangedSignatureOrMissingFieldStops() {
        val newSignature=set.replace("IIZ","IIZZ")
        assertNull(AbiCompatibility.framework(methods,codes,mapOf(newSignature to "a",get to "b",dispatch to "c"),codes))
        assertNull(AbiCompatibility.framework(methods,codes,methods,codes.filterKeys{!it.contains("update")}))
        assertNull(AbiCompatibility.framework(methods,codes,methods,codes.mapValues{7}))
    }
}
