package app.bluedotoff
import org.junit.Test
import org.junit.Assert.*
import app.bluedotoff.core.*
class DevicePresentationTest {
    @Test fun earlySupportUsesPlatformPrerequisitesRatherThanFoldModel(){
        assertTrue(DeviceSupport.basic("samsung",37,listOf("arm64-v8a")))
        assertTrue(DeviceSupport.basic("SAMSUNG",38,listOf("arm64-v8a","armeabi-v7a")))
        assertFalse(DeviceSupport.basic("samsung",36,listOf("arm64-v8a")))
        assertFalse(DeviceSupport.basic("other",37,listOf("arm64-v8a")))
        assertFalse(DeviceSupport.basic("samsung",37,listOf("x86_64")))
    }
    @Test fun blueWaterTracksRealProgressAndWaitsForFinalCleanup(){
        val m=UiModel(busy=true,progress=BatchProgress(35,70,BatchPhase.CHECKING))
        assertEquals(.5f,m.blueRemaining,.001f)
        assertEquals(.01f,m.copy(progress=BatchProgress(70,70,BatchPhase.CLOSING)).blueRemaining,.001f)
        assertEquals(0f,m.copy(progress=BatchProgress(70,70,BatchPhase.FINISHED)).blueRemaining,.001f)
        assertEquals(1f,UiModel().blueRemaining,.001f)
    }
}
