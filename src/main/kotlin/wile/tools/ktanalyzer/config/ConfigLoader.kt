package wile.tools.ktanalyzer.config

import org.yaml.snakeyaml.Yaml
import wile.tools.ktanalyzer.api.Severity
import java.io.File

object ConfigLoader {
    fun load(file: File): AnalyzerConfig {
        if (!file.exists()) return AnalyzerConfig()
        val raw = Yaml().load<Map<String, Any>>(file.readText()) ?: return AnalyzerConfig()

        val suppress = (raw["suppress"] as? List<*>)
            ?.filterIsInstance<String>()
            ?.toSet()
            ?: emptySet()

        val severityOverrides = ((raw["severity-overrides"] as? Map<*, *>) ?: emptyMap<Any, Any>())
            .entries
            .mapNotNull { (k, v) ->
                val id = k as? String ?: return@mapNotNull null
                val sev = runCatching {
                    Severity.valueOf((v as? String ?: return@mapNotNull null).uppercase())
                }.getOrNull() ?: return@mapNotNull null
                id to sev
            }
            .toMap()

        return AnalyzerConfig(suppress = suppress, severityOverrides = severityOverrides)
    }
}
