package wile.tools.krit.config

import wile.tools.krit.api.Severity

data class AnalyzerConfig(
    val suppress: Set<String> = emptySet(),
    val severityOverrides: Map<String, Severity> = emptyMap(),
)
