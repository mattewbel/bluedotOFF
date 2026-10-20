package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test

class LocalAdbPolicyTest {
    private fun state(dev:Boolean=true,wifi:Boolean=true,guid:Boolean=true,key:Boolean=true,pair:Boolean=false,ports:List<Int> = listOf(41037))=LocalAdbPolicy.readiness(dev,wifi,guid,key,pair,ports)
    @Test fun developerAndWirelessAreSeparateManualRequirements(){assertEquals(LocalAdbState.DEVELOPER_OPTIONS_OFF,state(dev=false));assertEquals(LocalAdbState.WIRELESS_DEBUGGING_OFF,state(wifi=false));assertEquals(LocalAdbState.ADB_CONNECTED,state())}
    @Test fun missingKeyCannotCreateImplicitReconnectIdentity(){assertEquals(LocalAdbState.NOT_PAIRED,state(key=false));assertEquals(LocalAdbState.NOT_PAIRED,state(guid=false))}
    @Test fun pairingBlocksNormalConnection(){assertEquals(LocalAdbState.PAIRING_IN_PROGRESS,state(pair=true))}
    @Test fun zeroMultipleAndInvalidEndpointsNeverAutoConnect(){for(p in listOf(emptyList(),listOf(1,2),listOf(0),listOf(65536)))assertEquals(LocalAdbState.ADB_DISCOVERY_FAILED,state(ports=p));assertEquals(LocalAdbState.ADB_CONNECTED,state(ports=listOf(1,1)))}
    @Test fun normalNotificationReplyHasOnlySixDigits(){LocalAdbPolicy.reply("246810")!!.use{assertNull(it.port);assertEquals(6,it.code.size)}}
    @Test fun permissionDeniedManualFallbackKeepsPairingPortSeparate(){LocalAdbPolicy.reply("54321 246810")!!.use{assertEquals(54321,it.port);assertEquals(6,it.code.size)}}
    @Test fun malformedRemoteInputCannotAllocateOrForwardCode(){for(input in listOf("","12345","1234567","12345x","0 246810","65536 246810","1  246810","1\n246810","1 246810;id"," 246810"))assertNull(LocalAdbPolicy.reply(input))}
    @Test fun replyMaterialIsCleared(){val reply=LocalAdbPolicy.reply("246810")!!;reply.close();assertTrue(reply.code.all{it==0.toByte()})}
    @Test fun wrongShellUidAndContextFailClosed(){for(pair in listOf(1000 to "u:r:shell:s0",2000 to "u:r:untrusted_app:s0",0 to "u:r:su:s0")){try{LocalAdbPolicy.validateShell(pair.first,pair.second);fail()}catch(_:IllegalArgumentException){}};LocalAdbPolicy.validateShell(2000,"u:r:shell:s0")}
    @Test fun unsupportedAbiAndChangedFirmwareFailClosed(){for(b in listOf(false to "fp",true to "other")){try{LocalAdbPolicy.validateProfile(b.first,b.second,"fp");fail()}catch(_:IllegalArgumentException){}};LocalAdbPolicy.validateProfile(true,"fp","fp")}
}
