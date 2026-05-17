package wile.tools.ktanalyzer.config

import wile.tools.ktanalyzer.api.Severity

data class AnalyzerConfig(
    val suppress: Set<String> = emptySet(),
    val severityOverrides: Map<String, Severity> = emptyMap(),
)
