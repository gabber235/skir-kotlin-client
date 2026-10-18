package build.skir

import build.skir.internal.JsonDecoding
import build.skir.internal.StructSerializer
import build.skir.internal.UnrecognizedFields
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BinaryNestingDepthTest {
    @Test
    fun `default limit accepts a useful recursive value`() {
        val decoded = serializer.fromBytes(nestedBytes(512))

        var cursor: RecursiveValue? = decoded
        var depth = 0
        while (cursor?.child != null) {
            depth += 1
            cursor = cursor.child
        }
        assertThat(depth).isEqualTo(512)
    }

    @Test
    fun `default limit rejects excessive binary nesting`() {
        val failure =
            assertThrows<IllegalArgumentException> {
                serializer.fromBytes(nestedBytes(10_000))
            }

        assertThat(failure).hasMessageThat().contains("Maximum binary nesting depth")
    }

    @Test
    fun `excessive nesting is rejected before constructing decoded values`() {
        var constructed = 0
        val checked = recursiveSerializer(onCreate = { constructed += 1 })

        val failure =
            assertThrows<IllegalArgumentException> {
                checked.fromBytes(nestedBytes(10_000))
            }

        assertThat(failure).hasMessageThat().contains("Maximum binary nesting depth")
        assertThat(constructed).isEqualTo(0)
    }

    @Test
    fun `callers can choose a stricter binary nesting limit`() {
        val failure =
            assertThrows<IllegalArgumentException> {
                serializer.fromBytes(
                    nestedBytes(128),
                    UnrecognizedValuesPolicy.DROP,
                    maxNestingDepth = 64,
                )
            }

        assertThat(failure).hasMessageThat().contains("64")
    }

    @Test
    fun `concurrent decodes own independent depth state`() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results =
                List(2) {
                    executor.submit<Int> {
                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS))
                        var cursor: RecursiveValue? = serializer.fromBytes(nestedBytes(512))
                        var depth = 0
                        while (cursor?.child != null) {
                            depth += 1
                            cursor = cursor.child
                        }
                        depth
                    }
                }
            check(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            results.forEach { assertThat(it.get(10, TimeUnit.SECONDS)).isEqualTo(512) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `skipped unknown values observe the same depth limit`() {
        val failure =
            assertThrows<IllegalArgumentException> {
                serializer.fromBytes(
                    bytesWithUnknownNestedValue(128),
                    UnrecognizedValuesPolicy.DROP,
                    maxNestingDepth = 64,
                )
            }

        assertThat(failure).hasMessageThat().contains("64")
    }

    @Test
    fun `nested calls own their strict per call limit`() {
        val nestedFailure = AtomicReference<Throwable?>()
        lateinit var reentrant: Serializer<RecursiveValue>
        reentrant =
            recursiveSerializer {
                nestedFailure.set(
                    runCatching {
                        serializer.fromBytes(
                            nestedBytes(2),
                            UnrecognizedValuesPolicy.DROP,
                            maxNestingDepth = 1,
                        )
                    }.exceptionOrNull(),
                )
            }

        val decoded =
            reentrant.fromBytes(
                nestedBytes(1),
                UnrecognizedValuesPolicy.DROP,
                maxNestingDepth = 32,
            )

        assertThat(decoded.child).isNotNull()
        assertThat(nestedFailure.get()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(nestedFailure.get()).hasMessageThat().contains("1")
        assertThat(serializer.fromBytes(nestedBytes(1)).child).isNotNull()
    }

    @Test
    fun `json fallback does not retain binary depth state`() {
        assertThat(serializer.fromBytes(" \n [] \t".encodeToByteArray())).isEqualTo(RecursiveValue())
        assertThat(serializer.fromBytes("0".encodeToByteArray())).isEqualTo(RecursiveValue())

        val failure =
            assertThrows<IllegalArgumentException> {
                serializer.fromBytes(
                    nestedBytes(2),
                    UnrecognizedValuesPolicy.DROP,
                    maxNestingDepth = 1,
                )
            }
        assertThat(failure).hasMessageThat().contains("1")
    }

    @Test
    fun `json fallback rejects excessive nesting before parsing`() {
        val failure =
            assertThrows<IllegalArgumentException> {
                serializer.fromBytes(("[".repeat(10_000) + "]".repeat(10_000)).encodeToByteArray())
            }

        assertThat(failure).hasMessageThat().contains("Maximum JSON nesting depth")
    }

    @Test
    fun `json nesting ignores brackets inside strings and escaped quotes`() {
        JsonDecoding.requireWithinLimit("""{"value":"[{}]"}""", 1)
        JsonDecoding.requireWithinLimit("""{"value":"\\\"[{}]"}""", 1)
        val failure =
            assertThrows<IllegalArgumentException> {
                JsonDecoding.requireWithinLimit("""{"value":"\\\\","nested":{}}""", 1)
            }

        assertThat(failure).hasMessageThat().contains("Maximum JSON nesting depth 1 exceeded")
    }

    @Test
    fun `malformed json remains a parser error`() {
        val failure =
            assertThrows<IllegalArgumentException> {
                serializer.fromJsonCode("[[")
            }

        assertThat(failure).hasMessageThat().doesNotContain("Maximum JSON nesting depth")
    }

    @Test
    fun `supported json nesting survives several thread stack sizes`() {
        val json = "[".repeat(512) + "[]" + "]".repeat(512)
        listOf(1024L, 2048L, 4096L).forEach { stackSizeKib ->
            val result = AtomicReference<Result<Int>>()
            val thread =
                Thread(
                    null,
                    {
                        result.set(
                            runCatching {
                                var cursor: RecursiveValue? = serializer.fromJsonCode(json)
                                var depth = 0
                                while (cursor?.child != null) {
                                    depth += 1
                                    cursor = cursor.child
                                }
                                depth
                            },
                        )
                    },
                    "json depth stack $stackSizeKib KiB",
                    stackSizeKib * 1024,
                )
            thread.start()
            thread.join(TimeUnit.SECONDS.toMillis(10))

            assertThat(thread.isAlive).isFalse()
            assertThat(result.get().getOrThrow()).isEqualTo(512)
        }
    }

    @Test
    fun `supported nesting survives several thread stack sizes`() {
        listOf(512L, 1024L, 2048L).forEach { stackSizeKib ->
            val result = AtomicReference<Result<Int>>()
            val thread =
                Thread(
                    null,
                    {
                        result.set(
                            runCatching {
                                var cursor: RecursiveValue? = serializer.fromBytes(nestedBytes(512))
                                var depth = 0
                                while (cursor?.child != null) {
                                    depth += 1
                                    cursor = cursor.child
                                }
                                depth
                            },
                        )
                    },
                    "binary depth stack $stackSizeKib KiB",
                    stackSizeKib * 1024,
                )
            thread.start()
            thread.join(TimeUnit.SECONDS.toMillis(10))

            assertThat(thread.isAlive).isFalse()
            assertThat(result.get().getOrThrow()).isEqualTo(512)
        }
    }

    @Test
    fun `excessive nesting fails normally on several thread stack sizes`() {
        listOf(1024L, 2048L, 4096L).forEach { stackSizeKib ->
            val result = AtomicReference<Throwable?>()
            val thread =
                Thread(
                    null,
                    {
                        result.set(runCatching { serializer.fromBytes(nestedBytes(10_000)) }.exceptionOrNull())
                    },
                    "binary excessive stack $stackSizeKib KiB",
                    stackSizeKib * 1024,
                )
            thread.start()
            thread.join(TimeUnit.SECONDS.toMillis(10))

            assertThat(thread.isAlive).isFalse()
            assertThat(result.get()).isInstanceOf(IllegalArgumentException::class.java)
            assertThat(result.get()).hasMessageThat().contains("Maximum binary nesting depth")
        }
    }

    private data class RecursiveValue(
        val child: RecursiveValue? = null,
        val unrecognizedFields: UnrecognizedFields<RecursiveValue>? = null,
    )

    private data class MutableRecursiveValue(
        var child: RecursiveValue? = null,
        var unrecognizedFields: UnrecognizedFields<RecursiveValue>? = null,
    )

    private val serializer: Serializer<RecursiveValue> = recursiveSerializer()

    private fun recursiveSerializer(
        onCreate: () -> Unit = {},
        onChild: () -> Unit = {},
    ): Serializer<RecursiveValue> =
        StructSerializer<RecursiveValue, MutableRecursiveValue>(
            recordId = "test:RecursiveValue",
            doc = "",
            defaultInstance = RecursiveValue(),
            newMutableFn = {
                onCreate()
                MutableRecursiveValue(it?.child, it?.unrecognizedFields)
            },
            toFrozenFn = { RecursiveValue(it.child, it.unrecognizedFields) },
            getUnrecognizedFields = RecursiveValue::unrecognizedFields,
            setUnrecognizedFields = { value, fields -> value.unrecognizedFields = fields },
        ).let { implementation ->
            val result = Serializer(implementation)
            implementation.addField(
                name = "child",
                kotlinName = "child",
                number = 0,
                serializer = Serializers.optional(result),
                doc = "",
                getter = RecursiveValue::child,
                setter = { value, child ->
                    value.child = child
                    if (child != null) onChild()
                },
            )
            implementation.finalizeStruct()
            result
        }

    private fun nestedBytes(depth: Int): ByteArray =
        byteArrayOf('s'.code.toByte(), 'k'.code.toByte(), 'i'.code.toByte(), 'r'.code.toByte()) +
            ByteArray(depth) { 247.toByte() } +
            byteArrayOf(246.toByte())

    private fun bytesWithUnknownNestedValue(depth: Int): ByteArray =
        byteArrayOf(
            's'.code.toByte(),
            'k'.code.toByte(),
            'i'.code.toByte(),
            'r'.code.toByte(),
            248.toByte(),
            255.toByte(),
        ) + ByteArray(depth) { 247.toByte() } + byteArrayOf(246.toByte())
}
