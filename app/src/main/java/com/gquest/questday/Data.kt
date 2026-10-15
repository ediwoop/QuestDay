package com.gquest.questday

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import java.time.LocalDate
import kotlinx.coroutines.flow.*
import org.json.*

@Entity
data class Mission(
    @PrimaryKey val id: String,
    val title: String,
    val category: String = "Bienestar",
    val difficulty: Int = 1,
    val kind: String = "habit",
    val days: String = "1,2,3,4,5,6,7",
    val date: String = "",
    val target: Int = 1,
    val unit: String = "veces",
    val archived: Boolean = false,
)

@Entity(primaryKeys = ["missionId", "date"])
data class Completion(
    val missionId: String,
    val date: String,
    val amount: Int = 0,
    val xp: Int = 0,
)

@Entity data class Claim(@PrimaryKey val id: String, val coins: Int, val crystals: Int)

@Entity data class Item(@PrimaryKey val id: String)

@Dao
interface QuestDao {
    @Query("SELECT * FROM Mission") fun missions(): Flow<List<Mission>>

    @Query("SELECT * FROM Completion") fun completions(): Flow<List<Completion>>

    @Query("SELECT * FROM Claim") fun claims(): Flow<List<Claim>>

    @Query("SELECT * FROM Item") fun items(): Flow<List<Item>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(m: Mission)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(c: Completion)

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun claim(c: Claim): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun own(i: Item): Long

    @Query("DELETE FROM Mission") suspend fun clearMissions()

    @Query("DELETE FROM Completion") suspend fun clearCompletions()

    @Query("DELETE FROM Claim") suspend fun clearClaims()

    @Query("DELETE FROM Item") suspend fun clearItems()
}

@Database(
    entities = [Mission::class, Completion::class, Claim::class, Item::class],
    version = 1,
    exportSchema = false,
)
abstract class QuestDb : RoomDatabase() {
    abstract fun dao(): QuestDao
}

val Context.profileStore by preferencesDataStore("profile")

data class Profile(
    val name: String = "",
    val avatar: String = "🧭",
    val rest: String = "",
    val reminders: Boolean = false,
    val hour: Int = 19,
    val language: String = "es",
)

data class QuestState(
    val missions: List<Mission> = emptyList(),
    val completions: List<Completion> = emptyList(),
    val claims: List<Claim> = emptyList(),
    val items: List<Item> = emptyList(),
    val profile: Profile = Profile(),
    val ready: Boolean = false,
) {
    val xp
        get() = completions.sumOf { it.xp }

    val level
        get() = Rules.level(xp)

    val coins
        get() = claims.sumOf { it.coins } - items.sumOf { Shop.cost(it.id) }

    val crystals
        get() = claims.sumOf { it.crystals } - items.sumOf { Shop.crystalCost(it.id) }

    val points
        get() = (xp / 100).coerceAtMost(75)

    fun completion(m: Mission, date: LocalDate): Completion? {
        val row = completions.firstOrNull { it.missionId == m.id && it.date == date.toString() }
        return if (m.kind == "goal")
            Completion(
                m.id,
                date.toString(),
                completions
                    .filter { it.missionId == m.id }
                    .sumOf { it.amount }
                    .coerceAtMost(m.target),
                row?.xp ?: 0,
            )
        else row
    }

    fun rested(date: LocalDate) = date.toString() in profile.rest.split(",")

    fun done(m: Mission, date: LocalDate) = (completion(m, date)?.amount ?: 0) >= m.target

    fun scheduled(m: Mission, date: LocalDate) =
        !m.archived &&
            when (m.kind) {
                "task" -> m.date <= date.toString()
                "goal" -> true
                else -> date.dayOfWeek.value.toString() in m.days.split(",")
            }

    fun today(date: LocalDate) =
        missions.filter {
            scheduled(it, date) &&
                !((it.kind == "task" || it.kind == "goal") &&
                    completions
                        .filter { c -> c.missionId == it.id && c.date != date.toString() }
                        .sumOf { c -> c.amount } >= it.target)
        }

    fun streak(m: Mission, now: LocalDate): Int {
        var n = 0
        var d = now
        if (!done(m, d)) d = d.minusDays(1)
        repeat(3660) {
            if (m.kind != "habit") return n
            if (rested(d)) {
                d = d.minusDays(1)
                return@repeat
            }
            if (d.dayOfWeek.value.toString() in m.days.split(",")) {
                if (!done(m, d)) return n
                n++
            }
            d = d.minusDays(1)
        }
        return n
    }
}

object Rules {
    const val DAILY_CAP = 600

    fun reward(difficulty: Int) = difficulty.coerceIn(1, 3) * 20

