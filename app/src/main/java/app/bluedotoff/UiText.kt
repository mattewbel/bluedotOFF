package app.bluedotoff

import android.content.Context
import org.json.JSONObject

/** Presentation-only translation. Journal values, predicates, package labels and IPC stay intact. */
object UiText {
    /** Catalogs in assets/i18n, keyed by the Korean source sentence. Korean shows the source itself. */
    val LANGUAGES=listOf("en","es","pt-BR","hi","zh-Hant","zh-Hans","ja","de","id")
    private val catalogs=HashMap<String,Pair<Map<String,String>,List<Pair<Regex,String>>>>()
    /** Catalog for a locale: null = Korean (source text). Unsupported languages use English (owner decision 2026-10-17). */
    fun catalogFor(locale:java.util.Locale):String?=when(locale.language){
        "ko"->null
        "es"->"es"
        "pt"->"pt-BR"
        "hi"->"hi"
        "ja"->"ja"
        "de"->"de"
        // Android still reports Indonesian with the legacy code "in".
        "id","in"->"id"
        "zh"->if(locale.script=="Hant"||locale.script.isEmpty()&&locale.country in setOf("TW","HK","MO"))"zh-Hant" else "zh-Hans"
        else->"en"
    }
    @Synchronized private fun load(context:Context,name:String):Pair<Map<String,String>,List<Pair<Regex,String>>> = catalogs.getOrPut(name){
        val text=runCatching{context.assets.open("i18n/$name.json").bufferedReader(Charsets.UTF_8).use{it.readText()}}.getOrNull()
        val map=text?.let{val j=JSONObject(it);j.keys().asSequence().associateWith{k->j.getString(k)}}?:emptyMap()
        val patterns=map.filterKeys{it.contains("{0}")}.map{(source,target)->
            val pieces=source.split(Regex("\\{[0-9]+\\}"))
            Regex(pieces.joinToString("(.+?)"){Regex.escape(it)},RegexOption.DOT_MATCHES_ALL) to target
        }
        map to patterns
    }
    private fun lookup(context:Context,name:String,value:String):String? {
        val (map,patterns)=load(context,name)
        map[value]?.let{return it}
        for((pattern,target)in patterns){
            val match=pattern.matchEntire(value)?:continue
            return Regex("\\{([0-9]+)\\}").replace(target){token->match.groupValues[token.groupValues[1].toInt()+1]}
        }
        return null
    }
    fun text(context:Context,value:String):String {
        val name=catalogFor(context.resources.configuration.locales[0])
        if(name==null||value.none{it in '가'..'힣'})return value
        // A sentence missing in a new catalog falls back to English, then to the Korean source.
        // Unknown diagnostics remain verbatim; never hide a real error with a success message.
        return lookup(context,name,value)?:(if(name!="en")lookup(context,"en",value) else null)?:value
    }
    const val GENERIC_ERROR="작업을 마치지 못했어요. 기기 연결을 확인한 뒤 다시 시도해주세요."
    /** Internal English diagnostics (helper codes, exception text) become plain guidance. Korean product messages pass through. */
    fun error(value:String):String=when(value){
        // Journal diagnostics from older builds stay intact on disk; only their presentation changes.
        "안전성 확인에 실패해 원래 표시 상태로 복원했습니다."->"변경 후 상태를 확인하지 못해 원래 표시 상태로 되돌렸어요. 다시 확인해 주세요."
        "현재 상태의 지속성을 확인하지 못했습니다. 다시 확인해 주세요."->"표시 상태가 유지되는지 확인하지 못했어요. 다시 확인해 주세요."
        "이전 빌드의 기록은 지속성 검사가 없어 새 적용 확인이 필요합니다."->"숨김이 유지되는지 다시 확인해야 해요."
        "중단된 적용의 지속성을 확인하지 못했습니다. 직접 다시 확인해 주세요."->"숨김이 유지되는지 다시 확인해야 해요."
        // 1.2.2-1.3.0 rc: the stored text named a button the detail screen may not offer (no connection); it offers what fits.
        "숨김 유지 여부를 다시 확인해야 해요. 재적용을 눌러주세요."->"숨김이 유지되는지 다시 확인해야 해요."
        else->RENAMED_130[value]?:RENAMED_130[value.removeSuffix(" 변경된 내용은 없습니다.")]?.let{"$it 아무것도 바꾸지 않았어요."}?:if(value.isBlank()||value.any{it in '가'..'힣'})value else GENERIC_ERROR
    }
    /** 1.3.0 wording review (plain words, GPT-checked): records and history written by older builds keep the old sentence. */
    private val RENAMED_130=mapOf(
        "새 설치입니다. 과거 기록은 적용하지 않습니다." to "앱이 새로 설치됐어요. 예전 기록은 쓰지 않아요.",
        "중단된 작업의 안전성을 확인하지 못했습니다. 추가 변경을 차단했습니다." to "작업이 중간에 끊긴 뒤 상태를 확인하지 못해서, 안전을 위해 이 앱은 더 바꾸지 않아요.",
        "복원을 확인하지 못했습니다. 추가 변경을 차단했습니다." to "원래대로 되돌렸는지 확인하지 못했어요. 안전을 위해 이 앱은 더 바꾸지 않아요.",
        "기기 업데이트 후 중단된 작업의 안전성을 다시 확인해야 합니다." to "휴대폰 업데이트 전에 끊긴 작업이 있어요. 이 앱의 상태를 다시 확인해야 해요.",
        "권한 상태와 이전 안전성 기록이 일치하지 않습니다. 직접 확인해 주세요." to "앱의 권한이나 상태가 등록할 때 기록과 맞지 않아요. 직접 확인해 주세요.",
        "적용 직전 앱 상태가 바뀌어 변경하지 않았습니다." to "바꾸기 직전에 앱 상태가 바뀌어서 그대로 두었어요. 상태를 확인한 뒤 다시 시도해 주세요.",
        "화면을 떠나 남은 앱은 변경하지 않았습니다. 완료된 적용과 등록 기록은 유지됩니다." to "화면을 떠나서 남은 앱은 바꾸지 않았어요. 이미 끝난 적용과 등록 기록은 그대로예요.",
        "작업 결과를 확인하지 못했습니다. 등록 기록을 유지했습니다." to "작업 결과를 확인하지 못했어요. 등록 기록은 그대로예요.",
        "적용 직전 앱 상태가 바뀌어 변경을 멈췄습니다." to "바꾸기 직전에 앱 상태가 바뀌어서 멈췄어요. 다시 시도해 주세요.",
        "이 앱과 묶인 앱 구성이 바뀌어 변경을 멈췄습니다." to "이 앱과 묶인 앱이 바뀌어서 안전을 위해 멈췄어요.",
        "복원 기록 저장을 확인하지 못했습니다. 추가 변경을 중지했습니다." to "원래 상태 기록이 저장됐는지 확인하지 못해서 더 바꾸지 않았어요.",
        "이 기기에서는 안전한 적용을 확인할 수 없습니다. 변경된 내용은 없습니다." to "이 휴대폰에서는 안전하게 바꿀 수 있는지 확인할 수 없어요. 아무것도 바꾸지 않았어요.",
        "복원 상태를 확인할 수 없어 추가 변경을 차단했습니다." to "원래 상태를 확인할 수 없어서, 안전을 위해 이 앱은 바꾸지 않았어요.",
        "앱 설치 정보가 바뀌었습니다. 상세 화면에서 새로 등록해 주세요." to "앱이 새로 설치됐어요. 앱 상세 화면에서 새로 등록해 주세요.",
        "중단된 작업의 복원 확인이 필요합니다." to "끊긴 작업이 있어서 먼저 이 앱의 상태를 확인해야 해요.",
        "현재 앱 권한 상태에서 안전한 복원을 확인할 수 없습니다. 변경하지 않았습니다." to "지금 권한 상태로는 안전하게 되돌릴 수 있는지 확인할 수 없어서 바꾸지 않았어요.",
        "복원할 등록 기록이 없습니다." to "되돌릴 기록이 없어요.",
        "표시 설정의 지속성과 안전성 확인 실패" to "바꾼 뒤 표시 설정이 유지되는지 확인하지 못했어요.",
        "공유 앱 묶음 쓰기를 지원하지 않는 연결입니다." to "지금 연결 방식으로는 묶음 앱을 바꿀 수 없어요.",
        "이 앱은 공유 앱 묶음으로 함께 변경해야 합니다." to "이 앱은 묶음 앱이라 묶음 전체를 함께 바꿔야 해요.",
        "앞선 작업의 복원 또는 연결 확인이 필요해 변경하지 않았습니다." to "앞선 작업의 복원이나 연결을 먼저 확인해야 해서 바꾸지 않았어요.",
        "정확한 위치 권한이 허용된 앱이 묶음에 없습니다." to "이 묶음에는 ‘정확한 위치’를 허용한 앱이 없어요.",
        "공유 앱 묶음의 크기를 확인할 수 없습니다." to "묶음에 든 앱을 모두 확인하지 못했어요.",
        "핵심 시스템 또는 설치 정보가 불확실한 앱이 묶음에 포함돼 변경할 수 없습니다." to "묶음에 핵심 시스템 앱이나 정보를 확인할 수 없는 앱이 있어서 바꿀 수 없어요.",
        "공유 앱의 위치 표시 설정이 서로 달라 변경하지 않았습니다." to "묶음 앱끼리 표시 설정이 서로 달라서 바꾸지 않았어요.",
        "공유 앱의 위치 사용 상태를 확인하지 못했습니다." to "묶음 앱의 위치 사용 상태를 확인하지 못했어요.",
        "공유 앱의 위치 권한 상태를 안전하게 확인하지 못했습니다." to "묶음 앱의 위치 권한 상태를 확인하지 못했어요.",
        "기기 호환성을 확인하지 못했습니다." to "이 휴대폰에서 쓸 수 있는지 확인하지 못했어요.",
        "묶음 일부에만 등록 기록이 있어 변경하지 않았습니다." to "묶음 앱 중 일부만 기록이 있어서 바꾸지 않았어요.",
        "복원할 공유 앱 묶음 기록이 없습니다." to "되돌릴 묶음 기록이 없어요.",
        "공유 앱 묶음의 복원 확인이 필요합니다." to "먼저 묶음 앱의 상태를 확인해야 해요.",
        "공유 앱의 원래 표시 기록이 서로 달라 변경하지 않았습니다." to "묶음 앱끼리 원래 상태 기록이 달라서 바꾸지 않았어요.",
        "적용 직전 공유 앱 상태가 바뀌었습니다." to "바꾸기 직전에 묶음 앱 상태가 바뀌어서 멈췄어요.",
        "화면을 떠나 공유 앱 묶음은 변경하지 않았습니다." to "화면을 떠나서 묶음 앱은 바꾸지 않았어요.",
        "공유 앱 묶음의 지속성 확인에 실패했습니다." to "묶음 앱의 표시 설정이 유지되는지 확인하지 못했어요.",
        "중단된 공유 앱 묶음의 상태가 기록과 달라 변경하지 않았습니다." to "끊긴 묶음 작업 뒤 상태가 기록과 달라서 바꾸지 않았어요.",
        "중단된 공유 앱 묶음을 원래대로 되돌렸는지 확인하지 못했습니다. 추가 변경을 차단했습니다." to "끊긴 묶음 작업을 원래대로 되돌렸는지 확인하지 못했어요. 안전을 위해 더 바꾸지 않아요.",
        "공유 앱 묶음의 전체 설치 정보를 확인하지 못했습니다." to "묶음 앱의 설치 정보를 모두 확인하지 못했어요.",
        "공유 앱 묶음의 설치 정보가 바뀌었습니다." to "묶음 앱 중 새로 설치된 앱이 있어요.",
        "공유 앱의 쓰기 직전 상태가 바뀌었습니다." to "바꾸기 직전에 묶음 앱 상태가 바뀌어서 멈췄어요.",
        "Android 핵심 시스템 구성 요소는 지원하지 않습니다." to "Android 핵심 시스템 앱이라 숨길 수 없어요.",
        "사용자 0의 앱만 지원합니다." to "기본 사용자 앱만 숨길 수 있어요. (보안 폴더·두 번째 사용자 앱 제외)",
        "지원하지 않는 특수 시스템 앱입니다." to "특수 시스템 앱이라 숨길 수 없어요.",
        "상시 실행되는 핵심 시스템 구성 요소는 지원하지 않습니다." to "항상 실행되는 핵심 시스템 앱이라 숨길 수 없어요.",
        "핵심 OS 패키지는 지원하지 않습니다." to "Android 핵심 시스템 앱이라 숨길 수 없어요.",
        "이 앱은 다른 앱과 위치 설정을 공유하므로 따로 적용할 수 없습니다." to "다른 앱과 위치 설정을 함께 써서 이 앱만 숨길 수 없어요.",
        "이 앱과 묶인 앱을 모두 확인하지 못했습니다." to "이 앱과 묶인 앱을 모두 확인하지 못했어요.",
        "이 앱은 오래된 방식으로 다른 앱과 묶여 있습니다. 이 기기에서는 아직 안전성이 확인되지 않았습니다." to "다른 앱과 오래된 방식으로 묶여 있어서, 이 휴대폰에서 안전하게 바꿀 수 있는지 아직 확인되지 않았어요.",
        "앱 설치 정보 또는 서명을 확인하지 못했습니다." to "앱의 설치 정보를 확인하지 못했어요.",
        "정확한 위치 권한이 허용된 앱만 적용할 수 있습니다." to "‘정확한 위치’를 허용한 앱만 숨길 수 있어요.",
        "현재 위치 사용 상태를 안전하게 확인할 수 없습니다." to "이 앱의 위치 사용 상태를 확인하지 못했어요.",
        "앱 목록 크기 또는 형식 확인 실패" to "앱 목록을 읽지 못했어요. 잠시 뒤 다시 확인해 주세요.",
        "앱 목록이 바뀌었습니다. 다시 확인해 주세요." to "앱 목록이 바뀌었어요. 다시 확인해 주세요.",
        "앱 목록이 변경되었습니다. 다시 확인해 주세요." to "앱 목록이 바뀌었어요. 다시 확인해 주세요.",
        "앱 정보 확인 불가" to "앱 정보를 읽지 못했어요.",
        "앱 정보를 확인하지 못했습니다." to "앱 정보를 확인하지 못했어요.",
        "사용 기록 형식을 확인하지 못했습니다." to "위치 사용 기록을 읽지 못했어요.",
        "앱 묶음 정보를 확인하지 못했습니다." to "묶음 앱 정보를 확인하지 못했어요.",
        "서명 조회 불가" to "앱 정보를 읽지 못했어요.",
        "복원 기록 형식을 확인할 수 없습니다. 변경을 차단했습니다." to "원래 상태 기록을 읽지 못해서, 안전을 위해 바꾸지 않아요.",
        "복원 기록이 손상되어 변경을 차단했습니다." to "원래 상태 기록이 망가져서, 안전을 위해 바꾸지 않아요.",
        "복원 기록 저장 확인 실패" to "원래 상태 기록이 저장됐는지 확인하지 못했어요."
    )
}
