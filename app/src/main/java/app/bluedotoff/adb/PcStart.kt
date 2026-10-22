package app.bluedotoff.adb

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.io.File

/** 1.2.1 "PC로 시작", for users without Wi-Fi: one adb command from a PC starts the same resident watch helper the
 *  wireless path starts ([WatchProtocol.launch]); the app accepts it only after the usual checks ([WatchClient.attach]).
 *  No network port (`adb tcpip`), no secrets in the script. Research: research/usb-bootstrap-20261020 (Fold7 PASS). */
object PcStart {
    const val PATH="/sdcard/Android/data/app.bluedotoff/files/start.sh"
    /** Run from inside the unzipped platform-tools folder, so no PATH setup is needed (Codex U1). */
    const val WINDOWS=".\\adb shell sh $PATH"
    const val MAC="./adb shell sh $PATH"
    /** LF only (the source file may be CRLF); the APK path is looked up on the phone, so app updates need no new script. */
    internal val SCRIPT=listOf(
        "#!/system/bin/sh",
        "# bluedotOFF: starts its background watch helper. Written by the app; run it with adb shell from a PC.",
        "if [ \"\$(id -u)\" != 2000 ]; then echo 'bluedotOFF: run this with adb shell from a PC.' >&2; exit 1; fi",
        "APK=",
        "for line in \$(pm path --user 0 app.bluedotoff); do",
        "  case \"\$line\" in package:/data/app/*/base.apk) APK=\"\${line#package:}\" ;; esac",
        "done",
        "if [ -z \"\$APK\" ] || [ ! -r \"\$APK\" ]; then echo 'bluedotOFF: the app was not found on this phone.' >&2; exit 1; fi",
        "CLASSPATH=\"\$APK\" nohup setsid app_process /system/bin app.bluedotoff.adb.ResidentWatchHelper \"\$APK\" </dev/null >/dev/null 2>&1 &",
        "echo 'bluedotOFF: start requested. Check the app on the phone.'"
    ).joinToString("\n",postfix="\n")
    /** What the user does on the PC, in the order they do it, for the PC the user picked (the phone cannot tell;
     *  owner 2026-10-21); step 1 names the download as the page labels it, kept short (owner). */
    fun steps(mac:Boolean)=listOf(
        if(mac)"PC에서 developer.android.com/tools/releases/platform-tools 를 열어 ‘SDK Platform-Tools for Mac’을 내려받고 압축을 푸세요."
        else "PC에서 developer.android.com/tools/releases/platform-tools 를 열어 ‘SDK Platform-Tools for Windows’를 내려받고 압축을 푸세요.",
        "휴대폰의 개발자 옵션에서 ‘USB 디버깅’을 켜고 USB 케이블로 PC에 연결하세요. ‘USB 디버깅을 허용할까요?’ 창이 뜨면 ‘허용’을 누르세요.",
        if(mac)"‘터미널’ 앱을 여세요(Command+스페이스를 누르고 ‘터미널’ 검색). cd와 빈칸 하나를 입력한 뒤, 압축을 풀어 생긴 platform-tools 폴더를 터미널 창에 끌어다 놓고 Return을 누르세요."
        else "압축을 풀어 생긴 폴더 안의 ‘platform-tools’ 폴더를 여세요. 폴더 안 빈 곳을 마우스 오른쪽 버튼으로 눌러 ‘터미널에서 열기’를 고르세요(Windows 10은 Shift를 누른 채 오른쪽 클릭 → ‘여기에 PowerShell 창 열기’).",
        if(mac)"아래 명령을 터미널에 입력하고 Return을 누르세요. 직접 입력하기 어렵다면 ‘PC로 안내 보내기’로 메일이나 메신저에 보낸 뒤, Mac에서 복사해 붙여넣으세요."
        else "아래 명령을 명령 창에 입력하고 Enter를 누르세요. 직접 입력하기 어렵다면 ‘PC로 안내 보내기’로 메일이나 메신저에 보낸 뒤, PC에서 복사해 붙여넣으세요.")
    fun command(mac:Boolean)=if(mac)MAC else WINDOWS
    const val AGAIN="휴대폰을 다시 시작하거나 bluedotOFF를 업데이트하면 PC에서 이 명령을 다시 실행해야 해요. USB 디버깅을 껐다면 다시 켠 뒤 실행하세요. 그 전까지는 Wi-Fi 없이 앱 숨기기와 복원을 쓸 수 있어요."
    /** Shown on Home once the PC start worked (Codex U2): the cable is not needed, USB debugging is. */
    const val DONE="PC에서 시작했어요. 이제 USB 케이블은 빼도 돼요. USB 디버깅은 켜 두세요."
    /** The common adb messages and what to do; the app cannot see the PC, so the user matches the text (Codex U1). */
    val HELP=listOf(
        "‘adb’를 찾을 수 없다는 오류: platform-tools 폴더에서 명령 창을 열었는지, 명령 맨 앞의 .\\ 또는 ./까지 그대로 붙여넣었는지 확인하세요.",
        "unauthorized: 휴대폰의 ‘USB 디버깅을 허용할까요?’ 창에서 ‘허용’을 누른 뒤 다시 실행하세요. 창이 안 보이면 케이블을 뺐다가 다시 꽂으세요.",
        "no devices/emulators found: USB 디버깅이 켜져 있는지 확인하고 케이블을 다시 꽂으세요. 충전 전용 케이블로는 연결되지 않아요.",
        "more than one device/emulator: 다른 휴대폰이나 에뮬레이터를 PC에서 빼고 다시 실행하세요.",
        "명령은 끝났는데 휴대폰 화면이 넘어가지 않으면: bluedotOFF의 백그라운드 감시가 켜져 있는지 확인한 뒤 명령을 한 번 더 실행하세요.")
    private fun prefs(c:Context)=c.getSharedPreferences("ui-state",Context.MODE_PRIVATE)
    fun chosen(c:Context)=prefs(c).getBoolean("pcStart",false)
    fun choose(c:Context,on:Boolean){check(prefs(c).edit().putBoolean("pcStart",on).commit())}
    fun mac(c:Context)=prefs(c).getBoolean("pcMac",false)
    fun setMac(c:Context,on:Boolean){prefs(c).edit().putBoolean("pcMac",on).apply()}
    /** Writes the start script into the app's own shared-storage folder (adb shell can read it); false if unavailable. */
    fun prepare(c:Context):Boolean=runCatching{
        val dir=c.getExternalFilesDir(null)?:return false
        val file=File(dir,"start.sh")
        if(!file.isFile||file.readText()!=SCRIPT)file.writeText(SCRIPT)
        true
    }.getOrDefault(false)
    fun copy(c:Context,mac:Boolean){c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("bluedotOFF",command(mac)))}
    /** The guide for the picked PC (steps and command) to send to the PC, e.g. by mail or a messenger: a copy on the
     *  phone usually cannot be pasted on the PC. */
    fun share(c:Context,mac:Boolean)=runCatching{
        val text=(listOf("bluedotOFF")+steps(mac).mapIndexed{i,step->"${i+1}. "+app.bluedotoff.UiText.text(c,step)}+listOf(command(mac))).joinToString("\n\n")
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
