package whilestevego.krit.config

import whilestevego.krit.api.Severity
import java.io.File

data class AnalyzerConfig(
    val suppress: Set<String> = emptySet(),
    val severityOverrides: Map<String, Severity> = emptyMap(),
    val classpath: List<File> = emptyList(),
)
