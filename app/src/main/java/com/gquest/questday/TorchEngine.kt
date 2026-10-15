package com.gquest.questday

/** Pure deterministic rules: one accepted orthogonal move consumes one light. */
data class TorchLevel(
    val title: String,
    val rows: List<String>,
    val light: Int,
    val par: Int,
    val hint: String,
) {
    val tiles = rows.joinToString("")
    val start = tiles.indexOf('S')
    val relicMask = mask('R')
    val chestMask = mask('C')

    private fun mask(tile: Char) =
        tiles.foldIndexed(0L) { i, acc, t -> if (t == tile) acc or (1L shl i) else acc }

    init {
        require(rows.size == 5 && rows.all { it.length == 5 })
        require(tiles.count { it == 'S' } == 1 && tiles.count { it == 'E' } == 1 && relicMask != 0L)
    }
}

data class TorchRun(
    val level: Int,
    val position: Int,
    val light: Int,
    val moves: Int = 0,
    val collected: Long = 0,
    val doors: Long = 0,
    val keys: Int = 0,
)

data class TorchMove(val run: TorchRun, val message: String, val changed: Boolean = false)

object TorchEngine {
    val levels =
        listOf(
            TorchLevel(
                "La primera chispa",
                listOf("S...E", ".#.#.", "..R..", ".#.#.", "....."),
                10,
                8,
                "Recoge la reliquia y alcanza la salida. Cada paso consume una unidad de luz.",
            ),
            TorchLevel(
                "Un pequeño desvío",
                listOf("S...E", ".###.", "..R..", ".#.#.", "..C.."),
                14,
                12,
                "El cofre es opcional. Decide si puedes recogerlo y volver a tiempo.",
            ),
            TorchLevel(
                "Tras la puerta",
                listOf("S.#.E", "..#..", ".KD.R", "..#..", "..#C."),
                13,
                12,
                "Recoge la llave para abrir la puerta. Una vez abierta puedes volver a cruzarla.",
            ),
            TorchLevel(
                "Fuego prestado",
                listOf("S...E", ".#.#.", "..T..", ".#.#.", "R...C"),
                11,
                16,
                "El brasero recupera 5 de luz, una sola vez. Inclúyelo en tu ruta.",
            ),
            TorchLevel(
                "Dos orillas",
                listOf("S.#.E", ".K#..", "..D..", "..#R.", "C.#T."),
                13,
                18,
                "Piensa en el cofre de la izquierda antes de cruzar. El brasero está al otro lado.",
            ),
            TorchLevel(
                "El regreso",
                listOf("S#..E", ".#.#.", ".T.R.", ".###.", "..C.."),
                11,
                16,
                "El camino al cofre consume luz. Busca dónde recuperar energía antes.",
            ),
            TorchLevel(
                "El pasillo dorado",
                listOf("S.#CE", "..#..", "K.DR.", "#.#T.", "..#.."),
                8,
                8,
                "Con una ruta precisa puedes salir con la reliquia y el cofre sin gastar un paso de más.",
            ),
            TorchLevel(
                "La ruta larga",
                listOf("S..#E", ".#.#.", "K.D.R", ".###.", "C..T."),
                11,
                12,
                "Puedes volver por la puerta o rodear las ruinas y aprovechar el brasero.",
            ),
            TorchLevel(
                "Hasta el último paso",
                listOf("S.#.E", "..#..", "K.D..", "#.#R.", "T.#.C"),
                13,
                12,
                "Hay un brasero en el rincón inferior izquierdo. Comprueba si el desvío compensa.",
            ),
            TorchLevel(
                "La última antorcha",
                listOf("S.#CE", ".T#..", "K.D.R", ".##..", "...T."),
                9,
                12,
                "Usa los braseros con intención. La ruta más corta no siempre deja suficiente luz.",
            ),
        )

    fun fresh(level: Int) = TorchRun(level, levels[level].start, levels[level].light)

    fun relic(run: TorchRun) =
        run.collected and levels[run.level].relicMask == levels[run.level].relicMask

    fun allTreasure(run: TorchRun) =
        run.collected and levels[run.level].chestMask == levels[run.level].chestMask

    fun won(run: TorchRun) = levels[run.level].tiles[run.position] == 'E' && relic(run)

    fun lost(run: TorchRun) = run.light <= 0 && !won(run)

    fun stars(run: TorchRun) =
        if (!won(run)) 0
        else
            1 +
                (if (allTreasure(run)) 1 else 0) +
                (if (run.moves <= levels[run.level].par) 1 else 0)

    fun move(run: TorchRun, to: Int): TorchMove {
        if (won(run) || lost(run)) return TorchMove(run, "Reinicia para volver a jugar.")
        if (
            to !in 0..24 ||
                kotlin.math.abs(to % 5 - run.position % 5) +
                    kotlin.math.abs(to / 5 - run.position / 5) != 1
        )
            return TorchMove(run, "Toca una casilla contigua: arriba, abajo, izquierda o derecha.")
        val tile = levels[run.level].tiles[to]
        val bit = 1L shl to
        if (tile == '#') return TorchMove(run, "Un muro bloquea el paso. No has gastado luz.")
        val opened = run.doors and bit != 0L
        if (tile == 'D' && !opened && run.keys == 0)
            return TorchMove(run, "Necesitas una llave. No has gastado luz.")
        var next = run.copy(position = to, light = run.light - 1, moves = run.moves + 1)
        var message = "Elige tu siguiente paso."
        if (tile == 'D' && !opened) {
            next = next.copy(keys = next.keys - 1, doors = next.doors or bit)
            message = "Puerta abierta. Podrás cruzarla de nuevo sin llave."
        }
        if (run.collected and bit == 0L) {
            when (tile) {
                'K' -> {
                    next = next.copy(keys = next.keys + 1, collected = next.collected or bit)
                    message = "¡Llave conseguida! Ya puedes abrir una puerta."
                }
                'R' -> {
                    next = next.copy(collected = next.collected or bit)
                    message = "¡Reliquia recuperada! Busca la salida o ve a por el cofre."
                }
                'C' -> {
                    next = next.copy(collected = next.collected or bit)
                    message = "¡Cofre recogido! Ahora asegúrate de salir con la reliquia."
                }
                'T' -> {
                    next = next.copy(light = next.light + 5, collected = next.collected or bit)
                    message = "¡Brasero encendido! +5 de luz. Solo funciona una vez."
                }
            }
        }
        if (won(next)) message = "¡Has escapado con la reliquia!"
        else if (lost(next)) message = "Se ha apagado la antorcha. Prueba otra ruta."
        else if (tile == 'E' && !relic(next))
            message = "Has encontrado la salida, pero todavía falta la reliquia."
        return TorchMove(next, message, true)
    }
}
