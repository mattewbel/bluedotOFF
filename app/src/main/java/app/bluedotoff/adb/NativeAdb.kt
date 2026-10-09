package app.bluedotoff.adb

/** Fixed loopback native sockets only. No command API or external host argument. */
object NativeAdb {
    init { System.loadLibrary("bluedot_adb") }
    external fun identity():ByteArray
    external fun open(port:Int,privatePem:ByteArray,certificatePem:ByteArray):Long
    external fun startTls(handle:Long)
    external fun read(handle:Long,length:Int):ByteArray
    external fun write(handle:Long,data:ByteArray)
    external fun close(handle:Long)
    external fun pair(handle:Long,code:ByteArray,adbPublicKey:ByteArray):String
}
