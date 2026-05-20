package whilestevego.krit

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.optionalValue
import com.github.ajalt.clikt.parameters.types.file
import whilestevego.krit.api.Severity
import whilestevego.krit.config.ConfigLoader
import whilestevego.krit.engine.AnalysisRunner
import whilestevego.krit.engine.PsiEngine
import whilestevego.krit.report.SarifReporter
import whilestevego.krit.report.TextReporter
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import kotlin.system.exitProcess

class KritCommand :
    CliktCommand(
        name = "krit",
        help = """
            Analyze Kotlin source files using the Kotlin K2 compiler's native diagnostics and
            IDE-level inspections.

            Diagnostics cover type errors, unresolved references, and other compiler-detected
            problems. Inspections add style, correctness, and code-quality checks drawn from
            the IntelliJ Kotlin plugin.

            Findings are reported with a severity level — ERROR, WARNING, HINT, or INFO — and
            the process exits with code 1 when any finding meets the --fail-on-severity threshold
            (default: ERROR), making krit suitable for CI pipelines and pre-commit hooks.
        """.trimIndent(),
        epilog = """
            ## Examples

            Analyze a source directory:

                krit -i src/main/kotlin

            Analyze with full type resolution:

                krit -i src/main/kotlin -cp lib/dep1.jar:lib/dep2.jar

            Run common/conservative checks only (matches Kotlin Language Server defaults):

                krit -i src/main/kotlin --common-checks

            Output SARIF for GitHub Code Scanning:

                krit -i src/main/kotlin --format sarif --output report.json

            Fail on warnings in CI:

                krit -i src/main/kotlin --fail-on-severity WARNING

            ## Severity levels

            INFO < HINT < WARNING < ERROR

            ## Config file

            Place a `krit.yml` in `config/krit.yml` (or pass `--config`) to suppress rules or
            override severities:

                suppress:
                  - RULE_ID
                severity-overrides:
                  NOISY_RULE: HINT
                  CRITICAL_RULE: ERROR
        """.trimIndent(),
        printHelpOnEmptyArgs = true,
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
        option("--config", "-c", help = "Path to krit.yml")
            .file()
            .default(File("config/krit.yml"))

    private val format by
        option("--format", "-f", help = "Output format: text (default) or sarif").default("text")

    private val absolutePaths by
        option("--absolute-paths", help = "Print absolute file paths (default: paths relative to CWD)")
            .flag(default = false)

    private val outputFile by
        option("--output", "-o", help = "Write output to file instead of stdout").file()

    private val commonChecks by
        option(
                "--common-checks",
                help = "Restrict compiler diagnostics to ONLY_COMMON_CHECKERS (excludes extended checks)",
            )
            .flag(default = false)

    private val failOnSeverity by
        option(
                "--fail-on-severity",
                help = "Exit with code 1 when any finding reaches this severity (WARNING or ERROR)",
            )
            .default("ERROR")

    private val errorLog by
        option(
                "--error-log",
                metavar = "FILE",
                help = "Write inspection errors to FILE (omit FILE to use krit-errors.log)",
            )
            .file()
            .optionalValue(File("krit-errors.log"))

    override fun run() {
        val config = ConfigLoader.load(configFile)
        val classpathFiles = buildList {
            if (classpath.isNotBlank())
                addAll(classpath.split(File.pathSeparatorChar).map(::File).filter { it.exists() })
            addAll(config.classpath)
        }

        val sourceFiles = inputs.flatMap(::collectKtFiles)
        if (sourceFiles.isEmpty()) {
            echo("No Kotlin files found in the specified inputs.", err = true)
            return
        }

        PsiEngine(extraClasspath = classpathFiles, commonChecksOnly = commonChecks).use { engine ->
            val runner = AnalysisRunner(engine, config)
            val rawFindings = runner.analyze(inputs = inputs, sourceFiles = sourceFiles)
            val findings = if (absolutePaths) rawFindings else {
                val cwd = File("").canonicalFile
                rawFindings.map { f ->
                    val rel = File(f.filePath).relativeToOrNull(cwd)
                    f.copy(filePath = if (rel != null) "./${rel.path}" else f.filePath)
                }
            }

            val logFile = errorLog
            if (logFile != null) {
                val inspectionErrors = engine.inspectionErrors
                if (inspectionErrors.isNotEmpty()) {
                    logFile.printWriter(Charsets.UTF_8).use { log ->
                        log.println("krit inspection error log — ${java.time.Instant.now()}")
                        log.println("${inspectionErrors.size} error(s)\n")
                        inspectionErrors.forEach { err ->
                            log.println("[${err.inspectionId}] ${err.filePath} @ ${err.context}")
                            log.println(err.throwable.toString())
                            err.throwable.stackTrace.take(8).forEach { log.println("    at $it") }
                            log.println()
                        }
                    }
                    echo("krit: ${inspectionErrors.size} inspection error(s) written to ${logFile.path}", err = true)
                }
            }

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

fun main(args: Array<String>) {
    suppressStderrPrefixes(
        // IntelliJ registry keys accessed before the registry XML is loaded in standalone mode.
        "WARN: Attempt to load key '",
    )
    KritCommand().main(args)
}

private fun suppressStderrPrefixes(vararg prefixes: String) {
    System.setErr(PrintStream(LineFilterStream(System.err, prefixes.toList()), true, Charsets.UTF_8))
}

internal class LineFilterStream(
    private val delegate: OutputStream,
    private val suppressPrefixes: List<String>,
) : OutputStream() {
    private val buf = StringBuilder()

    override fun write(b: Int) {
        val ch = b.toChar()
        if (ch == '\n') flush() else buf.append(ch)
    }

    override fun write(bytes: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) write(bytes[i].toInt())
    }

    override fun flush() {
        val line = buf.toString()
        buf.clear()
        if (line.isNotEmpty() && suppressPrefixes.none { line.startsWith(it) }) {
            delegate.write((line + "\n").toByteArray(Charsets.UTF_8))
            delegate.flush()
        }
    }

    override fun close() {
        flush()
        delegate.close()
    }
}
