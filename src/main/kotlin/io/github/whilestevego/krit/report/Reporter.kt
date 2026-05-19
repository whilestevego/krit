package io.github.whilestevego.krit.report

import io.github.whilestevego.krit.api.LintFinding
import java.io.PrintWriter

interface Reporter {
    fun report(findings: List<LintFinding>, writer: PrintWriter)
}
