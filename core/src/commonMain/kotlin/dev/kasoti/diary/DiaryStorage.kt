package dev.kasoti.diary

/**
 * The byte-level half of the diary, described as an interface.
 *
 * DESIGN.md §4 specifies the layout — a JSONL file plus a parallel `embeddings.bin` holding the
 * 128×int8 vectors *in the same record order*. `:core` is forbidden `expect`/`actual`, so the
 * file handles themselves live in `:platform`; this interface is the seam, and
 * [InMemoryDiaryStorage] is the reference implementation that proves the layout.
 *
 * Two properties matter for correctness and are therefore part of the contract rather than
 * implementation detail:
 *  - [appendLine]/[appendVector] must be **ordered and durable** — [flush] must not return
 *    until the bytes are on the device. A diary that loses its last few events after a
 *    checkpoint seizure is a diary with a chain that does not verify.
 *  - [readLines] and [readVectors] must be index-aligned. [FileDiary] refuses to load when
 *    they are not, rather than searching vectors against the wrong records — a silent
 *    misalignment would return a similarity from someone else's face, which is the worst
 *    possible failure mode for this system.
 */
interface DiaryStorage {

    fun appendLine(jsonLine: String)

    /** @param vector exactly [dev.kasoti.face.Embedding.DIM] int8 bytes, in record order. */
    fun appendVector(vector: ByteArray)

    /** Durability barrier. */
    fun flush()

    fun readLines(): List<String>

    fun readVectors(): List<ByteArray>

    /**
     * Atomically replaces the whole store. Used only by [Diary.purge] and [Diary.wipe].
     *
     * Append-only is the property that makes a torn write recoverable; rewriting throws it
     * away, so an implementation must write a temporary file and rename rather than truncate
     * in place. A crash mid-purge that empties the diary is unrecoverable data loss.
     */
    fun replaceAll(lines: List<String>, vectors: List<ByteArray>)
}

/** Reference store: byte-for-byte the same layout as the on-disk one, held in RAM. */
class InMemoryDiaryStorage : DiaryStorage {
    private val lines = mutableListOf<String>()
    private val vectors = mutableListOf<ByteArray>()

    /** Number of [flush] calls, so durability can be asserted rather than assumed. */
    var flushCount: Int = 0
        private set

    override fun appendLine(jsonLine: String) {
        lines += jsonLine
    }

    override fun appendVector(vector: ByteArray) {
        vectors += vector.copyOf()
    }

    override fun flush() {
        flushCount++
    }

    override fun readLines(): List<String> = lines.toList()

    override fun readVectors(): List<ByteArray> = vectors.map { it.copyOf() }

    override fun replaceAll(lines: List<String>, vectors: List<ByteArray>) {
        require(lines.size == vectors.size) { "lines and vectors must stay index-aligned" }
        this.lines.clear()
        this.lines += lines
        this.vectors.clear()
        this.vectors += vectors.map { it.copyOf() }
    }

    /** The `embeddings.bin` bytes this store would write, for byte-layout assertions. */
    fun vectorBytes(): ByteArray {
        val total = vectors.sumOf { it.size }
        val out = ByteArray(total)
        var at = 0
        for (v in vectors) {
            v.copyInto(out, at)
            at += v.size
        }
        return out
    }
}
