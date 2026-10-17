package app.bluedotoff.adb

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.ui.graphics.toArgb
import app.bluedotoff.BlueDotAppearance
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import app.bluedotoff.core.*

/** One actual step and one primary action; technical diagnostics are kept in advanced options. */
class LocalAdbSetupActivity:androidx.activity.ComponentActivity() {
    private lateinit var session:SetupSession
    private lateinit var box:LinearLayout
    private val colors by lazy { BlueDotAppearance.scheme(this) }
    private var lastKey=""
    private var advanced=false
    private var foreground=false
    private var navigating=false
    private var permissionDialog=false
    private var notificationSettingsOpened=false
    private var batterySettingsOpened=false
    private var notificationReminder:android.app.AlertDialog?=null
    private var code:EditText?=null
    private val changed:()->Unit={runOnUiThread{render()}}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        permissionDialog=state?.getBoolean("permissionDialog")?:false
        usbChoicePending=state?.getBoolean("usbChoicePending")?:false
        notificationSettingsOpened=state?.getBoolean("notificationSettingsOpened")?:false
        batterySettingsOpened=state?.getBoolean("batterySettingsOpened")?:false
        advanced=app.bluedotoff.BuildConfig.DEBUG&&intent.getBooleanExtra("diagnostics",false)
        session=SetupSession.acquire(this)
        val gutter=dp(if(resources.configuration.screenWidthDp>=760)BlueDotAppearance.WIDE_GUTTER else BlueDotAppearance.PAGE_GUTTER)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(colors.surface.toArgb())}
        root.addView(LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(gutter,0,gutter,0);minimumHeight=dp(BlueDotAppearance.HEADER_HEIGHT)
            addView(TextView(this@LocalAdbSetupActivity).apply {
                setText(app.bluedotoff.R.string.brand_name);textSize=24f;letterSpacing=-0.033f;includeFontPadding=false;setTextColor(colors.onSurface.toArgb())
            },LinearLayout.LayoutParams(0,-2,1f))
            addView(View(this@LocalAdbSetupActivity).apply {
                background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(BlueDotAppearance.dotBlue.toArgb())}
                contentDescription=app.bluedotoff.UiText.text(this@LocalAdbSetupActivity,"bluedotOFF 아이콘")
            },LinearLayout.LayoutParams(dp(18),dp(18)))
        },LinearLayout.LayoutParams(-1,-2))
        box=object:LinearLayout(this){
            override fun onMeasure(widthSpec:Int,heightSpec:Int){
                val width=View.MeasureSpec.getSize(widthSpec).coerceAtMost(dp(BlueDotAppearance.SETUP_WIDTH))
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),heightSpec)
            }
        }.apply{orientation=LinearLayout.VERTICAL;setPadding(0,dp(24),0,dp(48))}
        val frame=FrameLayout(this).apply {
            setPadding(gutter,0,gutter,0)
            addView(box,FrameLayout.LayoutParams(-1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
        root.addView(ScrollView(this).apply{isFillViewport=true;addView(frame)},LinearLayout.LayoutParams(-1,0,1f))
        root.setOnApplyWindowInsetsListener { view,insets ->
            val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard=insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,maxOf(bars.bottom,keyboard.bottom))
            insets
        }
        setContentView(root)
        window.insetsController?.setSystemBarsAppearance(
            if(BlueDotAppearance.dark(this))0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        root.requestApplyInsets()
        onBackPressedDispatcher.addCallback(this,object:androidx.activity.OnBackPressedCallback(true){
            override fun handleOnBackPressed(){backFromSetup()}
        })
        session.attach(changed)
    }
    override fun onStart(){super.onStart();app.bluedotoff.ForegroundState.started()}
    override fun onStop(){app.bluedotoff.ForegroundState.stopped();super.onStop()}
    override fun onResume(){super.onResume();foreground=true;if(notificationSettingsOpened){notificationSettingsOpened=false;session.notificationsResult()};if(!permissionDialog){if(batterySettingsOpened){batterySettingsOpened=false;session.batterySettingsReturned()}else session.resume()};render()}
    override fun onPause(){foreground=false;super.onPause()}
    override fun onSaveInstanceState(state:Bundle){state.putBoolean("permissionDialog",permissionDialog);state.putBoolean("usbChoicePending",usbChoicePending);state.putBoolean("notificationSettingsOpened",notificationSettingsOpened);state.putBoolean("batterySettingsOpened",batterySettingsOpened);super.onSaveInstanceState(state)}
    override fun onDestroy(){notificationReminder?.dismiss();code?.text?.clear();session.detach(changed);if(isFinishing&&!isChangingConfigurations)session.close();super.onDestroy()}
    private fun add(view:View){
        box.addView(view,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(BlueDotAppearance.SECTION_GAP)})
    }
    private fun rounded(color:Int)=GradientDrawable().apply{setColor(color);cornerRadius=dp(BlueDotAppearance.BUTTON_RADIUS).toFloat()}
    private fun text(value:String,size:Float=16f){add(TextView(this).apply{
        text=app.bluedotoff.UiText.text(this@LocalAdbSetupActivity,value);textSize=size;includeFontPadding=false
        setTextColor(if(size>=28f)colors.onSurface.toArgb() else colors.onSurfaceVariant.toArgb())
        setLineHeight(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,if(size>=28f)36f else if(size==14f)20f else 24f,resources.displayMetrics).toInt())
    })}
    private fun button(label:String,action:()->Unit){add(primaryButton(label,action))}
    private fun primaryButton(label:String,action:()->Unit)=Button(this).apply{
        text=app.bluedotoff.UiText.text(this@LocalAdbSetupActivity,label);isAllCaps=false;textSize=14f
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);includeFontPadding=false
        minHeight=dp(BlueDotAppearance.BUTTON_HEIGHT);minimumHeight=minHeight
        setPadding(dp(24),dp(16),dp(24),dp(16));setTextColor(colors.onPrimary.toArgb());backgroundTintList=null
        background=RippleDrawable(ColorStateList.valueOf(colors.onPrimary.copy(alpha=0.16f).toArgb()),rounded(colors.primary.toArgb()),null)
        setOnClickListener{action()}
    }
    private fun outlinedButton(label:String,action:()->Unit)=Button(this).apply{
        text=app.bluedotoff.UiText.text(this@LocalAdbSetupActivity,label);isAllCaps=false;textSize=14f
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);includeFontPadding=false
        minHeight=dp(BlueDotAppearance.BUTTON_HEIGHT);minimumHeight=minHeight
        setPadding(dp(24),dp(16),dp(24),dp(16));setTextColor(colors.primary.toArgb());backgroundTintList=null
        background=RippleDrawable(ColorStateList.valueOf(colors.primary.copy(alpha=0.12f).toArgb()),GradientDrawable().apply{setStroke(dp(1),this@LocalAdbSetupActivity.colors.outlineVariant.toArgb());cornerRadius=dp(BlueDotAppearance.BUTTON_RADIUS).toFloat()},null)
        setOnClickListener{action()}
    }
    /** Step 9, USB option: the same risk notice as Settings (confirm unlocks after 5 seconds), then USB debugging, then the
     *  same permission as automatic management - it starts watching again after a restart or an update. */
    private var usbChoicePending=false
    private fun chooseUsbWatch(){
        val t={v:String->app.bluedotoff.UiText.text(this,v)}
        val dialog=android.app.AlertDialog.Builder(this).setTitle(t("백그라운드 감시를 켤까요?"))
            .setMessage(t("켜기 전에 꼭 읽어주세요.")+"\n\n"+app.bluedotoff.BackgroundWatch.RISKS.joinToString("\n"){"• "+t(it)})
            .setPositiveButton(t("확인 (5초)"),null).setNegativeButton(t("취소"),null).create()
        dialog.setOnShowListener{
            val ok=dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE);ok.isEnabled=false
            var left=5
            val tick=object:Runnable{override fun run(){if(!dialog.isShowing)return;left--
                if(left>0){ok.text=t("확인 (${left}초)");ok.postDelayed(this,1000)}else{ok.text=t("확인");ok.isEnabled=true}}}
            ok.postDelayed(tick,1000)
            ok.setOnClickListener{dialog.dismiss();usbChoicePending=true;render()
                if(!app.bluedotoff.adb.WatchClient.usbEnabled(this))app.bluedotoff.SettingsEntrypoints.developerOptions(this)}
        }
        dialog.show()
    }
    /** Runs once step 9 is showing again (setup re-checks the connection after a visit to Settings) and USB debugging is on. */
    private fun finishUsbChoice(){
        if(!usbChoicePending||!app.bluedotoff.adb.WatchClient.usbEnabled(this))return
        usbChoicePending=false
        app.bluedotoff.BackgroundWatch.set(this,true)
        session.enableManagement()
    }
    private fun link(label:String,action:()->Unit){add(TextView(this).apply{
        text=app.bluedotoff.UiText.text(this@LocalAdbSetupActivity,label);textSize=14f;includeFontPadding=false
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);minHeight=dp(48);gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(12),dp(12),dp(12),dp(12));setTextColor(colors.primary.toArgb())
        background=RippleDrawable(ColorStateList.valueOf(colors.primary.copy(alpha=0.12f).toArgb()),null,rounded(android.graphics.Color.WHITE))
        isClickable=true;setOnClickListener{action()}
    })}
    private fun progress(step:Int,total:Int){
        add(LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            minimumHeight=dp(48)
            contentDescription=app.bluedotoff.UiText.text(this@LocalAdbSetupActivity,"연결 준비 단계 보기")
            isClickable=true;isFocusable=true
            background=RippleDrawable(ColorStateList.valueOf(colors.primary.copy(alpha=0.12f).toArgb()),null,rounded(android.graphics.Color.WHITE))
            setOnClickListener{app.bluedotoff.ScopeDialogs.steps(this@LocalAdbSetupActivity,SetupProgress.steps(session.facts))}
            addView(TextView(this@LocalAdbSetupActivity).apply{
                text=BlueDotAppearance.setupProgress(this@LocalAdbSetupActivity,step,total);textSize=14f;includeFontPadding=false
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);setTextColor(colors.primary.toArgb())
            })
            addView(object:ProgressBar(this@LocalAdbSetupActivity,null,android.R.attr.progressBarStyleHorizontal){
                private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas:android.graphics.Canvas){
                    val gap=dp(3).toFloat();val segment=(width-gap*(total-1))/total.toFloat()
                    repeat(total){i->
                        paint.color=when{ i+1==step->BlueDotAppearance.dotBlue.toArgb();i+1<step->colors.primary.toArgb();else->colors.outlineVariant.toArgb()}
                        val left=i*(segment+gap);canvas.drawRoundRect(left,0f,left+segment,height.toFloat(),dp(2).toFloat(),dp(2).toFloat(),paint)
                    }
                }
            }.apply{max=total;progress=step;isIndeterminate=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(-1,dp(6)).apply{topMargin=dp(12)})
        })
    }
    private fun input(view:EditText){
        view.textSize=16f;view.setTextColor(colors.onSurface.toArgb());view.setHintTextColor(colors.onSurfaceVariant.toArgb())
        view.backgroundTintList=ColorStateList.valueOf(colors.primary.toArgb());view.minHeight=dp(56)
        view.setPadding(dp(12),dp(12),dp(12),dp(12));add(view)
    }
    private fun render(){
        if(isDestroyed||!::box.isInitialized||permissionDialog)return
        val f=session.facts;val p=SetupFlow.presentation(f)
        if(p.stage==SetupStage.READY&&foreground&&!navigating&&!advanced){navigating=true;session.close(keepScan=true);startActivity(Intent(this,app.bluedotoff.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();return}
        // The pending USB choice must re-render: with USB debugging already on, that render is where step 9 finishes.
        val key="${p.stage}|${p.total}|${f.autoBlocked}|${session.notifications}|${session.manualInput}|${session.needsPort}|${session.inputError}|$advanced|${session.diagnosticResult}|${session.managementError}|${session.scanProgress}|$usbChoicePending"
        if(lastKey==key)return;lastKey=key;code?.text?.clear();code=null;box.removeAllViews()
        progress(p.progress,p.total)
        text(p.title,28f)
        if(f.autoBlocked&&p.stage==SetupStage.WIRELESS_DEBUGGING_OFF)listOf(GuideStep.AUTO_BLOCKER,GuideStep.AUTO_BLOCKER_OFF).forEach{add(app.bluedotoff.GuideArtwork(this,GuideSelection(it)))}
        else OnboardingGuide.current(p.stage,session.notifications,session.manualInput)?.let{image->
            add(app.bluedotoff.GuideArtwork(this,image))
        }
        if(session.manualInput&&p.stage in listOf(SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT,SetupStage.PAIRING_NOTIFICATION_READY,SetupStage.GUIDE_PAIRING_CODE_PAGE)){
            add(TextView(this).apply{
                text=app.bluedotoff.UiText.text(this@LocalAdbSetupActivity,"분할 화면으로 두 앱을 함께 열어주세요")
                textSize=18f;typeface=Typeface.create("sans-serif-medium",Typeface.BOLD);setTextColor(colors.onTertiaryContainer.toArgb())
                background=rounded(colors.tertiaryContainer.toArgb());setPadding(dp(16),dp(16),dp(16),dp(16))
            })
        }
        text(if(session.manualInput&&p.stage in listOf(SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT,SetupStage.PAIRING_NOTIFICATION_READY))"분할 화면에서 설정과 bluedotOFF를 함께 열어 코드를 입력하세요. 코드 창을 닫으면 연결할 수 없어요." else p.body)
        if(p.action!=SetupAction.NONE){if(session.manualInput&&p.stage==SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT)link(p.button){act(p.action)}else button(p.button){act(p.action)}}
        // Without automatic management the app cannot see Auto Blocker; a greyed-out switch is the sign the user sees.
        if(p.stage==SetupStage.WIRELESS_DEBUGGING_OFF&&!f.autoBlocked)add(TextView(this).apply{
            text=app.bluedotoff.UiText.text(this@LocalAdbSetupActivity,"무선 디버깅 버튼이 회색으로 잠겨 있나요? 눌러서 해결 방법 보기")
            textSize=16f;typeface=Typeface.create("sans-serif-medium",Typeface.BOLD);setTextColor(colors.onTertiaryContainer.toArgb())
            background=rounded(colors.tertiaryContainer.toArgb());setPadding(dp(16),dp(16),dp(16),dp(16));isClickable=true;setOnClickListener{autoBlockerHelp()}
        })
        val scan=session.scanProgress.takeIf{p.stage in listOf(SetupStage.VERIFYING,SetupStage.FINAL_CHECK)}
        if(scan!=null){
            text(if(scan.finishing)"${scan.total}개 앱 확인 완료 · 마무리 중…" else "${scan.completed} / ${scan.total}개 앱 확인 · ${scan.percent}%")
            add(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{
                max=100;progress=if(scan.finishing)100 else scan.percent;isIndeterminate=false;progressTintList=ColorStateList.valueOf(colors.primary.toArgb())
                progressBackgroundTintList=ColorStateList.valueOf(colors.secondaryContainer.toArgb())
            })
            if(scan.finishing)text("앱 확인을 마쳤어요. 기록과 연결 종료를 확인하고 있어요.",14f)
        }else if(p.stage in listOf(SetupStage.PAIRING,SetupStage.CONNECTING,SetupStage.VERIFYING,SetupStage.FINAL_CHECK,SetupStage.ENABLING_MANAGEMENT))add(ProgressBar(this).apply{indeterminateTintList=ColorStateList.valueOf(colors.primary.toArgb())})
        if(p.stage==SetupStage.AUTOMATIC_MANAGEMENT){
            if(session.managementError.isNotBlank())text(session.managementError,14f)
            add(outlinedButton("USB 디버깅으로 유지 (백그라운드 감시)"){chooseUsbWatch()})
            if(usbChoicePending&&app.bluedotoff.adb.WatchClient.usbEnabled(this))box.post{finishUsbChoice()}
            if(usbChoicePending)text("개발자 옵션에서 ‘USB 디버깅’을 켠 뒤 이 화면으로 돌아오세요. 켜져 있으면 자동으로 다음 단계로 넘어가요.",14f)
            link("지금은 사용하지 않기"){usbChoicePending=false;if(app.bluedotoff.BackgroundWatch.enabled(this@LocalAdbSetupActivity))app.bluedotoff.BackgroundWatch.set(this@LocalAdbSetupActivity,false);session.skipManagement()}
        }
        if(p.stage==SetupStage.BATTERY_OPTIMIZATION)link("나중에"){session.skipBatteryChoice()}
        if(p.stage==SetupStage.NOTIFICATION_PERMISSION)link("알림 없이 계속"){recommendNotifications()}
        if(p.stage==SetupStage.NEARBY_PERMISSION)link("직접 입력으로 연결"){session.skipNearby()}
        if(p.stage in listOf(SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT,SetupStage.PAIRING_NOTIFICATION_READY,SetupStage.GUIDE_PAIRING_CODE_PAGE)){
            if(session.manualInput){
                text("분할 화면을 유지한 채 아래에 코드를 입력하세요.")
                var port:EditText?=null
                if(session.needsPort){port=EditText(this).apply{hint=app.bluedotoff.UiText.text(this@LocalAdbSetupActivity,"페어링 포트");inputType=InputType.TYPE_CLASS_NUMBER;isSaveEnabled=false};input(port)}
                code=EditText(this).apply{hint=app.bluedotoff.UiText.text(this@LocalAdbSetupActivity,"6자리 페어링 코드");inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD;isSaveEnabled=false;importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS}
                input(code!!)
                if(session.inputError)text("6자리 숫자와 코드 화면의 포트를 확인해주세요.")
                button("이 코드로 연결"){val input=code!!.text;session.submit(input,manualPort=port?.text?.toString()?.toIntOrNull());input.clear()}
            }else link("알림이 보이지 않나요? 앱에서 코드 입력"){session.openManual()}
            link("연결 준비 취소"){session.close();finish()}
        }
        if(p.stage==SetupStage.CONNECTION_RECOVERY)link("한 번만 다시 페어링"){session.beginPairing()}
        link(if(advanced)"고급 옵션 닫기" else "문제가 있나요? 고급 옵션"){advanced=!advanced;render()}
        if(advanced){
            if(p.stage==SetupStage.READY)button("홈으로 돌아가기"){session.close(keepScan=true);startActivity(Intent(this,app.bluedotoff.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish()}
            text("네트워크 권한은 이 휴대폰의 연결에만 사용해요. 외부 서버로 데이터를 보내지 않아요.",14f)
            link("이 앱의 알림 설정"){openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))}
            link("무선 디버깅 화면 열기"){wirelessSettings()}
            link("연결할 기기 선택"){session.chooseConnection()}
            link("새 코드로 다시 페어링"){session.beginPairing()}
            if(app.bluedotoff.BuildConfig.DEBUG){
                link("읽기 전용 전체 검증"){session.debugReadOnly(false)}
                link("연결 중단 검사 (읽기 전용)"){session.debugReadOnly(true)}
                link("대량 연결 검증 (읽기 전용)"){session.debugBatchReadOnly()}
                if(session.diagnosticResult.isNotBlank())text(session.diagnosticResult,14f)
            }
            link("이 앱의 페어링 초기화"){
                android.app.AlertDialog.Builder(this).setTitle(app.bluedotoff.UiText.text(this,"이 앱의 연결 정보 초기화")).setMessage(app.bluedotoff.UiText.text(this,"bluedotOFF의 키만 삭제해요. Android 설정의 페어링된 기기 목록은 직접 관리할 수 있어요. 등록 앱의 표시 설정은 바뀌지 않아요."))
                    .setNegativeButton(app.bluedotoff.UiText.text(this,"취소"),null).setPositiveButton(app.bluedotoff.UiText.text(this,"초기화")){_,_->if(!LocalAdbSession.busy){AdbHostIdentity(this).reset();session.beginPairing()}}.show()
            }
        }
    }
    /** Same two-picture guide as the Home dialog, for setup where the block cannot be detected. */
    private fun autoBlockerHelp(){
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),0)}
        fun line(value:String,bold:Boolean=false)=content.addView(TextView(this).apply{text=value;textSize=15f;setPadding(0,dp(8),0,dp(8));if(bold)typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)})
        line(app.bluedotoff.UiText.text(this,"이 기능이 켜져 있으면 휴대폰이 무선 디버깅을 막아서 bluedotOFF가 연결할 수 없어요. 아래 순서대로 끈 뒤 다시 시도해주세요."))
        listOf(GuideStep.AUTO_BLOCKER,GuideStep.AUTO_BLOCKER_OFF).forEach{step->
            val guide=app.bluedotoff.GuideContent.load(this,GuideSelection(step))
            line("${step.number}  ${guide.title}",bold=true);content.addView(app.bluedotoff.GuideArtwork(this,GuideSelection(step)));line(guide.body)
        }
        // The pictures scroll; the button stays visible below them.
        val frame=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;addView(android.widget.ScrollView(this@LocalAdbSetupActivity).apply{addView(content)},LinearLayout.LayoutParams(-1,-2,1f))}
        val dialog=android.app.AlertDialog.Builder(this).setTitle(app.bluedotoff.UiText.text(this,"보안 위험 자동 차단을 꺼주세요")).setView(frame)
            .setNegativeButton(app.bluedotoff.UiText.text(this,"확인"),null).create()
        frame.addView(primaryButton("보안 설정 열기"){dialog.dismiss();app.bluedotoff.SettingsEntrypoints.autoBlocker(this)},LinearLayout.LayoutParams(-1,-2).apply{setMargins(dp(24),dp(12),dp(24),0)})
        dialog.show()
    }
    private fun chooseScope(){
        // Owner choice (2026-10-17): one explicit "이 범위로 시작". Choosing a scope here changes no app, so the
        // repeated confirmation was dropped; lowering an applied scope still asks first (MainActivity).
        app.bluedotoff.ScopeDialogs.choose(this,ApplicationScope.NORMAL,firstSetup=true){scope->session.chooseScope(scope)}
    }
    private fun act(action:SetupAction){when(action){
        SetupAction.START->session.welcome()
        SetupAction.NOTIFICATIONS->requestNotifications()
        SetupAction.DEVICE_INFO->app.bluedotoff.SettingsEntrypoints.softwareInformation(this)
        SetupAction.WIFI_SETTINGS->app.bluedotoff.SettingsEntrypoints.wifi(this)
        SetupAction.WIRELESS_SETTINGS->wirelessSettings()
        SetupAction.NEARBY->{if(session.requiresNearbyPermission){permissionDialog=true;requestPermissions(arrayOf("android.permission.ACCESS_LOCAL_NETWORK"),71)}else session.skipNearby()}
        SetupAction.AUTOMATIC_MANAGEMENT->{usbChoicePending=false;if(app.bluedotoff.BackgroundWatch.enabled(this))app.bluedotoff.BackgroundWatch.set(this,false);session.enableManagement()}
        SetupAction.BATTERY_OPTIMIZATION->{batterySettingsOpened=true;if(!app.bluedotoff.BatteryOptimization.open(this))batterySettingsOpened=false}
        SetupAction.APPLICATION_SCOPE->chooseScope()
        SetupAction.RETRY_PAIRING->session.beginPairing()
        SetupAction.RETRY_CONNECTION,SetupAction.RESUME->session.retryConnection()
        SetupAction.CHOOSE_CONNECTION->session.chooseConnection()
        SetupAction.SECURITY_SETTINGS->app.bluedotoff.SettingsEntrypoints.autoBlocker(this)
        SetupAction.NONE->Unit
    }}
    override fun onRequestPermissionsResult(r:Int,p:Array<String>,g:IntArray){
        super.onRequestPermissionsResult(r,p,g);permissionDialog=false
        if(r==70){session.notificationsResult();lastKey="";render();return}
        if(r==71){
            if(!session.broad)session.skipNearby()else session.permissionsChanged()
            lastKey="";render()
        }
    }
    private fun backFromSetup(){
        if(permissionDialog)return
        if(SetupFlow.stage(session.facts)==SetupStage.NOTIFICATION_PERMISSION&&!session.notifications)recommendNotifications()
        else finish()
    }
    private fun recommendNotifications(){
        if(notificationReminder?.isShowing==true)return
        notificationReminder=android.app.AlertDialog.Builder(this)
            .setTitle(app.bluedotoff.UiText.text(this,"알림을 켜면 더 쉽게 연결할 수 있어요"))
            .setMessage(app.bluedotoff.UiText.text(this,"알림에서 코드를 입력하면 설정 화면을 나갈 필요가 없어요. 알림 없이 계속하려면 설정과 이 앱을 분할 화면으로 함께 열어야 해요."))
            .setPositiveButton(app.bluedotoff.UiText.text(this,"알림 허용")){_,_->requestNotifications()}
            .setNegativeButton(app.bluedotoff.UiText.text(this,"분할 화면으로 계속")){_,_->session.skipNotifications()}
            .setNeutralButton(app.bluedotoff.UiText.text(this,"돌아가기"),null).show()
    }
    private fun requestNotifications(){
        if(session.notifications){session.notificationsResult();return}
        val prefs=getSharedPreferences("ui-state",0)
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED ||
            prefs.getBoolean("notificationRequested",false)&&!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)){
            notificationSettingsOpened=true
            openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
        }else{
            permissionDialog=true;session.notificationsAsked();prefs.edit().putBoolean("notificationRequested",true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),70)
        }
    }
    private fun openSettings(intent:Intent){runCatching{startActivity(intent)}.onFailure{startActivity(Intent(Settings.ACTION_SETTINGS))}}
    private fun wirelessSettings(){
        if(session.needsNearbyBeforeSettings){session.refresh();lastKey="";render();return}
        // Prepare while visible; returning from Settings is not a prerequisite.
        session.prepareSettingsHandoff()
        val direct=Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
        if(direct.resolveActivity(packageManager)!=null)openSettings(direct)
        else openSettings(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key","toggle_adb_wireless").putExtra(":settings:show_fragment_args",Bundle().apply{putString(":settings:fragment_args_key","toggle_adb_wireless")}))
    }
}
