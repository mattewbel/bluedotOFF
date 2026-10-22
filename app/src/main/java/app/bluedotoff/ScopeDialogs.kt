package app.bluedotoff

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.bluedotoff.core.ApplicationScope
import kotlinx.coroutines.delay

/** Presentation shared by native onboarding and Compose settings; no reads or writes. */
object ScopeDialogs {
    fun steps(activity:ComponentActivity,steps:List<app.bluedotoff.core.SetupStepStatus>):AutoCloseable =
        show(activity,1f){close->
            Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface){
                Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                    Text(UiText.text(activity,"연결 준비 단계"),style=MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState())){
                        steps.forEach{step->
                            Row(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                                Text("${step.number}",Modifier.width(24.dp),color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.labelLarge)
                                Text(UiText.text(activity,step.label),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                                Text(UiText.text(activity,when{step.current->"진행 중";step.complete->"완료";else->"아직 안 함"}),style=MaterialTheme.typography.labelMedium,color=if(step.current)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Button(onClick=close,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(BlueDotAppearance.BUTTON_RADIUS.dp)){Text(UiText.text(activity,"닫기"))}
                }
            }
        }
    fun choose(activity:ComponentActivity,initial:ApplicationScope,firstSetup:Boolean=false,tint:Float=1f,onChosen:(ApplicationScope)->Unit):AutoCloseable =
        show(activity,tint){close->
            var level by rememberSaveable{mutableIntStateOf(ApplicationScope.fromStoredLevel(initial.level).level)}
            val scope=ApplicationScope.fromLevel(level)
            ScopePanel("적용 범위",if(firstSetup)"이 범위로 시작" else "다음",close,{close();onChosen(scope)}){
                Copy("원하는 적용 범위를 선택하세요.")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()){
                    ApplicationScope.choices.forEachIndexed{index,item->
                        SegmentedButton(selected=level==item.level,onClick={level=item.level},
                            shape=SegmentedButtonDefaults.itemShape(index,2),icon={}){Copy(item.title)}
                    }
                }
                ScopeDetail(scope)
                Copy("선택한 앱에만 적용해요. 나중에 설정에서 바꿀 수 있어요.",small=true)
            }
        }

    /** The confirmation stays visible while the read-only target preview loads. */
    fun lowering(activity:ComponentActivity,scope:ApplicationScope,tint:Float=1f,
        load:(((List<Pair<String,String>>?,String)->Unit)->Unit),onConfirm:()->Unit):AutoCloseable =
        show(activity,tint){close->
            var targets by remember{mutableStateOf<List<Pair<String,String>>?>(null)}
            var error by remember{mutableStateOf("")}
            var expanded by rememberSaveable{mutableStateOf(false)}
            LaunchedEffect(Unit){load{items,message->targets=items;error=message}}
            ScopePanel("범위를 낮출까요?",if(targets?.isNotEmpty()==true)"복원 후 범위 변경" else "이 범위 사용",
                close,{close();onConfirm()},enabled=targets!=null&&error.isBlank()){
                ScopeDetail(scope)
                when {
                    error.isNotBlank()->Copy(error)
                    // A spinner shows the read-only check of registered apps is still running.
                    targets==null->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp);Copy("등록 앱의 적용 범위를 확인하고 있어요.",small=true)
                    }
                    targets!!.isEmpty()->Copy("원복할 등록 앱이 없어요. 앱의 표시 설정은 그대로 유지해요.",small=true)
                    else->{
                        Copy("등록한 앱 중 ${targets!!.size}개가 새 범위에서 빠져서 원래 상태로 돌아가요.")
                        Copy("모두 복원되면 범위를 바꿔요. 실패하면 현재 범위를 유지해요.",small=true)
                        TextButton(onClick={expanded=!expanded}){Copy(if(expanded)"대상 앱 접기" else "복원 대상 ${targets!!.size}개 보기")}
                        if(expanded)targets!!.forEach{(label,pkg)->HorizontalDivider();Text(label,style=MaterialTheme.typography.bodyMedium);Text(pkg,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                }
            }
        }

    fun confirm(activity:ComponentActivity,scope:ApplicationScope,targets:List<Pair<String,String>> = emptyList(),tint:Float=1f,onConfirm:()->Unit):AutoCloseable =
        show(activity,tint){close->
            var expanded by rememberSaveable{mutableStateOf(false)}
            ScopePanel(if(targets.isEmpty())"이 범위를 사용할까요?" else "범위를 낮출까요?",
                if(targets.isNotEmpty())"복원 후 범위 변경" else "이 범위 사용",close,{close();onConfirm()}){
                ScopeDetail(scope)
                if(targets.isEmpty())Copy("범위 선택만으로 앱을 변경하지 않아요. 앱 추가에서 직접 선택해주세요.",small=true)
                else{
                    Copy("제외되는 ${targets.size}개 앱을 먼저 원래 표시 상태로 복원해요.")
                    Copy("모두 복원되면 범위를 바꿔요. 실패하면 현재 범위를 유지해요.",small=true)
                    TextButton(onClick={expanded=!expanded}){Copy(if(expanded)"대상 앱 접기" else "복원 대상 ${targets.size}개 보기")}
                    if(expanded)Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(8.dp)){
                        targets.forEach{(label,pkg)->
                            HorizontalDivider()
                            Text(label,style=MaterialTheme.typography.bodyMedium)
                            Text(pkg,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

    /** Setup step 9: the same risk notice as Settings. The PC start asks in its own words, in this one notice instead
     *  of two in a row (owner 2026-10-21). */
    fun watch(activity:ComponentActivity,title:String=WATCH_TITLE,intro:String=WATCH_INTRO,action:String="켜기",onConfirm:()->Unit):AutoCloseable =
        show(activity,1f){close->WatchRiskPanel(close,{close();onConfirm()},title,intro,action)}

    /** A notice for the native setup screen: the first action is the main button, the others text buttons below it;
     *  every action closes the panel first. */
    fun alert(activity:ComponentActivity,title:String,body:String,vararg actions:Pair<String,()->Unit>,onClose:()->Unit={},
        extra:(@Composable ()->Unit)?=null):AutoCloseable =
        show(activity,1f,onClose){close->
            PanelLayout({Text(UiText.text(activity,title))},{DialogAction({close();actions[0].second()}){Text(UiText.text(activity,actions[0].first))}},
                {actions.drop(1).forEach{(label,run)->DialogCancel({close();run()}){Text(UiText.text(activity,label))}}}){
                Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){Copy(body);extra?.invoke()}
            }
        }
    /** 1.2.1, Home and Settings: starting the watch again from a PC; the same steps as the setup screen. */
    fun pcStart(activity:ComponentActivity):AutoCloseable {
        val pc=app.bluedotoff.adb.PcStart
        if(!pc.prepare(activity))return alert(activity,"PC로 시작하는 방법","시작 명령 파일을 만들지 못했어요. 휴대폰 저장공간을 확인한 뒤 이 화면을 다시 열어주세요.","닫기" to {})
        // Two buttons only (owner 2026-10-21): copy and help sit in the text, next to what they act on.
        return alert(activity,"PC로 시작하는 방법","Wi-Fi 없이 백그라운드 감시를 다시 시작하려면 PC에서 아래 순서대로 해주세요.",
            "PC로 안내 보내기" to {pc.share(activity,pc.mac(activity))},"닫기" to {},extra={
                var mac by remember{mutableStateOf(pc.mac(activity))}
                Copy("사용할 PC를 고르세요",small=true)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    listOf("Windows" to false,"Mac" to true).forEach{(label,isMac)->
                        FilterChip(selected=mac==isMac,onClick={pc.setMac(activity,isMac);mac=isMac},label={Text(label)})
                    }
                }
                pc.steps(mac).forEachIndexed{i,step->Text("${i+1}. ${UiText.text(activity,step)}",style=MaterialTheme.typography.bodyMedium)}
                androidx.compose.foundation.text.selection.SelectionContainer{Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh){
                    Text(pc.command(mac),Modifier.padding(16.dp),fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace,style=MaterialTheme.typography.bodyMedium)}}
                Row{
                    TextButton(onClick={pc.copy(activity,mac)}){Text(UiText.text(activity,"명령 복사"))}
                    TextButton(onClick={pcHelp(activity)}){Text(UiText.text(activity,"명령이 안 될 때"))}
                }
                Copy(pc.AGAIN,small=true)
            })
    }
    /** Short help for the usual adb messages on the PC (Codex U1). */
    fun pcHelp(activity:ComponentActivity):AutoCloseable =
        alert(activity,"명령이 안 될 때","PC 명령 창에 나온 문구에 맞는 항목을 확인하세요.","닫기" to {},extra={app.bluedotoff.adb.PcStart.HELP.forEach{Copy(it)}})
    /** Setup: Auto Blocker cannot be detected there, so the same two-picture guide as the Home dialog. */
    fun autoBlocker(activity:ComponentActivity):AutoCloseable =
        alert(activity,"보안 위험 자동 차단을 꺼주세요","이 기능이 켜져 있으면 휴대폰이 무선 디버깅을 막아서 bluedotOFF가 연결할 수 없어요. 아래 순서대로 끈 뒤 다시 시도해주세요.",
            "보안 설정 열기" to {SettingsEntrypoints.autoBlocker(activity)},"닫기" to {},extra={
                listOf(app.bluedotoff.core.GuideStep.AUTO_BLOCKER,app.bluedotoff.core.GuideStep.AUTO_BLOCKER_OFF).forEach{step->
                    val guide=GuideContent.load(activity,app.bluedotoff.core.GuideSelection(step))
                    Text("${step.number}  ${guide.title}",style=MaterialTheme.typography.titleSmall)
                    AndroidView({GuideArtwork(it,app.bluedotoff.core.GuideSelection(step))},Modifier.fillMaxWidth())
                    Copy(guide.body)
                }
            })

    private fun show(activity:ComponentActivity,tint:Float,onClose:()->Unit={},content:@Composable (()->Unit)->Unit):AutoCloseable {
        // Attach to the existing activity so lifecycle, saved state and the
        // native setup screen's window policy are inherited by Dialog.
        val view=ComposeView(activity)
        var closed=false
        lateinit var observer:DefaultLifecycleObserver
        val close={
            if(!closed){closed=true;activity.lifecycle.removeObserver(observer);(view.parent as? ViewGroup)?.removeView(view);view.disposeComposition();onClose()}
        }
        observer=object:DefaultLifecycleObserver{override fun onDestroy(owner:LifecycleOwner){close()}}
        activity.lifecycle.addObserver(observer)
        view.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        view.setContent{
            MaterialTheme(colorScheme=BlueDotAppearance.scheme(activity,tint=tint)){
                Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)){
                    Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center){
                        Box(Modifier.widthIn(max=560.dp).fillMaxWidth().heightIn(max=(activity.resources.configuration.screenHeightDp*0.82f).dp)){
                            content(close)
                        }
                    }
                }
            }
        }
        activity.addContentView(view,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
        return AutoCloseable{close()}
    }
}

@Composable private fun Copy(value:String,small:Boolean=false){
    Text(UiText.text(LocalContext.current,value),style=if(small)MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
        color=if(small)MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
}

/** "백그라운드 감시" risks; the confirm button unlocks after a visible 5-second wait. */
private const val WATCH_TITLE="백그라운드 감시를 켤까요?"
private const val WATCH_INTRO="켜기 전에 꼭 읽어주세요."
@Composable private fun WatchRiskPanel(cancel:()->Unit,confirm:()->Unit,title:String=WATCH_TITLE,intro:String=WATCH_INTRO,action:String="켜기"){
    var left by remember{mutableStateOf(5)}
    LaunchedEffect(Unit){while(left>0){delay(1000);left--}}
    ScopePanel(title,if(left>0)"$action (${left}초)" else action,cancel,confirm,enabled=left==0){
        Copy(intro)
        BackgroundWatch.RISKS.forEach{risk->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("•");Copy(risk)}}
        LinearProgressIndicator(progress={(5-left)/5f},modifier=Modifier.fillMaxWidth())
    }
}

@Composable private fun PanelDialog(dismiss:()->Unit,content:@Composable ()->Unit){
    val context=LocalContext.current
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center){
            Box(Modifier.widthIn(max=560.dp).fillMaxWidth().heightIn(max=(context.resources.configuration.screenHeightDp*0.82f).dp)){content()}
        }
    }
}

