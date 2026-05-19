package whilestevego.krit.config

import whilestevego.krit.api.Severity

data class AnalyzerConfig(
    val suppress: Set<String> = emptySet(),
    val severityOverrides: Map<String, Severity> = emptyMap(),
)
