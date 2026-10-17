package app.bluedotoff

import android.content.Context
import org.json.JSONObject

/** Presentation-only translation. Journal values, predicates, package labels and IPC stay intact. */
object UiText {
    /** Catalogs in assets/i18n, keyed by the Korean source sentence. Korean shows the source itself. */
    val LANGUAGES=listOf("en","es","pt-BR","hi","zh-Hant","zh-Hans")
    private val catalogs=HashMap<String,Pair<Map<String,String>,List<Pair<Regex,String>>>>()
    /** Catalog for a locale: null = Korean (source text). Unsupported languages use English (owner decision 2026-10-17). */
    fun catalogFor(locale:java.util.Locale):String?=when(locale.language){
        "ko"->null
        "es"->"es"
        "pt"->"pt-BR"
        "hi"->"hi"
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
        "이전 빌드의 기록은 지속성 검사가 없어 새 적용 확인이 필요합니다."->"숨김 유지 여부를 다시 확인해야 해요. 재적용을 눌러주세요."
        else->if(value.isBlank()||value.any{it in '가'..'힣'})value else GENERIC_ERROR
    }
}
