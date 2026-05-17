package whilestevego.ktanalyzer.config

import whilestevego.ktanalyzer.api.Severity

data class AnalyzerConfig(
    val suppress: Set<String> = emptySet(),
    val severityOverrides: Map<String, Severity> = emptyMap(),
)
