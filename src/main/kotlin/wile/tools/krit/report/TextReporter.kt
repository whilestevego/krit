package wile.tools.krit.report

import wile.tools.krit.api.LintFinding
import wile.tools.krit.api.Severity
import java.io.PrintWriter

object TextReporter : Reporter {
    override fun report(findings: List<LintFinding>, writer: PrintWriter) {
        findings.forEach { finding ->
            val tag = finding.severity.name.lowercase().padEnd(7)
            writer.println(
                "${finding.filePath}:${finding.line}:${finding.column}: $tag [${finding.ruleId}] ${finding.message}"
            )
        }
        if (findings.isNotEmpty()) writer.println()
        val errors   = findings.count { it.severity == Severity.ERROR }
        val warnings = findings.count { it.severity == Severity.WARNING }
        val hints    = findings.count { it.severity == Severity.HINT }
        val infos    = findings.count { it.severity == Severity.INFO }
        writer.println(
            "Found ${findings.size} finding(s): $errors error(s), $warnings warning(s), $hints hint(s), $infos info(s)"
        )
        writer.flush()
    }
}
