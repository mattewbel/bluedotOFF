package app.bluedotoff.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class DexAbiTest {
    private fun dex():ByteArray=ByteArray(112).also { b->"dex\n035\u0000".toByteArray().copyInto(b);val v=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);v.putInt(32,112);v.putInt(36,112);v.putInt(40,0x12345678) }
    private fun inspect(b:ByteArray):DexAbi.Result {
        val file=Files.createTempFile("abi-invalid-",".jar")
        try { ZipOutputStream(Files.newOutputStream(file)).use{it.putNextEntry(ZipEntry("classes.dex"));it.write(b);it.closeEntry()};return DexAbi.inspect(file.toFile(),setOf("Lrequired/Stub;")) }
        finally { Files.deleteIfExists(file) }
    }
    private fun rejected(b:ByteArray){try{inspect(b);fail("Malformed ABI must fail closed")}catch(_:Exception){}}
    @Test fun missingRequiredClassNeverInventsTransactions(){val r=inspect(dex());assertTrue(r.methods.isEmpty());assertTrue(r.transactions.isEmpty())}
    @Test fun unsupportedDexVersionIsRejected(){val b=dex();"042".toByteArray().copyInto(b,4);rejected(b)}
    @Test fun truncatedHeaderIsRejected(){rejected(ByteArray(40))}
    @Test fun invalidEndianIsRejected(){val b=dex();b[40]=0;rejected(b)}
    @Test fun tableIntegerOverflowIsRejected(){val b=dex();ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(56,Int.MAX_VALUE);rejected(b)}
}
