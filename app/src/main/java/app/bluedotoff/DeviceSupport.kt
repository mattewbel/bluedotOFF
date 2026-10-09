package app.bluedotoff

import android.os.Build

/** Early prerequisites only. The connected helper still verifies the actual firmware ABI. */
object DeviceSupport {
    fun basic(manufacturer:String,sdk:Int,abis:List<String>)=
        manufacturer.equals("samsung",true)&&sdk>=37&&"arm64-v8a" in abis
    fun basic()=basic(Build.MANUFACTURER,Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS.toList())
}
