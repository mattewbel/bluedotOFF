package app.bluedotoff.core

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LocalAdbProtocolTest {
    private fun rejected(block:()->Unit){try{block();fail("Malformed input accepted")}catch(_:IllegalArgumentException){}}
    @Test fun packetRoundTrip(){val p=AdbPacket(AdbPacket.WRTE,1,3,byteArrayOf(-1,0,127));val b=p.encode();val r=AdbPacket.decode(b.copyOfRange(0,24),b.copyOfRange(24,b.size));assertEquals(p.command,r.command);assertEquals(3,r.arg1);assertArrayEquals(p.payload,r.payload)}
    @Test fun modernChecksumZero(){val b=AdbPacket(AdbPacket.CNXN,payload=byteArrayOf(90)).encode();ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(16,0);assertEquals(90,AdbPacket.decode(b.copyOfRange(0,24),byteArrayOf(90)).payload[0].toInt())}
    @Test fun checksumMismatchRejected(){val b=AdbPacket(AdbPacket.WRTE,payload=byteArrayOf(90)).encode();rejected{AdbPacket.decode(b.copyOfRange(0,24),byteArrayOf(91))}}
    @Test fun wrongMagicRejected(){val b=AdbPacket(AdbPacket.CNXN).encode();b[20]=0;rejected{AdbPacket.length(b)}}
    @Test fun excessiveLengthRejected(){val b=AdbPacket(AdbPacket.WRTE).encode();ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).putInt(12,Int.MAX_VALUE);rejected{AdbPacket.length(b)}}
    @Test fun truncatedLengthRejected(){rejected{AdbPacket.length(ByteArray(23))}}
    @Test fun helperLaunchOnlyInstalledApk(){assertTrue(HelperLaunch.command("/data/app/~~ab==/app.bluedotoff-ab_c==/base.apk").startsWith("CLASSPATH=/data/app/"))}
    @Test fun shellInjectionRejected(){for(s in listOf("/data/app/a/base.apk;id","/data/app/$(id)/base.apk","/data/app/`id`/base.apk","/data/app/a/../base.apk","/data/app/a/base.apk\nid","/sdcard/base.apk","/data/app/a/base.apk x"))rejected{HelperLaunch.command(s)}}
    @Test fun helperFrameBounds(){rejected{HelperFrames.request(byteArrayOf())};rejected{HelperFrames.request(ByteArray(65537))};rejected{HelperFrames.responseSize(ByteBuffer.allocate(8).putInt(HelperFrames.MAGIC).putInt(6000001).array())}}
    @Test fun pairingReplySingleUse(){val p=PairingLease{0};val token=p.begin();assertTrue(p.take(token,"123456".toByteArray()));assertFalse(p.take(token,"123456".toByteArray()))}
    @Test fun pairingExpires(){var now=0L;val p=PairingLease{now};val token=p.begin();now=300000;assertFalse(p.take(token,"123456".toByteArray()))}
    @Test fun staleReplyAndCancelledGenerationRejected(){val p=PairingLease{0};val old=p.begin();val new=p.begin();assertFalse(p.take(old,"123456".toByteArray()));p.cancel();assertFalse(p.take(new,"123456".toByteArray()))}
    @Test fun wrongReplySchemaDoesNotConsumeLease(){val p=PairingLease{0};val token=p.begin();assertFalse(p.take(token,"12345".toByteArray()));assertFalse(p.take(token,"12345x".toByteArray()));assertTrue(p.take(token,"123456".toByteArray()))}
}
