package wile.tools.ktanalyzer.engine

import com.intellij.codeInspection.ProblemHighlightType
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import wile.tools.ktanalyzer.api.LintFinding
import wile.tools.ktanalyzer.api.Severity
import wile.tools.ktanalyzer.config.AnalyzerConfig
import java.io.File

class AnalysisRunner(
    private val engine: PsiEngine,
    private val config: AnalyzerConfig,
) {
    fun analyze(inputs: List<File>, sourceFiles: List<File>): List<LintFinding> {
        engine.analyze(inputs, sourceFiles)
        val compilerFindings = engine.messages.mapNotNull { convertMessage(it) }
        val inspectionFindings = engine.inspectionFindings.mapNotNull { convertInspectionFinding(it) }
        return (compilerFindings + inspectionFindings)
            .sortedWith(compareBy({ it.filePath }, { it.line }, { it.column }))
    }

    private fun convertMessage(msg: DiagnosticMessage): LintFinding? {
        val ruleId = msg.factoryName
        if (ruleId in config.suppress) return null

        val severity = config.severityOverrides[ruleId]
            ?: when (msg.severity) {
                KaSeverity.ERROR -> Severity.ERROR
                KaSeverity.WARNING -> Severity.WARNING
                KaSeverity.INFO -> Severity.INFO
            }

        return LintFinding(
            ruleId = ruleId,
            message = msg.message,
            severity = severity,
            filePath = msg.filePath,
            line = msg.line,
            column = msg.column,
        )
    }

    private fun convertInspectionFinding(f: InspectionFinding): LintFinding? {
        if (f.inspectionId in config.suppress) return null
        if (f.severity == ProblemHighlightType.INFORMATION) return null

        val severity = config.severityOverrides[f.inspectionId]
            ?: when (f.severity) {
                ProblemHighlightType.ERROR -> Severity.ERROR
                ProblemHighlightType.WARNING,
                ProblemHighlightType.WEAK_WARNING,
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING -> Severity.WARNING
                else -> Severity.INFO
            }

        return LintFinding(
            ruleId = f.inspectionId,
            message = f.message,
            severity = severity,
            filePath = f.filePath,
            line = f.line,
            column = f.column,
        )
    }
}
