package app.bluedotoff

import android.content.Context
import android.content.Intent

/** Text handed to the phone's share sheet (1.3.0, owner 2026-10-28). bluedotOFF itself sends nothing: the person picks
 *  the app that sends it, and the link is only text. */
object ShareText {
    const val URL="https://github.com/mattewbel/bluedotOFF/releases/latest"
    const val APP="bluedotOFF: 갤럭시 One UI 9에서 내가 고른 앱만 파란 위치 표시를 숨겨요. 루팅 없이 폰의 무선 디버깅으로 설정하고, 무료 오픈소스예요."
    fun app(c:Context)=UiText.text(c,APP)+"\n"+URL
    fun send(c:Context,text:String)=runCatching{
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
