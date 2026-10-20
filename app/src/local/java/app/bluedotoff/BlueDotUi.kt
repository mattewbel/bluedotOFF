@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
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
import kotlinx.coroutines.launch

private val DotBlue = BlueDotAppearance.dotBlue
fun TargetState.label(): String = when(this) {
    TargetState.APPLIED -> "적용 중"
    TargetState.NOT_APPLIED -> "적용 안 됨"
    TargetState.PENDING_HIDE,TargetState.PENDING_RESTORE,TargetState.PENDING_SHOW -> "확인 필요"
    TargetState.NEEDS_REAPPLY_BOOT -> "재부팅 · 재적용 필요"
    TargetState.NEEDS_REAPPLY_UPDATE -> "업데이트 · 재적용 필요"
    TargetState.NEEDS_REAPPLY_POLICY -> "표시 설정 변경 · 재적용 필요"
    TargetState.NEEDS_RECHECK_FIRMWARE -> "기기 호환성 재확인"
    TargetState.UNSUPPORTED_STATE -> "권한 상태 확인 필요"
    TargetState.MISSING_TARGET -> "삭제된 앱 · 조치 필요 없음"
    TargetState.SHARED_UID_BLOCKED -> "다른 앱과 묶임 · 적용 불가"
    TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW -> "앱 묶음 변경 · 확인 필요"
    TargetState.SHIZUKU_REQUIRED -> "연결 후 확인 필요"
    TargetState.FAILED_SAFE -> "다시 확인 필요"
    TargetState.FAILED_ROLLBACK -> "복원 확인 필요 · 변경 차단"
    TargetState.INACTIVE_INSTALL -> "새 설치 · 새로 등록 필요"
}
private fun Snapshot.blockReason(): String = rejectionReason()

