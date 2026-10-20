package app.bluedotoff

import android.content.Context
import org.json.JSONObject

/** Bound app resources reflect live framework overlays. No hidden API or privileged extension.
 *  Read on any firmware since 1.2.0: the roles come from the live overlay, and a firmware whose SystemUI code was not
 *  verified gets a Home notice instead of no classification (R-2). */
data class IndicatorPlatform(val supported:Boolean,val exemptions:Set<String>) {
    companion object {
        fun read(context:Context):IndicatorPlatform=runCatching {
            val profile=JSONObject(context.assets.open("indicator-profile.json").bufferedReader().use{it.readText()})
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
