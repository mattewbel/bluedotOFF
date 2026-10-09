package app.bluedotoff.core

enum class GuideStep(val number:Int,val asset:String) {
    WELCOME(1,"welcome"), NOTIFICATIONS(2,"notifications"), DEVELOPER(3,"developer"), WIFI(4,"wifi"), NEARBY(5,"nearby"),
    WIRELESS(6,"wireless"), CODE_PAGE(7,"code-page"), CODE_INPUT(7,"code-input"), VERIFY(8,"verify"),
    AUTOMATIC(9,"automatic"), SCOPE(10,"scope"), FINAL(11,"final")
}
data class GuideSelection(val step:GuideStep,val manual:Boolean=false)
object OnboardingGuide {
    val exampleSteps=setOf(GuideStep.NOTIFICATIONS,GuideStep.DEVELOPER,GuideStep.WIFI,GuideStep.NEARBY,GuideStep.WIRELESS,GuideStep.CODE_PAGE,GuideStep.CODE_INPUT)
    fun current(stage:SetupStage,notifications:Boolean=true,manual:Boolean=false):GuideSelection?=when(stage){
        SetupStage.NOTIFICATION_PERMISSION->GuideSelection(GuideStep.NOTIFICATIONS)
        SetupStage.NEARBY_PERMISSION->GuideSelection(GuideStep.NEARBY)
        SetupStage.DEVELOPER_OPTIONS_OFF->GuideSelection(GuideStep.DEVELOPER)
        SetupStage.WIFI_NOT_CONNECTED->GuideSelection(GuideStep.WIFI)
        SetupStage.WIRELESS_DEBUGGING_OFF->GuideSelection(GuideStep.WIRELESS)
        SetupStage.GUIDE_PAIRING_CODE_PAGE,SetupStage.WAIT_FOR_PAIRING_ADVERTISEMENT->
            if(manual)GuideSelection(GuideStep.CODE_INPUT,true)else GuideSelection(GuideStep.CODE_PAGE)
        SetupStage.PAIRING_NOTIFICATION_READY->GuideSelection(GuideStep.CODE_INPUT,!notifications||manual)
        else->null
    }
}
