package whilestevego.ktanalyzer.report

import whilestevego.ktanalyzer.api.LintFinding
import java.io.PrintWriter

interface Reporter {
    fun report(findings: List<LintFinding>, writer: PrintWriter)
}
