package whilestevego.krit

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream

class MainTest : FunSpec({

    fun stream(prefixes: List<String> = emptyList()): Pair<ByteArrayOutputStream, LineFilterStream> {
        val out = ByteArrayOutputStream()
        return out to LineFilterStream(out, prefixes)
    }

    test("line without suppressed prefix is written to delegate") {
        val (out, lfs) = stream(listOf("WARN: "))
        val bytes = "hello\n".toByteArray()
        lfs.write(bytes, 0, bytes.size)
        out.toString(Charsets.UTF_8) shouldBe "hello\n"
    }

    test("line starting with suppressed prefix is swallowed") {
        val (out, lfs) = stream(listOf("WARN: "))
        val bytes = "WARN: something bad\n".toByteArray()
        lfs.write(bytes, 0, bytes.size)
        out.toString(Charsets.UTF_8) shouldBe ""
    }

    test("line matching any one of multiple prefixes is suppressed") {
        val (out, lfs) = stream(listOf("WARN: ", "DEBUG: "))
        val bytes = "DEBUG: verbose\n".toByteArray()
        lfs.write(bytes, 0, bytes.size)
        out.toString(Charsets.UTF_8) shouldBe ""
    }

    test("empty prefix list: all lines pass through") {
        val (out, lfs) = stream(emptyList())
        val bytes = "any line\n".toByteArray()
        lfs.write(bytes, 0, bytes.size)
        out.toString(Charsets.UTF_8) shouldBe "any line\n"
    }

    test("write(bytes, off, len) produces same result as byte-by-byte write") {
        val (out1, lfs1) = stream()
        val (out2, lfs2) = stream()
        val bytes = "hello\n".toByteArray()
        lfs1.write(bytes, 0, bytes.size)
        bytes.forEach { lfs2.write(it.toInt()) }
        out1.toString(Charsets.UTF_8) shouldBe out2.toString(Charsets.UTF_8)
    }

    test("flush() with no preceding newline flushes pending buffer to delegate") {
        val (out, lfs) = stream()
        "hello".toByteArray().forEach { lfs.write(it.toInt()) }
        lfs.flush()
        out.toString(Charsets.UTF_8) shouldBe "hello\n"
    }

    test("close() flushes remaining buffer and closes delegate") {
        val (out, lfs) = stream()
        "bye".toByteArray().forEach { lfs.write(it.toInt()) }
        lfs.close()
        out.toString(Charsets.UTF_8) shouldBe "bye\n"
    }
})
