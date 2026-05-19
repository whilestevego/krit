package wile.tools.krit.report

import wile.tools.krit.api.LintFinding
import java.io.PrintWriter

interface Reporter {
    fun report(findings: List<LintFinding>, writer: PrintWriter)
}
