package app.bluedotoff.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded ADB packet codec, independent of privileged capability and target eligibility. */
data class AdbPacket(val command:Int,val arg0:Int=0,val arg1:Int=0,val payload:ByteArray=byteArrayOf()) {
    fun encode():ByteArray {
        require(payload.size<=MAX_PAYLOAD)
        return ByteBuffer.allocate(24+payload.size).order(ByteOrder.LITTLE_ENDIAN).putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size).putInt(payload.sumOf{it.toInt() and 255}).putInt(command.inv()).put(payload).array()
    }
    companion object {
        const val MAX_PAYLOAD=1048576
        const val CNXN=0x4e584e43;const val STLS=0x534c5453;const val OPEN=0x4e45504f
        const val OKAY=0x59414b4f;const val WRTE=0x45545257;const val CLSE=0x45534c43
        fun length(header:ByteArray):Int {require(header.size==24);val b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);val c=b.int;b.int;b.int;val n=b.int;b.int;require(b.int==c.inv()&&n in 0..MAX_PAYLOAD);return n}
        fun decode(header:ByteArray,payload:ByteArray):AdbPacket {
            require(payload.size==length(header));val b=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val c=b.int;val a=b.int;val d=b.int;b.int;val check=b.int
            require(check==0||check==payload.sumOf{it.toInt() and 255}){"ADB payload checksum invalid"}
            return AdbPacket(c,a,d,payload)
        }
    }
}

object HelperLaunch {
    private val path=Regex("/data/app/[A-Za-z0-9_~=/+.-]+/base\\.apk")
    fun command(apk:String):String {
        require(apk.length<=4096&&path.matches(apk)&&!apk.contains("/../")&& !apk.contains("/./"))
        return "CLASSPATH=$apk app_process /system/bin app.bluedotoff.adb.MinimalPrivilegedHelper $apk"
    }
}

object HelperFrames {
    const val MAGIC=0x42443037
    const val MAX_REQUEST=65536
    const val MAX_RESPONSE=6_000_000
    fun request(payload:ByteArray):ByteArray {require(payload.size in 1..MAX_REQUEST);return ByteBuffer.allocate(8+payload.size).putInt(MAGIC).putInt(payload.size).put(payload).array()}
    fun responseSize(header:ByteArray):Int {require(header.size==8);val b=ByteBuffer.wrap(header);require(b.int==MAGIC);return b.int.also{require(it in 1..MAX_RESPONSE)}}
}
