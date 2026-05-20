package whilestevego.krit.engine

import com.intellij.codeInspection.ProblemHighlightType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import whilestevego.krit.api.Severity
import whilestevego.krit.config.AnalyzerConfig
import java.io.File

class AnalysisRunnerTest : FunSpec({

    fun compilerMsg(
        factoryName: String,
        severity: KaSeverity = KaSeverity.WARNING,
        filePath: String = "/f.kt",
        line: Int = 1,
        column: Int = 1,
        message: String = "msg",
    ) = DiagnosticMessage(factoryName, message, severity, filePath, line, column)

    fun inspectionFinding(
        inspectionId: String,
        severity: ProblemHighlightType = ProblemHighlightType.WARNING,
        filePath: String = "/f.kt",
        line: Int = 1,
        column: Int = 1,
        message: String = "msg",
    ) = InspectionFinding(inspectionId, message, severity, filePath, line, column)

    fun engine(
        messages: List<DiagnosticMessage> = emptyList(),
        inspectionFindings: List<InspectionFinding> = emptyList(),
    ): PsiEngine = mockk<PsiEngine>(relaxed = true).also {
        every { it.messages } returns messages
        every { it.inspectionFindings } returns inspectionFindings
    }

    fun runner(config: AnalyzerConfig = AnalyzerConfig(), e: PsiEngine) =
        AnalysisRunner(e, config)

    // --- suppression ---

    test("compiler finding not in suppress is included") {
        val e = engine(messages = listOf(compilerMsg("RULE_A")))
        val findings = runner(e = e).analyze(emptyList(), emptyList())
        findings shouldHaveSize 1
        findings[0].ruleId shouldBe "RULE_A"
    }

    test("compiler finding whose ruleId is in suppress is filtered") {
        val e = engine(messages = listOf(compilerMsg("RULE_A")))
        val findings = runner(AnalyzerConfig(suppress = setOf("RULE_A")), e).analyze(emptyList(), emptyList())
        findings.shouldBeEmpty()
    }

    test("inspection finding whose inspectionId is in suppress is filtered") {
        val e = engine(inspectionFindings = listOf(inspectionFinding("MyInspection")))
        val findings = runner(AnalyzerConfig(suppress = setOf("MyInspection")), e).analyze(emptyList(), emptyList())
        findings.shouldBeEmpty()
    }

    test("inspection finding with INFORMATION highlight type is filtered regardless of suppress") {
        val e = engine(inspectionFindings = listOf(inspectionFinding("Noisy", ProblemHighlightType.INFORMATION)))
        val findings = runner(e = e).analyze(emptyList(), emptyList())
        findings.shouldBeEmpty()
    }

    // --- severity mapping: compiler ---

    test("KaSeverity.ERROR maps to Severity.ERROR") {
        val e = engine(messages = listOf(compilerMsg("R", KaSeverity.ERROR)))
        runner(e = e).analyze(emptyList(), emptyList())[0].severity shouldBe Severity.ERROR
    }

    test("KaSeverity.WARNING maps to Severity.WARNING") {
        val e = engine(messages = listOf(compilerMsg("R", KaSeverity.WARNING)))
        runner(e = e).analyze(emptyList(), emptyList())[0].severity shouldBe Severity.WARNING
    }

    test("KaSeverity.INFO maps to Severity.INFO") {
        val e = engine(messages = listOf(compilerMsg("R", KaSeverity.INFO)))
        runner(e = e).analyze(emptyList(), emptyList())[0].severity shouldBe Severity.INFO
    }

    // --- severity mapping: inspections ---

    test("ProblemHighlightType.ERROR maps to Severity.ERROR") {
        val e = engine(inspectionFindings = listOf(inspectionFinding("R", ProblemHighlightType.ERROR)))
        runner(e = e).analyze(emptyList(), emptyList())[0].severity shouldBe Severity.ERROR
    }

    test("ProblemHighlightType.WARNING maps to Severity.WARNING") {
        val e = engine(inspectionFindings = listOf(inspectionFinding("R", ProblemHighlightType.WARNING)))
        runner(e = e).analyze(emptyList(), emptyList())[0].severity shouldBe Severity.WARNING
    }

    test("ProblemHighlightType.GENERIC_ERROR_OR_WARNING maps to Severity.WARNING") {
        val e = engine(inspectionFindings = listOf(inspectionFinding("R", ProblemHighlightType.GENERIC_ERROR_OR_WARNING)))
        runner(e = e).analyze(emptyList(), emptyList())[0].severity shouldBe Severity.WARNING
    }

    test("ProblemHighlightType.WEAK_WARNING maps to Severity.HINT") {
        val e = engine(inspectionFindings = listOf(inspectionFinding("R", ProblemHighlightType.WEAK_WARNING)))
        runner(e = e).analyze(emptyList(), emptyList())[0].severity shouldBe Severity.HINT
    }

    test("unknown ProblemHighlightType (else branch) maps to Severity.HINT") {
        val e = engine(inspectionFindings = listOf(inspectionFinding("R", ProblemHighlightType.LIKE_DEPRECATED)))
        runner(e = e).analyze(emptyList(), emptyList())[0].severity shouldBe Severity.HINT
    }

    // --- severity overrides ---

    test("severityOverrides entry for compiler finding overrides default mapping") {
        val config = AnalyzerConfig(severityOverrides = mapOf("RULE_A" to Severity.HINT))
        val e = engine(messages = listOf(compilerMsg("RULE_A", KaSeverity.ERROR)))
        runner(config, e).analyze(emptyList(), emptyList())[0].severity shouldBe Severity.HINT
    }

    test("severityOverrides entry for inspection finding overrides default mapping") {
        val config = AnalyzerConfig(severityOverrides = mapOf("MyInspection" to Severity.ERROR))
        val e = engine(inspectionFindings = listOf(inspectionFinding("MyInspection", ProblemHighlightType.WEAK_WARNING)))
        runner(config, e).analyze(emptyList(), emptyList())[0].severity shouldBe Severity.ERROR
    }

    // --- sorting ---

    test("mixed compiler and inspection findings are sorted by (filePath, line, column)") {
        val e = engine(
            messages = listOf(
                compilerMsg("C1", filePath = "/b.kt", line = 5, column = 1),
                compilerMsg("C2", filePath = "/a.kt", line = 3, column = 2),
            ),
            inspectionFindings = listOf(
                inspectionFinding("I1", filePath = "/a.kt", line = 3, column = 1),
                inspectionFinding("I2", filePath = "/a.kt", line = 1, column = 1),
            ),
        )
        val findings = runner(e = e).analyze(emptyList(), emptyList())
        findings.map { it.ruleId } shouldBe listOf("I2", "I1", "C2", "C1")
    }

    // --- suppress wins over severityOverrides ---

    test("finding in both suppress and severityOverrides: suppress wins and finding is not returned") {
        val config = AnalyzerConfig(
            suppress = setOf("RULE_A"),
            severityOverrides = mapOf("RULE_A" to Severity.HINT),
        )
        val e = engine(messages = listOf(compilerMsg("RULE_A")))
        runner(config, e).analyze(emptyList(), emptyList()).shouldBeEmpty()
    }
})
