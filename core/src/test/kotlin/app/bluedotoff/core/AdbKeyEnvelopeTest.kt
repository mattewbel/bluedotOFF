package app.bluedotoff.core

import org.junit.Test
import org.junit.Assert.*
import javax.crypto.KeyGenerator

class AdbKeyEnvelopeTest {
    private fun key()=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
    private fun blocked(block:()->Unit){try{block();fail("Corruption must stop before opening any ADB socket")}catch(_:Exception){}}
    @Test fun persistedEnvelopeCanBeOpenedWithTheSameWrappingKey(){val k=key();val raw=ByteArray(300){it.toByte()};val saved=AdbKeyEnvelope.seal(raw,k);assertArrayEquals(raw,AdbKeyEnvelope.open(saved,k));assertFalse(saved.contentEquals(raw))}
    @Test fun ciphertextAndTagCorruptionCannotBecomeNewIdentity(){val k=key();val saved=AdbKeyEnvelope.seal(ByteArray(300){it.toByte()},k);for(i in listOf(1,12,13,saved.lastIndex)){val corrupt=saved.clone();corrupt[i]=(corrupt[i].toInt() xor 1).toByte();blocked{AdbKeyEnvelope.open(corrupt,k)}}}
    @Test fun missingOrDifferentWrappingKeyCannotDecryptExistingIdentity(){val saved=AdbKeyEnvelope.seal(ByteArray(300){it.toByte()},key());blocked{AdbKeyEnvelope.open(saved,key())}}
    @Test fun truncatedOversizedAndUnknownFormatMustBeRejected(){val k=key();for(saved in listOf(byteArrayOf(),ByteArray(29),ByteArray(16385),ByteArray(30).apply{this[0]=2})){blocked{AdbKeyEnvelope.open(saved,k)}}}
    @Test fun eachWriteUsesADifferentAuthenticatedIv(){val k=key();val raw=ByteArray(300);val a=AdbKeyEnvelope.seal(raw,k);val b=AdbKeyEnvelope.seal(raw,k);assertFalse(a.copyOfRange(1,13).contentEquals(b.copyOfRange(1,13)));assertArrayEquals(raw,AdbKeyEnvelope.open(a,k));assertArrayEquals(raw,AdbKeyEnvelope.open(b,k))}
}
