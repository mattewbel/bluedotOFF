package app.bluedotoff

import android.content.Context
import app.bluedotoff.core.Entry

/** Display-only acknowledgement; the durable journal remains the source of the archived explanation. */
object LocationRetirementNotice {
    fun take(context:Context,entries:List<Entry>):String? {
        val prefs=context.getSharedPreferences("location-retirement-notices",Context.MODE_PRIVATE)
        val seen=prefs.getStringSet("seen",emptySet()).orEmpty()
        fun key(e:Entry)=BinderBackend.digest(e.identity.toString().toByteArray())
        val fresh=entries.filter{it.locationRetired&&!it.enabled&&key(it) !in seen}
        if(fresh.isEmpty())return null
        if(!prefs.edit().putStringSet("seen",seen+fresh.map(::key)).commit())return null
        runCatching{ReapplyNotification.clear(context)}
        return if(fresh.size==1)"${fresh.single().label}: 업데이트 후 위치 권한을 요청하지 않아 관리 목록에서 제외했어요."
            else "업데이트 후 위치 권한을 요청하지 않는 앱 ${fresh.size}개를 관리 목록에서 제외했어요."
    }
}