@Composable
fun BlueDotApp(model: UiModel, navigate: (Page)->Unit, select: (String)->Unit, hide: (String)->Unit, restore: (String)->Unit, hideAll: ()->Unit, restoreAll: ()->Unit, openBackend: ()->Unit, setupBackend: ()->Unit, authorize: ()->Unit, refresh: ()->Unit, allowNotifications: ()->Unit, dismissNotifications: ()->Unit, begin: ()->Unit, loadApps: ()->Unit, clearNotice: ()->Unit, togglePick:(String)->Unit={}, addSelected:()->Unit={}, queryChanged:(String)->Unit={},filterChanged:(AppFilter)->Unit={},editHome:()->Unit={},toggleHome:(String)->Unit={},restoreSelected:()->Unit={},contentShown:()->Unit={},pickerRevision:Long=0L,historyViewChanged:(HistoryView)->Unit={},pickerViewChanged:(PickerView)->Unit={},wirelessExit:()->Unit={},toggleAll:()->Unit={},managementChanged:(Boolean)->Unit={},removeManagementPermission:()->Unit={},sharedInspect:(String)->Unit={},sharedAccept:()->Unit={},sharedDismiss:()->Unit={},scopeChange:()->Unit={},reapplyNeeded:(List<String>)->Unit={},notificationChanged:(Boolean)->Unit={},recheck:()->Unit={},bigGroupsAccept:()->Unit={},bigGroupsDismiss:()->Unit={},restorePickAll:(List<String>)->Unit={},rejectedDismiss:()->Unit={},showPick:(String)->Unit={},showPickAll:(List<String>)->Unit={},showSelected:()->Unit={},refreshHistory:()->Unit={},autoReapplyChanged:(Boolean)->Unit={},openShow:()->Unit={},beforeUninstall:()->Unit={},uninstallDismiss:()->Unit={},openAppInfo:()->Unit={},copyDiagnostics:()->Unit={},backgroundWatchChanged:(Boolean)->Unit={},openDeveloperOptions:()->Unit={},openBattery:(Boolean)->Unit={},dismissUsNotice:()->Unit={},dismissFirmwareNotice:()->Unit={},updateOfferAnswer:(Boolean)->Unit={},updateCheckChanged:(Boolean)->Unit={},checkUpdateNow:()->Unit={},openUpdate:(String)->Unit={},skipUpdate:(String)->Unit={}) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val motion = ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(model.page,pickerRevision){if(model.page==Page.PICK&&model.discovered.isNotEmpty()&&model.pickerRows!=null){withFrameNanos{};contentShown()}}
    val tint by animateFloatAsState(model.blueRemaining, if(motion) tween(580) else snap(), label="blue tint")
    val scheme = BlueDotAppearance.scheme(context,dark,tint)
    val surface = BlueDotAppearance.scheme(context,dark,0f).surface
    // A restore that skipped apps keeps its card: the 5-second snackbar alone read as "everything was restored".
    val expires=model.completion?.let{!it.hide&&it.error.isBlank()&&it.cleanupWarning.isBlank()&&it.results.isNotEmpty()&&it.results.all{r->r.success}}==true&&model.restoreSkipped==0
    var showSkipped by rememberSaveable{mutableStateOf(false)}
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
    val snackScope = rememberCoroutineScope()
    var showAutoBlocker by rememberSaveable { mutableStateOf(false) }
    // Measured height of the bottom navigation, so notices sit above it on every screen size and font scale.
    var navHeight by remember { mutableStateOf(0) }
    // Height of a page's pinned bottom buttons (앱 추가, 복원, 숨김 풀기), so notices do not cover those either.
    var actionHeight by remember { mutableStateOf(0) }
    val reportActions=remember{{h:Int->actionHeight=h}}
    LaunchedEffect(model.notice,model.busy) { if(model.notice==app.bluedotoff.adb.AutoBlocker.MESSAGE&&!model.busy){showAutoBlocker=true;clearNotice()} else if(model.notice.isNotBlank()&&!model.busy){val text=UiText.text(context,UiText.error(model.notice));clearNotice()
        // Shown for 5 s in its own scope: clearing or replacing the notice right after must not cut it short (it looked like ~1 s).
        snackScope.launch{snackbars.currentSnackbarData?.dismiss();val shown=launch{snackbars.showSnackbar(text,duration=SnackbarDuration.Indefinite)};kotlinx.coroutines.delay(5000);shown.cancel()}} }
    MaterialTheme(colorScheme=scheme) {
        BackHandler(enabled=model.busy&&!model.autoReapplying) {}
        BackHandler(enabled=model.page!=Page.HOME&&(!model.busy||model.autoReapplying)) { navigate(when(model.page){Page.DETAIL->model.detailBack;Page.SHOW,Page.HELP->model.pageBack;else->Page.HOME}) }
        Surface(Modifier.fillMaxSize(),color=surface,contentColor=scheme.onSurface) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                if(model.busy&&!model.scopePreview&&!model.autoReapplying){
                    val restoring=!model.operationHide
                    if(restoring)Box(Modifier.fillMaxSize().background(DotBlue))
                    BlueWaterBackground(tint,if(restoring)Color(0xFF191C20)else DotBlue,Modifier.fillMaxSize(),moving=motion)
                }else BlueWaterBackground(tint,BlueDotAppearance.scheme(context,dark,1f).surface,Modifier.fillMaxSize())
                val wide=maxWidth>=760.dp
                // Keep scroll/selection composition alive, but hide the page behind
                // the work overlay visually and from accessibility services.
                val pageVisibility=if(model.busy&&!model.scopePreview&&!model.autoReapplying)Modifier.alpha(0f).clearAndSetSemantics{} else Modifier
                Column(Modifier.fillMaxSize().then(pageVisibility).padding(horizontal=if(wide)BlueDotAppearance.WIDE_GUTTER.dp else BlueDotAppearance.PAGE_GUTTER.dp)) {
                    if(model.page==Page.HOME||model.gate!=Gate.READY)Row(Modifier.fillMaxWidth().heightIn(min=BlueDotAppearance.HEADER_HEIGHT.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(androidx.compose.ui.res.stringResource(R.string.brand_name),style=MaterialTheme.typography.headlineSmall,letterSpacing=(-0.8).sp,modifier=Modifier.weight(1f))
                        if(model.page==Page.HELP) TextButton(onClick={navigate(Page.HOME)},enabled=!model.busy){Text("홈")}
                        Box(Modifier.padding(start=12.dp).size(18.dp).background(DotBlue,CircleShape).semantics { contentDescription=UiText.text(context,"bluedotOFF 아이콘") })
                    }
                    if(!(model.page==Page.HOME||model.gate!=Gate.READY))Spacer(Modifier.height(16.dp))
                    // Automatic re-apply keeps the page and shows one progress line.
                    if(model.busy&&model.autoReapplying)model.progress?.let{p->Column(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        LinearProgressIndicator(progress={p.percent/100f},modifier=Modifier.fillMaxWidth())
                        Text("자동 재적용 중 · ${p.completed} / ${p.total}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }}
                    if(model.rechecking&&model.gate==Gate.READY&&!model.busy&&!(model.page==Page.PICK&&model.loadingApps)){
                        val sp=model.scanProgress
                        Column(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                            if(sp!=null)LinearProgressIndicator(progress={sp.percent/100f},modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(if(sp!=null)"앱 상태 확인 중 · ${sp.completed} / ${sp.total}" else "앱 상태 확인 중…",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if(!model.busy&&(!expires||completionVisible))model.completion?.takeUnless{it.hide&&it.error.isBlank()&&it.cleanupWarning.isBlank()&&it.results.isNotEmpty()&&it.results.all{r->r.success}}?.let{done->
                        Surface(Modifier.fillMaxWidth().padding(bottom=8.dp),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.secondaryContainer){
                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                                Text(done.summary(),style=MaterialTheme.typography.titleSmall)
                                if(!done.hide&&!done.show&&model.restoreSkipped>0){
                                    Text("확인이 필요한 ${model.restoreSkipped}개 앱은 건너뛰었어요.",style=MaterialTheme.typography.bodySmall)
                                    if(model.otherCheckCount>0)TextButton(onClick={showSkipped=true}){Text("남은 앱 보기")}
                                }
                                if(done.error.isNotBlank())Text(UiText.error(done.error),style=MaterialTheme.typography.bodySmall)
                                // Leaving the screen stops the batch; the unfinished apps stay selected, so say so (1.1.5).
                                if(!done.auto&&done.error.contains("화면을 떠나")){val left=when{done.show->if(model.page==Page.SHOW)model.showPicked.size else 0;done.hide->if(model.page==Page.PICK)model.picked.size else 0;else->if(model.page==Page.RESTORE)model.homePicked.size else 0}
                                    if(left>0)Text("남은 ${left}개 앱은 선택된 그대로예요. 아래 버튼을 다시 누르면 이어서 진행해요.",style=MaterialTheme.typography.bodySmall)}
                                if(model.wirelessWarning.isNotBlank()){
                                    Text(UiText.error(model.wirelessWarning),style=MaterialTheme.typography.bodySmall)
                                    TextButton(onClick=wirelessExit){Text("무선 디버깅 열기")}
                                }
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) { CompositionLocalProvider(LocalBottomActions provides reportActions) {
                    if(model.page==Page.HELP) HelpScreen(openBackend,setupBackend)
                    else if(model.gate!=Gate.READY) Wizard(model,openBackend,begin,{navigate(Page.HELP)},copyDiagnostics)
                    else when(model.page) {
                        Page.HOME -> HomeScreen(model,wide,{navigate(Page.PICK)},{showRestoreMenu=true},select,openBackend,{navigate(Page.HISTORY)},hide,editHome,toggleHome,restoreSelected,wirelessExit,{showReapplyNeeded=true},recheck,openDeveloperOptions=openDeveloperOptions,dismissUsNotice=dismissUsNotice,dismissFirmwareNotice=dismissFirmwareNotice,openUpdate=openUpdate,skipUpdate=skipUpdate)
                        Page.HISTORY -> HistoryScreen(model,hide,openBackend,historyViewChanged,select,refreshHistory)
                        Page.RESTORE -> RestoreScreen(model,toggleHome,restorePickAll,restoreSelected,openBackend)
                        Page.SHOW -> ShowScreen(model,showPick,showPickAll,showSelected)
                        Page.PICK -> PickerScreen(model,togglePick,addSelected,queryChanged,loadApps,openBackend,filterChanged,pickerViewChanged,toggleAll,sharedInspect)
                        Page.DETAIL -> DetailScreen(model,hide,restore,openBackend)
                        Page.REAPPLY -> ReapplyScreen(model,hideAll,openBackend,select)
                        Page.SETTINGS -> SettingsScreen(model,notificationChanged,openBackend,refresh,{navigate(Page.HELP)},wirelessExit,managementChanged,removeManagementPermission,scopeChange,openShow,beforeUninstall,copyDiagnostics,autoReapplyChanged,backgroundWatchChanged,openDeveloperOptions,openBattery,updateCheckChanged,checkUpdateNow,openUpdate)
                        Page.HELP -> Unit
                    }
                    } }
                    if(model.gate==Gate.READY)Column(Modifier.onSizeChanged{navHeight=it.height}){
                    HorizontalDivider(color=MaterialTheme.colorScheme.onSurface.copy(alpha=0.12f))
                    // Transparent so the bar follows the blue/system page background instead of floating as a gray block.
                    NavigationBar(containerColor=Color.Transparent,tonalElevation=0.dp) {
                        listOf(Page.HOME to "홈",Page.HISTORY to "기록",Page.SETTINGS to "설정").forEach{(page,label)->
                            NavigationBarItem(selected=model.page==page,onClick={navigate(page)},enabled=!model.busy||model.autoReapplying,icon={NavIcon(page)},label={Text(label,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium.let{st->val scale=LocalDensity.current.fontScale;if(scale>1.5f)st.copy(fontSize=st.fontSize*(1.5f/scale))else st})})
                        }
                    }
                    }
                }
                if(model.busy&&!model.scopePreview&&!model.autoReapplying) Surface(Modifier.fillMaxSize(),color=Color.Transparent) {
                    Box(Modifier.fillMaxSize().padding(24.dp),contentAlignment=Alignment.Center){
                    Surface(shape=RoundedCornerShape(24.dp),color=surface,contentColor=scheme.onSurface,modifier=Modifier.widthIn(max=560.dp)){
                    Column(Modifier.padding(24.dp).semantics { liveRegion=LiveRegionMode.Polite },horizontalAlignment=Alignment.CenterHorizontally) {
                        val p=model.progress
                        CircularProgressIndicator();Spacer(Modifier.height(24.dp))
                        Text(when(p?.phase){BatchPhase.CONNECTING->"연결을 준비하고 있어요";BatchPhase.CLOSING->"결과를 저장하고 연결을 마무리하고 있어요";else->if(model.operationHide)"적용하고 안전하게 확인하고 있어요" else if(model.operationShow)"숨김을 풀고 안전하게 확인하고 있어요" else "복원하고 안전하게 확인하고 있어요"},style=MaterialTheme.typography.titleMedium)
                        if(p!=null){Spacer(Modifier.height(20.dp));LinearProgressIndicator(progress={p.percent/100f},modifier=Modifier.fillMaxWidth());Spacer(Modifier.height(12.dp));Text(if(p.phase==BatchPhase.CLOSING)"${p.completed} / ${p.total}개 확인 · 마무리 중…" else "${p.completed} / ${p.total}개 확인 · ${p.percent}%",style=MaterialTheme.typography.titleLarge)}
                        Spacer(Modifier.height(12.dp));Text("끝날 때까지 이 화면을 열어 두세요. 앱을 나가면 남은 앱은 바꾸지 않아요.",textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=scheme.onSurfaceVariant)
                        if(model.operationHide)Text("일부 앱은 Android가 파란 위치 표시를 다시 켤 수 있어요. 적용 직후에도 풀릴 수 있으니, 필요하면 홈에서 다시 적용해주세요.",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall,color=scheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    }
                    }
                }
                // Follows the system theme (Material's default is the inverse colour) and closes on a tap.
                val navPad=if(model.gate==Gate.READY)with(LocalDensity.current){(navHeight+actionHeight).toDp()} else 16.dp
                SnackbarHost(snackbars,modifier=Modifier.align(Alignment.BottomCenter).padding(start=16.dp,end=16.dp,top=16.dp,bottom=navPad)){data->
                    val shape=RoundedCornerShape(14.dp)
                    // The content form adds no outer padding of its own, so the border sits on the box itself.
                    Snackbar(modifier=Modifier.padding(12.dp).clip(shape).border(1.dp,MaterialTheme.colorScheme.outlineVariant,shape).clickable(onClickLabel=UiText.text(context,"닫기")){data.dismiss()},
                        shape=shape,containerColor=MaterialTheme.colorScheme.surfaceContainerHighest,contentColor=MaterialTheme.colorScheme.onSurface){Text(data.visuals.message,translate=false)}
                }
            }
        }
        if(showAutoBlocker&&!model.busy) PanelAlert(onDismissRequest={showAutoBlocker=false},title={Text("보안 위험 자동 차단을 꺼주세요")},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){Text("이 기능이 켜져 있으면 휴대폰이 무선 디버깅을 막아서 bluedotOFF가 연결할 수 없어요. 아래 순서대로 끈 뒤 다시 시도해주세요.");GuideCard(GuideSelection(GuideStep.AUTO_BLOCKER));GuideCard(GuideSelection(GuideStep.AUTO_BLOCKER_OFF))}},
            confirmButton={DialogAction(onClick={showAutoBlocker=false;SettingsEntrypoints.autoBlocker(context)}){Text("보안 설정 열기")}},dismissButton={DialogCancel(onClick={showAutoBlocker=false}){Text("닫기")}})
        if(model.notificationOffer&&model.gate==Gate.READY&&!model.busy) PanelAlert(onDismissRequest=dismissNotifications,title={Text("재적용 알림")},text={Text("휴대폰 재시작이나 등록 앱 업데이트로 숨김이 풀릴 수 있을 때 알려드려요. 광고나 상시 알림은 없어요. 알림 없이도 핵심 기능은 쓸 수 있어요.")},confirmButton={DialogAction(onClick=allowNotifications){Text("알림 허용")}},dismissButton={DialogCancel(onClick=dismissNotifications){Text("나중에")}})
        if(model.updateOffer&&!model.notificationOffer&&model.gate==Gate.READY&&!model.busy) UpdateOfferDialog(updateOfferAnswer)
        if(model.rejectedGroupList.isNotEmpty()&&model.bigGroups.isEmpty()&&!model.busy) PanelAlert(onDismissRequest=rejectedDismiss,title={Text("선택하지 않은 앱 묶음")},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("아래 묶음은 안전하게 함께 바꿀 수 있는지 확인하지 못해서 선택하지 않았어요. 다른 앱 선택에는 영향이 없어요.")
                model.rejectedGroupList.forEach{g->Column{
                    Text(if(g.size>1)"${g.label} 외 ${g.size-1}개 앱" else g.label,style=MaterialTheme.typography.titleSmall,translate=g.size>1)
                    Text(g.reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }}
            }},confirmButton={DialogAction(onClick=rejectedDismiss){Text("확인")}})
        if(model.bigGroups.isNotEmpty()&&!model.busy) PanelAlert(onDismissRequest=bigGroupsDismiss,title={Text("큰 앱 묶음을 함께 선택할까요?")},
            text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                Text("아래 묶음은 여러 앱이 위치 설정 하나를 함께 써요. 하나만 고를 수 없어서, 선택하면 묶음 전체가 함께 숨겨지고 함께 복원돼요.")
                model.bigGroups.forEach{g->Column{
                    Text("${g.label} 외 ${g.names.size-1}개 앱",style=MaterialTheme.typography.titleSmall)
                    if(g.system)Text("Android 시스템 앱 포함",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                }}
            }},
            confirmButton={DialogAction(onClick=bigGroupsAccept){Text("함께 선택")}},dismissButton={DialogCancel(onClick=bigGroupsDismiss){Text("선택 안 함")}})
        model.sharedOffer?.let{offer->
            PanelAlert(onDismissRequest=sharedDismiss,title={Text("위치 설정을 함께 쓰는 앱")},text={
                Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                    Text("아래 앱은 위치 표시 설정을 공유해요. 하나만 따로 바꿀 수 없고, 함께 숨기거나 함께 복원해요.")
                    if(offer.loading)CircularProgressIndicator()
                    offer.members.forEach{s->Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh){Column(Modifier.fillMaxWidth().padding(12.dp)){Text(s.label,translate=false,style=MaterialTheme.typography.titleSmall);Text(s.identity.packageName,translate=false,style=MaterialTheme.typography.bodySmall)}}}
                    if(offer.error.isNotBlank())Text(UiText.error(offer.error),color=MaterialTheme.colorScheme.error)
                    if(!offer.loading&&offer.error.isBlank())Text("앱의 위치 권한은 그대로예요. 묶음 전체의 원래 표시 상태를 저장하고, 구성원이 바뀌면 더 이상 바꾸지 않아요.",style=MaterialTheme.typography.bodySmall)
                }
            },confirmButton={if(!offer.loading&&offer.error.isBlank())DialogAction(onClick=sharedAccept){Text(if(!offer.hide)"함께 복원" else if(offer.direct)"함께 재적용" else "묶음 전체 선택")}},dismissButton={DialogCancel(onClick=sharedDismiss){Text("취소")}})
        }
        if(showSkipped&&model.otherCheckCount>0)CheckNeededDialog(model,{showSkipped=false}){pkg->showSkipped=false;select(pkg)}
        if(showReapplyNeeded&&model.gate==Gate.READY&&!model.busy&&model.reapplyCount>0) ReapplyNeededDialog(model,{showReapplyNeeded=false},{packages->showReapplyNeeded=false;reapplyNeeded(packages)},{showReapplyNeeded=false;openBackend()})
        if(showRestoreMenu) PanelAlert(onDismissRequest={showRestoreMenu=false},title={Text("앱 복원")},
            text={Text("숨긴 앱을 원래 표시 상태로 되돌려요. 위치 권한과 사용 기록은 그대로예요. 앱을 지우기 전에는 모두 복원해 주세요.")},
            confirmButton={if(model.canOperate)DialogAction(onClick={showRestoreMenu=false;showRestoreAll=true}){Text("모두 복원")} else DialogAction(onClick={showRestoreMenu=false;openBackend()}){Text("연결 후 모두 복원")}},
            dismissButton={DialogCancel(onClick={showRestoreMenu=false;editHome()}){Text("골라서 복원")}})
        model.uninstallCheck?.takeIf{!model.busy}?.let{left->PanelAlert(onDismissRequest=uninstallDismiss,title={Text("앱 삭제 전 복원")},
            text={Text(if(left==0)"숨긴 앱을 모두 원래 상태로 되돌렸어요. 이제 bluedotOFF를 삭제해도 돼요. 앱을 삭제하면 권한과 연결 정보도 함께 지워져요. 무선 디버깅이 켜져 있다면 개발자 옵션에서 꺼주세요." else "${left}개 앱을 지금 복원하지 못했어요. 홈의 ‘등록한 앱’에서 상태를 확인한 뒤 다시 눌러주세요. 이 앱들이 남아 있는 동안에는 bluedotOFF를 삭제하지 마세요.")},
            confirmButton={if(left==0)DialogAction(onClick={uninstallDismiss();openAppInfo()}){Text("앱 정보 열기")} else DialogAction(onClick=uninstallDismiss){Text("확인")}},
            dismissButton={if(left==0)DialogCancel(onClick=uninstallDismiss){Text("닫기")}})}
        if(showRestoreAll) RestoreAllDialog(model.entries.count{it.enabled},{showRestoreAll=false},{showRestoreAll=false;restoreAll()})
    }
}

@Composable private fun Wizard(m:UiModel,open:()->Unit,begin:()->Unit,help:()->Unit,diagnostics:()->Unit={}) {
    val step=when(m.gate){Gate.WELCOME,Gate.UNSUPPORTED->1;Gate.NOT_RUNNING->6;Gate.CHECKING->11;else->8}
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
    Column(Modifier.widthIn(max=BlueDotAppearance.SETUP_WIDTH.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top=24.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(BlueDotAppearance.SECTION_GAP.dp)) {
        Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(BlueDotAppearance.setupProgress(LocalContext.current,step),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth().height(6.dp),horizontalArrangement=Arrangement.spacedBy(3.dp)){
                repeat(11){i->Box(Modifier.weight(1f).fillMaxHeight().background(when{i+1==step->BlueDotAppearance.dotBlue;i+1<step->MaterialTheme.colorScheme.primary;else->MaterialTheme.colorScheme.outlineVariant},RoundedCornerShape(2.dp)))}
            }
        }
        Text(when(m.gate){Gate.WELCOME->"믿는 앱만,\nBlue Dot 없이.";Gate.UNSUPPORTED->"이 기기는 지원하지 않아요";Gate.NOT_RUNNING->"무선 디버깅으로 연결해 주세요";Gate.CHECKING->if(m.checkingRegistered)"등록한 앱의 상태를 확인하고 있어요" else "기기를 확인하고 있어요";else->"기기를 확인하고 있어요"},style=MaterialTheme.typography.headlineMedium)
        Text(when(m.gate){Gate.WELCOME->"믿는 앱의 위치 기능과 사용 기록은 그대로 두고, 파란 위치 표시만 숨겨요.";Gate.UNSUPPORTED->"Samsung One UI 9 이상, Android 17 이상 기기에서 쓸 수 있어요. 지원하지 않는 기기에서는 아무것도 바꾸지 않아요.";Gate.NOT_RUNNING->"연결 설정에서 무선 디버깅을 켜고 기기를 연결해 주세요. 돌아오면 자동으로 확인해요.";Gate.CHECKING->if(m.checkingRegistered)"앱 목록은 그대로 사용하고, 등록한 앱의 현재 상태만 확인해요. 설정은 변경하지 않아요." else "기기와 앱 상태를 확인만 해요. 아무것도 바꾸지 않아요.";else->"기기와 앱 상태를 확인만 해요. 아무것도 바꾸지 않아요."},style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
                listOf("위치 권한을 ‘정확한 위치’로 허용한 앱만 숨길 수 있어요.","일부 앱은 스스로 설정을 바꿔서 숨김이 금방 풀려요.","풀린 앱은 bluedotOFF를 열 때 다시 숨겨요. USB 디버깅을 켜 두면 앱을 열지 않아도 감지하는 대로 다시 숨겨요.","갤럭시 ‘보안 위험 자동 차단’이 켜져 있으면 연결할 수 없어요.").forEach{line->
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("•",style=MaterialTheme.typography.bodyMedium,translate=false);Text(line,style=MaterialTheme.typography.bodyMedium.copy(lineBreak=androidx.compose.ui.text.style.LineBreak.Paragraph.copy(wordBreak=androidx.compose.ui.text.style.LineBreak.WordBreak.Phrase)))}
                }
            }
        }
        when(m.gate){
            Gate.WELCOME->Primary("시작하기",begin)
            Gate.NOT_RUNNING->Primary("연결 설정 열기",open)
            else->Unit
        }
        Column{
            TextButton(onClick=help){Text("설정 도움말")}
            // Settings is out of reach before a connection: an unsupported or unconnected device can still report itself.
            if(m.gate==Gate.UNSUPPORTED||m.gate==Gate.NOT_RUNNING)TextButton(onClick=diagnostics){Text("진단 정보 복사 및 보내기")}
        }
    }
    }
}

