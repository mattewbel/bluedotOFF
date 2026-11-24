package app.bluedotoff

import android.content.Context
import app.bluedotoff.adb.WatchExitReason
import app.bluedotoff.core.LocalAdbState
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Diagnostic-only histories with independent retention. IDs join the two sides locally, never in mail. */
internal object WatchEvidence {
    private val idPattern = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
    private val hostStages = LocalAdbState.entries.map { it.name }.toSet() + setOf(
        "WIRELESS_REQUIRED", "WIRELESS_ENABLE_REFUSED", "WIRELESS_ENABLE_FAILED", "WIRELESS_PREPARATION",
        "RESIDENT_LAUNCH", "LAUNCH_CLEANUP", "HANDSHAKE_TIMEOUT", "HANDSHAKE_WAIT", "HANDSHAKE_UNAVAILABLE","VISIT_ENDED",
        "USB_OFF","USB_UNREADABLE")
    val helperStages = setOf("PROFILE", "PACKAGE_CALLBACK", "PROCESS_CALLBACK", "CLIENT_HANDSHAKE")
    private val errorKinds = setOf("SECURITY", "REMOTE", "REFLECTION", "IO", "STATE", "OTHER")
    private val exitReasons = WatchExitReason.entries.map { it.name }.toSet()
    private val gates = setOf("USB_OFF", "USB_UNREADABLE", "WIFI_DISCONNECTED", "SCREEN_NOT_ACTIVE")
    private const val LIMIT = 12

    private fun prefs(c: Context) = c.getSharedPreferences("watch-evidence", Context.MODE_PRIVATE)
    private fun rows(c: Context, key: String): List<JSONObject> {
        val raw = prefs(c).getString(key, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (maxOf(0, array.length() - LIMIT) until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    @Synchronized private fun update(c: Context, key: String, id: String, edit: (JSONObject) -> Unit) {
        if (!idPattern.matches(id)) return
        runCatching {
            val rows = runCatching { rows(c, key) }.getOrDefault(emptyList()).toMutableList()
            val row = rows.find { it.optString("id") == id } ?: JSONObject().put("id", id)
                .put("at", System.currentTimeMillis()).also { rows.add(it) }
            edit(row)
            // Repeated deliberate stops must not displace the two most recent observed deaths.
            val keep = if (key=="exits" && rows.size>LIMIT) {
                val deaths=rows.filter { it.optBoolean("died") }.takeLast(2)
                val recent=rows.filterNot { it in deaths }.takeLast(LIMIT-deaths.size)
                rows.filter { it in deaths || it in recent }
            } else rows.takeLast(LIMIT)
            prefs(c).edit().putString(key, JSONArray(keep).toString()).apply()
        }
    }

    fun entered(c: Context, id: String, stage: String) {
        if (stage in hostStages) update(c, "starts", id) { it.put("entered", stage) }
    }
    fun hostFailure(c: Context, id: String, stage: String) {
        if (stage in hostStages) update(c, "starts", id) { it.put("host", stage) }
    }
    fun helperFailure(c: Context, id: String, stage: String, errorKind: String, external: Boolean=false) {
        if (stage in helperStages) update(c, "starts", id) {
            it.put("helper", stage).put("error", errorKind.takeIf { kind -> kind in errorKinds } ?: "OTHER")
            if (external) it.put("external", true)
        }
    }
    fun attached(c: Context, id: String, external: Boolean=false) = update(c, "starts", id) {
        it.put("attached", true)
        if (external) it.put("external", true)
    }
    fun refused(c: Context, id: String) = update(c, "starts", id) { it.put("refused", true) }
    fun availableAfterStart(c: Context, id: String) = update(c, "starts", id) { it.put("available", true) }
    private fun JSONObject.link(attempt: String) { if(idPattern.matches(attempt)) put("launch",attempt) }
    fun exit(c: Context, id: String, reason: String, attempt: String="") {
        if (reason in exitReasons) update(c, "exits", id) {
            if (!it.has("reason")) it.put("reason", reason)
            it.link(attempt)
        }
    }
    fun died(c: Context, id: String, attempt: String="") = update(c, "exits", id) {
        it.put("died",true)
        it.link(attempt)
    }
    fun stopRequested(c: Context, id: String, reason: WatchNotification.Reason, attempt: String) = update(c,"exits",id) {
        it.put("request",reason.name)
        it.link(attempt)
    }

    fun blocked(c: Context, gate: String) {
        if (gate !in gates) return
        runCatching {
            val prefs=prefs(c)
            val now=System.currentTimeMillis()
            if (prefs.getString("gate",null)!=gate || now-prefs.getLong("gateAt",0) !in 0L..59_999L)
                prefs.edit().putString("gate", gate).putLong("gateAt", now).apply()
        }
    }

    @Synchronized fun report(c: Context): String = runCatching {
        buildString {
            val starts=rows(c,"starts")
            for (row in rows(c, "exits")) {
                val reason = row.optString("reason").takeIf { it in exitReasons }
                append("Helper exit: ").append(Instant.ofEpochMilli(row.optLong("at"))).append(" | ")
                val start=starts.indexOfFirst { it.optString("id")==row.optString("launch") }
                if(start>=0) append("start #${start+1}; ")
                if (row.optBoolean("died")) append("active Binder died; ")
                WatchNotification.Reason.entries.find { it.name==row.optString("request") }?.let {
                    append("app requested stop (${it.name}); ")
                }
                appendLine(reason?.let { "helper reported $it" } ?: "no helper exit reason received")
            }
            for ((index,row) in starts.withIndex()) {
                append("Start attempt: #").append(index+1).append(" | ").append(Instant.ofEpochMilli(row.optLong("at")))
                if(row.optBoolean("external"))append(" | external launch")
                row.optString("entered").takeIf { it in hostStages }?.let { append(" | entered $it") }
                row.optString("host").takeIf { it in hostStages }?.let { append(" | app: $it") }
                row.optString("helper").takeIf { it in helperStages }?.let { append(" | helper: $it") }
                row.optString("error").takeIf { it in errorKinds }?.let { append(" ($it)") }
                if (row.optBoolean("refused")) append(" | app declined initial contact")
                if (row.optBoolean("attached")) append(" | connection confirmed")
                else if (row.optBoolean("available")) append(" | app observed an available helper")
                else if (!row.has("host") && !row.has("helper") && !row.optBoolean("refused")) {
                    append(" | outcome not recorded")
                }
                appendLine()
            }
            prefs(c).getString("gate", null)?.takeIf { it in gates }?.let {
                appendLine("Last start blocked before launch: ${Instant.ofEpochMilli(prefs(c).getLong("gateAt", 0))} | $it")
            }
        }.trimEnd()
    }.getOrDefault("Detailed watch evidence unavailable.")
}