/** The one dialog look of the app (owner 2026-10-18): title, content, a full-width main button, text buttons below. */
@Composable internal fun PanelLayout(title:@Composable ()->Unit,confirm:@Composable ()->Unit,dismiss:(@Composable ()->Unit)?=null,text:@Composable ()->Unit){
    Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.onSurface){
        Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            ProvideTextStyle(MaterialTheme.typography.titleLarge,title)
            // Long content scrolls inside; the buttons stay visible below it.
            Box(Modifier.weight(1f,fill=false)){ProvideTextStyle(MaterialTheme.typography.bodyMedium,text)}
            confirm()
            dismiss?.invoke()
        }
    }
}
@Composable internal fun DialogAction(onClick:()->Unit,enabled:Boolean=true,content:@Composable RowScope.()->Unit)=
    Button(onClick=onClick,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(BlueDotAppearance.BUTTON_RADIUS.dp),content=content)
@Composable internal fun DialogCancel(onClick:()->Unit,content:@Composable RowScope.()->Unit)=
    TextButton(onClick=onClick,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.onSurface),content=content)
/** Same slots as Material's AlertDialog, drawn as [PanelLayout]. */
@Composable internal fun PanelAlert(onDismissRequest:()->Unit,confirmButton:@Composable ()->Unit,dismissButton:(@Composable ()->Unit)?=null,
    title:@Composable ()->Unit,text:@Composable ()->Unit)=PanelDialog(onDismissRequest){PanelLayout(title,confirmButton,dismissButton,text)}

