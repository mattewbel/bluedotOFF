package app.bluedotoff

import android.content.Context
import android.os.Build
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Bound app resources reflect live framework overlays. No hidden API or privileged extension. */
data class IndicatorPlatform(val supported:Boolean,val exemptions:Set<String>) {
    companion object {
        fun read(context:Context,methodVerified:Boolean=false):IndicatorPlatform=runCatching {
            val profile=JSONObject(context.assets.open("indicator-profile.json").bufferedReader().use{it.readText()})
            if(!methodVerified){
            if(Build.FINGERPRINT!=profile.getString("fingerprint"))return IndicatorPlatform(false,emptySet())
            val digest=MessageDigest.getInstance("SHA-256")
            File("/system_ext/priv-app/SystemUI/SystemUI.apk").inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
            if(digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}!=profile.getString("systemUiSha256"))return IndicatorPlatform(false,emptySet())
            }
            val roles=profile.getJSONArray("roles")
            val values=(0 until roles.length()).map{i->
                val id=context.resources.getIdentifier(roles.getString(i),"string","android")
                check(id!=0);context.getString(id)
            }
            // This exact KR profile has nonempty UI/audio/visual roles. Unbound empty resources
            // were disproved by live overlay lookup; fail unknown if they cannot be resolved.
            check(values.size==6&&values[1].isNotEmpty()&&values[2].isNotEmpty()&&values[5].isNotEmpty())
            IndicatorPlatform(true,values.filter{it.isNotEmpty()}.toSet()+"android")
        }.getOrElse{IndicatorPlatform(false,emptySet())}
    }
}
