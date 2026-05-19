package whilestevego.krit.config

import org.yaml.snakeyaml.Yaml
import whilestevego.krit.api.Severity
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

        val classpath = buildList {
            // classpath: list of JAR/directory paths
            (raw["classpath"] as? List<*>)
                ?.filterIsInstance<String>()
                ?.map { File(it) }
                ?.filter { it.exists() }
                ?.let { addAll(it) }

            // classpath-file: path to a file whose content is the classpath (OS path-separator or newline separated)
            (raw["classpath-file"] as? String)
                ?.let { File(it) }
                ?.takeIf { it.exists() }
                ?.readText()
                ?.split(File.pathSeparatorChar, '\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.map { File(it) }
                ?.filter { it.exists() }
                ?.let { addAll(it) }
        }

        return AnalyzerConfig(suppress = suppress, severityOverrides = severityOverrides, classpath = classpath)
    }
}
