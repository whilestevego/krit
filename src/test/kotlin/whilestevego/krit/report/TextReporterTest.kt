package whilestevego.krit.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import whilestevego.krit.api.LintFinding
import whilestevego.krit.api.Severity
import java.io.PrintWriter
import java.io.StringWriter

class TextReporterTest : FunSpec({

    fun captureReport(findings: List<LintFinding>): String {
        val sw = StringWriter()
        TextReporter.report(findings, PrintWriter(sw))
        return sw.toString()
    }

    fun finding(
        ruleId: String = "RULE",
        message: String = "msg",
        severity: Severity = Severity.WARNING,
        filePath: String = "path/to/File.kt",
        line: Int = 10,
        column: Int = 5,
    ) = LintFinding(ruleId, message, severity, filePath, line, column)

    test("empty findings: summary is exact and no blank line precedes it") {
        val out = captureReport(emptyList())
        out shouldBe "Found 0 finding(s): 0 error(s), 0 warning(s), 0 hint(s), 0 info(s)\n"
    }

    test("ERROR severity tag is padded to 7 chars") {
        val out = captureReport(listOf(finding(severity = Severity.ERROR)))
        out shouldContain "error  "
    }

    test("WARNING severity tag is exactly 7 chars (no padding needed)") {
        val out = captureReport(listOf(finding(severity = Severity.WARNING)))
        out shouldContain "warning"
    }

    test("HINT severity tag is padded to 7 chars") {
        val out = captureReport(listOf(finding(severity = Severity.HINT)))
        out shouldContain "hint   "
    }

    test("INFO severity tag is padded to 7 chars") {
        val out = captureReport(listOf(finding(severity = Severity.INFO)))
        out shouldContain "info   "
    }

    test("non-empty findings: blank line appears between last finding and summary") {
        val out = captureReport(listOf(finding()))
        out shouldContain "\n\nFound "
    }

    test("finding line format is correct") {
        val out = captureReport(listOf(finding(ruleId = "RULE_ID", message = "the message", severity = Severity.ERROR, filePath = "path/to/File.kt", line = 10, column = 5)))
        out shouldContain "path/to/File.kt:10:5: error   [RULE_ID] the message"
    }

    test("mixed-severity counts are reported correctly") {
        val findings = listOf(
            finding(severity = Severity.ERROR),
            finding(severity = Severity.ERROR),
            finding(severity = Severity.WARNING),
        )
        val out = captureReport(findings)
        out shouldContain "Found 3 finding(s): 2 error(s), 1 warning(s), 0 hint(s), 0 info(s)"
    }
})
