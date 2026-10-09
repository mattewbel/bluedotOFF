package app.bluedotoff

import android.app.AppOpsManager
import android.os.Parcel
import android.os.Parcelable
import app.bluedotoff.core.LocationAccess

/** Read-only parser, used only after verifying this firmware's Proxy and parcel writers.
 * Uses public Parcel/Parcelable APIs; no hidden-API exemptions, reflective access or code injection.
 */
object AppOpsHistory {
    data class ReadResult(val access:LocationAccess,val hasLocationOps:Boolean)
    fun read(reply:Parcel,pkg:String,uid:Int):LocationAccess=readWithEvidence(reply,pkg,uid).access
    fun readWithEvidence(reply:Parcel,pkg:String,uid:Int):ReadResult {
        check(reply.dataSize()<=4_000_000){"History reply too large"}
        val packages=reply.readInt();check(packages in -1..1){"Unexpected package count"}
        if(packages<=0)return ReadResult(LocationAccess(),false)
        check(reply.readInt()==1 && reply.readString()==pkg && reply.readInt()==uid){"History identity mismatch"}
        val count=reply.readInt();check(count in 0..2){"Unexpected ops"}
        var result=LocationAccess()
        val seen=mutableSetOf<Int>()
        repeat(count){
            val op=reply.readInt();check(op in 0..1&&seen.add(op));reply.readInt() // Mode is checked separately by authoritative read().
            @Suppress("DEPRECATION") val map=reply.readHashMap(AppOpsManager::class.java.classLoader)?:error("Missing attributions")
            check(map.size<=256)
            for((tag,value) in map){
                check(tag==null||tag is String)
                check(value is Parcelable && value.javaClass.name=="android.app.AppOpsManager\$AttributedOpEntry")
                val p=Parcel.obtain()
                try{
                    value.writeToParcel(p,0);p.setDataPosition(0)
                    val bits=p.readByte().toInt() and 255;check(bits and 14==bits && p.readInt()==op)
                    val time=events(p,true)
                    if(time!=null)result=result.merge(LocationAccess.event(op,time,false))
                    events(p,false) // Both sparse arrays are serialized, including null (-1). Rejections never count.
                    check(p.dataAvail()==0)
                }finally{p.recycle()}
            }
        }
        return ReadResult(result,count>0)
    }
    private fun maxOfNullable(a:Long?,b:Long?):Long?=if(a==null)b else if(b==null)a else maxOf(a,b)
    private fun events(p:Parcel,access:Boolean):Long? {
        val count=p.readInt();check(count in -1..4096)
        var latest:Long?=null
        repeat(maxOf(0,count)){
            p.readLong() // UID state and access/proxy flags key; all location accesses are eligible.
            @Suppress("DEPRECATION") val event=p.readParcelable<Parcelable>(AppOpsManager::class.java.classLoader)
            check(event!=null && event.javaClass.name=="android.app.AppOpsManager\$NoteOpEvent")
            val e=Parcel.obtain()
            try{event.writeToParcel(e,0);e.setDataPosition(0);e.readByte();val time=e.readLong();check(time>=0);if(access&&time>0)latest=maxOfNullable(latest,time)}finally{e.recycle()}
        }
        return latest
    }
}
