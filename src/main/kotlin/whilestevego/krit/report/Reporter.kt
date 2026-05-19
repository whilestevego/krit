package whilestevego.krit.report

import whilestevego.krit.api.LintFinding
import java.io.PrintWriter

interface Reporter {
    fun report(findings: List<LintFinding>, writer: PrintWriter)
}
