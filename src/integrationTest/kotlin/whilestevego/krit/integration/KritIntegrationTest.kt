package whilestevego.krit.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import whilestevego.krit.api.LintFinding
import whilestevego.krit.api.Severity
import whilestevego.krit.config.AnalyzerConfig
import whilestevego.krit.engine.AnalysisRunner
import whilestevego.krit.engine.PsiEngine
import java.io.File
import java.nio.file.Files

class KritIntegrationTest : FunSpec({

    fun findStdlib(): File? =
        System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .map(::File)
            .find { it.name.startsWith("kotlin-stdlib") && it.extension == "jar" }

    fun withAnalysis(
        files: List<File>,
        config: AnalyzerConfig = AnalyzerConfig(),
        commonChecksOnly: Boolean = false,
        block: (List<LintFinding>) -> Unit,
    ) {
        PsiEngine(
            extraClasspath = listOfNotNull(findStdlib()),
            commonChecksOnly = commonChecksOnly,
        ).use { engine ->
            val findings = AnalysisRunner(engine, config).analyze(inputs = files, sourceFiles = files)
            block(findings)
        }
    }

    fun writeFixture(dir: File, name: String, content: String): File =
        File(dir, name).apply { writeText(content.trimIndent()) }

    // -------------------------------------------------------------------------

    test("type mismatch is reported") {
        val dir = Files.createTempDirectory("krit-int-test").toFile()
        try {
            val fixture = writeFixture(dir, "TypeMismatch.kt", """
                package fixtures
                fun f(): String = 42
            """)
            withAnalysis(listOf(fixture)) { findings ->
                findings.shouldNotBeEmpty()
                findings.any { it.severity == Severity.ERROR } shouldBe true
                findings.any { it.ruleId.contains("TYPE_MISMATCH") } shouldBe true
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("unresolved reference is reported") {
        val dir = Files.createTempDirectory("krit-int-test").toFile()
        try {
            val fixture = writeFixture(dir, "Unresolved.kt", """
                package fixtures
                val x = undefinedVariable
            """)
            withAnalysis(listOf(fixture)) { findings ->
                val match = findings.find { it.ruleId == "UNRESOLVED_REFERENCE" }
                match shouldNotBe null
                match!!.severity shouldBe Severity.ERROR
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("well-formed code produces no findings") {
        val dir = Files.createTempDirectory("krit-int-test").toFile()
        try {
            val fixture = writeFixture(dir, "Clean.kt", """
                package fixtures
                data class Point(val x: Double, val y: Double)
            """)
            withAnalysis(listOf(fixture)) { findings ->
                findings.shouldBeEmpty()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("multiple violations in one file are both reported, sorted by line") {
        val dir = Files.createTempDirectory("krit-int-test").toFile()
        try {
            val fixture = writeFixture(dir, "Multi.kt", """
                package fixtures
                val a = undefinedA
                val b = undefinedB
            """)
            withAnalysis(listOf(fixture)) { findings ->
                val ruleIds = findings.map { it.ruleId }
                ruleIds.count { it == "UNRESOLVED_REFERENCE" } shouldBe 2
                val lines = findings.map { it.line }
                lines shouldBe lines.sorted()
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("two files: only the violated file has findings") {
        val dir = Files.createTempDirectory("krit-int-test").toFile()
        try {
            val bad = writeFixture(dir, "Bad.kt", """
                package fixtures
                val x = undefinedVariable
            """)
            val good = writeFixture(dir, "Good.kt", """
                package fixtures
                data class Point(val x: Double, val y: Double)
            """)
            withAnalysis(listOf(bad, good)) { findings ->
                findings.shouldNotBeEmpty()
                findings.all { it.filePath.endsWith("Bad.kt") } shouldBe true
                findings.none { it.filePath.endsWith("Good.kt") } shouldBe true
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("suppress by ruleId removes that finding") {
        val dir = Files.createTempDirectory("krit-int-test").toFile()
        try {
            val fixture = writeFixture(dir, "TypeMismatch.kt", """
                package fixtures
                fun f(): String = 42
            """)
            withAnalysis(listOf(fixture)) { baseline ->
                val typeMismatchId = baseline.firstOrNull { it.ruleId.contains("TYPE_MISMATCH") }?.ruleId
                typeMismatchId shouldNotBe null
                val config = AnalyzerConfig(suppress = setOf(typeMismatchId!!))
                withAnalysis(listOf(fixture), config) { suppressed ->
                    suppressed.none { it.ruleId == typeMismatchId } shouldBe true
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("severity override changes the finding severity") {
        val dir = Files.createTempDirectory("krit-int-test").toFile()
        try {
            val fixture = writeFixture(dir, "TypeMismatch.kt", """
                package fixtures
                fun f(): String = 42
            """)
            withAnalysis(listOf(fixture)) { baseline ->
                val typeMismatchId = baseline.firstOrNull { it.ruleId.contains("TYPE_MISMATCH") }?.ruleId
                typeMismatchId shouldNotBe null
                val config = AnalyzerConfig(severityOverrides = mapOf(typeMismatchId!! to Severity.HINT))
                withAnalysis(listOf(fixture), config) { overridden ->
                    val overriddenFinding = overridden.firstOrNull { it.ruleId == typeMismatchId }
                    overriddenFinding shouldNotBe null
                    overriddenFinding!!.severity shouldBe Severity.HINT
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("commonChecksOnly smoke test: no crash, findings list returned") {
        val dir = Files.createTempDirectory("krit-int-test").toFile()
        try {
            val fixture = writeFixture(dir, "Unresolved.kt", """
                package fixtures
                val x = undefinedVariable
            """)
            withAnalysis(listOf(fixture), commonChecksOnly = true) { findings ->
                // Exact count not pinned; just verify no crash and result is a list
                findings shouldNotBe null
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    test("empty directory produces no findings") {
        val dir = Files.createTempDirectory("krit-int-test").toFile()
        try {
            withAnalysis(emptyList()) { findings ->
                findings.shouldBeEmpty()
            }
        } finally {
            dir.deleteRecursively()
        }
    }
})
