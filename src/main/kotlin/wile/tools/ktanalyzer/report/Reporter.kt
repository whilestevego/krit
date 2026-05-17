package wile.tools.ktanalyzer.report

import wile.tools.ktanalyzer.api.LintFinding
import java.io.PrintWriter

interface Reporter {
    fun report(findings: List<LintFinding>, writer: PrintWriter)
}
