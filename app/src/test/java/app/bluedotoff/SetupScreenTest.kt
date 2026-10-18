package app.bluedotoff

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.bluedotoff.adb.*
import app.bluedotoff.core.SetupFlow
import app.bluedotoff.core.SetupStage
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="ko-rKR-w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SetupScreenTest {
    /** The setup dialogs are the shared Compose panels (ScopeDialogs). */
    @get:Rule val compose=createEmptyComposeRule()
    /** Step 9 choice card: the clickable box around its title row. */
    private fun card(views:List<View>,title:String)=views.filterIsInstance<TextView>().single{it.text.toString()==title}.parent.parent as View
    @Test fun usbBatteryOfferHasTwelveStepsAndAnExplicitSkip(){
        runtime.finalCheckResolved=false;runtime.managementChoiceResolved=false;runtime.batteryChoiceRequired=true;runtime.batteryChoiceResolved=false
        runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        show("111-usb-management");session.skipManagement();shadowOf(Looper.getMainLooper()).idle()
        val activity=controller!!.get()
        fun views()=descendants(activity.findViewById<ViewGroup>(android.R.id.content))
        assertTrue(views().filterIsInstance<TextView>().any{it.text=="연결 준비 · 10 / 12"})
        assertTrue(views().filterIsInstance<Button>().any{it.text==BatteryOptimization.TITLE})
        assertEquals(12,views().filterIsInstance<ProgressBar>().single().max)
        views().filterIsInstance<TextView>().single{it.text=="나중에"}.performClick()
        assertTrue(runtime.batteryChoiceResolved)
        assertNotEquals(SetupStage.BATTERY_OPTIMIZATION,SetupFlow.stage(session.facts))
    }
    private fun descendants(root:View):List<View> = listOf(root)+(if(root is ViewGroup)(0 until root.childCount).flatMap{descendants(root.getChildAt(it))}else emptyList())
    @Test fun progressDoesNotColorPreparedFutureStepsAndOpensSharedStyleList(){
        runtime.notifications=false
        val views=show("24-notification-current-progress")
        assertEquals(2,SetupFlow.presentation(session.facts).progress)
        assertTrue(views.filterIsInstance<TextView>().none{it.text.contains("완료한 단계")})
        val bar=views.filterIsInstance<ProgressBar>().single()
        val bitmap=Bitmap.createBitmap(bar.width,bar.height,Bitmap.Config.ARGB_8888)
        bar.draw(Canvas(bitmap))
        val gap=3*context.resources.displayMetrics.density;val segment=(bar.width-gap*10)/11f
        val pending=BlueDotAppearance.scheme(context).outlineVariant.toArgb()
        for(i in 2..10)assertEquals(pending,bitmap.getPixel((i*(segment+gap)+segment/2).toInt(),bar.height/2))
        bitmap.recycle()
        (bar.parent as View).performClick();shadowOf(Looper.getMainLooper()).idle()
        val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(dialog.isShowing)
        val view=dialog.window!!.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.heightPixels,View.MeasureSpec.AT_MOST));view.layout(0,0,view.measuredWidth,view.measuredHeight)
        val capture=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(capture))
        File(System.getProperty("screenshotDir"),"24-setup-steps-dialog.png").outputStream().use{capture.compress(Bitmap.CompressFormat.PNG,100,it)};capture.recycle()
    }
    @Test @Config(sdk=[30]) fun unsupportedOlderAndroidStillShowsItsExplanation(){
        runtime.supported=false
        val views=show("79-unsupported-sdk30")
        assertTrue(views.filterIsInstance<TextView>().any{it.text=="이 기기의 지원 여부를 확인할 수 없어요"})
        assertTrue(views.filterIsInstance<Button>().isEmpty())
    }
    @Test fun backAfterNotificationDenialOffersRetryAndExplicitSplitScreenContinue(){
        runtime.notifications=false;runtime.developer=false
        val views=show("79-notification-before-request");val activity=controller!!.get()
        views.filterIsInstance<Button>().single().performClick()
        assertEquals(SetupStage.NOTIFICATION_PERMISSION,SetupFlow.stage(session.facts))
        activity.onRequestPermissionsResult(70,arrayOf("android.permission.POST_NOTIFICATIONS"),intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        assertEquals(SetupStage.NOTIFICATION_PERMISSION,SetupFlow.stage(session.facts))
        activity.onBackPressedDispatcher.onBackPressed()
        assertFalse(activity.isFinishing)
        compose.onNodeWithText("알림 허용").assertIsEnabled()
        compose.onNodeWithText("분할 화면으로 계속").performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SetupStage.DEVELOPER_OPTIONS_OFF,SetupFlow.stage(session.facts))
    }
    @Test fun notificationGrantMovesToDeveloperOnlyAfterTheCallback(){
        runtime.notifications=false;runtime.developer=false
        val views=show("79-notification-wait");val activity=controller!!.get()
        views.filterIsInstance<Button>().single().performClick();runtime.notifications=true;session.refresh()
        assertEquals(SetupStage.NOTIFICATION_PERMISSION,SetupFlow.stage(session.facts))
        activity.onRequestPermissionsResult(70,arrayOf("android.permission.POST_NOTIFICATIONS"),intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
        assertEquals(SetupStage.DEVELOPER_OPTIONS_OFF,SetupFlow.stage(session.facts))
    }
    @Test fun splitScreenIsExplicitlyHighlightedInManualPairing(){
        runtime.notifications=false;session.skipNotifications();runtime.pairEndpoint=LocalAdbDiscovery.Endpoint("own",37000)
        val views=show("79-manual-split")
        assertTrue(views.filterIsInstance<TextView>().any{it.text=="분할 화면으로 두 앱을 함께 열어주세요"})
        assertTrue(views.filterIsInstance<EditText>().isNotEmpty())
    }
    @Test fun nearbyPermissionIsRequestedBeforeLeavingForWirelessSettings(){
        runtime.wireless=false;runtime.broad=false
        val views=show("15-first-wireless-handoff")
        val activity=controller!!.get();views.filterIsInstance<Button>().single().performClick()
        assertEquals("android.content.pm.action.REQUEST_PERMISSIONS",shadowOf(activity).nextStartedActivity.action)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(listOf("android.permission.ACCESS_LOCAL_NETWORK"),shadowOf(activity).lastRequestedPermission.requestedPermissions.toList())
        assertFalse(runtime.alive)
        runtime.broad=true
        activity.onRequestPermissionsResult(71,arrayOf("android.permission.ACCESS_LOCAL_NETWORK"),intArrayOf(android.content.pm.PackageManager.PERMISSION_GRANTED))
        assertNull(shadowOf(activity).nextStartedActivity);assertFalse(runtime.alive)
        assertEquals(SetupStage.WIRELESS_DEBUGGING_OFF,SetupFlow.stage(session.facts))
        val root=activity.findViewById<ViewGroup>(android.R.id.content)
        fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
        all(root).filterIsInstance<Button>().single().performClick()
        assertNotNull(shadowOf(activity).nextStartedActivity);assertTrue(runtime.alive)
        runtime.wireless=true;runtime.pairEndpoint=LocalAdbDiscovery.Endpoint("own",37000);session.refresh()
        assertEquals(app.bluedotoff.core.SetupStage.PAIRING_NOTIFICATION_READY,app.bluedotoff.core.SetupFlow.stage(session.facts))
        assertEquals(0,runtime.managementCalls)
    }
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var runtime:FakeSetupRuntime
    private lateinit var session:SetupSession
    private lateinit var queue:SetupQueue
    private var controller:org.robolectric.android.controller.ActivityController<LocalAdbSetupActivity>?=null
    @Before fun create(){ScopePreferences(context).select(app.bluedotoff.core.ApplicationScope.GROUPS);context.getSharedPreferences("ui-state",0).edit().putBoolean("welcomed",true).commit();runtime=FakeSetupRuntime();queue=SetupQueue();session=SetupSession(context,runtime,queue);SetupSession::class.java.getDeclaredField("active").apply{isAccessible=true}.set(null,session)}
    @After fun destroy(){controller?.pause()?.stop()?.destroy();session.close();shadowOf(Looper.getMainLooper()).idle()}
    private fun show(name:String):List<View>{
        controller=Robolectric.buildActivity(LocalAdbSetupActivity::class.java).setup().visible()
        if(!runtime.managementChoiceResolved&&runtime.paired){queue.next();shadowOf(Looper.getMainLooper()).idle()}
        val activity=controller!!.get();val root=activity.findViewById<ViewGroup>(android.R.id.content)
        val width=context.resources.displayMetrics.widthPixels;val height=context.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE);root.draw(canvas)
        val folder=File(System.getProperty("screenshotDir"),"phase76").apply{mkdirs()}
        File(folder,name+".png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList())
        return all(root)
    }
    @Test fun developerOffHasOneActionAndBuildNumberGuide(){runtime.developer=false;val views=show("01-developer-off");assertEquals(listOf("휴대전화 정보 열기"),views.filterIsInstance<Button>().map{it.text.toString()});assertTrue(views.filterIsInstance<TextView>().any{it.text.contains("7번")});assertTrue(views.filterIsInstance<EditText>().isEmpty());assertEquals(0,controller!!.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)}
    @Test fun recommendedChoiceIsExplicitAndHasVisibleSkipBeforeHome(){
        runtime.managementChoiceResolved=false;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        val views=show("13-recommended-management")
        // Step 9 offers both ways to stay connected, plus a visible skip.
        assertTrue("no option is drawn as the main button",views.filterIsInstance<Button>().isEmpty())
        val (one,two)=listOf("① 앱을 켤 때만 적용","② 백그라운드 자동 감시").map{card(views,it)}
        assertEquals(one.width,two.width);assertEquals(one.background.javaClass,two.background.javaClass)
        assertTrue("short Korean titles keep the debugging on the same line",listOf(one,two).all{((it as ViewGroup).getChildAt(0) as android.widget.LinearLayout).orientation==android.widget.LinearLayout.HORIZONTAL})
        assertTrue(views.filterIsInstance<TextView>().any{it.text=="지금은 사용하지 않기"})
        assertTrue(views.filterIsInstance<TextView>().any{it.text.contains("일부 은행·결제 앱이 안 열릴 수 있어요")})
        assertEquals(0,runtime.managementCalls);assertNull(shadowOf(controller!!.get()).nextStartedActivity)
    }
    /** USB debugging already on: confirming the 5-second risk dialog must continue by itself (no Settings visit
     *  re-renders the screen). Previously the pending choice was not part of the render key and setup stalled. */
    @Test fun usbChoiceWithUsbAlreadyOnContinuesAfterConfirm(){
        android.provider.Settings.Global.putInt(context.contentResolver,android.provider.Settings.Global.ADB_ENABLED,1)
        runtime.managementChoiceResolved=false;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        val views=show("13b-usb-choice-usb-on")
        compose.mainClock.autoAdvance=false
        card(views,"② 백그라운드 자동 감시").performClick()
        shadowOf(Looper.getMainLooper()).idle();compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("켜기 (5초)").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(5100)
        compose.onNodeWithText("켜기").assertIsEnabled().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        // The choice was finished: watch preference set and the management work queued (then run here).
        assertTrue(BackgroundWatch.enabled(context));assertFalse(queue.tasks.isEmpty())
        queue.next();shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,runtime.managementCalls)
        // USB was already on, so Developer options is not opened.
        assertNull(shadowOf(controller!!.get()).nextStartedActivity?.takeIf{it.action==android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS})
    }
    /** USB debugging off: the choice opens Developer options; turning USB debugging on there and coming back finishes it. */
    @Test fun usbChoiceFinishesAfterTurningUsbOnInDeveloperOptions(){
        android.provider.Settings.Global.putInt(context.contentResolver,android.provider.Settings.Global.ADB_ENABLED,0)
        runtime.managementChoiceResolved=false;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        val views=show("13c-usb-choice-usb-off")
        compose.mainClock.autoAdvance=false
        card(views,"② 백그라운드 자동 감시").performClick()
        shadowOf(Looper.getMainLooper()).idle();compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("켜기 (5초)").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(5100)
        compose.onNodeWithText("켜기").assertIsEnabled().performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(BackgroundWatch.enabled(context));assertEquals(0,runtime.managementCalls)
        // The user turns USB debugging on in Developer options and returns.
        android.provider.Settings.Global.putInt(context.contentResolver,android.provider.Settings.Global.ADB_ENABLED,1)
        // Setup re-checks the connection after a visit to Settings, then shows step 9 again and finishes the choice.
        controller!!.pause().resume();shadowOf(Looper.getMainLooper()).idle()
        repeat(10){if(queue.tasks.isNotEmpty()){queue.next();shadowOf(Looper.getMainLooper()).idle()}}
        assertTrue(BackgroundWatch.enabled(context));assertEquals(1,runtime.managementCalls)
    }
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi",fontScale=1.5f) fun englishRecommendationFitsScrollableSharedLayout(){
        runtime.managementChoiceResolved=false;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        val views=show("14-recommended-management-en-large")
        assertTrue(views.filterIsInstance<TextView>().any{it.text=="Choose how to stay connected"})
        assertTrue(views.filterIsInstance<TextView>().any{it.text=="Not now"})
        assertTrue(views.filterIsInstance<Button>().isEmpty())
        // Long titles at a large font: the debugging moves under the title instead of squeezing it.
        listOf("① Only when you open the app","② Automatic background watch").forEach{assertEquals(android.widget.LinearLayout.VERTICAL,((card(views,it) as ViewGroup).getChildAt(0) as android.widget.LinearLayout).orientation)}
    }
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun englishStepNineAtNormalFont(){
        runtime.managementChoiceResolved=false;runtime.paired=true;runtime.connectEndpoint=LocalAdbDiscovery.Endpoint("saved",42000)
        val views=show("14b-recommended-management-en")
        listOf("① Only when you open the app","② Automatic background watch").forEach{card(views,it)}
    }
    @Test fun greyedOutWirelessSwitchLeadsToAutoBlockerGuide(){
        runtime.paired=true;runtime.wireless=false;val views=show("09-wireless-off-auto-blocker-hint")
        val hint=views.filterIsInstance<android.widget.TextView>().single{it.text.toString().startsWith("무선 디버깅 버튼이 회색으로 잠겨 있나요?")}
        hint.performClick();shadowOf(Looper.getMainLooper()).idle()
        compose.onNodeWithText("보안 설정 열기").assertIsEnabled();compose.onNodeWithText("닫기").assertIsEnabled()
    }
    @Test fun pairedWirelessOffDoesNotShowAnyCodeFields(){runtime.paired=true;runtime.wireless=false;val views=show("02-paired-wireless-off");assertEquals(listOf("무선 디버깅 열기"),views.filterIsInstance<Button>().map{it.text.toString()});assertTrue(views.filterIsInstance<EditText>().isEmpty())}
    @Test fun advertisementReadyKeepsSettingsNotificationAsPrimaryAction(){runtime.pairEndpoint=LocalAdbDiscovery.Endpoint("own",37000);val views=show("03-code-notification-ready");assertTrue(views.filterIsInstance<Button>().isEmpty());assertTrue(views.filterIsInstance<EditText>().isEmpty());assertTrue(views.filterIsInstance<TextView>().any{it.text.contains("코드 입력")})}
    @Test fun deniedNotificationUsesInAppInputWithoutShowingPortWhenResolved(){runtime.notifications=false;context.getSharedPreferences("local-adb",0).edit().putBoolean("notificationAsked",true).commit();runtime.pairEndpoint=LocalAdbDiscovery.Endpoint("own",37000);val views=show("04-notification-denied");assertEquals(listOf("이 코드로 연결"),views.filterIsInstance<Button>().map{it.text.toString()});val input=views.filterIsInstance<EditText>().single();assertEquals("6자리 페어링 코드",input.hint);assertFalse(input.isSaveEnabled);assertEquals(0,controller!!.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)}
    @Test fun firstNotificationExplanationPrecedesSettings(){runtime.notifications=false;val views=show("05-notification-first");assertEquals(listOf("알림 허용"),views.filterIsInstance<Button>().map{it.text.toString()});assertTrue(views.filterIsInstance<TextView>().any{it.text=="알림 없이 계속"})}
    @Test fun welcomeKeepsStartActionAndSharedHeader(){
        context.getSharedPreferences("ui-state",0).edit().putBoolean("welcomed",false).commit()
        val views=show("06-setup-welcome")
        assertTrue(views.filterIsInstance<TextView>().any{it.text=="bluedotOFF"})
        assertEquals(listOf("시작하기"),views.filterIsInstance<Button>().map{it.text.toString()})
        assertEquals(1,views.filterIsInstance<ProgressBar>().single().progress)
        views.filterIsInstance<Button>().single().performClick()
        assertTrue(session.facts.welcomed)
        assertEquals(0,runtime.pairCalls)
        assertEquals(0,runtime.connectCalls)
    }
    @Test fun wifiStepAndSkipActionKeepTheirOriginalBehavior(){
        runtime.wifiConnected=false;val views=show("07-wifi-off")
        assertEquals(listOf("Wi-Fi 열기"),views.filterIsInstance<Button>().map{it.text.toString()})
        assertEquals(4,views.filterIsInstance<ProgressBar>().single().progress)
        assertTrue(views.filterIsInstance<EditText>().isEmpty())
    }
    @Test @Config(qualifiers="en-rUS-w412dp-h915dp-xxhdpi") fun englishSetupUsesSameProgressAndAction(){
        runtime.wireless=false;val views=show("08-setup-english")
        assertTrue(views.filterIsInstance<TextView>().any{it.text=="Connection setup · 6 / 11"})
        assertEquals(listOf(UiText.text(context,"무선 디버깅 열기")),views.filterIsInstance<Button>().map{it.text.toString()})
    }
    @Test @Config(qualifiers="ko-rKR-w412dp-h915dp-night-xxhdpi") fun darkSetupUsesSameMainPalette(){
        runtime.developer=false;val views=show("09-setup-dark")
        val button=views.filterIsInstance<Button>().single()
        assertEquals(BlueDotAppearance.scheme(context).onPrimary.toArgb(),button.currentTextColor)
        assertTrue(BlueDotAppearance.dark(context))
    }
    @Test @Config(fontScale=1.5f) fun largeTextRetainsScrollableGuideAndSingleAction(){
        runtime.developer=false;val views=show("10-setup-large-text")
        assertEquals(1,views.filterIsInstance<Button>().size)
        assertEquals(1,views.filterIsInstance<ScrollView>().size)
        assertTrue(views.filterIsInstance<ScrollView>().single().getChildAt(0).height>0)
    }
    @Test @Config(qualifiers="ko-rKR-w960dp-h750dp-xhdpi") fun wideSetupCapsBodyWidthAndKeepsAction(){
        runtime.developer=false;val views=show("11-setup-wide")
        val button=views.filterIsInstance<Button>().single()
        assertTrue(button.width<=BlueDotAppearance.SETUP_WIDTH*context.resources.displayMetrics.density)
        assertEquals("휴대전화 정보 열기",button.text.toString())
    }
    @Test fun manualInputSurvivesAnUnchangedSessionRefresh(){
        runtime.notifications=false;context.getSharedPreferences("local-adb",0).edit().putBoolean("notificationAsked",true).commit()
        runtime.pairEndpoint=LocalAdbDiscovery.Endpoint("own",37000)
        val views=show("12-manual-input-empty");val input=views.filterIsInstance<EditText>().single()
        input.setText("123456");session.refresh();shadowOf(Looper.getMainLooper()).idle()
        assertEquals("123456",input.text.toString())
        assertFalse(input.isSaveEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS,input.importantForAutofill)
        input.text.clear()
    }
}
