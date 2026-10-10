package app.bluedotoff

import android.animation.ValueAnimator
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.bluedotoff.core.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private val DotBlue = BlueDotAppearance.dotBlue
fun TargetState.label(): String = when(this) {
    TargetState.APPLIED -> "bluedotOFF 적용중"
    TargetState.NOT_APPLIED -> "적용 안 됨"
    TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE -> "확인 필요"
    TargetState.NEEDS_REAPPLY_BOOT -> "재부팅 · 재적용 필요"
    TargetState.NEEDS_REAPPLY_UPDATE -> "업데이트 · 재적용 필요"
    TargetState.NEEDS_REAPPLY_POLICY -> "표시 설정 변경 · 재적용 필요"
    TargetState.NEEDS_RECHECK_FIRMWARE -> "기기 호환성 재확인"
    TargetState.UNSUPPORTED_STATE -> "권한 상태 확인 필요"
    TargetState.MISSING_TARGET -> "삭제된 앱 · 조치 필요 없음"
    TargetState.SHARED_UID_BLOCKED -> "다른 앱과 묶임 · 적용 불가"
    TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW -> "앱 묶음 변경 · 확인 필요"
    TargetState.SHIZUKU_REQUIRED -> "연결 후 확인 필요"
    TargetState.FAILED_SAFE -> "작업 중단 · 원복 확인"
    TargetState.FAILED_ROLLBACK -> "복원 확인 필요 · 변경 차단"
    TargetState.INACTIVE_INSTALL -> "새 설치 · 새로 등록 필요"
}
private fun Snapshot.blockReason(): String = rejectionReason()

