package app.bluedotoff
import android.content.pm.ApplicationInfo
import app.bluedotoff.core.*
import org.junit.Assert.*
import org.junit.Test

class OwnershipMetadataTest {
    @Test fun bothPreinstallFlagKindsCanRemainInDefaultHistory(){
        for(flag in listOf(ApplicationInfo.FLAG_SYSTEM,ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)){
            val ownership=classify(flag)
            val a=DiscoveredApp("test.app","지도",10502,preinstalled=true,launcher=true,access=LocationAccess(fine=100),indicatorHint=FineIndicatorHint.SENSITIVE,uidClass=ownership.uidClass)
            assertEquals(listOf(a),HistoryPolicy.list(listOf(a),emptySet(),HistoryView.APPS))
            assertNull(ownership.rejection(false))
        }
    }
    private fun classify(flags:Int)=BinderBackend.ownership("test.app",10502,listOf("test.app"),flags,false,null)
    @Test fun systemFlagAloneDoesNotBlock(){assertEquals(UidClass.NORMAL,classify(ApplicationInfo.FLAG_SYSTEM).uidClass);assertNull(classify(ApplicationInfo.FLAG_SYSTEM).rejection(false))}
    @Test fun updatedSystemAppAloneDoesNotBlock(){assertNull(classify(ApplicationInfo.FLAG_UPDATED_SYSTEM_APP).rejection(false))}
    @Test fun independentPreinstalledWithBothFlagsRemainsNormal(){assertEquals(UidClass.NORMAL,classify(ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP).uidClass)}
    @Test fun persistentFlagStillBlocks(){assertEquals(UidClass.PERSISTENT_CORE,classify(ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_PERSISTENT).uidClass)}
}