@Composable internal fun WatchRiskDialog(confirm:()->Unit,dismiss:()->Unit)=PanelDialog(dismiss){WatchRiskPanel(dismiss,confirm)}

/** Opt-in update check (1.1.2): asked once, default off. Closing the question counts as "off". */
@Composable internal fun UpdateOfferDialog(answer:(Boolean)->Unit)=PanelDialog({answer(false)}){
    ScopePanel("새 버전이 나오면 알려드릴까요?","켜기",{answer(false)},{answer(true)},cancelLabel="끄기"){
        Copy("켜면 하루에 한 번 GitHub에서 최신 버전 번호만 확인해요. 다운로드나 설치는 하지 않아요. 끄면 bluedotOFF는 인터넷을 쓰지 않아요. 나중에 설정에서 바꿀 수 있어요.")
    }
}

@Composable internal fun RestoreAllDialog(count:Int,cancel:()->Unit,confirm:()->Unit){
    val context=LocalContext.current
    Dialog(onDismissRequest=cancel,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center){
            Box(Modifier.widthIn(max=560.dp).fillMaxWidth().heightIn(max=(context.resources.configuration.screenHeightDp*0.82f).dp)){
                ScopePanel("모두 원래 상태로 복원","모두 복원",cancel,confirm){
                    Copy("등록한 ${count}개 앱을 원래 표시 상태로 복원할까요?")
                    Copy("공유 앱은 묶음 전체를 함께 복원해요. 설치 정보를 확인할 수 없는 앱은 변경하지 않아요.",small=true)
                }
            }
        }
    }
}