@Composable
fun BlueDotApp(model: UiModel, navigate: (Page)->Unit, select: (String)->Unit, hide: (String)->Unit, restore: (String)->Unit, hideAll: ()->Unit, restoreAll: ()->Unit, openBackend: ()->Unit, setupBackend: ()->Unit, authorize: ()->Unit, refresh: ()->Unit, allowNotifications: ()->Unit, dismissNotifications: ()->Unit, begin: ()->Unit, loadApps: ()->Unit, clearNotice: ()->Unit, togglePick:(String)->Unit={}, addSelected:()->Unit={}, queryChanged:(String)->Unit={},filterChanged:(AppFilter)->Unit={},editHome:()->Unit={},toggleHome:(String)->Unit={},restoreSelected:()->Unit={},contentShown:()->Unit={},pickerRevision:Long=0L,historyViewChanged:(HistoryView)->Unit={},pickerViewChanged:(PickerView)->Unit={},wirelessExit:()->Unit={},toggleAll:()->Unit={},managementChanged:(Boolean)->Unit={},removeManagementPermission:()->Unit={},sharedInspect:(String)->Unit={},sharedAccept:()->Unit={},sharedDismiss:()->Unit={},scopeChange:()->Unit={},reapplyNeeded:(List<String>)->Unit={},notificationChanged:(Boolean)->Unit={}) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val motion = ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(model.page,pickerRevision){if(model.page==Page.PICK&&model.discovered.isNotEmpty()&&model.pickerRows!=null){withFrameNanos{};contentShown()}}
    val tint by animateFloatAsState(model.blueRemaining, if(motion) tween(580) else snap(), label="blue tint")
    val scheme = BlueDotAppearance.scheme(context,dark,tint)
    val surface = BlueDotAppearance.scheme(context,dark,0f).surface
    val expires=model.completion?.let{!it.hide&&it.error.isBlank()&&it.cleanupWarning.isBlank()&&it.results.isNotEmpty()&&it.results.all{r->r.success}}==true
    var completionVisible by remember(model.completion){mutableStateOf(true)}
    LaunchedEffect(model.completion){
        val done=model.completion
        if(done!=null&&expires){
            val age=if(done.completedAt>0)(System.nanoTime()/1_000_000-done.completedAt).coerceAtLeast(0) else 0L
            delay((5000L-age).coerceAtLeast(0));completionVisible=false
        }
    }
    var showRestoreAll by remember { mutableStateOf(false) }
    var showRestoreMenu by remember { mutableStateOf(false) }
    var showReapplyNeeded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(model.reapplyCount,model.busy,model.page,model.gate){
        if(model.reapplyCount==0||model.busy||model.page!=Page.HOME||model.gate!=Gate.READY)showReapplyNeeded=false
    }
    val snackbars = remember { SnackbarHostState() }
    var showAutoBlocker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(model.notice,model.busy) { if(model.notice==app.bluedotoff.adb.AutoBlocker.MESSAGE&&!model.busy){showAutoBlocker=true;clearNotice()} else if(model.notice.isNotBlank()&&!model.busy){snackbars.showSnackbar(UiText.text(context,UiText.error(model.notice)),duration=SnackbarDuration.Short);clearNotice()} }
    MaterialTheme(colorScheme=scheme) {
        BackHandler(enabled=model.busy) {}
        BackHandler(enabled=model.page!=Page.HOME&&!model.busy) { navigate(Page.HOME) }
        Surface(Modifier.fillMaxSize(),color=surface,contentColor=scheme.onSurface) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                if(model.busy&&!model.scopePreview){
                    val restoring=!model.operationHide
                    if(restoring)Box(Modifier.fillMaxSize().background(DotBlue))
                    BlueWaterBackground(tint,if(restoring)Color(0xFF191C20)else DotBlue,Modifier.fillMaxSize(),moving=motion)
                }else BlueWaterBackground(tint,BlueDotAppearance.scheme(context,dark,1f).surface,Modifier.fillMaxSize())
                val wide=maxWidth>=760.dp
                // Keep scroll/selection composition alive, but hide the page behind
                // the work overlay visually and from accessibility services.
                val pageVisibility=if(model.busy&&!model.scopePreview)Modifier.alpha(0f).clearAndSetSemantics{} else Modifier
                Column(Modifier.fillMaxSize().then(pageVisibility).padding(horizontal=if(wide)BlueDotAppearance.WIDE_GUTTER.dp else BlueDotAppearance.PAGE_GUTTER.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min=BlueDotAppearance.HEADER_HEIGHT.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(androidx.compose.ui.res.stringResource(R.string.brand_name),style=MaterialTheme.typography.headlineSmall,letterSpacing=(-0.8).sp,modifier=Modifier.weight(1f))
                        if(model.page in listOf(Page.PICK,Page.DETAIL,Page.REAPPLY,Page.HELP)) TextButton(onClick={navigate(Page.HOME)},enabled=!model.busy){Text("홈")}
                        Box(Modifier.padding(start=12.dp).size(18.dp).background(DotBlue,CircleShape).semantics { contentDescription=UiText.text(context,"bluedotOFF 아이콘") })
                    }
                    if(!model.busy&&(!expires||completionVisible))model.completion?.takeUnless{it.hide&&it.error.isBlank()&&it.cleanupWarning.isBlank()&&it.results.isNotEmpty()&&it.results.all{r->r.success}}?.let{done->
                        Surface(Modifier.fillMaxWidth().padding(bottom=8.dp),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.secondaryContainer){
                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                Text(done.summary(),style=MaterialTheme.typography.titleSmall)
                                if(done.error.isNotBlank())Text(UiText.error(done.error),style=MaterialTheme.typography.bodySmall)
                                if(model.wirelessWarning.isNotBlank()){
                                    Text(UiText.error(model.wirelessWarning),style=MaterialTheme.typography.bodySmall)
                                    TextButton(onClick=wirelessExit){Text("무선 디버깅 열기")}
                                }
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                    if(model.page==Page.HELP) HelpScreen(openBackend,setupBackend)
                    else if(model.gate!=Gate.READY) Wizard(model,openBackend,setupBackend,authorize,refresh,begin,{navigate(Page.HELP)})
                    else when(model.page) {
                        Page.HOME -> HomeScreen(model,wide,{navigate(Page.PICK)},{showRestoreMenu=true},select,openBackend,{navigate(Page.HISTORY)},hide,editHome,toggleHome,restoreSelected,wirelessExit,{showReapplyNeeded=true})
                        Page.HISTORY -> HistoryScreen(model,hide,openBackend,historyViewChanged)
                        Page.PICK -> PickerScreen(model,togglePick,addSelected,queryChanged,loadApps,openBackend,filterChanged,pickerViewChanged,toggleAll,sharedInspect)
                        Page.DETAIL -> DetailScreen(model,hide,restore,openBackend)
                        Page.REAPPLY -> ReapplyScreen(model,hideAll,openBackend,select)
                        Page.SETTINGS -> SettingsScreen(model,notificationChanged,openBackend,refresh,{navigate(Page.HELP)},wirelessExit,managementChanged,removeManagementPermission,scopeChange)
                        Page.HELP -> Unit
                    }
                    }
                    if(model.gate==Gate.READY)HorizontalDivider(color=MaterialTheme.colorScheme.onSurface.copy(alpha=0.12f))
                    // Transparent so the bar follows the blue/system page background instead of floating as a gray block.
                    if(model.gate==Gate.READY)NavigationBar(containerColor=Color.Transparent,tonalElevation=0.dp) {
                        listOf(Page.HOME to "홈",Page.HISTORY to "기록",Page.SETTINGS to "설정").forEach{(page,label)->
                            NavigationBarItem(selected=model.page==page,onClick={navigate(page)},enabled=!model.busy,icon={Text(when(page){Page.HOME->"◉";Page.HISTORY->"◷";else->"⚙"},fontSize=22.sp)},label={Text(label)})
                        }
                    }
                }
                if(model.busy&&!model.scopePreview) Surface(Modifier.fillMaxSize(),color=Color.Transparent) {
                    Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center){
                    Surface(shape=RoundedCornerShape(24.dp),color=surface,contentColor=scheme.onSurface,modifier=Modifier.widthIn(max=560.dp)){
                    Column(Modifier.padding(24.dp).semantics { liveRegion=LiveRegionMode.Polite },horizontalAlignment=Alignment.CenterHorizontally) {
                        val p=model.progress
                        CircularProgressIndicator();Spacer(Modifier.height(24.dp))
                        Text(when(p?.phase){BatchPhase.CONNECTING->"연결을 준비하고 있습니다";BatchPhase.CLOSING->"결과를 저장하고 연결을 마무리합니다";else->if(model.operationHide)"안전하게 적용을 확인하고 있습니다" else "안전하게 복원을 확인하고 있습니다"},style=MaterialTheme.typography.titleMedium)
                        if(p!=null){Spacer(Modifier.height(20.dp));LinearProgressIndicator(progress={p.percent/100f},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(12.dp));Text(if(p.phase==BatchPhase.CLOSING)"${p.completed} / ${p.total}개 확인 · 마무리 중…" else "${p.completed} / ${p.total}개 확인 · ${p.percent}%",style=MaterialTheme.typography.titleLarge)}
                        Spacer(Modifier.height(12.dp));Text("확인이 끝난 뒤 결과를 알려드립니다.",textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=scheme.onSurfaceVariant)
                        if(p!=null)Text("작업이 끝날 때까지 이 화면을 열어 두세요.",Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodySmall,color=scheme.onSurfaceVariant)
                        if(model.operationHide)Text("일부 앱은 Android가 파란 위치 표시를 다시 켤 수 있어요. 적용 직후에도 풀릴 수 있으니, 필요하면 홈에서 다시 적용해주세요.",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall,color=scheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    }
                    }
                }
                SnackbarHost(snackbars,modifier=Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
        if(showAutoBlocker&&!model.busy) AlertDialog(onDismissRequest={showAutoBlocker=false},title={Text("보안 위험 자동 차단을 꺼주세요")},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){Text("이 기능이 켜져 있으면 휴대폰이 무선 디버깅을 막아서 bluedotOFF가 연결할 수 없어요. 아래 순서대로 끈 뒤 다시 시도해주세요.");GuideCard(GuideSelection(GuideStep.AUTO_BLOCKER));GuideCard(GuideSelection(GuideStep.AUTO_BLOCKER_OFF))};Primary("보안 설정 열기",{showAutoBlocker=false;SettingsEntrypoints.autoBlocker(context)})}},
            confirmButton={TextButton(onClick={showAutoBlocker=false}){Text("확인")}})
        if(model.notificationOffer&&model.gate==Gate.READY&&!model.busy) AlertDialog(onDismissRequest=dismissNotifications,title={Text("재적용 알림")},text={Text("휴대폰 재시작이나 등록 앱 업데이트로 숨김이 해제될 수 있을 때 알려드립니다. 광고나 상시 알림은 없습니다. 알림 없이도 핵심 기능은 사용할 수 있습니다.")},confirmButton={TextButton(onClick=allowNotifications){Text("알림 허용")}},dismissButton={TextButton(onClick=dismissNotifications){Text("나중에")}})
        model.sharedOffer?.let{offer->
            AlertDialog(onDismissRequest=sharedDismiss,title={Text("위치 설정을 함께 쓰는 앱")},text={
                Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("아래 앱은 위치 표시 설정을 공유해요. 하나만 따로 변경할 수 없고, 함께 숨기거나 함께 복원합니다.")
                    if(offer.loading)CircularProgressIndicator()
                    offer.members.forEach{s->Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh){Column(Modifier.fillMaxWidth().padding(12.dp)){Text(s.label,translate=false,style=MaterialTheme.typography.titleSmall);Text(s.identity.packageName,translate=false,style=MaterialTheme.typography.bodySmall)}}}
                    if(offer.error.isNotBlank())Text(UiText.error(offer.error),color=MaterialTheme.colorScheme.error)
                    if(!offer.loading&&offer.error.isBlank())Text("앱의 위치 권한은 유지합니다. 묶음 전체의 원래 표시 상태를 저장하고, 구성원이 바뀌면 추가 변경을 중지합니다.",style=MaterialTheme.typography.bodySmall)
                }
            },confirmButton={if(!offer.loading&&offer.error.isBlank())TextButton(onClick=sharedAccept){Text(if(!offer.hide)"함께 복원" else if(offer.direct)"함께 재적용" else "묶음 전체 선택")}},dismissButton={TextButton(onClick=sharedDismiss){Text("취소")}})
        }
        if(showReapplyNeeded&&model.gate==Gate.READY&&!model.busy&&model.reapplyCount>0) ReapplyNeededDialog(model,{showReapplyNeeded=false},{packages->showReapplyNeeded=false;reapplyNeeded(packages)},{showReapplyNeeded=false;openBackend()})
        if(showRestoreMenu) AlertDialog(onDismissRequest={showRestoreMenu=false},title={Text("앱 복원")},
            text={Text("숨긴 앱을 원래 표시 상태로 되돌려요. 위치 권한과 사용 기록은 그대로예요. 앱을 지우기 전에는 모두 복원해 주세요.")},
            confirmButton={TextButton(onClick={showRestoreMenu=false;showRestoreAll=true},enabled=model.canOperate){Text("모두 복원")}},
            dismissButton={TextButton(onClick={showRestoreMenu=false;if(!model.editing)editHome()}){Text("골라서 복원")}})
        if(showRestoreAll) RestoreAllDialog(model.entries.count{it.enabled},{showRestoreAll=false},{showRestoreAll=false;restoreAll()})
    }
}

