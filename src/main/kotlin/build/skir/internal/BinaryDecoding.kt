package build.skir.internal

import okio.BufferedSource

internal object BinaryDecoding {
    private val current = ThreadLocal<State?>()

    fun <T> withLimit(
        maxNestingDepth: Int,
        block: () -> T,
    ): T {
        require(maxNestingDepth > 0) { "maxNestingDepth must be positive" }
        val inherited = current.get()
        current.set(State(maxNestingDepth))
        return try {
            block()
        } finally {
            if (inherited == null) {
                current.remove()
            } else {
                current.set(inherited)
            }
        }
    }

    /** Rejects excessive wire nesting before recursive decoding constructs values. */
    fun requireWithinLimit(
        input: BufferedSource,
        maxNestingDepth: Int,
    ) {
        require(maxNestingDepth > 0) { "maxNestingDepth must be positive" }
        val remaining = ArrayDeque<Long>()
        remaining.addLast(1L)
        while (remaining.isNotEmpty()) {
            if (remaining.last() == 0L) {
                remaining.removeLast()
                continue
            }
            require(remaining.size <= maxNestingDepth) {
                "Maximum binary nesting depth $maxNestingDepth exceeded"
            }
            remaining[remaining.lastIndex] -= 1L
            val wire = input.readByte().toInt() and 0xFF
            val children =
                when (wire) {
                    232, 236 -> {
                        input.skip(2)
                        0L
                    }
                    233, 237, 240 -> {
                        input.skip(4)
                        0L
                    }
                    234, 238, 239, 241 -> {
                        input.skip(8)
                        0L
                    }
                    235 -> {
                        input.skip(1)
                        0L
                    }
                    243, 245 -> {
                        val length = decodeNumber(input).toLong()
                        require(length >= 0) { "Negative binary byte length" }
                        input.skip(length)
                        0L
                    }
                    247, in 251..254 -> 1L
                    248 -> 2L
                    249 -> 3L
                    250 ->
                        decodeNumber(input).toLong().also {
                            require(it >= 0) { "Negative binary element count" }
                        }
                    else -> 0L
                }
            if (children > 0) remaining.addLast(children)
        }
    }

    fun enter() {
        val state = checkNotNull(current.get()) { "Binary decoding requires a root limit" }
        if (state.depth >= state.maxNestingDepth) {
            throw IllegalArgumentException("Maximum binary nesting depth ${state.maxNestingDepth} exceeded")
        }
        state.depth += 1
    }

    fun exit() {
        val state = checkNotNull(current.get()) { "Binary decoding requires a root limit" }
        check(state.depth > 0) { "Binary decoding depth is already zero" }
        state.depth -= 1
    }

    private class State(
        val maxNestingDepth: Int,
        var depth: Int = 0,
    )
}
