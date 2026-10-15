package com.gquest.questday

import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class TorchEngineTest {
    private fun path(index: Int, route: String): TorchRun {
        var s = TorchEngine.fresh(index)
        route.forEach {
            val to =
                s.position +
                    when (it) {
                        'U' -> -5
                        'D' -> 5
                        'L' -> -1
                        else -> 1
                    }
            val r = TorchEngine.move(s, to)
            assertTrue("Illegal move $it in $route", r.changed)
            s = r.run
        }
        return s
    }

    @Test
    fun allTenLevelsHavePerfectReachableSolutions() {
        assertEquals(10, TorchEngine.levels.size)
        val routes =
            listOf(
                "DDRRUURR",
                "DDDDRRUURRUU",
                "DDRRRDDUURUU",
                "DDDDUURRDDRRUUUU",
                "DDDDUUURDRRDDUUUUR",
                "DDDDRRLLUURRRRUU",
                "DDRRRUUR",
                "DDDDRRRRUUUU",
                "DDRRRDDRUUUU",
                "DRDLRRRRULUR",
            )
        routes.forEachIndexed { i, route ->
            val r = path(i, route)
            assertTrue("Level ${i+1}", TorchEngine.won(r))
            assertTrue(TorchEngine.allTreasure(r))
            assertEquals("Stars level ${i+1}", 3, TorchEngine.stars(r))
            assertTrue(r.light >= 0)
        }
    }

    @Test
    fun wallAndDiagonalDoNotSpendLight() {
        val s = TorchEngine.fresh(0)
        assertFalse(TorchEngine.move(s, 6).changed)
        val down = TorchEngine.move(s, 5).run
        assertEquals(down, TorchEngine.move(down, 6).run)
    }

    @Test
    fun cannotWrapAcrossBoardEdges() {
        val s = TorchEngine.fresh(0).copy(position = 4)
        assertFalse(TorchEngine.move(s, 5).changed)
    }

    @Test
    fun exitNeedsRelic() {
        val r = path(0, "RRRR")
        assertFalse(TorchEngine.won(r))
        assertFalse(TorchEngine.relic(r))
    }

    @Test
    fun lastPointCanWin() {
        val r = path(6, "DDRRRUUR")
        assertEquals(0, r.light)
        assertTrue(TorchEngine.won(r))
        assertFalse(TorchEngine.lost(r))
    }

    @Test
    fun depletedLightStopsMovement() {
        val s = TorchEngine.fresh(0).copy(light = 1)
        val r = TorchEngine.move(s, 1).run
        assertTrue(TorchEngine.lost(r))
        assertFalse(TorchEngine.move(r, 2).changed)
    }

    @Test
    fun brazierWorksOnlyOnce() {
        var r = path(3, "DDRR")
        assertEquals(12, r.light)
        r = TorchEngine.move(r, 11).run
        r = TorchEngine.move(r, 12).run
        assertEquals(10, r.light)
    }

    @Test
    fun lockedDoorRejectsWithoutKey() {
        val s = TorchEngine.fresh(2).copy(position = 11)
        assertEquals(s, TorchEngine.move(s, 12).run)
    }

    @Test
    fun openingDoorSpendsKeyAndKeepsDoorOpen() {
        var r = path(2, "DDRR")
        assertEquals(0, r.keys)
        assertTrue(r.doors and (1L shl 12) != 0L)
        r = TorchEngine.move(r, 11).run
        r = TorchEngine.move(r, 12).run
        assertEquals(0, r.keys)
        assertEquals(12, r.position)
    }

    @Test
    fun replayStartsFromIdenticalCleanState() {
        val a = path(1, "DDDDRRUURRUU")
        assertTrue(TorchEngine.won(a))
        assertEquals(TorchRun(1, 0, 14), TorchEngine.fresh(1))
        assertEquals(TorchEngine.fresh(1), TorchEngine.fresh(1))
    }

    @Test
    fun cannotCollectSameKeyTwice() {
        var r = path(2, "DDR")
        assertEquals(1, r.keys)
        r = TorchEngine.move(r, 10).run
        r = TorchEngine.move(r, 11).run
        assertEquals(1, r.keys)
    }

    @Test
    fun allExpertRoutesAreOptimalForAllTreasure() {
        TorchEngine.levels.indices.forEach { i ->
            val queue = ArrayDeque<TorchRun>()
            queue.add(TorchEngine.fresh(i))
            val seen = hashMapOf<List<Long>, Int>()
            var winner: TorchRun? = null
            while (queue.isNotEmpty()) {
                val r = queue.removeFirst()
                val key = listOf(r.position.toLong(), r.collected, r.doors, r.keys.toLong())
                if ((seen[key] ?: -1) >= r.light) continue
                seen[key] = r.light
                if (TorchEngine.won(r)) {
                    if (TorchEngine.allTreasure(r)) {
                        winner = r
                        break
                    }
                    continue
                }
                listOf(-5, 5, -1, 1).forEach { delta ->
                    val move = TorchEngine.move(r, r.position + delta)
                    if (move.changed) queue.add(move.run)
                }
            }
            assertNotNull("Level ${i+1} must be solvable", winner)
            assertEquals("Expert par ${i+1}", TorchEngine.levels[i].par, winner!!.moves)
        }
    }
}