@Composable private fun Wizard(m:UiModel,open:()->Unit,install:()->Unit,authorize:()->Unit,refresh:()->Unit,begin:()->Unit,help:()->Unit) {
    val step=when(m.gate){Gate.WELCOME,Gate.UNSUPPORTED->1;Gate.MISSING,Gate.NOT_RUNNING,Gate.AUTHORIZATION,Gate.WRONG_MODE->6;Gate.CHECKING->11;else->8}
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
    Column(Modifier.widthIn(max=BlueDotAppearance.SETUP_WIDTH.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top=24.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(BlueDotAppearance.SECTION_GAP.dp)) {
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(BlueDotAppearance.setupProgress(LocalContext.current,step),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(progress={step/11f},modifier=Modifier.fillMaxWidth())
        }
        Text(when(m.gate){Gate.WELCOME->"믿는 앱만,\nBlue Dot 없이.";Gate.UNSUPPORTED->"이 기기는 지원하지 않습니다";Gate.MISSING->"기기 연결이 필요합니다";Gate.NOT_RUNNING->"무선 디버깅으로 연결해 주세요";Gate.AUTHORIZATION->"기기 페어링을 완료해 주세요";Gate.WRONG_MODE->"일반 ADB 모드가 필요합니다";Gate.ABI_FAILED->"안전한 적용을 확인할 수 없습니다";Gate.CHECKING->if(m.checkingRegistered)"등록한 앱의 상태를 확인하고 있어요" else "기기를 확인하고 있습니다";else->"기기를 확인하고 있습니다"},style=MaterialTheme.typography.headlineMedium)
        Text(when(m.gate){Gate.WELCOME->"신뢰하는 앱의 위치 기능과 사용 기록은 유지하고, 위치 표시만 숨깁니다.";Gate.UNSUPPORTED->"Samsung One UI 9 이상 · Android 17 이상 기기에서 호환성을 확인합니다. 지원되지 않는 환경은 변경하지 않습니다.";Gate.MISSING->"연결 설정에서 개발자 옵션과 무선 디버깅을 확인하고, 이 기기를 한 번 페어링해 주세요.";Gate.NOT_RUNNING->"연결 설정에서 무선 디버깅을 켜고 기기를 연결해 주세요. 돌아오면 자동으로 확인합니다.";Gate.AUTHORIZATION->"설정을 읽고 선택한 앱에만 적용하기 위해 권한이 필요합니다. 거절했다면 다시 요청할 수 있습니다.";Gate.WRONG_MODE->"이 기기의 일반 ADB shell 연결만 지원합니다.";Gate.ABI_FAILED->m.notice.ifBlank{"현재 기기/앱에서는 안전한 적용을 확인할 수 없습니다. 변경된 내용은 없습니다."};Gate.CHECKING->if(m.checkingRegistered)"앱 목록은 그대로 사용하고, 등록한 앱의 현재 상태만 확인해요. 설정은 변경하지 않아요." else "읽기 전용 검사입니다. 대상 앱의 설정은 변경하지 않습니다.";else->"읽기 전용 검사입니다. 대상 앱의 설정은 변경하지 않습니다."},style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(m.gate==Gate.CHECKING){
            val scan=m.scanProgress
            if(scan==null)CircularProgressIndicator()
            else{
                LinearProgressIndicator(progress={if(scan.finishing)1f else scan.percent/100f},modifier=Modifier.fillMaxWidth())
                Text(if(scan.finishing)"${scan.total}개 앱 확인 완료 · 마무리 중…" else "${scan.completed} / ${scan.total}개 앱 확인 · ${scan.percent}%",style=MaterialTheme.typography.titleMedium)
                if(scan.finishing)Text("앱 확인을 마쳤어요. 기록과 연결 종료를 확인하고 있어요.",style=MaterialTheme.typography.bodySmall)
            }
        }
        if(m.gate==Gate.WELCOME)Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.tertiaryContainer,contentColor=MaterialTheme.colorScheme.onTertiaryContainer,border=BorderStroke(1.dp,MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha=0.18f))){
            Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("미리 알아두세요",style=MaterialTheme.typography.titleMedium)
                listOf("일부 앱은 적용 후 몇 분 만에 숨김이 풀릴 수 있어요.","앱이 스스로 구성을 바꾸거나 업데이트되면 숨김 설정이 기본값으로 돌아가요.","풀린 앱은 홈에 ‘재적용 필요’로 표시돼요. 눌러서 다시 적용하면 돼요.","갤럭시의 ‘보안 위험 자동 차단’이 켜져 있으면 연결할 수 없어요. 연결 안내에서 끄는 방법을 보여드려요.").forEach{line->
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("•",style=MaterialTheme.typography.bodyMedium,translate=false);Text(line,style=MaterialTheme.typography.bodyMedium.copy(lineBreak=androidx.compose.ui.text.style.LineBreak.Paragraph.copy(wordBreak=androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase)))}
                }
            }
        }
        when(m.gate){
            Gate.WELCOME->Primary("시작하기",begin)
            Gate.MISSING->Primary("연결 설정 열기",install)
            Gate.NOT_RUNNING->Primary("연결 설정 열기",open)
            Gate.AUTHORIZATION->Primary("다시 요청",authorize)
            Gate.WRONG_MODE->Primary("연결 설정 열기",open)
            Gate.ABI_FAILED->Primary("다시 확인",refresh)
            else->Unit
        }
        TextButton(onClick=help){Text("설정 도움말")}
    }
    }
}

