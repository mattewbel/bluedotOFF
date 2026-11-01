package app.bluedotoff

import android.content.Context

/** Three short answers at the top of Help, for the way the person connects (owner + GPT 2026-10-28): what they
 *  understood during setup is easy to forget days later. Same facts as the longer Help topics below them. */
object UsageGuide {
    fun caption(pcOnly:Boolean)=if(pcOnly)"PC로 시작해서 쓰는 경우의 안내예요." else "Wi-Fi로 연결해서 쓰는 경우의 안내예요."
    fun items(pcOnly:Boolean)=listOf(
        "평소에는 어떻게 쓰나요?" to if(pcOnly)"새로 숨길 앱은 홈의 ‘앱 추가’로 넣어요. PC로 시작한 백그라운드 감시가 켜져 있는 동안 숨김이 풀린 앱을 감지하는 대로 다시 숨겨요."
            else "새로 숨길 앱은 홈의 ‘앱 추가’로 넣어요. 백그라운드 감시를 켜 두면 숨김이 풀린 앱을 감지하는 대로 다시 숨기고, 꺼져 있으면 홈의 ‘다시 적용할 앱’ 줄에서 다시 숨길 수 있어요.",
        "재부팅하면 무엇을 해야 하나요?" to if(pcOnly)"휴대폰을 재시작하거나 bluedotOFF를 업데이트하면 감시가 멈춰요. PC에 연결해 시작 명령을 다시 실행해 주세요."
            else "백그라운드 감시, 자동 관리, ‘앱을 열 때 자동 재적용’이 모두 켜져 있으면 Wi-Fi에 연결될 때 감시를 다시 켜고 풀린 숨김도 다시 적용해요. 그렇지 않으면 Wi-Fi에 연결된 상태에서 bluedotOFF를 열어주세요.",
        "은행 앱이 안 열리면 어떻게 하나요?" to if(pcOnly)"USB 디버깅이나 개발자 옵션이 켜져 있으면 일부 은행·결제 앱이 열리지 않을 수 있어요. 잠시 USB 디버깅을 끄고, 그래도 안 되면 개발자 옵션을 꺼주세요. 다시 쓸 때는 개발자 옵션과 USB 디버깅을 켠 뒤 PC에서 시작 명령을 다시 실행해 주세요."
            else "USB 디버깅이나 개발자 옵션이 켜져 있으면 일부 은행·결제 앱이 열리지 않을 수 있어요. 잠시 USB 디버깅을 끄고, 그래도 안 되면 개발자 옵션을 꺼주세요. 다시 쓸 때는 개발자 옵션을 켠 뒤 Wi-Fi에서 bluedotOFF를 열어주세요. 백그라운드 감시를 쓰려면 USB 디버깅도 켜주세요.")
    /** Plain text for the share sheet, e.g. to send it to oneself. */
    fun text(c:Context,pcOnly:Boolean)=(listOf("bluedotOFF · "+UiText.text(c,caption(pcOnly)))+items(pcOnly).map{(q,a)->UiText.text(c,q)+"\n"+UiText.text(c,a)}).joinToString("\n\n")
}
