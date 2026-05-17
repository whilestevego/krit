package wile.tools.ktanalyzer.api

data class LintFinding(
    val ruleId: String,
    val message: String,
    val severity: Severity,
    val filePath: String,
    val line: Int,
    val column: Int,
)
