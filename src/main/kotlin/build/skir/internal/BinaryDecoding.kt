package build.skir.internal

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
