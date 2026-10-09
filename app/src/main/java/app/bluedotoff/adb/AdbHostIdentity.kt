package app.bluedotoff.adb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import app.bluedotoff.core.AdbKeyEnvelope

class AdbIdentityCorruptException:java.io.IOException("KEY_CORRUPT · 저장된 페어링 키를 확인할 수 없습니다. 연결 설정에서 페어링을 초기화해 주세요.")

/** Exportable ADB key is wrapped by a non-exportable Android Keystore AES key. */
class AdbHostIdentity(private val context:Context) {
    companion object { private const val ALIAS="bluedot.adb.wrap.v1" }
    private val storage=AtomicFile(File(context.noBackupFilesDir,"local-adb-identity.enc"))
    val exists get()=storage.baseFile.isFile
    data class Material(val privatePem:ByteArray,val certificatePem:ByteArray,val publicKey:ByteArray):AutoCloseable {
        override fun close(){privatePem.fill(0);certificatePem.fill(0);publicKey.fill(0)}
    }
    @Synchronized private fun wrappingKey(allowCreate:Boolean):SecretKey {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (ks.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        if(!allowCreate)throw AdbIdentityCorruptException()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun decode(raw:ByteArray):Material {
        val b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        fun part():ByteArray{require(b.remaining()>=4);val n=b.int;require(n in 1..8192&&n<=b.remaining());return ByteArray(n).also{b.get(it)}}
        val parts=mutableListOf<ByteArray>()
        try{repeat(3){parts+=part()};require(!b.hasRemaining());return Material(parts[0],parts[1],parts[2])}
        catch(e:Exception){parts.forEach{it.fill(0)};throw e}
    }
    @Synchronized fun loadOrCreate():Material {
        if(!exists){
            val raw=NativeAdb.identity()
            try {
                decode(raw).close()
                val encrypted=AdbKeyEnvelope.seal(raw,wrappingKey(true))
                val f=storage.startWrite()
                try{f.write(encrypted);storage.finishWrite(f)}catch(e:Exception){storage.failWrite(f);throw e}
            }finally{raw.fill(0)}
        }
        try{
            val raw=AdbKeyEnvelope.open(storage.readFully(),wrappingKey(false))
            try{return decode(raw)}finally{raw.fill(0)}
        }catch(_:Exception){throw AdbIdentityCorruptException()}
    }
    @Synchronized fun reset(){storage.delete();context.getSharedPreferences("local-adb",Context.MODE_PRIVATE).edit().remove("pairedGuid").apply()}
}