@Composable internal fun ReapplyNeededDialog(model:UiModel,cancel:()->Unit,confirm:(List<String>)->Unit,open:()->Unit){
    val context=LocalContext.current
    val packages=model.reapplyTargetPackages
    val grouped=model.reapplyEntries.any{model.selectionGroup(it.identity.packageName).size>1}
    Dialog(onDismissRequest=cancel,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Box(Modifier.fillMaxWidth().padding(24.dp),contentAlignment=Alignment.Center){
            Box(Modifier.widthIn(max=560.dp).fillMaxWidth().heightIn(max=(context.resources.configuration.screenHeightDp*0.82f).dp)){
                ScopePanel("재적용이 필요한 앱",if(model.canOperate)"${packages.size}개 앱 재적용" else "연결 설정 열기",cancel,
                    {if(model.canOperate)confirm(packages)else open()},enabled=packages.isNotEmpty()&&!model.busy){
                    Copy("아래 앱의 현재 상태를 확인한 뒤 bluedotOFF를 다시 적용해요.")
                    if(grouped)Copy("위치 표시 설정을 공유하는 앱은 묶음 전체를 함께 재적용해요.",small=true)
                    packages.forEach{pkg->
                        val entry=model.entries.firstOrNull{it.identity.packageName==pkg}
                        Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
                            Text(entry?.label?:pkg,style=MaterialTheme.typography.titleSmall)
                            if(entry!=null)Copy(model.status(entry),small=true)
                        }
                        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                    }
                    if(!model.canOperate)Copy("연결 설정을 마친 뒤 재적용을 눌러주세요.",small=true)
                }
            }
        }
    }
}

@Composable private fun ScopePanel(title:String,action:String,cancel:()->Unit,confirm:()->Unit,enabled:Boolean=true,cancelLabel:String="취소",body:@Composable ColumnScope.()->Unit){
    val context=LocalContext.current
    // Only accessibility font sizes, landscape or an expanded restore list need scrolling.
    PanelLayout({Text(UiText.text(context,title))},{DialogAction(confirm,enabled){Text(UiText.text(context,action))}},{DialogCancel(cancel){Copy(cancelLabel)}}){
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp),content=body)
    }
}

@Composable private fun ScopeDetail(scope:ApplicationScope){
    val context=LocalContext.current
    val normal=scope==ApplicationScope.NORMAL
    val title=if(normal)"일반 적용" else "전체 적용"
    val description=if(normal)"위치 설정을 따로 쓰는 앱만 선택해요. 조건을 통과한 기본 설치 앱도 포함해요." else "일반 적용에 공유 앱과 핵심 시스템 앱을 더해요. 기기와 위치 권한 상태에 따라 실제 추가되는 앱은 적을 수 있어요."
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text(UiText.text(context,title),style=MaterialTheme.typography.titleMedium)
            Copy(description)
            if(!normal)Copy("공유 앱은 구성원을 확인하고 묶음 전체에 적용해요. 시스템 묶음은 다른 기능의 위치 표시에도 영향을 줄 수 있어요.",small=true)
        }
    }
}
