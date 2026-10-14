package app.bluedotoff

import android.content.Context
import app.bluedotoff.core.BatchResult
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

enum class ReapplySource(val label:String) {
    BACKGROUND("백그라운드 감시"), APP_OPEN("앱을 열 때"), MANUAL("직접 재적용")
}
data class ReapplyHistoryItem(val id:String,val packageName:String,val label:String,val at:Long,val success:Boolean,val source:ReapplySource)

/** Display-only recent results, separate from the durable restore journal. Never controls a permission write.
 * Logging faults cannot turn a verified operation into a failure or trigger a rollback. */
class ReapplyHistoryStore(private val context:Context) {
    companion object {
        const val MAX_ITEMS=200
        private val lock=Any()
        internal fun legacy(lines:List<String>):List<ReapplyHistoryItem> {
            val time=SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).apply{isLenient=false}
            val pattern=Regex("""^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d) \| result \| pkg=([A-Za-z0-9_.]+) mode=(watch|auto) ok=(true|false)(?: .*|)$""")
            return lines.mapNotNull{line->pattern.matchEntire(line)?.destructured?.let{(date,pkg,mode,ok)->
                runCatching{ReapplyHistoryItem("legacy:"+BinderBackend.digest(line.toByteArray()),pkg,pkg,time.parse(date)!!.time,ok=="true",if(mode=="watch")ReapplySource.BACKGROUND else ReapplySource.APP_OPEN)}.getOrNull()
            }}.distinctBy{it.id}.sortedByDescending{it.at}.take(MAX_ITEMS)
        }
        private fun encode(row:ReapplyHistoryItem)=JSONObject().put("id",row.id).put("package",row.packageName).put("label",row.label)
            .put("at",row.at).put("success",row.success).put("source",row.source.name).toString()
        private fun decode(text:String):ReapplyHistoryItem?=runCatching {
            val json=JSONObject(text)
            val row=ReapplyHistoryItem(json.getString("id"),json.getString("package"),json.getString("label"),json.getLong("at"),json.getBoolean("success"),ReapplySource.valueOf(json.getString("source")))
            require(row.id.isNotBlank()&&row.packageName.matches(Regex("[A-Za-z0-9_.]+"))&&row.at>0)
            row
        }.getOrNull()
    }
    private val file get()=File(context.filesDir,"reapply-history.jsonl")
    private fun read():List<ReapplyHistoryItem> {
        if(!file.exists()) {
            // Retain this test build's already collected automatic results on the first upgrade.
            val old=File(context.filesDir,"test-log.txt")
            val imported=if(old.isFile)legacy(old.readLines()) else emptyList()
            file.parentFile?.mkdirs()
            file.writeText(imported.joinToString("\n",postfix=if(imported.isEmpty())"" else "\n"){encode(it)})
            return imported
        }
        return file.useLines{lines->lines.mapNotNull(::decode).toList()}.distinctBy{it.id}.sortedByDescending{it.at}.take(MAX_ITEMS)
    }
    fun entries():List<ReapplyHistoryItem> = synchronized(lock){read()}
    fun record(result:BatchResult,source:ReapplySource,at:Long=System.currentTimeMillis()) {
        synchronized(lock){runCatching {
            val previous=read()
            val row=ReapplyHistoryItem(UUID.randomUUID().toString(),result.packageName,result.entry?.label?:result.packageName,at,result.success,source)
            if(previous.size>=MAX_ITEMS||file.length()>256_000) {
                val retained=(listOf(row)+previous).take(MAX_ITEMS)
                file.writeText(retained.joinToString("\n",postfix="\n"){encode(it)})
            }else {
                // A crash can leave a partial last line. Separate it before appending the next valid result.
                if(file.length()>0&&java.io.RandomAccessFile(file,"r").use{it.seek(it.length()-1);it.read()!=10})file.appendText("\n")
                file.appendText(encode(row)+"\n")
            }
        }.onFailure{android.util.Log.w("bluedotOFF","Reapply display history could not be saved")}}
    }
}
