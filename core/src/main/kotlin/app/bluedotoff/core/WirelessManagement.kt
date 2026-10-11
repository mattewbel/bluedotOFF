package app.bluedotoff.core

/** Permission possession never substitutes for the user's fresh, private preference. */
data class WirelessFacts(val userAction:Boolean, val developer:Boolean, val wifiConnected:Boolean,
    val consent:Boolean, val granted:Boolean, val paired:Boolean, val wireless:Boolean)
object WirelessManagementPolicy {
    fun mayEnable(f:WirelessFacts)=f.userAction&&f.developer&&f.wifiConnected&&f.consent&&f.granted&&f.paired&&!f.wireless
    fun mayDisable(consent:Boolean,granted:Boolean,helperExited:Boolean,socketClosed:Boolean,operationCompleted:Boolean)=consent&&granted&&helperExited&&socketClosed&&operationCompleted
}
object FirmwareSupport {
    fun setup(manufacturer:String,sdk:Int,oneUi:String)=manufacturer.equals("samsung",true)&&sdk>=37&&(oneUi.toIntOrNull()?:0)>=90000
}
enum class CandidateCheck { AVAILABLE, NEEDS_CHECK, BLOCKED }
enum class SelectionState { NONE, SOME, ALL }
object PickerSelection {
    fun scope(rows:List<DiscoveredApp>)=rows.filter{it.canCheck}.map{it.packageName}.toSet()
    fun state(rows:List<DiscoveredApp>,selected:Set<String>):SelectionState {
        val scope=scope(rows);val count=(scope intersect selected).size
        return when {count==0->SelectionState.NONE;count==scope.size->SelectionState.ALL;else->SelectionState.SOME}
    }
    fun toggle(rows:List<DiscoveredApp>,selected:Set<String>):Set<String> =
        if(state(rows,selected)==SelectionState.ALL)selected-scope(rows)else selected+scope(rows)
}
data class ScanStamp(val build:String,val packageEpoch:Long,val created:Long) {
    fun valid(build:String,packageEpoch:Long,now:Long)=this.build==build&&this.packageEpoch==packageEpoch&&now>=created&&now-created<1_800_000
}