@Composable private fun HomeScreen(m:UiModel,wide:Boolean,add:()->Unit,restoreMenu:()->Unit,select:(String)->Unit,open:()->Unit,history:()->Unit,hide:(String)->Unit,edit:()->Unit,toggle:(String)->Unit,restoreSelected:()->Unit,wirelessExit:()->Unit,showNeeded:()->Unit,recheck:()->Unit={},openDeveloperOptions:()->Unit={},dismissUsNotice:()->Unit={},dismissFirmwareNotice:()->Unit={},openUpdate:(String)->Unit={},skipUpdate:(String)->Unit={}) {
    val context=LocalContext.current
    var showCheck by rememberSaveable{mutableStateOf(false)}
    if(showCheck&&m.otherCheckCount>0)CheckNeededDialog(m,{showCheck=false}){pkg->showCheck=false;select(pkg)}
    val attention=m.reapplyCount>0||m.otherCheckCount>0
    @Composable fun RecentCard() {
        m.recentHistoryApp?.let{a->
            Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp)){
                    Text("최근 위치 사용 앱",style=MaterialTheme.typography.labelLarge)
                    Text("${a.label} · ${UiText.text(LocalContext.current,accessAge(a.access.latest))}",Modifier.padding(top=6.dp),style=MaterialTheme.typography.bodyMedium)
                    Row{TextButton(onClick=history){Text("기록 보기")};if(m.canActFromHistory(a)&&m.entries.none{it.enabled&&it.identity.packageName==a.packageName&&it.state==TargetState.APPLIED&&it.settlingVerified})TextButton(onClick={hide(a.packageName)},enabled=m.canOperate&&!m.busy){Text("숨기기")}}
                }
            }
        }
    }
    @Composable fun Hero() {
        Column(Modifier.padding(top=10.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            if(m.usModel)Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.tertiaryContainer,contentColor=MaterialTheme.colorScheme.onTertiaryContainer,border=BorderStroke(1.dp,MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha=0.18f))){
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("미국 판매 모델 안내",style=MaterialTheme.typography.titleMedium)
                    Text("미국 판매 모델은 위치 표시를 띄우는 방식이 하나 더 있어서, 적용해도 파란 위치 표시가 사라지지 않을 수 있어요. 위치 권한과 사용 기록은 그대로이고, 언제든 원래 상태로 복원할 수 있어요.",style=MaterialTheme.typography.bodyMedium)
                    TextButton(onClick=dismissUsNotice){Text("확인했어요")}
                }
            }
            if(m.firmwareNotice)Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.tertiaryContainer,contentColor=MaterialTheme.colorScheme.onTertiaryContainer,border=BorderStroke(1.dp,MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha=0.18f))){
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("확인되지 않은 시스템 안내",style=MaterialTheme.typography.titleMedium)
                    Text("휴대폰 업데이트나 기종 차이로, 개발자가 확인한 시스템과 일부가 달라요. bluedotOFF는 계속 쓸 수 있지만, 이 휴대폰에서 파란 위치 표시가 사라지는지는 아직 확인되지 않았어요. 적용 후에도 파란 점이 보이면 GitHub에 알려주세요. 위치 권한과 사용 기록은 그대로이고, 언제든 원래 상태로 복원할 수 있어요.",style=MaterialTheme.typography.bodyMedium)
                    TextButton(onClick=dismissFirmwareNotice){Text("확인했어요")}
                }
            }
            m.updateCard?.let{tag->Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.secondaryContainer,contentColor=MaterialTheme.colorScheme.onSecondaryContainer){
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("새 버전이 있어요",style=MaterialTheme.typography.titleMedium)
                    Text("bluedotOFF ${tag}이 나왔어요. GitHub 출시 페이지에서 받을 수 있어요.",style=MaterialTheme.typography.bodyMedium)
                    FlowRow{TextButton(onClick={openUpdate(tag)}){Text("출시 페이지 열기")};TextButton(onClick={skipUpdate(tag)}){Text("이 버전 건너뛰기")}}
                }
            }}
            // After a phone update the structure check can fail: that is not a connection problem, so no "연결 준비" here.
            val osChanged=m.compatibility==SetupFlow.OS_CHANGED
            if(osChanged||!m.connected&&!m.automaticReady&&!m.journalProblem)Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.tertiaryContainer,contentColor=MaterialTheme.colorScheme.onTertiaryContainer,border=BorderStroke(1.dp,MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha=0.18f))){
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text(if(osChanged)"휴대폰 업데이트로 내부 구조가 바뀌었어요" else "설정을 변경하려면 연결이 필요해요",style=MaterialTheme.typography.titleMedium)
                    Text(m.compatibility,style=MaterialTheme.typography.bodyMedium)
                    if(!osChanged)TextButton(onClick=open){Text("연결 준비")}
                }
            }
            if(m.journalProblem)Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.errorContainer,contentColor=MaterialTheme.colorScheme.onErrorContainer){
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("복원 기록을 확인하지 못했어요",style=MaterialTheme.typography.titleMedium)
                    Text("숨긴 앱을 원래대로 되돌리는 데 필요한 기록을 읽거나 저장하지 못해서, 안전을 위해 모든 변경을 멈췄어요. 다시 연결해도 해결되지 않아요.",style=MaterialTheme.typography.bodyMedium)
                    Text("앱 데이터를 지우거나 앱을 삭제하지 마세요. 기록이 사라지면 숨긴 앱을 원래대로 되돌릴 수 없어요. 설정 → 진단 정보 복사 및 보내기로 알려주세요.",style=MaterialTheme.typography.bodyMedium)
                }
            }
            // Background watch is chosen but not running: say why and what brings it back (USB debugging first).
            // Not while it is being started again (opening the app restarts it).
            if(m.backgroundWatch&&!m.watchRunning&&!(m.watchStarting&&m.usbDebugging)&&m.gate==Gate.READY)Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.secondaryContainer,contentColor=MaterialTheme.colorScheme.onSecondaryContainer){
                Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("백그라운드 감시가 멈춰 있어요",style=MaterialTheme.typography.titleMedium)
                    Text(WatchNotification.advice(m.usbDebugging,m.autoManagement),style=MaterialTheme.typography.bodyMedium)
                    if(!m.usbDebugging)TextButton(onClick=openDeveloperOptions){Text("USB 디버깅 켜기")}
                }
            }
            Text(when{m.allLastVerifiedApplied->"적용 완료";m.reapplyCount>0->"일부 앱의\n재적용이 필요해요";m.visibleEntries.any{it.enabled}->"일부 앱의\n상태 확인이 필요해요";else->"미적용"},style=MaterialTheme.typography.displaySmall)
            if(m.allLastVerifiedApplied)Text("${m.lastVerifiedAppliedCount}개 앱 적용 중",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            else if(m.visibleEntries.any{it.enabled}){
                Text("${m.lastVerifiedAppliedCount}개 앱 적용 중",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(m.reapplyCount>0)Text("${m.reapplyCount}개 앱 · 재적용 필요",Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).clickable(role=androidx.compose.ui.semantics.Role.Button,onClick=showNeeded).padding(vertical=12.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                if(m.otherCheckCount>0)Text("${m.otherCheckCount}개 앱 · 상태 확인 필요",Modifier.fillMaxWidth().heightIn(min=48.dp).clip(RoundedCornerShape(12.dp)).clickable(role=androidx.compose.ui.semantics.Role.Button,onClick={showCheck=true}).padding(vertical=12.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
                Text("완료된 앱은 유지하고, 필요한 앱만 확인해주세요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }else Text("신뢰하는 앱을 골라\n위치 표시만 가볍게 숨기세요.",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(m.lastCheckedAt>0L&&m.visibleEntries.any{it.enabled})Row(verticalAlignment=Alignment.CenterVertically){
                val ctx=LocalContext.current
                // Mid-sentence in English: "Last checked just now", not "Just now".
                Text("${UiText.text(ctx,"마지막 확인")} ${UiText.text(ctx,accessAge(m.lastCheckedAt)).replaceFirstChar{it.lowercase()}}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,translate=false)
                RefreshButton(m.rechecking,!m.busy&&m.canOperate,recheck)
            }
            // Owner choice (2026-10-17): when apps need attention the list comes first, so this card moves below it.
            if(wide||!attention)RecentCard()
            Primary("앱 추가",add)
            if(m.successToken>0&&!m.busy&&m.connected&&!m.autoManagement){Text("작업을 마쳤다면 무선 디버깅을 꺼두세요.",style=MaterialTheme.typography.bodySmall);TextButton(onClick=wirelessExit){Text("무선 디버깅 끄기")}}
            if(m.visibleEntries.any{it.enabled})OutlinedButton(onClick=restoreMenu,enabled=!m.busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("앱 복원")}
        }
    }
    @Composable fun Registered() {
        Column(Modifier.fillMaxWidth().padding(top=20.dp)) {
            if(m.connected&&m.visibleEntries.isEmpty()){Text("준비 완료",color=MaterialTheme.colorScheme.primary);Spacer(Modifier.height(16.dp))}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("등록한 앱 · ${m.visibleEntries.count{it.enabled}}개",style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f));if(m.visibleEntries.isNotEmpty())TextButton(onClick=edit){Text("편집")}}
            Spacer(Modifier.height(12.dp))
            if(m.visibleEntries.isEmpty())Text("아직 등록한 앱이 없어요.",Modifier.padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            else Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) { LazyColumn(Modifier.heightIn(max=460.dp).padding(horizontal=12.dp)) { items(m.registeredDisplayEntries,key={it.identity.packageName}) { e -> AppRow(e.identity.packageName,e.label,m.status(e),{select(e.identity.packageName)});HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.55f)) } } }
            Text("총 ${m.visibleEntries.count{it.enabled}}개 앱 등록",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if(wide) Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(44.dp)) { Box(Modifier.weight(1f)){Hero()};Box(Modifier.weight(1.15f)){Registered()} }
    else Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom=36.dp)) { Hero();Registered();if(attention)Box(Modifier.padding(top=20.dp)){RecentCard()} }
}

