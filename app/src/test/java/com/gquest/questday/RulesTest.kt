package com.gquest.questday

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class RulesTest {
    @Test
    fun difficultiesAndCap() {
        assertEquals(20, Rules.reward(1))
        assertEquals(40, Rules.reward(2))
        assertEquals(60, Rules.reward(3))
        assertEquals(10, Rules.award(60, 590))
        assertEquals(0, Rules.award(60, 600))
    }

    @Test
    fun levelBoundaries() {
        assertEquals(1, Rules.level(0))
        assertEquals(1, Rules.level(199))
        assertEquals(2, Rules.level(200))
        assertEquals(100, Rules.level(50000))
        assertEquals(1, Rules.nextXp(199))
    }

    @Test
    fun scheduleHonorsWeekdaysAndArchives() {
        val mon = LocalDate.of(2026, 10, 12)
        val m = Mission("m", "Leer", days = "1,3,5")
        val s = QuestState(missions = listOf(m))
        assertTrue(s.scheduled(m, mon))
        assertFalse(s.scheduled(m, mon.plusDays(1)))
        assertFalse(s.scheduled(m.copy(archived = true), mon))
    }

    @Test
    fun streakSkipsUnscheduledDays() {
        val mon = LocalDate.of(2026, 10, 12)
        val m = Mission("m", "Leer", days = "1,3,5")
        val s =
            QuestState(
                missions = listOf(m),
                completions =
                    listOf(
                        Completion("m", mon.toString(), 1, 20),
                        Completion("m", mon.minusDays(3).toString(), 1, 20),
                    ),
            )
        assertEquals(2, s.streak(m, mon))
    }

    @Test
    fun restDaysRemainInHistory() {
        val mon = LocalDate.of(2026, 10, 12)
        val m = Mission("m", "Leer")
        val s =
            QuestState(
                missions = listOf(m),
                completions =
                    listOf(
                        Completion("m", mon.toString(), 1, 20),
                        Completion("m", mon.minusDays(2).toString(), 1, 20),
                    ),
                profile = Profile(rest = mon.minusDays(1).toString()),
            )
        assertEquals(2, s.streak(m, mon))
    }

    @Test
    fun goalsAccumulateAcrossDates() {
        val today = LocalDate.of(2026, 10, 15)
        val m = Mission("g", "Libro", kind = "goal", target = 5)
        val s =
            QuestState(
                completions =
                    listOf(
                        Completion("g", today.minusDays(1).toString(), 2, 0),
                        Completion("g", today.toString(), 3, 40),
                    )
            )
        assertTrue(s.done(m, today))
        assertEquals(5, s.completion(m, today)?.amount)
        assertEquals(40, s.xp)
    }

    @Test
    fun spendingUsesInventory() {
        val s = QuestState(claims = listOf(Claim("point:5", 100, 1)), items = listOf(Item("fox")))
        assertEquals(20, s.coins)
        assertEquals(1, s.crystals)
    }

    @Test
    fun overdueTaskRemainsVisibleUntilCompleted() {
        val today = LocalDate.of(2026, 10, 15)
        val m = Mission("t", "Cita", kind = "task", date = today.minusDays(1).toString())
        assertEquals(1, QuestState(missions = listOf(m)).today(today).size)
        assertTrue(
            QuestState(
                    missions = listOf(m),
                    completions = listOf(Completion("t", today.minusDays(1).toString(), 1, 20)),
                )
                .today(today)
                .isEmpty()
        )
    }

    @Test
    fun completedGoalLeavesTodayOnFollowingDay() {
        val today = LocalDate.of(2026, 10, 15)
        val m = Mission("g", "Libro", kind = "goal", target = 5)
        val s =
            QuestState(
                missions = listOf(m),
                completions = listOf(Completion("g", today.minusDays(1).toString(), 5, 20)),
            )
        assertTrue(s.today(today).isEmpty())
    }

    @Test
    fun rareCosmeticSpendsCrystals() {
        val s =
            QuestState(claims = listOf(Claim("point:15", 100, 5)), items = listOf(Item("dragon")))
        assertEquals(100, s.coins)
        assertEquals(0, s.crystals)
    }
}
