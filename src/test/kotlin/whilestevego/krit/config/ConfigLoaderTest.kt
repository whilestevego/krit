package whilestevego.krit.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import whilestevego.krit.api.Severity
import java.io.File
import java.nio.file.Files

class ConfigLoaderTest : FunSpec({

    fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("krit-config-test").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    test("file does not exist returns defaults") {
        val result = ConfigLoader.load(File("/nonexistent/krit.yml"))
        result shouldBe AnalyzerConfig()
    }

    test("empty file content returns defaults") {
        withTempDir { dir ->
            val f = File(dir, "krit.yml").apply { writeText("") }
            ConfigLoader.load(f) shouldBe AnalyzerConfig()
        }
    }

    test("suppress list is parsed") {
        withTempDir { dir ->
            val f = File(dir, "krit.yml").apply {
                writeText("suppress:\n  - RULE_A\n  - RULE_B\n")
            }
            ConfigLoader.load(f).suppress shouldBe setOf("RULE_A", "RULE_B")
        }
    }

    test("non-string items in suppress list are filtered out") {
        withTempDir { dir ->
            val f = File(dir, "krit.yml").apply {
                writeText("suppress:\n  - RULE_A\n  - 42\n  - ~\n")
            }
            ConfigLoader.load(f).suppress shouldBe setOf("RULE_A")
        }
    }

    test("severity-overrides are parsed") {
        withTempDir { dir ->
            val f = File(dir, "krit.yml").apply {
                writeText("severity-overrides:\n  RULE_X: WARNING\n")
            }
            ConfigLoader.load(f).severityOverrides shouldContainExactly mapOf("RULE_X" to Severity.WARNING)
        }
    }

    test("invalid severity value is silently skipped") {
        withTempDir { dir ->
            val f = File(dir, "krit.yml").apply {
                writeText("severity-overrides:\n  RULE_X: critical\n")
            }
            ConfigLoader.load(f).severityOverrides.shouldBeEmpty()
        }
    }

    test("non-string severity-override key is silently skipped") {
        withTempDir { dir ->
            val f = File(dir, "krit.yml").apply {
                writeText("severity-overrides:\n  42: WARNING\n")
            }
            ConfigLoader.load(f).severityOverrides.shouldBeEmpty()
        }
    }

    test("classpath with one existing and one missing path includes only existing") {
        withTempDir { dir ->
            val existing = File(dir, "dep.jar").apply { writeText("fake") }
            val f = File(dir, "krit.yml").apply {
                writeText("classpath:\n  - ${existing.absolutePath}\n  - /nonexistent/lib.jar\n")
            }
            val result = ConfigLoader.load(f).classpath
            result.map { it.absolutePath } shouldBe listOf(existing.absolutePath)
        }
    }

    test("classpath with all missing paths returns empty") {
        withTempDir { dir ->
            val f = File(dir, "krit.yml").apply {
                writeText("classpath:\n  - /nonexistent/a.jar\n  - /nonexistent/b.jar\n")
            }
            ConfigLoader.load(f).classpath.shouldBeEmpty()
        }
    }

    test("classpath-file with path-separator-delimited paths is parsed") {
        withTempDir { dir ->
            val a = File(dir, "a.jar").apply { writeText("a") }
            val b = File(dir, "b.jar").apply { writeText("b") }
            val cpFile = File(dir, "classpath.txt").apply {
                writeText("${a.absolutePath}${File.pathSeparatorChar}${b.absolutePath}")
            }
            val f = File(dir, "krit.yml").apply {
                writeText("classpath-file: ${cpFile.absolutePath}\n")
            }
            val result = ConfigLoader.load(f).classpath.map { it.absolutePath }
            result shouldBe listOf(a.absolutePath, b.absolutePath)
        }
    }

    test("classpath-file with newline-delimited paths is parsed") {
        withTempDir { dir ->
            val a = File(dir, "a.jar").apply { writeText("a") }
            val b = File(dir, "b.jar").apply { writeText("b") }
            val cpFile = File(dir, "classpath.txt").apply {
                writeText("${a.absolutePath}\n${b.absolutePath}\n")
            }
            val f = File(dir, "krit.yml").apply {
                writeText("classpath-file: ${cpFile.absolutePath}\n")
            }
            val result = ConfigLoader.load(f).classpath.map { it.absolutePath }
            result shouldBe listOf(a.absolutePath, b.absolutePath)
        }
    }

    test("classpath-file pointing to nonexistent file contributes nothing") {
        withTempDir { dir ->
            val f = File(dir, "krit.yml").apply {
                writeText("classpath-file: /nonexistent/cp.txt\n")
            }
            ConfigLoader.load(f).classpath.shouldBeEmpty()
        }
    }

    test("both classpath and classpath-file entries are merged") {
        withTempDir { dir ->
            val a = File(dir, "a.jar").apply { writeText("a") }
            val b = File(dir, "b.jar").apply { writeText("b") }
            val cpFile = File(dir, "classpath.txt").apply { writeText(b.absolutePath) }
            val f = File(dir, "krit.yml").apply {
                writeText(
                    "classpath:\n  - ${a.absolutePath}\nclasspath-file: ${cpFile.absolutePath}\n"
                )
            }
            val result = ConfigLoader.load(f).classpath.map { it.absolutePath }
            result shouldBe listOf(a.absolutePath, b.absolutePath)
        }
    }
})
