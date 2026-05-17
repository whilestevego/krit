package wile.tools.ktanalyzer.report

import wile.tools.ktanalyzer.api.LintFinding
import wile.tools.ktanalyzer.api.Severity
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
        val errors = findings.count { it.severity == Severity.ERROR }
        val warnings = findings.count { it.severity == Severity.WARNING }
        val infos = findings.count { it.severity == Severity.INFO }
        writer.println(
            "Found ${findings.size} finding(s): $errors error(s), $warnings warning(s), $infos info(s)"
        )
        writer.flush()
    }
}
