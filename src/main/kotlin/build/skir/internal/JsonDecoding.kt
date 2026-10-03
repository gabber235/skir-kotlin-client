package build.skir.internal

internal object JsonDecoding {
    fun requireWithinLimit(
        input: String,
        maxNestingDepth: Int,
    ) {
        require(maxNestingDepth > 0) { "maxNestingDepth must be positive" }
        var depth = 0
        var inString = false
        var escaped = false
        input.forEach { character ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
            } else {
                when (character) {
                    '"' -> inString = true
                    '[', '{' -> {
                        depth += 1
                        require(depth <= maxNestingDepth) {
                            "Maximum JSON nesting depth $maxNestingDepth exceeded"
                        }
                    }
                    ']', '}' -> depth -= 1
                }
            }
        }
    }
}
