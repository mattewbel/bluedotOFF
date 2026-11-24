package app.bluedotoff

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Small on-device, privacy-safe history for diagnosing background-watch lifecycle reports. */
internal object WatchDiagnostics {
    private const val PREFS = "watch-diagnostics"
    private const val KEY_EVENTS = "events"
    private const val KEY_WORK = "last_work"
    private const val KEY_OPEN_WORK = "last_open_work"
    private const val KEY_LAST_WRITE = "last_successful_write"
    private const val LIMIT = 10

    /** Only fixed lifecycle facts are stored; callers cannot attach package names or arbitrary text. */
    enum class Event(val label: String) {
        START_FAILED("Watch start failed (legacy record; multiple reports may refer to one attempt)"),
        RESTART_REQUESTED("A watch start was requested while previous helper contact was outstanding"),
        INIT_FAILED("Watch initialization failed"),
        CONTACT_UNCONFIRMED("Helper contact could not be confirmed"),
        CONTACT_DELAYED("Expected helper contact was delayed"),
        BINDER_DIED("Active helper connection died"),
        ACTIVE_REFUSED("App asked its active helper to exit"),
        APP_UPDATED("App updated"),
        USB_OFF("USB debugging reported off"),
        USB_UNKNOWN("USB debugging state could not be read"),
        UNOWNED_USB_UNKNOWN("Unowned helper contact refused while USB debugging state was unavailable"),
        BOOT("Phone restarted"),
        ATTACH_SAME("Existing helper connection confirmed"),
        ATTACH_NEW("New helper connection confirmed")
    }

    private data class Item(val at: Long, val event: Event)

    /** Best-effort only: a diagnostic storage error must never affect normal app behavior. */
    @Synchronized fun record(context: Context, event: Event) {
        runCatching {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val items = decode(prefs.getString(KEY_EVENTS, null)).toMutableList()
            items += Item(System.currentTimeMillis(), event)
            val json = JSONArray().apply {
                items.takeLast(LIMIT).forEach { item ->
                    put(JSONObject().put("at", item.at).put("event", item.event.name))
                }
            }
            prefs.edit().putString(KEY_EVENTS, json.toString()).apply()
        }
    }

    /** Saves only totals from the most recent completed watch work, never per-app details. */
    fun watchWork(context: Context, checked: Int, rewritten: Int) {
        runCatching {
            val now = System.currentTimeMillis()
            val json = JSONObject().put("at", now).put("countsInitialReads", true)
                .put("checked", checked.coerceAtLeast(0)).put("rewritten", rewritten.coerceAtLeast(0))
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val edit = prefs.edit().putString(KEY_WORK, json.toString())
            if (rewritten > 0) edit.putString(KEY_LAST_WRITE, JSONObject().put("at", now).put("count", rewritten).toString())
            edit.apply()
        }
    }

    /** App-open reapply has a different meaning: checked counts only apps verified as complete. */
    fun openWork(context: Context, checked: Int, rewritten: Int) {
        runCatching {
            val now = System.currentTimeMillis()
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val edit = prefs.edit().putString(KEY_OPEN_WORK, JSONObject().put("at", now)
                .put("checked", checked.coerceAtLeast(0)).put("rewritten", rewritten.coerceAtLeast(0)).toString())
            if (rewritten > 0) edit.putString(KEY_LAST_WRITE, JSONObject().put("at", now).put("count", rewritten).toString())
            edit.apply()
        }
    }

    /** English text suitable for the user's existing support email and clipboard. */
    fun report(context: Context): String = listOf(legacyReport(context),
        WatchEvidence.report(context).ifEmpty { "Build 70+ watch evidence: no start or exit evidence recorded." })
        .filter { it.isNotEmpty() }.joinToString("\n")

    private fun legacyReport(context: Context): String = runCatching {
        val items = decode(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_EVENTS, null))
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val work = runCatching { JSONObject(prefs.getString(KEY_WORK, null) ?: "") }.getOrNull()
        val openWork = runCatching { JSONObject(prefs.getString(KEY_OPEN_WORK, null) ?: "") }.getOrNull()
        val lastWrite = runCatching { JSONObject(prefs.getString(KEY_LAST_WRITE, null) ?: "") }.getOrNull()
        buildString {
            appendLine("Background watch local history (latest $LIMIT events; stored on this device; times UTC)")
            if (work == null) appendLine("Latest background watch pass: not recorded")
            else {
                val unit = if (work.optBoolean("countsInitialReads")) "apps read" else "apps with verification snapshots"
                appendLine("Latest background watch pass (${Instant.ofEpochMilli(work.optLong("at"))}): ${work.optInt("checked").coerceAtLeast(0)} $unit; ${work.optInt("rewritten").coerceAtLeast(0)} successful writes")
            }
            if (openWork == null) appendLine("Latest app-open automatic reapply: not recorded")
            else appendLine("Latest app-open automatic reapply (${Instant.ofEpochMilli(openWork.optLong("at"))}): ${openWork.optInt("checked").coerceAtLeast(0)} apps verified complete; ${openWork.optInt("rewritten").coerceAtLeast(0)} successful writes")
            if (lastWrite != null && lastWrite.optLong("at") > 0 && lastWrite.optInt("count") > 0)
                appendLine("Last successful write (${Instant.ofEpochMilli(lastWrite.optLong("at"))}): ${lastWrite.optInt("count")} apps")
            if (items.isEmpty()) append("No watch events recorded yet.")
            else items.forEach { append(Instant.ofEpochMilli(it.at).toString()).append("  ").appendLine(it.event.label) }
        }.trimEnd()
    }.getOrElse { "Background watch local history is unavailable." }

    private fun decode(raw: String?): List<Item> = runCatching {
        if (raw.isNullOrBlank()) return emptyList()
        val json = JSONArray(raw)
        (0 until json.length()).mapNotNull { index ->
            val row = json.optJSONObject(index) ?: return@mapNotNull null
            val at = row.optLong("at", -1L).takeIf { it > 0 } ?: return@mapNotNull null
            val event = runCatching { Event.valueOf(row.optString("event")) }.getOrNull() ?: return@mapNotNull null
            Item(at, event)
        }.takeLast(LIMIT)
    }.getOrDefault(emptyList())
}
