package whilestevego.krit.report

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import whilestevego.krit.api.LintFinding
import whilestevego.krit.api.Severity
import java.io.PrintWriter
import java.io.StringWriter

class SarifReporterTest : FunSpec({

    fun captureReport(findings: List<LintFinding>): JsonObject {
        val sw = StringWriter()
        SarifReporter.report(findings, PrintWriter(sw))
        return Json.parseToJsonElement(sw.toString()).jsonObject
    }

    fun rawReport(findings: List<LintFinding>): String {
        val sw = StringWriter()
        SarifReporter.report(findings, PrintWriter(sw))
        return sw.toString()
    }

    fun finding(
        ruleId: String = "RULE",
        message: String = "msg",
        severity: Severity = Severity.WARNING,
        filePath: String = "path/to/File.kt",
        line: Int = 1,
        column: Int = 1,
    ) = LintFinding(ruleId, message, severity, filePath, line, column)

    test("empty findings: results and rules arrays are empty") {
        val root = captureReport(emptyList())
        val run = root["runs"]!!.jsonArray[0].jsonObject
        run["results"]!!.jsonArray.size shouldBe 0
        run["tool"]!!.jsonObject["driver"]!!.jsonObject["rules"]!!.jsonArray.size shouldBe 0
    }

    test("schema field is set correctly") {
        val root = captureReport(emptyList())
        root["\$schema"]!!.jsonPrimitive.content shouldBe "https://json.schemastore.org/sarif-2.1.0.json"
    }

    test("version field is 2.1.0") {
        val root = captureReport(emptyList())
        root["version"]!!.jsonPrimitive.content shouldBe "2.1.0"
    }

    test("ERROR maps to level error") {
        val root = captureReport(listOf(finding(severity = Severity.ERROR)))
        val result = root["runs"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject
        result["level"]!!.jsonPrimitive.content shouldBe "error"
    }

    test("WARNING maps to level warning") {
        val root = captureReport(listOf(finding(severity = Severity.WARNING)))
        val result = root["runs"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject
        result["level"]!!.jsonPrimitive.content shouldBe "warning"
    }

    test("HINT maps to level note") {
        val root = captureReport(listOf(finding(severity = Severity.HINT)))
        val result = root["runs"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject
        result["level"]!!.jsonPrimitive.content shouldBe "note"
    }

    test("INFO maps to level note") {
        val root = captureReport(listOf(finding(severity = Severity.INFO)))
        val result = root["runs"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray[0].jsonObject
        result["level"]!!.jsonPrimitive.content shouldBe "note"
    }

    test("two findings with same ruleId produce one rule entry") {
        val root = captureReport(listOf(finding(ruleId = "DUPE"), finding(ruleId = "DUPE")))
        val rules = root["runs"]!!.jsonArray[0].jsonObject["tool"]!!.jsonObject["driver"]!!.jsonObject["rules"]!!.jsonArray
        rules.size shouldBe 1
        rules[0].jsonObject["id"]!!.jsonPrimitive.content shouldBe "DUPE"
    }

    test("rules are sorted alphabetically") {
        val root = captureReport(listOf(finding(ruleId = "ZEBRA"), finding(ruleId = "AARDVARK")))
        val rules = root["runs"]!!.jsonArray[0].jsonObject["tool"]!!.jsonObject["driver"]!!.jsonObject["rules"]!!.jsonArray
        rules[0].jsonObject["id"]!!.jsonPrimitive.content shouldBe "AARDVARK"
        rules[1].jsonObject["id"]!!.jsonPrimitive.content shouldBe "ZEBRA"
    }

    test("json() escapes backslash, double-quote, and newline in messages") {
        val raw = rawReport(listOf(finding(message = "has\\slash and \"quote\" and\nnewline")))
        raw shouldNotBe null
        // The escaped forms must appear in the raw JSON string
        raw.contains("\\\\") shouldBe true
        raw.contains("\\\"") shouldBe true
        raw.contains("\\n") shouldBe true
    }

    test("full output is parseable JSON") {
        val findings = listOf(
            finding(ruleId = "A", severity = Severity.ERROR, filePath = "A.kt", line = 1, column = 1),
            finding(ruleId = "B", severity = Severity.WARNING, filePath = "B.kt", line = 5, column = 3),
        )
        val sw = StringWriter()
        SarifReporter.report(findings, PrintWriter(sw))
        // Will throw if not valid JSON
        Json.parseToJsonElement(sw.toString()) shouldNotBe null
    }
})
