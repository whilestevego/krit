package wile.tools.ktanalyzer

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import wile.tools.ktanalyzer.api.Severity
import wile.tools.ktanalyzer.config.ConfigLoader
import wile.tools.ktanalyzer.engine.AnalysisRunner
import wile.tools.ktanalyzer.engine.PsiEngine
import wile.tools.ktanalyzer.report.SarifReporter
import wile.tools.ktanalyzer.report.TextReporter
import java.io.File
import java.io.PrintWriter
import kotlin.system.exitProcess

class KtAnalyzerCommand :
    CliktCommand(
        name = "kt-analyzer",
        help = "Analyze Kotlin source files using the Kotlin compiler's native diagnostics.",
    ) {
    private val inputs by
        option("--input", "-i", help = "Source directory or .kt file to analyze (repeatable)")
            .file(mustExist = true)
            .multiple(required = true)

    private val classpath by
        option(
                "--classpath",
                "-cp",
                help = "Compile classpath for type resolution (${File.pathSeparator}-separated paths)",
            )
            .default("")

    private val configFile by
        option("--config", "-c", help = "Path to kt-analyzer.yml")
            .file()
            .default(File("config/kt-analyzer.yml"))

    private val format by
        option("--format", "-f", help = "Output format: text (default) or sarif").default("text")

    private val outputFile by
        option("--output", "-o", help = "Write output to file instead of stdout").file()

    private val failOnSeverity by
        option(
                "--fail-on-severity",
                help = "Exit with code 1 when any finding reaches this severity (WARNING or ERROR)",
            )
            .default("ERROR")

    override fun run() {
        val config = ConfigLoader.load(configFile)
        val classpathFiles =
            if (classpath.isBlank()) emptyList()
            else classpath.split(File.pathSeparatorChar).map(::File).filter { it.exists() }

        val sourceFiles = inputs.flatMap(::collectKtFiles)
        if (sourceFiles.isEmpty()) {
            echo("No Kotlin files found in the specified inputs.", err = true)
            return
        }

        PsiEngine(extraClasspath = classpathFiles).use { engine ->
            val runner = AnalysisRunner(engine, config)
            val findings = runner.analyze(inputs = inputs, sourceFiles = sourceFiles)

            val writer =
                outputFile?.let { PrintWriter(it, Charsets.UTF_8) }
                    ?: PrintWriter(System.out, true)
            try {
                when (format.lowercase()) {
                    "sarif" -> SarifReporter.report(findings, writer)
                    else -> TextReporter.report(findings, writer)
                }
            } finally {
                if (outputFile != null) writer.close()
            }

            val threshold =
                runCatching { Severity.valueOf(failOnSeverity.uppercase()) }
                    .getOrElse { Severity.ERROR }
            // IntelliJ's thread pools create non-daemon threads; exitProcess is required so
            // the JVM doesn't hang after analysis when only warnings (not errors) were found.
            exitProcess(if (findings.any { it.severity >= threshold }) 1 else 0)
        }
    }

    private fun collectKtFiles(root: File): List<File> =
        if (root.isFile && root.extension in setOf("kt", "java")) listOf(root)
        else root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList()
}

fun main(args: Array<String>) = KtAnalyzerCommand().main(args)
