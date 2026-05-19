package io.github.whilestevego.krit.config

import io.github.whilestevego.krit.api.Severity

data class AnalyzerConfig(
    val suppress: Set<String> = emptySet(),
    val severityOverrides: Map<String, Severity> = emptyMap(),
)