@Composable private fun HomeScreen(m:UiModel,wide:Boolean,add:()->Unit,restoreMenu:()->Unit,select:(String)->Unit,open:()->Unit,history:()->Unit,hide:(String)->Unit,edit:()->Unit,toggle:(String)->Unit,restoreSelected:()->Unit,wirelessExit:()->Unit,showNeeded:()->Unit) {
    val context=LocalContext.current
    @Composable fun Hero() {
        Column(Modifier.padding(top=10.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            if(m.usModel)Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.tertiaryContainer,contentColor=MaterialTheme.colorScheme.onTertiaryContainer,border=BorderStroke(1.dp,MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha=0.18f))){
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("미국 판매 모델 안내",style=MaterialTheme.typography.titleMedium)
                    Text("미국 판매 모델은 위치 표시를 띄우는 방식이 하나 더 있어서, 적용해도 파란 점이 사라지지 않을 수 있어요. 위치 권한과 사용 기록은 그대로이고, 언제든 원래 상태로 복원할 수 있어요.",style=MaterialTheme.typography.bodyMedium)
                }
            }
            if(!m.connected&&!m.automaticReady)Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.tertiaryContainer,contentColor=MaterialTheme.colorScheme.onTertiaryContainer,border=BorderStroke(1.dp,MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha=0.18f))){
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("설정을 변경하려면 연결이 필요해요",style=MaterialTheme.typography.titleMedium)
                    Text(m.compatibility,style=MaterialTheme.typography.bodyMedium)
                    TextButton(onClick=open){Text("연결 준비")}
                }
            }
            Text(when{m.allLastVerifiedApplied->"bluedotOFF\n적용완료";m.reapplyCount>0->"일부 앱의\n재적용이 필요해요";m.visibleEntries.any{it.enabled}->"일부 앱의\n상태 확인이 필요해요";else->"bluedotOFF\n미적용"},style=MaterialTheme.typography.displaySmall)
            if(m.allLastVerifiedApplied)Text("${m.lastVerifiedAppliedCount}개 앱 bluedotOFF 적용중",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            else if(m.visibleEntries.any{it.enabled}){
                Text("${m.lastVerifiedAppliedCount}개 앱 bluedotOFF 적용중",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(m.reapplyCount>0)Text("${m.reapplyCount}개 앱 · 재적용 필요",Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).clickable(role=androidx.compose.ui.semantics.Role.Button,onClick=showNeeded).padding(vertical=12.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                if(m.otherCheckCount>0)Text("${m.otherCheckCount}개 앱 · 상태 확인 필요",style=MaterialTheme.typography.bodyMedium)
                Text("완료된 앱은 유지하고, 필요한 앱만 확인해주세요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }else Text("신뢰하는 앱을 골라\n위치 표시만 가볍게 숨기세요.",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            m.recentHistoryApp?.let{a->
                Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)){
                        Text("최근 위치 사용 앱",style=MaterialTheme.typography.labelLarge)
                        Text("${a.label} · ${UiText.text(LocalContext.current,accessAge(a.access.latest))}",Modifier.padding(top=6.dp),style=MaterialTheme.typography.bodyMedium)
                        Row{TextButton(onClick=history){Text("기록 보기")};if(m.canActFromHistory(a)&&m.entries.none{it.enabled&&it.identity.packageName==a.packageName&&it.state==TargetState.APPLIED&&it.settlingVerified})TextButton(onClick={hide(a.packageName)},enabled=m.canOperate&&!m.busy){Text("숨기기")}}
                    }
                }
            }
            Primary("앱 추가",add)
            if(m.successToken>0&&!m.busy&&m.connected&&!m.autoManagement){Text("작업을 마쳤다면 무선 디버깅을 꺼두세요.",style=MaterialTheme.typography.bodySmall);TextButton(onClick=wirelessExit){Text("무선 디버깅 끄기")}}
            if(m.visibleEntries.any{it.enabled}&&!m.editing)OutlinedButton(onClick=restoreMenu,enabled=!m.busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("앱 복원")}
        }
    }
    @Composable fun Registered() {
        Column(Modifier.fillMaxWidth().padding(top=20.dp)) {
            if(m.connected&&m.visibleEntries.isEmpty()){Text("준비 완료",color=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(16.dp))}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("등록한 앱 · ${m.visibleEntries.count{it.enabled}}개",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));if(m.visibleEntries.isNotEmpty())TextButton(onClick=edit){Text(if(m.editing)"완료" else "편집")}}
            if(m.editing){Primary("선택한 ${m.homePicked.size}개 숨김 해제",restoreSelected,m.homePicked.isNotEmpty()&&m.canOperate&&!m.busy)}
            Spacer(Modifier.height(12.dp))
            if(m.visibleEntries.isEmpty())Text("아직 등록한 앱이 없습니다.",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            else Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) { LazyColumn(Modifier.heightIn(max=460.dp).padding(horizontal=12.dp)) { items(m.registeredDisplayEntries,key={it.identity.packageName}) { e -> if(m.editing)Row(Modifier.fillMaxWidth().clickable{toggle(e.identity.packageName)},verticalAlignment=Alignment.CenterVertically){Checkbox(e.identity.packageName in m.homePicked,{toggle(e.identity.packageName)});Column(Modifier.weight(1f).padding(vertical=12.dp)){Text(e.label,translate=false);Text(m.status(e),style=MaterialTheme.typography.bodySmall)}}else AppRow(e.identity.packageName,e.label,m.status(e),{select(e.identity.packageName)});HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.55f)) } } }
            Text("총 ${m.visibleEntries.count{it.enabled}}개 앱 등록",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(wide) Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(44.dp)) { Box(Modifier.weight(1f)){Hero()};Box(Modifier.weight(1.15f)){Registered()} }
    else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom=36.dp)) { Hero();Registered() }
}

