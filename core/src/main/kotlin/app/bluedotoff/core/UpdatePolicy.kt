package app.bluedotoff.core

/** Opt-in update check (1.1.2, owner request): at most once a day, numeric versions only, one notice per version. */
object UpdatePolicy {
    const val INTERVAL_MS=24L*60*60*1000
    private val plain=Regex("[0-9]{1,6}(\\.[0-9]{1,6}){0,3}")
    /** "1.1.2" or "v1.1.2". Anything else (a pre-release such as "1.2.0-beta", text) is not a release to announce. */
    fun parse(tag:String):List<Int>?=tag.trim().removePrefix("v").takeIf{plain.matches(it)}?.split('.')?.map{it.toInt()}
    fun newer(latest:String,current:String):Boolean {
        val a=parse(latest)?:return false
        val b=parse(current)?:return false
        for(i in 0 until maxOf(a.size,b.size)){
            val x=a.getOrElse(i){0};val y=b.getOrElse(i){0}
            if(x!=y)return x>y
        }
        return false
    }
    /** A clock moved backwards also allows a check, so a wrong date cannot block it for good. */
    fun due(now:Long,lastCheck:Long)=lastCheck<=0L||now<lastCheck||now-lastCheck>=INTERVAL_MS
    /** Announce a newer version once: not after it was shown or notified, and never after "skip this version". */
    fun announce(latest:String?,current:String,notified:String?,skipped:String?)=
        latest!=null&&newer(latest,current)&&latest!=notified&&latest!=skipped
}
