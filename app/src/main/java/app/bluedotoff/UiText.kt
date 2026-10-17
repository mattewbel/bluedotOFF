package app.bluedotoff

import android.content.Context
import org.json.JSONObject

/** Presentation-only translation. Journal values, predicates, package labels and IPC stay intact. */
object UiText {
    private var catalog:Map<String,String>?=null
    private var patterns:List<Pair<Regex,String>> = emptyList()
    @Synchronized private fun load(context:Context):Map<String,String> {
        catalog?.let{return it}
        val j=JSONObject(context.assets.open("i18n/en.json").bufferedReader(Charsets.UTF_8).use{it.readText()})
        val map=j.keys().asSequence().associateWith{j.getString(it)}
        patterns=map.filterKeys{it.contains("{0}")}.map{(source,target)->
            val pieces=source.split(Regex("\\{[0-9]+\\}"))
            Regex(pieces.joinToString("(.+?)"){Regex.escape(it)},RegexOption.DOT_MATCHES_ALL) to target
        }
        catalog=map;return map
    }
    fun text(context:Context,value:String):String {
        if(context.resources.configuration.locales[0].language=="ko" || value.none{it in '가'..'힣'})return value
        val map=load(context)
        map[value]?.let{return it}
        for((pattern,target)in patterns){
            val match=pattern.matchEntire(value)?:continue
            return Regex("\\{([0-9]+)\\}").replace(target){token->match.groupValues[token.groupValues[1].toInt()+1]}
        }
        // Unknown diagnostics remain verbatim; never hide a real error with a success message.
        return value
    }
    const val GENERIC_ERROR="작업을 마치지 못했어요. 기기 연결을 확인한 뒤 다시 시도해주세요."
    /** Internal English diagnostics (helper codes, exception text) become plain guidance. Korean product messages pass through. */
    fun error(value:String):String=when(value){
        // Journal diagnostics from older builds stay intact on disk; only their presentation changes.
        "안전성 확인에 실패해 원래 표시 상태로 복원했습니다."->"변경 후 상태를 확인하지 못해 원래 표시 상태로 되돌렸어요. 다시 확인해 주세요."
        "현재 상태의 지속성을 확인하지 못했습니다. 다시 확인해 주세요."->"표시 상태가 유지되는지 확인하지 못했어요. 다시 확인해 주세요."
        "이전 빌드의 기록은 지속성 검사가 없어 새 적용 확인이 필요합니다."->"숨김 유지 여부를 다시 확인해야 해요. 재적용을 눌러주세요."
        else->if(value.isBlank()||value.any{it in '가'..'힣'})value else GENERIC_ERROR
    }
}
