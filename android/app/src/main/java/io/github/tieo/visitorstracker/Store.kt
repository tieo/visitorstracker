package io.github.tieo.visitorstracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.tieo.visitorstracker.source.Slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/** One read of an office; [partial] reads covered only its newest days. */
data class Poll(
    val at: Instant,
    val ok: Boolean,
    val free: Int?,
    val appointments: Int?,
    val error: String?,
    val partial: Boolean = false,
)

/** A slot's free stretch: first and last poll showing it, and the first poll no longer showing it. */
data class SlotRow(
    val id: Long,
    val start: Instant,
    val end: Instant,
    val resource: String,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val goneSeen: Instant?,
)

data class LocalAlert(val id: Long, val office: String, val until: String)

/**
 * History of free slots on the device.
 *
 * A slot is stored as the stretch of polls during which it was seen free. A
 * slot that disappears and later comes back (a cancellation) starts a new row.
 * Failed polls are recorded but never close a slot, so an outage does not look
 * like a rush of bookings. Times are epoch milliseconds.
 */
class Store private constructor(context: Context) :
    SQLiteOpenHelper(context, "slots.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE polls (id INTEGER PRIMARY KEY, office TEXT NOT NULL, at INTEGER NOT NULL, ok INTEGER NOT NULL, free INTEGER, appointments INTEGER, error TEXT, partial INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX polls_office_at ON polls (office, at)")
        db.execSQL("CREATE TABLE slots (id INTEGER PRIMARY KEY, office TEXT NOT NULL, start INTEGER NOT NULL, `end` INTEGER NOT NULL, resource TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, gone_seen INTEGER)")
        db.execSQL("CREATE INDEX slots_open ON slots (office, gone_seen)")
        db.execSQL("CREATE TABLE blocks (system TEXT PRIMARY KEY, until INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE alerts (id INTEGER PRIMARY KEY, office TEXT NOT NULL, until_date TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE alert_hits (alert INTEGER NOT NULL REFERENCES alerts (id) ON DELETE CASCADE, slot INTEGER NOT NULL, PRIMARY KEY (alert, slot))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE polls ADD COLUMN partial INTEGER NOT NULL DEFAULT 0")
    }

    fun recordFailure(office: String, at: Instant, error: String) {
        writableDatabase.insert("polls", null, ContentValues().apply {
            put("office", office); put("at", at.toEpochMilli()); put("ok", 0); put("error", error.take(500))
        })
        changed()
    }

    /** Stores one successful poll and returns the ids of newly free slots. */
    fun recordSnapshot(office: String, at: Instant, slots: List<Slot>, appointments: Int, coveredFrom: Instant? = null): List<Long> {
        val now = at.toEpochMilli()
        val seen = slots.associateBy { it.start.toEpochMilli() to it.resource }
        val created = mutableListOf<Long>()
        val db = writableDatabase
        db.beginTransaction()
        try {
            // A partial read says nothing about the days it skipped: it only closes slots inside
            // the range it covered, and its poll is marked so counts over the whole office skip it.
            db.insert("polls", null, ContentValues().apply {
                put("office", office); put("at", now); put("ok", 1)
                put("free", seen.size); put("appointments", appointments)
                put("partial", if (coveredFrom == null) 0 else 1)
            })
            val stillOpen = mutableSetOf<Pair<Long, String>>()
            val floor = coveredFrom?.toEpochMilli() ?: Long.MIN_VALUE
            db.rawQuery("SELECT id, start, resource FROM slots WHERE office = ? AND gone_seen IS NULL AND start >= ?",
                arrayOf(office, floor.toString())).use { c ->
                while (c.moveToNext()) {
                    val key = c.getLong(1) to c.getString(2)
                    val column = if (key in seen) "last_seen" else "gone_seen"
                    if (key in seen) stillOpen += key
                    db.execSQL("UPDATE slots SET $column = ? WHERE id = ?", arrayOf(now, c.getLong(0)))
                }
            }
            for ((key, slot) in seen) {
                if (key in stillOpen) continue
                created += db.insert("slots", null, ContentValues().apply {
                    put("office", office); put("start", key.first); put("end", slot.end.toEpochMilli())
                    put("resource", key.second); put("first_seen", now); put("last_seen", now)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        changed()
        return created
    }

    /** The latest poll of each office that was polled at all. */
    fun latestPolls(): Map<String, Instant> =
        readableDatabase.rawQuery("SELECT office, MAX(at) FROM polls GROUP BY office", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), Instant.ofEpochMilli(c.getLong(1))) }
        }

    fun blockedUntil(system: String): Instant? =
        readableDatabase.rawQuery("SELECT until FROM blocks WHERE system = ?", arrayOf(system)).use { c ->
            if (c.moveToFirst()) Instant.ofEpochMilli(c.getLong(0)) else null
        }

    fun block(system: String, until: Instant) {
        writableDatabase.execSQL(
            "INSERT INTO blocks (system, until) VALUES (?, ?) ON CONFLICT (system) DO UPDATE SET until = excluded.until",
            arrayOf<Any>(system, until.toEpochMilli()),
        )
    }

    fun polls(office: String): List<Poll> =
        readableDatabase.rawQuery(
            "SELECT at, ok, free, appointments, error, partial FROM polls WHERE office = ? ORDER BY at", arrayOf(office),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Poll(Instant.ofEpochMilli(c.getLong(0)), c.getInt(1) == 1,
                        if (c.isNull(2)) null else c.getInt(2), if (c.isNull(3)) null else c.getInt(3),
                        if (c.isNull(4)) null else c.getString(4), c.getInt(5) == 1))
                }
            }
        }

    fun slots(office: String, openOnly: Boolean = false, ids: Collection<Long>? = null): List<SlotRow> {
        val where = buildString {
            append("office = ?")
            if (openOnly) append(" AND gone_seen IS NULL")
            if (ids != null) append(" AND id IN (${ids.joinToString(",").ifEmpty { "-1" }})")
        }
        return readableDatabase.rawQuery(
            "SELECT id, start, `end`, resource, first_seen, last_seen, gone_seen FROM slots WHERE $where ORDER BY start",
            arrayOf(office),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(SlotRow(c.getLong(0), Instant.ofEpochMilli(c.getLong(1)), Instant.ofEpochMilli(c.getLong(2)),
                        c.getString(3), Instant.ofEpochMilli(c.getLong(4)), Instant.ofEpochMilli(c.getLong(5)),
                        if (c.isNull(6)) null else Instant.ofEpochMilli(c.getLong(6))))
                }
            }
        }
    }

    fun alerts(office: String? = null): List<LocalAlert> {
        val (where, args) = if (office == null) "" to emptyArray<String>() else "WHERE office = ?" to arrayOf(office)
        return readableDatabase.rawQuery("SELECT id, office, until_date FROM alerts $where ORDER BY id", args).use { c ->
            buildList { while (c.moveToNext()) add(LocalAlert(c.getLong(0), c.getString(1), c.getString(2))) }
        }
    }

    fun addAlert(office: String, until: String): Long {
        val id = writableDatabase.insert("alerts", null, ContentValues().apply {
            put("office", office); put("until_date", until); put("created", System.currentTimeMillis())
        })
        changed()
        return id
    }

    fun deleteAlert(id: Long) {
        writableDatabase.delete("alerts", "id = ?", arrayOf(id.toString()))
        changed()
    }

    /** Marks slots as announced for an alert; returns those not announced before. */
    fun claimHits(alert: Long, slots: Collection<Long>): List<Long> = slots.filter { slot ->
        writableDatabase.insertWithOnConflict("alert_hits", null, ContentValues().apply {
            put("alert", alert); put("slot", slot)
        }, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    private fun changed() {
        version.value++
    }

    companion object {
        @Volatile private var instance: Store? = null
        private val version = MutableStateFlow(0)

        /** Bumped on every write, so open screens reload what they show. */
        val changes: StateFlow<Int> = version.asStateFlow()

        fun get(context: Context): Store =
            instance ?: synchronized(this) { instance ?: Store(context.applicationContext).also { instance = it } }
    }
}