/** "표시 켜기": apps whose location indicator is OFF with no bluedotOFF record. Same layout as the restore page.
 *  Turning one on is not a restore (there is no recorded original); preinstalled apps may be OFF by Android's own choice,
 *  so "전체 선택" takes only the apps the user installed. */
@Composable private fun ShowScreen(m:UiModel,toggle:(String)->Unit,toggleAll:(List<String>)->Unit,show:()->Unit) {
    var query by rememberSaveable{mutableStateOf("")}
    var confirm by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text("숨김이 남은 앱",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            Text("${m.showPicked.size}개 선택됨",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        }
        Text("bluedotOFF를 지웠다가 다시 설치하면, 예전에 숨긴 앱이 기록 없이 숨겨진 채 남을 수 있어요. 기록이 없어서 복원할 수 없으니, 대신 숨김을 풀 수 있어요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(m.loadingApps||m.rechecking)LinearProgressIndicator(Modifier.fillMaxWidth())
        val rows=m.leftOffApps.filter{a->query.isBlank()||a.label.contains(query.trim(),true)||a.packageName.contains(query.trim(),true)}
        val names=rows.filter{m.canShow(it)&&!it.preinstalled}.map{it.packageName}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            val searchHeight=(32f+20f*LocalDensity.current.fontScale).coerceAtLeast(52f).dp
            OutlinedTextField(query,{query=it},modifier=Modifier.weight(1f).height(searchHeight),singleLine=true,placeholder={Text("앱 검색",style=MaterialTheme.typography.bodyMedium)},textStyle=MaterialTheme.typography.bodyMedium,shape=RoundedCornerShape(14.dp))
            Row(verticalAlignment=Alignment.CenterVertically){
                val picked=names.count{it in m.showPicked}
                Text("전체 선택",Modifier.clickable(enabled=!m.busy&&names.isNotEmpty()){toggleAll(names)},style=MaterialTheme.typography.bodySmall)
                TriStateCheckbox(state=when{picked==0->androidx.compose.ui.state.ToggleableState.Off;picked==names.size->androidx.compose.ui.state.ToggleableState.On;else->androidx.compose.ui.state.ToggleableState.Indeterminate},onClick={toggleAll(names)},enabled=!m.busy&&names.isNotEmpty())
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(rows,key={it.packageName}){a->
                val can=m.canShow(a)
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=!m.busy&&can){toggle(a.packageName)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    AppIcon(a.packageName,a.label,32)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
                        Text(a.label,translate=false,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(when{m.sharesUid(a)->"다른 앱과 묶여 있어 여기서는 풀 수 없어요";!can->"지금 적용 범위에서는 풀 수 없어요";a.preinstalled->"기본 앱 · 원래 꺼져 있었을 수 있어요";else->"숨김 남음 · 기록 없음"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Checkbox(checked=a.packageName in m.showPicked,onCheckedChange={toggle(a.packageName)},enabled=!m.busy&&can)
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))
            }
        }
        if(rows.isEmpty()&&!m.loadingApps)Text(if(query.isBlank())"숨김이 남은 앱이 없어요." else "조건에 맞는 앱이 없어요.",style=MaterialTheme.typography.bodySmall)
        Column(Modifier.fillMaxWidth().bottomActions().padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("‘전체 선택’은 직접 설치한 앱만 골라요. 기본 앱은 원래 표시가 꺼져 있었을 수 있어서 하나씩 골라주세요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Primary("${m.showPicked.size}개 숨김 풀기",{confirm=true},enabled=m.showPicked.isNotEmpty()&&m.canOperate&&!m.busy)
        }
    }
    if(confirm)PanelAlert(onDismissRequest={confirm=false},title={Text("숨김 풀기")},
        text={Text("선택한 ${m.showPicked.size}개 앱의 숨김을 풀어요(파란 위치 표시 설정을 켜요). 시스템에서 표시를 제외하는 앱은 숨김을 풀어도 파란 위치 표시가 나타나지 않을 수 있어요. 위치 권한과 사용 기록은 그대로예요.")},
        confirmButton={DialogAction(onClick={confirm=false;show()}){Text("숨김 풀기")}},dismissButton={DialogCancel(onClick={confirm=false}){Text("취소")}})
}

/** Same layout as "앱 추가": search, select all, checkboxes, and one button at the bottom. Checked = restore. */
@Composable private fun RestoreScreen(m:UiModel,toggle:(String)->Unit,toggleAll:(List<String>)->Unit,restore:()->Unit,open:()->Unit={}) {
    var query by rememberSaveable{mutableStateOf("")}
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text("앱 복원",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            Text("${m.homePicked.size}개 선택됨",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        }
        val rows=m.registeredDisplayEntries.filter{e->query.isBlank()||e.label.contains(query.trim(),true)||e.identity.packageName.contains(query.trim(),true)}
        val names=rows.map{it.identity.packageName}
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            val searchHeight=(32f+20f*LocalDensity.current.fontScale).coerceAtLeast(52f).dp
            OutlinedTextField(query,{query=it},modifier=Modifier.weight(1f).height(searchHeight),singleLine=true,placeholder={Text("앱 검색",style=MaterialTheme.typography.bodyMedium)},textStyle=MaterialTheme.typography.bodyMedium,shape=RoundedCornerShape(14.dp))
            Row(verticalAlignment=Alignment.CenterVertically){
                val picked=names.count{it in m.homePicked}
                Text("전체 선택",Modifier.clickable(enabled=!m.busy&&names.isNotEmpty()){toggleAll(names)},style=MaterialTheme.typography.bodySmall)
                TriStateCheckbox(state=when{picked==0->androidx.compose.ui.state.ToggleableState.Off;picked==names.size->androidx.compose.ui.state.ToggleableState.On;else->androidx.compose.ui.state.ToggleableState.Indeterminate},onClick={toggleAll(names)},enabled=!m.busy&&names.isNotEmpty())
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            items(rows,key={it.identity.packageName}){e->
                Row(Modifier.fillMaxWidth().heightIn(min=56.dp).clickable(enabled=!m.busy){toggle(e.identity.packageName)}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    AppIcon(e.identity.packageName,e.label,32)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(e.label,translate=false,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis);Text(m.status(e),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Checkbox(checked=e.identity.packageName in m.homePicked,onCheckedChange={toggle(e.identity.packageName)},enabled=!m.busy)
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))
            }
        }
        if(rows.isEmpty())Text("조건에 맞는 앱이 없어요.",style=MaterialTheme.typography.bodySmall)
        Column(Modifier.fillMaxWidth().bottomActions().padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text("선택한 앱을 원래 표시 상태로 되돌려요. 위치 권한과 사용 기록은 그대로예요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);// Without a connection the button leads to setup instead of sitting grey; the selection comes back afterwards.
            if(m.canOperate)Primary("${m.homePicked.size}개 복원",restore,enabled=m.homePicked.isNotEmpty()&&!m.busy) else Primary("연결 후 ${m.homePicked.size}개 복원",open,enabled=m.homePicked.isNotEmpty()&&!m.busy)}
    }
}

private fun pickerReason(a:DiscoveredApp,scope:ApplicationScope=ApplicationScope.GROUPS):String=when {
    scope!=ApplicationScope.NORMAL&&a.uidPeers.size>1->"다른 앱과 묶여 있어요 · 함께 선택돼요"
    scope==ApplicationScope.ALL&&a.block.isNotEmpty()->a.block
    a.check==CandidateCheck.NEEDS_CHECK->"앱의 권한과 설치 정보를 다시 확인해주세요."
    a.uidClass==UidClass.MULTI_PACKAGE_SHARED_UID->"다른 앱과 묶여 있어요 · 함께 선택돼요"
    a.uidClass==UidClass.SINGLETON_SHARED_USER->"이 기기에서 공유 권한 구조가 안전한지 확인하지 못했어요."
    a.uidClass in setOf(UidClass.CORE_UID,UidClass.PERSISTENT_CORE,UidClass.CRITICAL_PACKAGE)->"핵심 시스템 구성 요소라 바꿀 수 없어요."
    a.block.contains("UID")->"앱의 권한과 설치 정보를 안전하게 확인하지 못했어요."
    a.block.isNotEmpty()->a.block
    else->"지금 설정에서는 안전하게 바꿀 수 있는지 확인할 수 없어요."
}
@Composable private fun PickerScreen(m:UiModel,toggle:(String)->Unit,add:()->Unit,queryChanged:(String)->Unit,load:()->Unit,open:()->Unit,filterChanged:(AppFilter)->Unit,viewChanged:(PickerView)->Unit,toggleAll:()->Unit,sharedInspect:(String)->Unit) {
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            Text("앱 추가",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
            Text("${m.picked.size}개 선택됨",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        }
        if(m.picked.isNotEmpty())LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(m.displayApps.filter{it.packageName in m.picked},key={it.packageName}){a->SuggestionChip(onClick={toggle(a.packageName)},label={Text(a.label)})}}
        // The selected chip is scrolled into view (long English labels at large text pushed it off a narrow screen).
        val filterRow=rememberLazyListState();LaunchedEffect(m.filter){filterRow.animateScrollToItem(AppFilter.entries.indexOf(m.filter))}
        LazyRow(state=filterRow,horizontalArrangement=Arrangement.spacedBy(8.dp)){items(AppFilter.entries){f->FilterChip(m.filter==f,{filterChanged(f)},label={Text(f.label)})}}
        val scopeNote=@Composable{
            Text(m.applicationScope.title,style=MaterialTheme.typography.bodySmall)
            Text(if(m.applicationScope==ApplicationScope.ALL)"‘정확한 위치’를 허용한 앱만 숨길 수 있어요. 나머지 앱은 표시만 해요. 공유 앱은 구성원을 확인한 뒤 묶음으로 선택하세요." else "‘정확한 위치’를 허용한 앱만 숨길 수 있어요. 찾는 앱이 없으면 그 앱의 위치 권한을 확인해 주세요.",style=MaterialTheme.typography.bodySmall)
        }
        // With very large text the fixed header left room for one or two apps; the note then scrolls with the list.
        val noteScrolls=LocalDensity.current.fontScale>=1.5f
        if(!noteScrolls)scopeNote()
        if(!m.canOperate&&m.discovered.isEmpty()){if(noteScrolls)scopeNote();Primary("연결 설정 열기",open);return}
        if(m.loadingApps)LinearProgressIndicator(Modifier.fillMaxWidth())
        var reason by remember{mutableStateOf<String?>(null)}
        val rows=m.pickerApps
        val unmanagedNotSensitive=rows.any{m.leftOff(it)}
        // Only rows the user can act on: selectable apps and shared groups first, then already applied ones.
        // Apps that need checking or cannot be changed are left out; nothing can be done with them here.
        val available=rows.filter{m.canAct(it)||m.canPickGroup(it)}
            .sortedBy{if(m.canSelect(it)||m.canPickGroup(it))0 else 1}
        // While searching, a matching app that cannot be hidden is listed after them with the reason, so the
        // user does not think the search or the scan missed it. Nothing about it can be selected.
        // Picked apps are not "unavailable": a chosen shared group leaves the list and shows as chips above.
        val unavailable=if(m.query.isBlank())emptyList() else available.toHashSet().let{shown->rows.filterNot{it in shown||it.packageName in m.picked}}
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
                        Text(when{m.isApplied(a.packageName)->"적용 중";a.packageName in m.managedPackages->"등록한 앱";m.leftOff(a)->"숨김 남음 · 기록 없음";else->"적용 가능"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }else{
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){Text(a.label,translate=false,style=MaterialTheme.typography.titleSmall);Text(pickerReason(a,m.applicationScope),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                    // Already hidden apps cannot be picked again: a check mark without a box, so it is not read as a selection
                    // (a checked box contradicted "0개 선택됨"). Same 48dp slot keeps the rows aligned.
                    val applied=m.isApplied(a.packageName)
                    if(applied)Box(Modifier.size(48.dp),contentAlignment=Alignment.Center){Icon(androidx.compose.material.icons.Icons.Filled.Check,contentDescription=null,tint=BlueDotAppearance.dotBlue,modifier=Modifier.size(24.dp))}
                    else Checkbox(checked=a.packageName in m.picked,onCheckedChange={if(group)sharedInspect(a.packageName)else toggle(a.packageName)},enabled=(selectable||a.packageName in m.picked)&&!m.busy&&!m.loadingApps)
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))
            }}
            if(noteScrolls)item(key="scope-note"){Column(Modifier.padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){scopeNote()}}
            appRows(available)
            if(unavailable.isNotEmpty()){
                item(key="unavailable-header"){Text("숨길 수 없는 앱",Modifier.padding(top=16.dp,bottom=4.dp),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                appRows(unavailable)
            }
        }
        reason?.let{message->PanelAlert(onDismissRequest={reason=null},title={Text("앱 확인 결과")},text={Text(message)},confirmButton={DialogAction(onClick={reason=null}){Text("확인")}})}
        if(available.isEmpty()&&unavailable.isEmpty()&&!m.loadingApps)Text("조건에 맞는 앱이 없어요.",style=MaterialTheme.typography.bodySmall)
        Column(Modifier.fillMaxWidth().bottomActions().padding(bottom=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(if(unmanagedNotSensitive)"‘숨김 남음 · 기록 없음’은 bluedotOFF 기록 없이 이미 숨겨져 있는 앱이에요. 다시 숨길 필요가 없고, 설정 → ‘숨김이 남은 앱’에서 숨김을 풀 수 있어요." else "선택한 앱을 등록하고 파란 위치 표시를 숨겨요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant);Primary("${m.picked.size}개 추가",add,enabled=m.picked.isNotEmpty()&&!m.busy)}
    }
}

/** Bottom navigation icons share one 24dp frame so the three icons look the same size and the labels line up
 *  (text glyphs such as ◷ and ⚙ had different heights). Home is the blue dot, the app's symbol. */
@Composable private fun NavIcon(page:Page){
    val color=LocalContentColor.current
    Box(Modifier.size(24.dp),contentAlignment=Alignment.Center){
        when(page){
            Page.HOME->Box(Modifier.size(20.dp).background(DotBlue,CircleShape))
            Page.HISTORY->androidx.compose.foundation.Canvas(Modifier.size(20.dp)){
                val w=2.dp.toPx()
                drawCircle(color,radius=size.minDimension/2-w/2,style=androidx.compose.ui.graphics.drawscope.Stroke(w))
                drawLine(color,center,androidx.compose.ui.geometry.Offset(center.x,center.y-size.height*0.27f),w,cap=androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color,center,androidx.compose.ui.geometry.Offset(center.x+size.width*0.2f,center.y+size.height*0.08f),w,cap=androidx.compose.ui.graphics.StrokeCap.Round)
            }
            else->Icon(androidx.compose.material.icons.Icons.Outlined.Settings,contentDescription=null,modifier=Modifier.size(24.dp))
        }
    }
}
/** Small circular-arrow button; spins while the registered apps are being re-read. */
@Composable private fun RefreshButton(spinning:Boolean,enabled:Boolean,onClick:()->Unit,label:String="다시 확인"){
    val ctx=LocalContext.current;val color=MaterialTheme.colorScheme.primary
    IconButton(onClick=onClick,enabled=enabled&&!spinning,modifier=Modifier.semantics{contentDescription=UiText.text(ctx,label)}){
        if(spinning)CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
        else androidx.compose.foundation.Canvas(Modifier.size(20.dp).alpha(if(enabled)1f else 0.38f)){
            val stroke=2.dp.toPx();val r=size.minDimension/2f-stroke*1.5f;val c=center
            drawArc(color,startAngle=-60f,sweepAngle=290f,useCenter=false,topLeft=androidx.compose.ui.geometry.Offset(c.x-r,c.y-r),size=androidx.compose.ui.geometry.Size(r*2,r*2),style=androidx.compose.ui.graphics.drawscope.Stroke(stroke,cap=androidx.compose.ui.graphics.StrokeCap.Round))
            // Arrowhead at the arc start (-60 degrees), pointing along the turning direction.
            val a=Math.toRadians(-60.0);val tip=androidx.compose.ui.geometry.Offset(c.x+(r*Math.cos(a)).toFloat(),c.y+(r*Math.sin(a)).toFloat());val h=stroke*2.2f
            drawPath(androidx.compose.ui.graphics.Path().apply{moveTo(tip.x+h,tip.y-h*0.2f);lineTo(tip.x-h*0.2f,tip.y-h*1.2f);lineTo(tip.x-h*0.3f,tip.y+h*0.6f);close()},color)
        }
    }
}
fun accessAge(time:Long?,now:Long=System.currentTimeMillis()):String {
    if(time==null)return "위치 기록 없음"
    val seconds=((now-time).coerceAtLeast(0)/1000)
    return when{seconds<60->"방금 전";seconds<3600->"${seconds/60}분 전";seconds<86400->"${seconds/3600}시간 전";else->"${seconds/86400}일 전"}
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun HistoryScreen(m:UiModel,hide:(String)->Unit,open:()->Unit,viewChanged:(HistoryView)->Unit,select:(String)->Unit={},refresh:()->Unit={}) {
    val refreshing=m.rechecking||m.checkingRegistered||m.loadingApps||m.busy||m.reapplyHistoryLoading
    val reapply=m.historyView==HistoryView.REAPPLY
    val canRefresh=!refreshing&&(reapply||m.canOperate)
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Row(verticalAlignment=Alignment.CenterVertically){
            Text(if(reapply)"재적용 기록" else "최근 위치 사용",Modifier.weight(1f),style=MaterialTheme.typography.headlineMedium)
            RefreshButton(refreshing,canRefresh,refresh)
        }
        if(!reapply)Text(if(m.historyView==HistoryView.APPS)"최근 위치를 쓴 앱이에요. 시스템 서비스까지 보려면 ‘전체 위치 사용’을 누르세요." else "시스템 서비스까지 포함한 위치 사용 기록이에요. 파란 위치 표시가 뜬 것과는 다를 수 있어요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        val viewRow=rememberLazyListState();LaunchedEffect(m.historyView){viewRow.animateScrollToItem(HistoryView.entries.indexOf(m.historyView))}
        LazyRow(state=viewRow,horizontalArrangement=Arrangement.spacedBy(8.dp)){items(HistoryView.entries){view->FilterChip(selected=m.historyView==view,onClick={viewChanged(view)},label={Text(view.label)})}}
        // With automatic management the app connects by itself when the tab opens; only manual users must connect first.
        if(!reapply&&!m.connected&&!m.automaticReady){Text("현재 기록을 확인하려면 무선 디버깅으로 연결해 주세요.");TextButton(onClick=open){Text("연결 설정 열기")}}
        // One progress line only: the app-wide "앱 상태 확인 중…" bar already covers a re-check, and the pull spinner
        // slides back once released (the header button keeps spinning), so no second bar or spinner over the list.
        // isRefreshing stays false, so the pull state is sent back explicitly (it would otherwise stay at the threshold).
        if(refreshing&&!m.rechecking)LinearProgressIndicator(Modifier.fillMaxWidth())
        // R-4: one app (search by name) and/or failures only; kept while switching tabs.
        var reapplyQuery by rememberSaveable{mutableStateOf("")};var failedOnly by rememberSaveable{mutableStateOf(false)}
        fun reapplyLabel(item:ReapplyHistoryItem)=if(item.label==item.packageName)m.entries.lastOrNull{it.identity.packageName==item.packageName}?.label
            ?:m.displayApps.firstOrNull{it.packageName==item.packageName}?.label?:item.label else item.label
        if(reapply&&m.reapplyHistory.isNotEmpty())Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            val searchHeight=(32f+20f*LocalDensity.current.fontScale).coerceAtLeast(52f).dp
            OutlinedTextField(reapplyQuery,{reapplyQuery=it},modifier=Modifier.weight(1f).height(searchHeight),singleLine=true,placeholder={Text("앱 검색",style=MaterialTheme.typography.bodyMedium)},textStyle=MaterialTheme.typography.bodyMedium,shape=RoundedCornerShape(14.dp))
            FilterChip(selected=failedOnly,onClick={failedOnly=!failedOnly},label={Text("실패만")})
        }
        val pull=androidx.compose.material3.pulltorefresh.rememberPullToRefreshState();val pullScope=rememberCoroutineScope()
        PullToRefreshBox(isRefreshing=false,onRefresh={if(canRefresh)refresh();pullScope.launch{pull.animateToHidden()}},modifier=Modifier.weight(1f),state=pull) {
        LazyColumn(Modifier.fillMaxSize()){
            if(reapply){
                val q=reapplyQuery.trim()
                val rows=m.reapplyHistory.filter{(!failedOnly||!it.success)&&(q.isEmpty()||reapplyLabel(it).contains(q,true)||it.packageName.contains(q,true))}
                if(m.reapplyHistory.isEmpty())item("reapply-empty"){Text("아직 재적용 기록이 없어요.",Modifier.fillMaxWidth().padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                else if(rows.isEmpty())item("reapply-none"){Text("조건에 맞는 기록이 없어요.",Modifier.fillMaxWidth().padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
                items(rows.sortedByDescending{it.at},key={it.id}){item->
                    val label=reapplyLabel(item)
                    // Like the location tab: a registered app opens its detail page (Back returns here).
                    val registered=m.entries.any{it.enabled&&it.identity.packageName==item.packageName}
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled=registered&&!m.busy,onClickLabel=UiText.text(LocalContext.current,"앱 정보 보기")){select(item.packageName)}.padding(vertical=14.dp,horizontal=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                        Text(label,translate=false,style=MaterialTheme.typography.titleSmall)
                        Text("${UiText.text(LocalContext.current,if(item.success)"재적용 완료" else "재적용 실패")} · ${UiText.text(LocalContext.current,item.source.label)} · ${UiText.text(LocalContext.current,accessAge(item.at))}",style=MaterialTheme.typography.bodySmall,color=if(item.success)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))
                }
            }else{
            if(m.historyApps.isEmpty()&&!m.loadingApps)item(key="location-empty"){Text(if(m.historyAvailable)"최근 위치 사용 기록이 없어요." else "사용 기록을 확인하지 못했어요.",Modifier.fillMaxWidth().padding(vertical=24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(m.historyApps,key={it.packageName}){a->
            val status=m.historyStatus(a)
            val applied=m.entries.any{it.enabled&&it.identity.packageName==a.packageName&&it.state==TargetState.APPLIED&&it.settlingVerified}
            val blue by animateFloatAsState(if(applied)0f else 1f,label="history dot")
            val dotColor=if(a.indicatorHint==FineIndicatorHint.SENSITIVE&&HistoryPolicy.relevant(a,emptySet()))DotBlue else MaterialTheme.colorScheme.onSurfaceVariant
            val needs=m.entries.any{it.identity.packageName==a.packageName&&it.enabled&&it.state in listOf(TargetState.NEEDS_REAPPLY_UPDATE,TargetState.NEEDS_REAPPLY_BOOT,TargetState.NEEDS_REAPPLY_POLICY)}
            // The whole row opens the app's detail page; the ripple shows the tap.
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled=!m.busy,onClickLabel=UiText.text(LocalContext.current,"앱 정보 보기")){select(a.packageName)}.padding(vertical=14.dp,horizontal=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Box(Modifier.size(18.dp),contentAlignment=Alignment.Center){Box(Modifier.size(8.dp).alpha(blue).background(dotColor,CircleShape));if(applied)Text("✓",color=MaterialTheme.colorScheme.primary)}
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
                    Text(a.label,translate=false,style=MaterialTheme.typography.titleSmall)
                    Text("${UiText.text(LocalContext.current,accessAge(a.access.latest))} 위치를 사용했어요 · ${UiText.text(LocalContext.current,if(a.access.fine!=null&&a.access.fine==a.access.latest)"정확한 위치" else "대략적인 위치")}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(applied&&m.connected&&a.packageName in m.freshPackages)"bluedotOFF 적용 중 ✓" else status,style=MaterialTheme.typography.bodySmall,color=if(applied)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(m.canActFromHistory(a)&&!applied)TextButton(onClick={hide(a.packageName)},enabled=m.canOperate&&!m.busy){Text(if(needs)"재적용" else "숨기기")}
                else Text("›",color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))
            }
            }
        }
        }
    }
}
@Composable private fun DetailScreen(m:UiModel,hide:(String)->Unit,restore:(String)->Unit,open:()->Unit) {
    val e=m.entries.lastOrNull{it.identity.packageName==m.selected}
    val s=m.candidates.firstOrNull{it.identity.packageName==m.selected}
    val pkg=m.selected ?: ""
    val grouped=e?.groupIdentities?.isNotEmpty()==true
    val changedSinceVerification=e?.state==TargetState.APPLIED&&e.enabled&&s!=null&&s.bit!=0&&pkg in m.freshPackages&&!m.loadingDetail
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top=20.dp,bottom=60.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        AppIcon(pkg,s?.label?:e?.label?:"앱",72)
        Text(s?.label?:e?.label?:"앱 정보를 확인해 주세요",translate=s==null&&e==null,style=MaterialTheme.typography.headlineLarge)
        Text(if(changedSinceVerification)"재적용 필요" else e?.let{m.status(it)}?:"bluedotOFF 미적용",style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
        // Locked states (interrupted or unconfirmed work) cannot be fixed in the app yet (BACKLOG B-1). The original
        // value is kept in the restore record, so the one thing that must not happen is clearing data or uninstalling.
        if(e?.enabled==true&&e.state in listOf(TargetState.NEEDS_REVIEW,TargetState.FAILED_ROLLBACK))Text("안전을 위해 이 앱은 더 바꾸지 않도록 잠가 두었어요. 원래 상태 기록은 그대로 있어요. 앱 데이터를 지우거나 bluedotOFF를 삭제하지 말고, 설정 → 진단 정보 복사 및 보내기로 알려주세요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(e?.error?.isNotBlank()==true){val recoverable=e.state==TargetState.FAILED_SAFE;Surface(shape=RoundedCornerShape(16.dp),color=if(recoverable)MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.errorContainer){Text(UiText.error(e.error),Modifier.padding(16.dp),color=if(recoverable)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer)}}
        HorizontalDivider()
        Text("위치 권한",style=MaterialTheme.typography.labelLarge)
        Text(if(s==null)"기기를 연결하면 현재 상태를 확인할 수 있어요." else if(grouped&&!s.fineDeclared)"이 앱은 위치 권한을 직접 요청하지 않고, 묶음의 표시 설정을 함께 써요." else if(!s.fineGranted)"정확한 위치 허용 안 됨" else if(s.backgroundGranted)"정확한 위치 · 항상 허용" else "정확한 위치 · 사용 중 허용",style=MaterialTheme.typography.bodyLarge)
        if(e!=null)Text("마지막 확인  ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(e.lastVerifiedTime))}",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
        if(e?.originalBit==0)Text("등록할 때부터 표시가 꺼져 있던 앱이에요. 복원해도 꺼진 상태 그대로예요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(grouped)Text("공유 앱 묶음 · 함께 숨기고 함께 복원해요.",style=MaterialTheme.typography.bodyMedium)
        val blocked=s?.takeIf{!it.safe()&&!grouped}?.blockReason()
        if(blocked!=null)Text(blocked,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(!m.canOperate)Primary("연결 설정 열기",open)
        else if(e?.state in listOf(TargetState.FAILED_ROLLBACK,TargetState.SHARED_UID_CHANGED,TargetState.NEEDS_REVIEW))Text("안전한지 다시 확인해야 해서 더 이상 바꾸지 않았어요. 도움말의 복구 안내를 확인해 주세요.",color=MaterialTheme.colorScheme.error)
        else {
            if(m.loadingDetail)LinearProgressIndicator(Modifier.fillMaxWidth())
            val verifiedApplied=e?.let{it.enabled&&it.state==TargetState.APPLIED&&it.settlingVerified}==true&&!changedSinceVerification
            Primary(if(verifiedApplied)"적용 중" else if(e?.state==TargetState.INACTIVE_INSTALL)"새로 등록 · bluedotOFF 적용" else if(e?.enabled==true)"bluedotOFF 재적용" else "bluedotOFF 적용",{hide(pkg)},enabled=s!=null&&blocked==null&&pkg.isNotBlank()&&!m.loadingDetail&&!verifiedApplied)
            if(e?.enabled==true)OutlinedButton(onClick={restore(pkg)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),enabled=s!=null&&blocked==null&&!m.loadingDetail){Text(if(grouped)"묶음 원래 상태로 복원" else "숨김 해제 · 원래 상태로 복원")}
        }
        Text("위치 권한이나 앱의 사용 기록은 바꾸지 않아요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        var advanced by remember { mutableStateOf(false) }
        TextButton(onClick={advanced=!advanced}){Text(if(advanced)"상세 정보 접기" else "고급 정보")}
        if(advanced)Text("Package: $pkg\nUID: ${s?.identity?.uid?:e?.identity?.uid?:"미확인"}\n${m.compatibility}\n원본 표시 bit: ${e?.originalBit?:"등록 전"}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ReapplyScreen(m:UiModel,apply:()->Unit,open:()->Unit,select:(String)->Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top=22.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text("숨김 다시 적용",style=MaterialTheme.typography.headlineLarge)
        Text("등록 앱을 하나씩 다시 확인해요. 바꿀 수 없는 앱은 건너뛰고, 결과는 위쪽에 보여드려요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        m.registeredDisplayEntries.filter{it.enabled}.forEach { e->AppRow(e.identity.packageName,e.label,m.status(e),{select(e.identity.packageName)}) }
        Text(if(m.connected)"기기 연결 확인됨" else "기기 연결 필요",style=MaterialTheme.typography.titleSmall)
        if(m.canOperate)Primary("모두 재적용",apply,enabled=m.entries.any{it.enabled})else Primary("연결 설정 열기",open)
        Text("백그라운드 감시를 쓰지 않으면 재부팅이나 업데이트 때 저절로 설정을 바꾸지는 않아요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
    PanelAlert(onDismissRequest=close,title={Text("오픈소스 라이선스")},confirmButton={DialogAction(onClick=close){Text("닫기")}},text={
        Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("bluedotOFF는 아래 오픈소스를 사용해요. AndroidX와 Kotlin 표준 라이브러리도 Apache License 2.0으로 배포돼요.",style=MaterialTheme.typography.bodySmall)
            items.forEach{(name,license,body)->
                TextButton(onClick={open=if(open==name)null else name}){Text("$name · $license",translate=false)}
                if(open==name)Text(body,translate=false,style=MaterialTheme.typography.bodySmall.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace))
            }
        }
    })
}

@Composable private fun SettingsScreen(m:UiModel,notificationChanged:(Boolean)->Unit,open:()->Unit,refresh:()->Unit,help:()->Unit,wirelessExit:()->Unit,managementChanged:(Boolean)->Unit,removeManagementPermission:()->Unit,scopeChange:()->Unit,openShow:()->Unit={},beforeUninstall:()->Unit={},copyDiagnostics:()->Unit={},autoReapplyChanged:(Boolean)->Unit={},backgroundWatchChanged:(Boolean)->Unit={},openDeveloperOptions:()->Unit={},openBattery:(Boolean)->Unit={},updateCheckChanged:(Boolean)->Unit={},checkUpdateNow:()->Unit={},openUpdate:(String)->Unit={}) {
    var uninstallConfirm by remember{mutableStateOf(false)}
    val toRestore=m.entries.count{it.enabled&&it.state!=TargetState.MISSING_TARGET}
    if(uninstallConfirm)PanelAlert(onDismissRequest={uninstallConfirm=false},title={Text("앱 삭제 전 복원")},
        text={Text("등록한 ${toRestore}개 앱을 모두 원래 표시 상태로 되돌려요. 끝나면 bluedotOFF를 삭제해도 되는지 알려드려요.")},
        confirmButton={DialogAction(onClick={uninstallConfirm=false;beforeUninstall()}){Text("복원 시작")}},dismissButton={DialogCancel(onClick={uninstallConfirm=false}){Text("취소")}})
    val context=LocalContext.current
    var autoConsent by remember{mutableStateOf(false)}
    var watchConsent by remember{mutableStateOf(false)}
    var watchOwnsConnection by remember{mutableStateOf(false)}
    if(watchOwnsConnection)PanelAlert(onDismissRequest={watchOwnsConnection=false},title={Text("백그라운드 감시를 쓰는 중이에요")},
        text={Text("백그라운드 감시를 쓰는 동안에는 USB 디버깅으로 연결해요. 자동 관리는 재부팅이나 업데이트 뒤 감시를 다시 켤 때만 써요. 바꾸려면 백그라운드 감시를 먼저 꺼주세요.")},
        confirmButton={DialogAction(onClick={watchOwnsConnection=false}){Text("확인")}})
    if(watchConsent)WatchRiskDialog({watchConsent=false;backgroundWatchChanged(true)},{watchConsent=false})
    var advancedSettings by rememberSaveable{mutableStateOf(false)}
    var showLicenses by rememberSaveable{mutableStateOf(false)}
    if(showLicenses)OpenSourceLicenses{showLicenses=false}
    if(autoConsent)PanelAlert(onDismissRequest={autoConsent=false},title={Text("무선 디버깅 자동 관리")},text={Text("bluedotOFF를 쓰는 동안만 무선 디버깅을 켜 두고, 앱을 나가면 잠시 후 꺼요. 이를 위해 시스템 설정 변경 권한을 처음 한 번 받고, 무선 디버깅을 켜고 끄는 데만 써요. 다른 무선 디버깅 연결도 함께 끊길 수 있어요.")},confirmButton={DialogAction(onClick={autoConsent=false;managementChanged(true)}){Text("자동 관리 사용")}},dismissButton={DialogCancel(onClick={autoConsent=false}){Text("취소")}})
    @Composable fun LinkRow(label:String,detail:String="",onClick:()->Unit){
        Row(Modifier.fillMaxWidth().heightIn(min=52.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick=onClick).padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
            Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
            if(detail.isNotEmpty())Text(detail,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,translate=false)
            else Text("›",Modifier.padding(start=12.dp),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,translate=false)
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top=16.dp,bottom=48.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
        Text("설정",style=MaterialTheme.typography.headlineLarge)
        // One line when both fit; otherwise the scope goes under the title instead of squeezing it (long languages).
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.spacedBy(4.dp)){
            Text("현재 적용 범위",Modifier.align(Alignment.CenterVertically).padding(end=12.dp),style=MaterialTheme.typography.titleMedium)
            // The scope name itself opens the scope choice (owner request 1.1.4; was a separate scope-change button).
            Text(m.applicationScope.title,Modifier.align(Alignment.CenterVertically).heightIn(min=48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(enabled=!m.busy,role=androidx.compose.ui.semantics.Role.Button,onClick=scopeChange).wrapContentHeight(Alignment.CenterVertically).padding(start=8.dp),
                style=MaterialTheme.typography.bodyLarge,color=if(m.busy)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        }
        Text(m.applicationScope.description,style=MaterialTheme.typography.bodySmall)
        Text(if(m.applicationScope==ApplicationScope.NORMAL)"일반 적용을 누르면 적용 범위를 변경할 수 있어요." else "전체 적용을 누르면 적용 범위를 변경할 수 있어요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(m.scopePending)Text("범위 변경이 완료되지 않았어요. 제외되는 앱을 복원해야 범위를 낮출 수 있어요.",color=MaterialTheme.colorScheme.error)
        HorizontalDivider()
        Row(Modifier.toggleable(value=m.autoManagement,enabled=!m.busy||(m.autoManagement&&!m.autoReapplying),role=androidx.compose.ui.semantics.Role.Switch,onValueChange={if(m.backgroundWatch)watchOwnsConnection=true else if(it)autoConsent=true else managementChanged(false)}),verticalAlignment=Alignment.CenterVertically){Text("무선 디버깅 자동 관리",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Switch(checked=m.autoManagement,onCheckedChange=null,enabled=(!m.busy||m.autoManagement)&&!m.backgroundWatch)}
        Text(when{!m.backgroundWatch->"앱을 사용하는 동안 연결을 유지하고, 앱을 나가면 잠시 후 무선 디버깅을 꺼요.";m.autoManagement->"백그라운드 감시를 쓰는 동안에는 평소에 무선 디버깅을 켜지 않아요. 재부팅이나 업데이트 뒤 감시를 다시 켤 때만 잠깐 켜요.";else->"백그라운드 감시를 다시 켜려면 Wi-Fi에 연결하고 무선 디버깅을 직접 켠 뒤 앱을 열어주세요."},style=MaterialTheme.typography.bodySmall)
        if(!m.autoManagement&&!m.backgroundWatch){
            Text("작업이 끝나면 무선 디버깅을 꺼두세요. 적용 상태와 복원 기록은 유지돼요.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=wirelessExit,enabled=!m.busy){Text("무선 디버깅 끄기")}
        }
        if(!m.canOperate)OutlinedButton(onClick=open){Text("연결 설정 열기")}
        HorizontalDivider()
        // Third section (owner request): the first switch-on shows the risks with a 5-second wait.
        Row(Modifier.toggleable(value=m.backgroundWatch,enabled=!m.busy,role=androidx.compose.ui.semantics.Role.Switch,onValueChange={if(it&&!m.watchAcknowledged)watchConsent=true else backgroundWatchChanged(it)}),verticalAlignment=Alignment.CenterVertically){Text("백그라운드 감시",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Switch(checked=m.backgroundWatch,onCheckedChange=null,enabled=!m.busy)}
        Text("USB 디버깅을 켜 두면 bluedotOFF를 열지 않아도 숨김이 풀린 앱을 감지하는 대로 다시 적용해요.",style=MaterialTheme.typography.bodySmall)
        if(m.backgroundWatch&&!m.usbDebugging)TextButton(onClick=openDeveloperOptions){Text("USB 디버깅 켜러 가기")}
        HorizontalDivider()
        val batteryOn=m.batteryUnrestricted==true
        Row(Modifier.toggleable(value=batteryOn,enabled=!m.busy&&m.batteryUnrestricted!=null,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=openBattery),verticalAlignment=Alignment.CenterVertically){Text(BatteryOptimization.TITLE,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Switch(checked=batteryOn,onCheckedChange=null,enabled=!m.busy&&m.batteryUnrestricted!=null)}
        Text(BatteryOptimization.DESCRIPTION,style=MaterialTheme.typography.bodySmall)
        Text("끄려면 앱 정보 → 배터리에서 ‘최적화’를 선택해주세요.",style=MaterialTheme.typography.bodySmall)
        if(m.batteryUnrestricted==null)TextButton(onClick={openBattery(false)},enabled=!m.busy){Text("배터리 설정 상태를 확인할 수 없어요. 설정 열기")}
        HorizontalDivider()
        val notifyOn=m.notifications&&m.reapplyNotify
        Row(Modifier.toggleable(value=notifyOn,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=notificationChanged),verticalAlignment=Alignment.CenterVertically){Text("재적용 알림",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Switch(checked=notifyOn,onCheckedChange=null)}
        Text("휴대폰 재시작이나 앱 업데이트로 숨김이 풀리면 알려드려요. 절전 모드에서는 알림이 오지 않을 수 있어요.",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Row(Modifier.toggleable(value=m.autoReapply,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=autoReapplyChanged),verticalAlignment=Alignment.CenterVertically){Text("앱을 열 때 자동 재적용",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);Switch(checked=m.autoReapply,onCheckedChange=null)}
        Text("숨김이 풀린 앱을 앱을 열 때 자동으로 다시 적용해요. 무선 디버깅 자동 관리나 백그라운드 감시를 켠 경우에 동작해요.",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Row(Modifier.toggleable(value=m.updateCheck,role=androidx.compose.ui.semantics.Role.Switch,onValueChange=updateCheckChanged),verticalAlignment=Alignment.CenterVertically){
            // Check now: the refresh icon next to the title (owner request 1.1.4; was a text button).
            Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically){Text("업데이트 확인",Modifier.weight(1f,fill=false),style=MaterialTheme.typography.titleMedium);if(m.updateCheck)RefreshButton(m.updateChecking,true,checkUpdateNow,"지금 확인")}
            Switch(checked=m.updateCheck,onCheckedChange=null)}
        Text("켜면 하루에 한 번 GitHub에서 새 버전이 있는지만 확인해요. 다운로드나 설치는 하지 않아요. 끄면 bluedotOFF는 인터넷을 쓰지 않아요.",style=MaterialTheme.typography.bodySmall)
        if(m.updateCheck){
            val latest=m.latestVersion
            Text(if(latest==null)"현재 버전 ${BuildConfig.VERSION_NAME} · 최신 버전은 아직 확인 전" else "현재 버전 ${BuildConfig.VERSION_NAME} · 최신 버전 $latest",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(latest!=null&&UpdatePolicy.newer(latest,BuildConfig.VERSION_NAME))TextButton(onClick={openUpdate(latest)}){Text("출시 페이지 열기")}
            if(m.updateStatus.isNotEmpty()&&!m.updateChecking)Text(m.updateStatus,style=MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        // Only needed after bluedotOFF was removed and installed again (its records go with it).
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
            LinkRow("숨김이 남은 앱 (재설치 시에만)",onClick=openShow)
            Text("bluedotOFF를 지웠다가 다시 설치했을 때만 필요해요. 예전에 숨긴 앱이 기록 없이 숨겨진 채 남아 있으면 여기서 숨김을 풀 수 있어요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        // Uninstalling or clearing data does not restore anything; this is the one step to take first.
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
            LinkRow("앱 삭제 전 복원"){if(toRestore==0)beforeUninstall() else uninstallConfirm=true}
            Text("bluedotOFF를 지우기 전에 눌러주세요. 숨긴 앱을 모두 원래 상태로 되돌려요. 앱 삭제나 데이터 초기화만으로는 숨김이 풀리지 않아요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Column(verticalArrangement=Arrangement.spacedBy(2.dp)){
            Text("정보 및 지원",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
            LanguageRow()
            LinkRow("도움말 · 문제 해결",onClick=help)
            LinkRow("진단 정보 복사 및 보내기",onClick=copyDiagnostics)
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
        Text("시작하기 → 알림 선택 → 개발자 옵션 → Wi-Fi → 근처 기기 권한 → 무선 디버깅 → 코드 입력 → 기기와 앱 확인 → 연결 유지 방법 선택 → 적용 범위 → 최종 기기 확인 → 홈",style=MaterialTheme.typography.bodyMedium)
        Text("USB 디버깅으로 유지를 고르면, 적용 범위 선택 전에 배터리 사용 제한 해제를 선택할 수 있어요. ‘나중에’를 눌러 건너뛰어도 돼요.",style=MaterialTheme.typography.bodySmall)
        GuideStep.entries.filter{it in OnboardingGuide.exampleSteps}.forEach{GuideCard(GuideSelection(it))}
        GuideCard(GuideSelection(GuideStep.CODE_INPUT,true))
        Primary("연결 설정 열기",open);OutlinedButton(onClick=install,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)){Text("공식 연결 설정 열기")}
        HorizontalDivider()
        Text("적용이 안 될 때",style=MaterialTheme.typography.titleMedium)
        Text("기기 연결과 정확한 위치 권한을 확인하세요. 다른 앱과 위치 설정을 공유하는 앱과 핵심 시스템 구성 요소는 개별 적용할 수 없어요. 기본 설치 앱도 조건을 통과하면 선택할 수 있어요. 기기마다 호환성을 검사하며, 일부 지역이나 소프트웨어 버전은 지원되지 않을 수 있어요. 업데이트 후에는 다시 확인해요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("위치 설정을 공유하는 앱은 구성원 목록을 확인하고 묶음 전체를 선택할 수 있어요. 핵심 시스템 앱은 전체 적용에서 선택할 수 있어요. 묶음 전체의 설치 정보와 위치 권한 상태를 확인하지 못하면 변경하지 않아요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("보안 위험 자동 차단이 켜져 있으면 Android가 무선 디버깅을 막아 연결할 수 없어요. 작업할 때는 설정 → 보안 및 개인정보 보호에서 꺼주세요. ‘자동으로 켜기’를 켜두면 30분 뒤 다시 켜져요. 이때는 USB 디버깅도 꺼져서 백그라운드 감시도 멈춰요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("재적용 알림은 bluedotOFF가 완전히 종료돼 있는 동안 업데이트된 앱은 바로 알려드리지 못해요. 다음에 앱을 열면 확인해서 보여드려요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("백그라운드 감시",style=MaterialTheme.typography.titleMedium)
        Text("설정에서 켜면 USB 디버깅으로 연결을 유지해서, bluedotOFF를 열지 않아도 숨김이 풀린 앱을 감지하는 대로 다시 숨겨요. USB 디버깅을 계속 켜 둬야 하고, 일부 은행·결제 앱은 이때 실행되지 않을 수 있어요.")
        Text("휴대폰을 재시작하거나 bluedotOFF를 업데이트하거나 USB 디버깅을 끄면 감시가 멈추고 알림과 홈에서 알려드려요. USB 디버깅을 다시 켜고 Wi-Fi에 연결된 상태에서 bluedotOFF를 열면 다시 시작해요(자동 관리가 꺼져 있으면 무선 디버깅도 켜주세요). 처음 연결할 때처럼 코드를 다시 넣을 필요는 없어요.")
        Text("휴대폰을 재시작한 경우에는 자동 관리와 앱을 열 때 자동 재적용이 켜져 있으면, Wi-Fi에 연결될 때 감시를 다시 켜고 풀린 숨김도 다시 적용해요.")
        Text("은행 앱이 개발자 옵션 자체를 검사하면 USB 디버깅만 꺼서는 안 될 수 있어요. 그럴 때는 개발자 옵션을 잠시 끄고, 다시 쓸 때 개발자 옵션과 USB 디버깅을 켠 뒤 Wi-Fi에서 bluedotOFF를 열어주세요.")
        Text("복원 확인이 필요할 때",style=MaterialTheme.typography.titleMedium)
        Text("실패 기록을 지우려고 앱 데이터를 초기화하거나 앱을 삭제하지 마세요. 기기를 연결해 현재 상태를 읽은 뒤, GitHub의 bluedotOFF 저장소 Issues에 문의해 주세요. 상태가 확실하지 않은 앱은 bluedotOFF가 스스로 다시 바꾸지 않아요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("bluedotOFF를 지웠다가 다시 설치했을 때",style=MaterialTheme.typography.titleMedium)
        Text("앱을 지우면 원래 상태 기록도 함께 지워져서, 숨겨 둔 앱이 숨겨진 채로 남을 수 있어요. 이런 앱은 설정 → ‘숨김이 남은 앱’에서 숨김을 풀 수 있어요. 휴대폰을 재시작하면 표시가 돌아오기도 하지만 앱마다 달라요. 지우기 전에는 설정 → ‘앱 삭제 전 복원’을 눌러주세요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("위치 사용 기록",style=MaterialTheme.typography.titleMedium)
        Text("앱 기록은 최근 위치를 쓴 앱 목록이에요. ‘전체 위치 사용’에서는 시스템 서비스도 볼 수 있어요. 삼성 설정의 권한 사용 기록과는 출처가 달라요. 숨김을 적용해도 위치 사용 기록은 남고, 기록이 있다고 파란 위치 표시가 떴다는 뜻은 아니에요.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("루팅이나 부트로더 해제는 필요 없어요. 휴대폰의 무선 디버깅 기능(백그라운드 감시를 켜면 USB 디버깅도)만 쓰고, 앱의 위치 권한과 보안 설정은 바꾸지 않아요. 네트워크 권한은 휴대폰 안의 무선 디버깅 연결과, 직접 켠 경우의 업데이트 확인(GitHub)에만 써요. 광고·분석·추적 기능은 없어요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
private val LocalBottomActions=staticCompositionLocalOf<(Int)->Unit>{{}}
/** Marks a page's pinned bottom buttons; their height lifts notices above them. */
@Composable private fun Modifier.bottomActions():Modifier{val report=LocalBottomActions.current;DisposableEffect(report){onDispose{report(0)}};return onSizeChanged{report(it.height)}}
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

/** Home's "상태 확인 필요": the apps behind the number. Tapping one opens its detail, which explains the state. */
@Composable private fun CheckNeededDialog(m:UiModel,close:()->Unit,open:(String)->Unit){
    PanelAlert(onDismissRequest=close,title={Text("상태 확인이 필요한 앱")},
        text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){
            Text("앱을 누르면 상태와 할 수 있는 일을 볼 수 있어요.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            m.checkEntries.forEach{e->
                Column(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable{open(e.identity.packageName)}.padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
                    Text(e.label,translate=false,style=MaterialTheme.typography.titleSmall)
                    Text(m.status(e),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=0.35f))
            }
        }},
        confirmButton={DialogAction(onClick=close){Text("닫기")}})
}

/** App language (Android 13+ per-app language; the same list appears in Android Settings > Apps > bluedotOFF > Language).
 *  Android recreates the screen in the chosen language. Names are shown in their own language. */
private val APP_LANGUAGES=listOf("" to "시스템 기본값","en" to "English","ko" to "한국어","es" to "Español","pt-BR" to "Português (Brasil)","hi" to "हिन्दी","zh-Hant" to "繁體中文","zh-Hans" to "简体中文")
@Composable private fun LanguageRow(){
    val context=LocalContext.current
    if(android.os.Build.VERSION.SDK_INT<33)return
    val manager=context.getSystemService(android.app.LocaleManager::class.java)?:return
    var open by remember{mutableStateOf(false)}
    val current=manager.applicationLocales.toLanguageTags()
    val currentName=APP_LANGUAGES.firstOrNull{it.first.equals(current,true)}?.second?:current
    Row(Modifier.fillMaxWidth().heightIn(min=52.dp).clip(RoundedCornerShape(12.dp)).clickable{open=true}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
        Text("언어",Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
        Text(currentName,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,translate=current.isEmpty())
    }
    if(open)PanelAlert(onDismissRequest={open=false},title={Text("언어")},
        text={Column(Modifier.verticalScroll(rememberScrollState())){APP_LANGUAGES.forEach{(tag,name)->
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).clickable{
                open=false
                manager.applicationLocales=if(tag.isEmpty())android.os.LocaleList.getEmptyLocaleList() else android.os.LocaleList.forLanguageTags(tag)
            },verticalAlignment=Alignment.CenterVertically){
                RadioButton(selected=tag.equals(current,true),onClick=null)
                Text(name,Modifier.padding(start=12.dp),style=MaterialTheme.typography.bodyLarge,translate=tag.isEmpty())
            }
        }}},
        confirmButton={DialogAction(onClick={open=false}){Text("닫기")}})
}