    fun level(xp: Int) = (xp.coerceAtLeast(0) / 200 + 1).coerceAtMost(100)

    fun nextXp(xp: Int) = if (level(xp) == 100) 0 else 200 - xp % 200

    fun award(wanted: Int, otherToday: Int) =
        wanted.coerceAtMost((DAILY_CAP - otherToday).coerceAtLeast(0)).coerceAtLeast(0)
}

object Shop {
    val entries =
        listOf(
            Triple("fox", "🦊", 80),
            Triple("wizard", "🧙", 120),
            Triple("astronaut", "🧑‍🚀", 180),
            Triple("dragon", "🐉", 0),
            Triple("crown", "👑", 300),
        )

    fun cost(id: String) = entries.firstOrNull { it.first == id }?.third ?: 0

    fun crystalCost(id: String) = if (id == "dragon") 5 else 0
}

class Repository(context: Context) {
    private val app = context.applicationContext
    val db = Room.databaseBuilder(app, QuestDb::class.java, "questday.db").build()
    val dao = db.dao()
    private val profile =
        app.profileStore.data.map { p ->
            Profile(
                p[stringPreferencesKey("name")] ?: "",
                p[stringPreferencesKey("avatar")] ?: "🧭",
                p[stringPreferencesKey("rest")] ?: "",
                p[booleanPreferencesKey("reminders")] ?: false,
                p[intPreferencesKey("hour")] ?: 19,
                p[stringPreferencesKey("language")] ?: "es",
            )
        }
    val state =
        combine(dao.missions(), dao.completions(), dao.claims(), dao.items(), profile) {
            m,
            c,
            r,
            i,
            p ->
            QuestState(m, c, r, i, p, true)
        }

    suspend fun profile(p: Profile) {
        app.profileStore.edit {
            it[stringPreferencesKey("name")] = p.name
            it[stringPreferencesKey("avatar")] = p.avatar
            it[stringPreferencesKey("rest")] = p.rest
            it[booleanPreferencesKey("reminders")] = p.reminders
            it[intPreferencesKey("hour")] = p.hour
            it[stringPreferencesKey("language")] = p.language
        }
    }

    suspend fun progress(s: QuestState, m: Mission, amount: Int, date: LocalDate) {
        db.withTransaction {
            val current = dao.completions().first()
            val prior =
                if (m.kind == "goal")
                    current
                        .filter { it.missionId == m.id && it.date != date.toString() }
                        .sumOf { it.amount }
                else 0
            val a = (amount - prior).coerceIn(0, (m.target - prior).coerceAtLeast(0))
            val other =
                current
                    .filter { it.date == date.toString() && it.missionId != m.id }
                    .sumOf { it.xp }
            val xp =
                if (a + prior >= m.target && prior < m.target)
                    Rules.award(Rules.reward(m.difficulty), other)
                else 0
            dao.put(Completion(m.id, date.toString(), a, xp))
        }
    }

    suspend fun claim(s: QuestState, point: Int): Boolean {
        if (point < 1 || point > s.points || s.claims.any { it.id == "point:$point" }) return false
        return dao.claim(
            Claim(
                "point:$point",
                if (point % 15 == 0) 100 else if (point % 5 == 0) 40 else 15,
                if (point % 15 == 0) 5 else if (point % 5 == 0) 1 else 0,
            )
        ) != -1L
    }

    suspend fun buy(s: QuestState, id: String): Boolean {
        return db.withTransaction {
            val owned = dao.items().first()
            val coins = dao.claims().first().sumOf { it.coins } - owned.sumOf { Shop.cost(it.id) }
            if (
                owned.any { it.id == id } ||
                    Shop.entries.none { it.first == id } ||
                    coins < Shop.cost(id) ||
                    dao.claims().first().sumOf { it.crystals } -
                        owned.sumOf { Shop.crystalCost(it.id) } < Shop.crystalCost(id)
            )
                false
            else dao.own(Item(id)) != -1L
        }
    }

    fun export(s: QuestState): String =
        JSONObject()
            .put("format", "questday")
            .put("version", 1)
            .put(
                "profile",
                JSONObject()
                    .put("name", s.profile.name)
                    .put("avatar", s.profile.avatar)
                    .put("rest", s.profile.rest),
            )
            .put(
                "missions",
                JSONArray(
                    s.missions.map {
                        JSONObject()
                            .put("id", it.id)
                            .put("title", it.title)
                            .put("category", it.category)
                            .put("difficulty", it.difficulty)
                            .put("kind", it.kind)
                            .put("days", it.days)
                            .put("date", it.date)
                            .put("target", it.target)
                            .put("unit", it.unit)
                            .put("archived", it.archived)
                    }
                ),
            )
            .put(
                "completions",
                JSONArray(
                    s.completions.map {
                        JSONObject()
                            .put("missionId", it.missionId)
                            .put("date", it.date)
                            .put("amount", it.amount)
                            .put("xp", it.xp)
                    }
                ),
            )
            .put(
                "claims",
                JSONArray(
                    s.claims.map {
                        JSONObject()
                            .put("id", it.id)
                            .put("coins", it.coins)
                            .put("crystals", it.crystals)
                    }
                ),
            )
            .put("items", JSONArray(s.items.map { it.id }))
            .toString(2)

