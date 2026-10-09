package app.bluedotoff.core

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** File codec only. Production passes a non-exportable AndroidKeyStore key. */
object AdbKeyEnvelope {
    fun seal(plain:ByteArray,key:SecretKey):ByteArray {
        require(plain.size in 1..16354)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key)
        val encrypted=cipher.doFinal(plain);val iv=cipher.iv;require(iv.size==12)
        return byteArrayOf(1)+iv+encrypted
    }
    fun open(stored:ByteArray,key:SecretKey):ByteArray {
        require(stored.size in 30..16384&&stored[0].toInt()==1){"Stored local ADB identity invalid"}
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,stored.copyOfRange(1,13)))
        return cipher.doFinal(stored,13,stored.size-13)
    }
}