private fun pickerReason(a:DiscoveredApp,scope:ApplicationScope=ApplicationScope.GROUPS):String=when {
    scope!=ApplicationScope.NORMAL&&a.uidPeers.size>1->"다른 앱과 묶여 있어요 · 함께 선택돼요"
    scope==ApplicationScope.ALL&&a.block.isNotEmpty()->a.block
    a.check==CandidateCheck.NEEDS_CHECK->"앱의 권한과 설치 정보를 다시 확인해주세요."
    a.uidClass==UidClass.MULTI_PACKAGE_SHARED_UID->"다른 앱과 묶여 있어요 · 함께 선택돼요"
    a.uidClass==UidClass.SINGLETON_SHARED_USER->"이 기기에서 공유 권한 구조의 안전성을 확인하지 못했습니다."
    a.uidClass in setOf(UidClass.CORE_UID,UidClass.PERSISTENT_CORE,UidClass.CRITICAL_PACKAGE)->"핵심 시스템 구성 요소여서 변경할 수 없습니다."
    a.block.contains("UID")->"앱의 권한과 설치 정보를 안전하게 확인하지 못했습니다."
    a.block.isNotEmpty()->a.block
    else->"현재 설정에서 안전한 변경을 확인할 수 없습니다."
}
@Composable private fun PickerScreen(m:UiModel,toggle:(String)->Unit,add:()->Unit,queryChanged:(String)->Unit,load:()->Unit,open:()->Unit,filterChanged:(AppFilter)->Unit,viewChanged:(PickerView)->Unit,toggleAll:()->Unit,sharedInspect:(String)->Unit) {
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text("앱 추가",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            Text("${m.picked.size}개 선택됨",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        }
        if(m.picked.isNotEmpty())LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(m.displayApps.filter{it.packageName in m.picked},key={it.packageName}){a->SuggestionChip(onClick={toggle(a.packageName)},label={Text(a.label)})}}
        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(AppFilter.entries){f->FilterChip(m.filter==f,{filterChanged(f)},label={Text(f.label)})}}
        Text(m.applicationScope.title,style=MaterialTheme.typography.bodySmall)
        if(m.applicationScope==ApplicationScope.ALL)Text("위치 권한이 없는 앱은 표시만 해요. 공유 앱은 구성원을 확인한 뒤 묶음으로 선택하세요.",style=MaterialTheme.typography.bodySmall)
        if(!m.canOperate&&m.discovered.isEmpty()){Primary("연결 설정 열기",open);return}
        if(m.loadingApps)LinearProgressIndicator(Modifier.fillMaxWidth())
        var reason by remember{mutableStateOf<String?>(null)}
        val rows=m.pickerApps
        val unmanagedNotSensitive=rows.any{it.packageName !in m.managedPackages&&PickerPolicy.alreadyNotSensitive(it)}
        // Only rows the user can act on: selectable apps and shared groups first, then already applied ones.
        // Apps that need checking or cannot be changed are left out; nothing can be done with them here.
        val available=rows.filter{m.canAct(it)||m.canPickGroup(it)}
            .sortedBy{if(m.canSelect(it)||m.canPickGroup(it))0 else 1}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            // Keep normal text-field padding while allowing larger accessibility text to grow.
            val searchHeight=(32f+20f*LocalDensity.current.fontScale).coerceAtLeast(52f).dp
            OutlinedTextField(m.query,queryChanged,modifier=Modifier.weight(1f).height(searchHeight),singleLine=true,placeholder={Text("앱 검색",style=MaterialTheme.typography.bodyMedium)},textStyle=MaterialTheme.typography.bodyMedium,shape=RoundedCornerShape(14.dp))
            Row(verticalAlignment=Alignment.CenterVertically){
                Text("전체 선택",Modifier.clickable(enabled=!m.busy&&!m.loadingApps&&available.any{m.canSelect(it)||m.canPickGroup(it)},onClick=toggleAll),style=MaterialTheme.typography.bodySmall)
                TriStateCheckbox(state=when(PickerSelection.state(available.filter{m.canSelect(it)},m.picked)){SelectionState.NONE->androidx.compose.ui.state.ToggleableState.Off;SelectionState.SOME->androidx.compose.ui.state.ToggleableState.Indeterminate;SelectionState.ALL->androidx.compose.ui.state.ToggleableState.On},onClick=toggleAll,enabled=!m.busy&&!m.loadingApps&&available.any{m.canSelect(it)||m.canPickGroup(it)})
            }
        }
        if(m.loadingApps)Text("앱 확인 중…",style=MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f)) {
            fun androidx.compose.foundation.lazy.LazyListScope.appRows(apps:List<DiscoveredApp>){items(apps,key={it.packageName}){a->
                val safe=m.canAct(a);val group=m.canPickGroup(a);val selectable=m.canSelect(a)||group
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=!m.busy&&!m.loadingApps&&(selectable||!safe)){if(group)sharedInspect(a.packageName)else if(selectable)toggle(a.packageName)else if(m.canInspectGroup(a))sharedInspect(a.packageName)else reason=pickerReason(a,m.applicationScope)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    AppIcon(a.packageName,a.label,32)
                    if(safe){
                        Text(a.label,translate=false,modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(when{m.isApplied(a.packageName)->"적용중";a.packageName in m.managedPackages->"등록한 앱";PickerPolicy.alreadyNotSensitive(a)->"이미 적용된 앱";else->"적용 가능"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }else{
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(a.label,translate=false,style=MaterialTheme.typography.titleSmall);Text(pickerReason(a,m.applicationScope),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                    Checkbox(checked=a.packageName in m.picked,onCheckedChange={if(group)sharedInspect(a.packageName)else toggle(a.packageName)},enabled=(selectable||a.packageName in m.picked)&&!m.busy&&!m.loadingApps)
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))
            }}
            appRows(available)
        }
        reason?.let{message->AlertDialog(onDismissRequest={reason=null},title={Text("앱 확인 결과")},text={Text(message)},confirmButton={TextButton(onClick={reason=null}){Text("확인")}})}
        if(available.isEmpty()&&!m.loadingApps)Text("조건에 맞는 앱이 없습니다.",style=MaterialTheme.typography.bodySmall)
        Column(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(if(unmanagedNotSensitive)"이미 적용된 앱은 위치 표시가 꺼져 있어요. 새로 등록하면 이 꺼짐 상태가 복원 기준이 돼요." else "선택한 앱을 등록하고 위치 표시 숨김을 적용합니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Primary("${m.picked.size}개 추가",add,enabled=m.picked.isNotEmpty()&&!m.busy)}
    }
}

fun accessAge(time:Long?,now:Long=System.currentTimeMillis()):String {
    if(time==null)return "위치 기록 없음"
    val seconds=((now-time).coerceAtLeast(0)/1000)
    return when{seconds<60->"방금 전";seconds<3600->"${seconds/60}분 전";seconds<86400->"${seconds/3600}시간 전";else->"${seconds/86400}일 전"}
}
@Composable private fun HistoryScreen(m:UiModel,hide:(String)->Unit,open:()->Unit,viewChanged:(HistoryView)->Unit) {
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("최근 위치 사용",style=MaterialTheme.typography.headlineMedium)
        Text(if(m.historyView==HistoryView.APPS)"사용자 앱 중심으로 정리된 기록입니다. 등록한 숨김 앱도 유지하며, 내부 서비스는 전체 위치 사용에서 확인할 수 있습니다." else "시스템·내부 서비스를 포함한 실제 위치 접근 기록입니다. Blue Dot 표시 여부와 동일하지 않을 수 있습니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){HistoryView.entries.forEach{view->FilterChip(selected=m.historyView==view,onClick={viewChanged(view)},label={Text(view.label)})}}
        if(!m.connected){Text("현재 기록을 확인하려면 무선 디버깅으로 연결해 주세요.");TextButton(onClick=open){Text("연결 설정 열기")}}
        if(m.loadingApps||m.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(m.historyApps.isEmpty()&&!m.loadingApps)Text(if(m.historyAvailable)"최근 위치 사용 기록이 없습니다." else "사용 기록을 확인하지 못했습니다.",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(Modifier.weight(1f)){items(m.historyApps,key={it.packageName}){a->
            val status=m.historyStatus(a)
            val applied=m.entries.any{it.enabled&&it.identity.packageName==a.packageName&&it.state==TargetState.APPLIED&&it.settlingVerified}
            val blue by animateFloatAsState(if(applied)0f else 1f,label="history dot")
            val dotColor=if(a.indicatorHint==FineIndicatorHint.SENSITIVE&&HistoryPolicy.relevant(a,emptySet()))DotBlue else MaterialTheme.colorScheme.onSurfaceVariant
            val needs=m.entries.any{it.identity.packageName==a.packageName&&it.enabled&&it.state in listOf(TargetState.NEEDS_REAPPLY_UPDATE,TargetState.NEEDS_REAPPLY_BOOT,TargetState.NEEDS_REAPPLY_POLICY)}
            Row(Modifier.fillMaxWidth().padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Box(Modifier.size(18.dp),contentAlignment=Alignment.Center){Box(Modifier.size(8.dp).alpha(blue).background(dotColor,CircleShape));if(applied)Text("✓",color=MaterialTheme.colorScheme.primary)}
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text(a.label,translate=false,style=MaterialTheme.typography.titleSmall)
                    Text("${UiText.text(LocalContext.current,accessAge(a.access.latest))} 위치를 사용했습니다 · ${UiText.text(LocalContext.current,if(a.access.fine!=null&&a.access.fine==a.access.latest)"정확한 위치" else "대략적인 위치")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(applied&&m.connected&&a.packageName in m.freshPackages)"bluedotOFF 적용중 ✓" else status,style=MaterialTheme.typography.bodySmall,color=if(applied)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(m.canActFromHistory(a)&&!applied)TextButton(onClick={hide(a.packageName)},enabled=m.canOperate&&!m.busy){Text(if(needs)"재적용" else "숨기기")}
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))
        }}
    }
}
@Composable private fun DetailScreen(m:UiModel,hide:(String)->Unit,restore:(String)->Unit,open:()->Unit) {
    val e=m.entries.lastOrNull{it.identity.packageName==m.selected}
    val s=m.candidates.firstOrNull{it.identity.packageName==m.selected}
    val pkg=m.selected ?: ""
    val grouped=e?.groupIdentities?.isNotEmpty()==true
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top=20.dp,bottom=60.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        AppIcon(pkg,s?.label?:e?.label?:"앱",72)
        Text(s?.label?:e?.label?:"앱 정보를 확인해 주세요",translate=s==null&&e==null,style=MaterialTheme.typography.headlineLarge)
        Text(e?.let{m.status(it)}?:"bluedotOFF 미적용",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
        if(e?.error?.isNotBlank()==true)Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.errorContainer){Text(UiText.error(e.error),Modifier.padding(16.dp),color=MaterialTheme.colorScheme.onErrorContainer)}
        HorizontalDivider()
        Text("위치 권한",style=MaterialTheme.typography.labelLarge)
        Text(if(s==null)"기기 연결 시 현재 상태를 확인할 수 있습니다." else if(grouped&&!s.fineDeclared)"이 앱은 위치 권한을 직접 요청하지 않으며 묶음의 표시 설정을 공유합니다." else if(!s.fineGranted)"정확한 위치 허용 안 됨" else if(s.backgroundGranted)"정확한 위치 · 항상 허용" else "정확한 위치 · 사용 중 허용",style=MaterialTheme.typography.bodyLarge)
        if(e!=null)Text("마지막 확인  ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(e.lastVerifiedTime))}",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        if(e?.originalBit==0)Text("등록할 때부터 표시가 꺼져 있던 앱입니다. 복원해도 등록 당시의 표시 꺼짐 상태를 유지합니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(grouped)Text("공유 앱 묶음 · 함께 숨기고 함께 복원합니다.",style=MaterialTheme.typography.bodyMedium)
        val blocked=s?.takeIf{!it.safe()&&!grouped}?.blockReason()
        if(blocked!=null)Text(blocked,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!m.canOperate)Primary("연결 설정 열기",open)
        else if(e?.state in listOf(TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW))Text("안전성 검토가 필요해 추가 변경을 차단했습니다. 진단과 복구 안내를 확인해 주세요.",color=MaterialTheme.colorScheme.error)
        else {
            if(m.loadingDetail)LinearProgressIndicator(Modifier.fillMaxWidth())
            Primary(if(e?.state==TargetState.INACTIVE_INSTALL)"새로 등록 · bluedotOFF 적용" else if(e?.enabled==true&&e.state!=TargetState.APPLIED)"bluedotOFF 재적용" else "bluedotOFF 적용",{hide(pkg)},enabled=s!=null&&blocked==null&&pkg.isNotBlank()&&!m.loadingDetail)
            if(e?.enabled==true)OutlinedButton(onClick={restore(pkg)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=s!=null&&blocked==null&&!m.loadingDetail){Text(if(grouped)"묶음 원래 상태로 복원" else "숨김 해제 · 원래 상태로 복원")}
        }
        Text("위치 권한이나 앱의 사용 기록은 바꾸지 않습니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        var advanced by remember { mutableStateOf(false) }
        TextButton(onClick={advanced=!advanced}){Text(if(advanced)"상세 정보 접기" else "고급 정보")}
        if(advanced)Text("Package: $pkg\nUID: ${s?.identity?.uid?:e?.identity?.uid?:"미확인"}\n${m.compatibility}\n원본 표시 bit: ${e?.originalBit?:"등록 전"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ReapplyScreen(m:UiModel,apply:()->Unit,open:()->Unit,select:(String)->Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top=22.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text("숨김 다시 적용",style=MaterialTheme.typography.headlineLarge)
        Text("등록 앱을 하나씩 다시 확인합니다. 변경할 수 없는 앱은 건너뛰고 결과는 상단에서 안내합니다.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        m.registeredDisplayEntries.filter{it.enabled}.forEach { e->AppRow(e.identity.packageName,e.label,m.status(e),{select(e.identity.packageName)}) }
        Text(if(m.connected)"기기 연결 확인됨" else "기기 연결 필요",style=MaterialTheme.typography.titleSmall)
        if(m.canOperate)Primary("모두 재적용",apply,enabled=m.entries.any{it.enabled})else Primary("연결 설정 열기",open)
        Text("재부팅과 업데이트 때 자동으로 시스템 설정을 바꾸지 않습니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Third-party notices bundled in assets/licenses (Apache-2.0 asks distributions to include them). */
@Composable private fun OpenSourceLicenses(close:()->Unit) {
    val context=LocalContext.current
    val items=remember{listOf(
        Triple("AOSP ADB (pairing)","Apache License 2.0","licenses/AOSP-ADB-NOTICE.txt"),
        Triple("BoringSSL","Apache License 2.0","licenses/BoringSSL-LICENSE.txt"),
        Triple("fiat-crypto","Apache License 2.0","licenses/Fiat-Crypto-LICENSE.txt")
    ).map{(name,license,path)->Triple(name,license,runCatching{context.assets.open(path).bufferedReader().use{it.readText()}}.getOrDefault(""))}}
    var open by remember{mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=close,title={Text("오픈소스 라이선스")},confirmButton={TextButton(onClick=close){Text("닫기")}},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("bluedotOFF는 아래 오픈소스를 사용합니다. AndroidX와 Kotlin 표준 라이브러리도 Apache License 2.0으로 배포됩니다.",style=MaterialTheme.typography.bodySmall)
            items.forEach{(name,license,body)->
                TextButton(onClick={open=if(open==name)null else name}){Text("$name · $license",translate=false)}
                if(open==name)Text(body,translate=false,style=MaterialTheme.typography.bodySmall.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace))
            }
        }
    })
}

@Composable private fun SettingsScreen(m:UiModel,notificationChanged:(Boolean)->Unit,open:()->Unit,refresh:()->Unit,help:()->Unit,wirelessExit:()->Unit,managementChanged:(Boolean)->Unit,removeManagementPermission:()->Unit,scopeChange:()->Unit) {
    val context=LocalContext.current
    var autoConsent by remember{mutableStateOf(false)}
    var advancedSettings by rememberSaveable{mutableStateOf(false)}
    var showLicenses by rememberSaveable{mutableStateOf(false)}
    if(showLicenses)OpenSourceLicenses{showLicenses=false}
    if(autoConsent)AlertDialog(onDismissRequest={autoConsent=false},title={Text("무선 디버깅 자동 관리")},text={Text("bluedotOFF를 사용할 때만 무선 디버깅을 켜고, 작업이 끝나면 다시 끕니다. Android 시스템 설정을 변경할 수 있는 권한을 최초 한 번 사용하며, 이 앱에서는 무선 디버깅 ON/OFF에만 사용합니다. 다른 무선 디버깅 연결도 종료될 수 있습니다.")},confirmButton={TextButton(onClick={autoConsent=false;managementChanged(true)}){Text("자동 관리 사용")}},dismissButton={TextButton(onClick={autoConsent=false}){Text("취소")}})
    @Composable fun LinkRow(label:String,detail:String="",onClick:()->Unit){
        Row(Modifier.fillMaxWidth().heightIn(min=52.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
            if(detail.isNotEmpty())Text(detail,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,translate=false)
            else Text("›",Modifier.padding(start=12.dp),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,translate=false)
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top=16.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        Text("설정",style=MaterialTheme.typography.headlineLarge)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
            Text("현재 적용 범위",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            Text(m.applicationScope.title,style=MaterialTheme.typography.bodyMedium,maxLines=1,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.End)
        }
        Text(m.applicationScope.description,style=MaterialTheme.typography.bodySmall)
        if(m.scopePending)Text("범위 변경이 완료되지 않았어요. 제외되는 앱을 복원해야 범위를 낮출 수 있어요.",color=MaterialTheme.colorScheme.error)
        OutlinedButton(onClick=scopeChange,enabled=!m.busy){Text("적용 범위 변경")}
        HorizontalDivider()
        Row(Modifier.toggleable(value=m.autoManagement,enabled=!m.busy||m.autoManagement,role=androidx.compose.ui.semantics.Role.Switch,onValueChange={if(it)autoConsent=true else managementChanged(false)}),verticalAlignment=Alignment.CenterVertically){Text("무선 디버깅 자동 관리",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Switch(checked=m.autoManagement,onCheckedChange=null,enabled=!m.busy||m.autoManagement)}
        Text("bluedotOFF를 사용할 때만 무선 디버깅을 켜고, 작업이 끝나면 자동으로 다시 끕니다.",style=MaterialTheme.typography.bodySmall)
        if(!m.autoManagement){
            Text("작업이 끝나면 무선 디버깅을 꺼두세요. 적용 상태와 복원 기록은 유지돼요.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=wirelessExit,enabled=!m.busy){Text("무선 디버깅 끄기")}
        }
        if(!m.canOperate)OutlinedButton(onClick=open){Text("연결 설정 열기")}
        HorizontalDivider()
        val notifyOn=m.notifications&&m.reapplyNotify
        Row(Modifier.toggleable(value=notifyOn,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=notificationChanged),verticalAlignment=Alignment.CenterVertically){Text("재적용 알림",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Switch(checked=notifyOn,onCheckedChange=null)}
        Text("휴대폰 재시작이나 앱 업데이트로 숨김이 풀리면 알려드려요.",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
            Text("정보 및 지원",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
            LinkRow("도움말 · 문제 해결",onClick=help)
            LinkRow(if(advancedSettings)"고급 설정 닫기" else "고급 설정"){advancedSettings=!advancedSettings}
            if(advancedSettings)Column(Modifier.padding(start=12.dp,bottom=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                Text("기기 호환성",style=MaterialTheme.typography.titleSmall);Text(m.compatibility,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick=refresh){Text("읽기 전용으로 다시 확인")}
                TextButton(onClick=open){Text("연결 설정 열기")}
                if(m.autoManagement)TextButton(onClick=wirelessExit,enabled=!m.busy){Text("무선 디버깅 끄기")}
                if(m.autoPermission){
                    Text("스위치를 끄면 자동 관리가 중단돼요. 시스템 설정 변경 권한까지 회수하려면 아래 버튼을 사용하세요.",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick=removeManagementPermission,enabled=!m.busy){Text("자동 관리 권한 제거")}
                }
                if(BuildConfig.DEBUG)TextButton(onClick={context.startActivity(android.content.Intent(context,app.bluedotoff.adb.LocalAdbSetupActivity::class.java).putExtra("diagnostics",true))}){Text("읽기 전용 연결 진단")}
            }
            LinkRow("오픈소스 라이선스"){showLicenses=true}
            LinkRow("버전",BuildConfig.VERSION_NAME){}
        }
    }
}

@Composable private fun HelpScreen(open:()->Unit,install:()->Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top=16.dp,bottom=60.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text("필요한 순간에만,\n무선 디버깅.",style=MaterialTheme.typography.headlineLarge)
        Text("시작하기 → 알림 선택 → 개발자 옵션 → Wi-Fi → 근처 기기 권한 → 무선 디버깅 → 코드 입력 → 기기와 앱 확인 → 자동 관리 선택 → 적용 범위 → 최종 기기 확인 → 홈",style=MaterialTheme.typography.bodyMedium)
        GuideStep.entries.filter{it in OnboardingGuide.exampleSteps}.forEach{GuideCard(GuideSelection(it))}
        GuideCard(GuideSelection(GuideStep.CODE_INPUT,true))
        Primary("연결 설정 열기",open);OutlinedButton(onClick=install,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("공식 연결 설정 열기")}
        HorizontalDivider()
        Text("적용이 안 될 때",style=MaterialTheme.typography.titleMedium)
        Text("기기 연결과 정확한 위치 권한을 확인하세요. 다른 앱과 위치 설정을 공유하는 앱과 핵심 시스템 구성 요소는 개별 적용할 수 없어요. 기본 설치 앱도 조건을 통과하면 선택할 수 있어요. 기기마다 호환성을 검사하며, 일부 지역이나 소프트웨어 버전은 지원되지 않을 수 있어요. 업데이트 후에는 다시 확인해요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("위치 설정을 공유하는 앱은 구성원 목록을 확인하고 묶음 전체를 선택할 수 있어요. 핵심 시스템 앱은 전체 적용에서 선택할 수 있어요. 묶음 전체의 설치 정보와 위치 권한 상태를 확인하지 못하면 변경하지 않아요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("보안 위험 자동 차단이 켜져 있으면 Android가 무선 디버깅을 막아 연결할 수 없어요. 작업할 때는 설정 → 보안 및 개인정보 보호에서 꺼주세요. ‘자동으로 켜기’를 켜두면 30분 뒤 다시 켜져요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("재적용 알림은 bluedotOFF가 완전히 종료돼 있는 동안 업데이트된 앱은 바로 알려드리지 못해요. 다음에 앱을 열면 확인해서 보여드려요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("복원 확인이 필요할 때",style=MaterialTheme.typography.titleMedium)
        Text("실패 기록을 지우려고 앱 데이터를 초기화하거나 앱을 삭제하지 마세요. 기기를 연결해 현재 상태를 읽은 뒤, GitHub의 bluedotOFF 저장소 Issues에 문의해 주세요. 상태가 확실하지 않은 앱은 bluedotOFF가 스스로 다시 바꾸지 않아요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("위치 사용 기록",style=MaterialTheme.typography.titleMedium)
        Text("앱 기록은 최근 위치 사용을 사용자 앱 중심으로 정리한 목록입니다. 전체 위치 사용에서는 시스템·내부 서비스도 볼 수 있습니다. 삼성 설정의 권한 사용 현황과는 데이터 출처가 다릅니다. 숨김이 적용돼도 위치 사용 기록은 남으며, 기록 자체가 파란 표시를 띄웠다는 뜻은 아닙니다.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("루팅이나 부트로더 해제는 필요 없습니다. 휴대폰의 무선 디버깅 기능만 사용하며, 앱의 위치 권한과 보안 설정은 바꾸지 않습니다. 네트워크 권한은 휴대폰 안의 무선 디버깅 연결에만 사용합니다. 외부 서버 통신·광고·분석·추적 기능은 없습니다.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable private fun Primary(label:String,action:()->Unit,enabled:Boolean=true) { Button(onClick=action,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=BlueDotAppearance.BUTTON_HEIGHT.dp),shape=RoundedCornerShape(BlueDotAppearance.BUTTON_RADIUS.dp)){Text(label,style=MaterialTheme.typography.titleSmall)} }
@Composable private fun AppRow(pkg:String,label:String,state:String,select:()->Unit) { Row(Modifier.fillMaxWidth().heightIn(min=84.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick=select).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)){AppIcon(pkg,label,44);Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(label,translate=false,style=MaterialTheme.typography.titleSmall);Text(state,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Text("›",fontSize=26.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)} }
@Composable private fun AppIcon(pkg:String,label:String,size:Int) {
    val context=LocalContext.current
    var bitmap by remember(pkg){mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)}
    LaunchedEffect(pkg){bitmap=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){runCatching{context.packageManager.getApplicationIcon(pkg).toBitmap(128,128).asImageBitmap()}.getOrNull()}}
    val icon=bitmap
    if(icon!=null)Image(icon,contentDescription=UiText.text(context,"$label 아이콘"),modifier=Modifier.size(size.dp).clip(RoundedCornerShape((size/4).dp)))
    else Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.secondaryContainer,RoundedCornerShape((size/4).dp)),contentAlignment=Alignment.Center){Text(label.take(1),translate=false,style=MaterialTheme.typography.titleLarge,color=MaterialTheme.colorScheme.onSecondaryContainer)}
}

private fun demo(applied:Boolean=false,gate:Gate=Gate.READY,page:Page=Page.HOME):UiModel {
    val id=Identity("demo.weather",0,10792,listOf("demo"),1,1,1)
    val e=Entry(id,"날씨",256,true,if(applied)TargetState.APPLIED else TargetState.NEEDS_REAPPLY_BOOT,1,"demo","demo",true,settlingVerified=applied)
    return UiModel(gate=gate,page=page,entries=listOf(e),connected=true,freshPackages=setOf(id.packageName),compatibility="읽기 검사 완료",notifications=true)
}
@Composable private fun Demo(m:UiModel){BlueDotApp(m,{}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})}
@Preview(name="01 Welcome",showBackground=true) @Composable fun WelcomePreview(){Demo(demo(gate=Gate.WELCOME))}
@Preview(name="02 Missing",showBackground=true) @Composable fun MissingPreview(){Demo(demo(gate=Gate.MISSING))}
@Preview(name="03 Not running",showBackground=true) @Composable fun NotRunningPreview(){Demo(demo(gate=Gate.NOT_RUNNING))}
@Preview(name="04 Compatibility",showBackground=true) @Composable fun CompatibilityPreview(){Demo(demo(gate=Gate.CHECKING))}
@Preview(name="05 Blue home",showBackground=true) @Composable fun BlueHomePreview(){Demo(demo())}
@Preview(name="06 Applying",showBackground=true) @Composable fun ApplyingPreview(){Demo(demo().copy(busy=true))}
@Preview(name="07 Applied",showBackground=true) @Composable fun AppliedPreview(){Demo(demo(applied=true))}
@Preview(name="08 Reapply",showBackground=true) @Composable fun ReapplyPreview(){Demo(demo(page=Page.REAPPLY))}
@Preview(name="09 Dark",showBackground=true,uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES) @Composable fun DarkPreview(){Demo(demo(applied=true))}
@Preview(name="10 Fold wide",showBackground=true,widthDp=960,heightDp=750) @Composable fun FoldPreview(){Demo(demo(applied=true))}
@Preview(name="11 Ready",showBackground=true) @Composable fun ReadyPreview(){Demo(demo().copy(entries=emptyList()))}

/** Translate product copy at the display boundary; installed app labels are kept verbatim. */
@Composable private fun Text(text:String,modifier:Modifier=Modifier,color:Color=Color.Unspecified,
    fontSize:androidx.compose.ui.unit.TextUnit=androidx.compose.ui.unit.TextUnit.Unspecified,
    letterSpacing:androidx.compose.ui.unit.TextUnit=androidx.compose.ui.unit.TextUnit.Unspecified,
    overflow:TextOverflow=TextOverflow.Clip,maxLines:Int=Int.MAX_VALUE,
    textAlign:androidx.compose.ui.text.style.TextAlign?=null,
    style:androidx.compose.ui.text.TextStyle=LocalTextStyle.current,translate:Boolean=true) {
    androidx.compose.material3.Text(if(translate)UiText.text(LocalContext.current,text)else text,
        modifier=modifier,color=color,fontSize=fontSize,letterSpacing=letterSpacing,
        overflow=overflow,maxLines=maxLines,textAlign=textAlign,style=style)
}