    suspend fun restore(text: String) {
        require(text.length < 2_000_000) { "La copia supera 2 MB" }
        val j = JSONObject(text)
        require(j.getString("format") == "questday" && j.getInt("version") == 1) {
            "Formato no compatible"
        }
        val missions = j.getJSONArray("missions")
        require(missions.length() <= 1000)
        val ms =
            (0 until missions.length()).map {
                val o = missions.getJSONObject(it)
                Mission(
                    o.getString("id"),
                    o.getString("title"),
                    o.getString("category"),
                    o.getInt("difficulty"),
                    o.getString("kind"),
                    o.getString("days"),
                    o.getString("date"),
                    o.getInt("target"),
                    o.getString("unit"),
                    o.getBoolean("archived"),
                )
            }
        require(
            ms.map { it.id }.distinct().size == ms.size &&
                ms.all {
                    it.id.length in 1..100 &&
                        it.title.length in 1..80 &&
                        it.target in 1..10000 &&
                        it.difficulty in 1..3 &&
                        it.kind in listOf("habit", "task", "goal") &&
                        it.days.split(",").all { d -> d in (1..7).map { n -> n.toString() } }
                }
        ) {
            "Misiones no válidas"
        }
        ms.filter { it.date.isNotBlank() }.forEach { LocalDate.parse(it.date) }
        val cs = j.getJSONArray("completions")
        require(cs.length() <= 50000)
        val completions =
            (0 until cs.length()).map {
                val o = cs.getJSONObject(it)
                Completion(
                    o.getString("missionId"),
                    o.getString("date"),
                    o.getInt("amount"),
                    o.getInt("xp"),
                )
            }
        require(
            completions.all { c ->
                val m = ms.firstOrNull { it.id == c.missionId }
                m != null &&
                    c.amount in 0..10000 &&
                    c.xp in 0..60 &&
                    LocalDate.parse(c.date) <= LocalDate.now()
            }
        ) {
            "Progreso no válido"
        }
        require(
            completions.groupBy { it.missionId to it.date }.all { it.value.size == 1 } &&
                completions
                    .groupBy { it.date }
                    .all { it.value.sumOf { c -> c.xp } <= Rules.DAILY_CAP }
        )
        val rs = j.getJSONArray("claims")
        require(rs.length() <= 75)
        val claims =
            (0 until rs.length()).map {
                val o = rs.getJSONObject(it)
                Claim(o.getString("id"), o.getInt("coins"), o.getInt("crystals"))
            }
        require(
            claims.map { it.id }.distinct().size == claims.size &&
                claims.all {
                    it.id.startsWith("point:") &&
                        it.id.substringAfter(":").toInt() in 1..75 &&
                        it.coins in 0..100 &&
                        it.crystals in 0..5
                }
        )
        val os = j.getJSONArray("items")
        require(os.length() <= Shop.entries.size)
        val items = (0 until os.length()).map { Item(os.getString(it)) }
        require(
            items.map { it.id }.distinct().size == items.size &&
                items.all { i -> Shop.entries.any { it.first == i.id } } &&
                claims.sumOf { it.coins } >= items.sumOf { Shop.cost(it.id) } &&
                claims.sumOf { it.crystals } >= items.sumOf { Shop.crystalCost(it.id) }
        )
        val p = j.getJSONObject("profile")
        val name = p.getString("name")
        require(name.length in 1..30)
        val avatar = p.optString("avatar", "🧭")
        require(avatar.length <= 20)
        val rest = p.optString("rest", "")
        require(rest.length <= 40000)
        rest.split(",").filter { it.isNotBlank() }.forEach { LocalDate.parse(it) }
        db.withTransaction {
            dao.clearCompletions()
            dao.clearMissions()
            dao.clearClaims()
            dao.clearItems()
            ms.forEach { dao.put(it) }
            completions.forEach { dao.put(it) }
            claims.forEach { dao.claim(it) }
            items.forEach { dao.own(it) }
        }
        profile(Profile(name, avatar, rest))
    }
}
