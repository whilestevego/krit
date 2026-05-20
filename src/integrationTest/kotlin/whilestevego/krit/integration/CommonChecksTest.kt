package whilestevego.krit.integration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import whilestevego.krit.api.LintFinding
import whilestevego.krit.config.AnalyzerConfig
import whilestevego.krit.engine.AnalysisRunner
import whilestevego.krit.engine.PsiEngine
import java.io.File
import java.nio.file.Files

/**
 * Verifies that IDE inspections expected to fire under --common-checks actually produce findings.
 *
 * Each test writes a minimal Kotlin fixture that unambiguously triggers one inspection,
 * runs the analysis with commonChecksOnly = true, and asserts the ruleId is present.
 *
 * Note on UsePropertyAccessSyntax: the fixture uses an extension function on the Java type
 * (implicit receiver) rather than an explicit `w.getName()` call. The inspection's internal
 * `propertyResolvesToSyntheticProperty` check resolves differently for the two forms under K2
 * standalone: the qualified path's `instanceof KaSyntheticJavaPropertySymbol` check fails in
 * standalone mode, while the non-qualified path's `semanticallyEquals` check succeeds.
 */
class CommonChecksTest : FunSpec({

    fun writeFixture(dir: File, name: String, content: String): File =
        File(dir, name).apply { writeText(content.trimIndent()) }

    fun findStdlib(): File? =
        System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .map(::File)
            .find { it.name.startsWith("kotlin-stdlib") && it.extension == "jar" }

    fun compileJavaToJar(javaFile: File): File? {
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler() ?: return null
        val classDir = Files.createTempDirectory("krit-classes").toFile()
        try {
            val fm = compiler.getStandardFileManager(null, null, null)
            val units = fm.getJavaFileObjectsFromFiles(listOf(javaFile))
            val task = compiler.getTask(null, fm, null, listOf("-d", classDir.absolutePath), null, units)
            if (!task.call()) return null
            val jar = File.createTempFile("krit-java-", ".jar")
            jar.deleteOnExit()
            java.util.jar.JarOutputStream(jar.outputStream()).use { jos ->
                classDir.walkTopDown().filter { it.extension == "class" }.forEach { cls ->
                    val entry = cls.relativeTo(classDir).path.replace('\\', '/')
                    jos.putNextEntry(java.util.jar.JarEntry(entry))
                    cls.inputStream().use { it.copyTo(jos) }
                    jos.closeEntry()
                }
            }
            return jar
        } finally {
            classDir.deleteRecursively()
        }
    }

    fun analyze(files: List<File>, extraClasspath: List<File> = emptyList()): List<LintFinding> {
        PsiEngine(
            extraClasspath = listOfNotNull(findStdlib()) + extraClasspath,
            commonChecksOnly = true,
        ).use { engine ->
            return AnalysisRunner(engine, AnalyzerConfig())
                .analyze(inputs = files, sourceFiles = files)
        }
    }

    // -------------------------------------------------------------------------
    // EnumValuesSoftDeprecate
    // -------------------------------------------------------------------------

    test("EnumValuesSoftDeprecate: Enum.values() call is flagged under --common-checks") {
        val dir = Files.createTempDirectory("krit-test").toFile()
        try {
            val fixture = writeFixture(dir, "EnumValues.kt", """
                package fixtures

                enum class Direction { NORTH, SOUTH, EAST, WEST }

                fun allDirections(): Array<Direction> = Direction.values()
            """)
            val findings = analyze(listOf(fixture))
            val ruleIds = findings.map { it.ruleId }
            ruleIds shouldContain "EnumValuesSoftDeprecate"
        } finally {
            dir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // PropertyName
    // -------------------------------------------------------------------------

    test("PropertyName: property with non-camelCase name is flagged under --common-checks") {
        val dir = Files.createTempDirectory("krit-test").toFile()
        try {
            val fixture = writeFixture(dir, "PropertyName.kt", """
                package fixtures

                class Config {
                    val MAX_RETRIES = 3
                }
            """)
            val findings = analyze(listOf(fixture))
            val ruleIds = findings.map { it.ruleId }
            ruleIds shouldContain "PropertyName"
        } finally {
            dir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // LocalVariableName
    // -------------------------------------------------------------------------

    test("LocalVariableName: local variable with non-camelCase name is flagged under --common-checks") {
        val dir = Files.createTempDirectory("krit-test").toFile()
        try {
            val fixture = writeFixture(dir, "LocalVariableName.kt", """
                package fixtures

                fun compute(): Int {
                    val MY_RESULT = 42
                    return MY_RESULT
                }
            """)
            val findings = analyze(listOf(fixture))
            val ruleIds = findings.map { it.ruleId }
            ruleIds shouldContain "LocalVariableName"
        } finally {
            dir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // ReplaceJavaStaticMethodWithKotlinAnalog
    // -------------------------------------------------------------------------

    test("ReplaceJavaStaticMethodWithKotlinAnalog: Collections.sort() is flagged under --common-checks") {
        val dir = Files.createTempDirectory("krit-test").toFile()
        try {
            val fixture = writeFixture(dir, "ReplaceJavaStatic.kt", """
                package fixtures

                import java.util.Collections

                fun sortList(list: MutableList<Int>) {
                    Collections.sort(list)
                }
            """)
            val findings = analyze(listOf(fixture))
            val ruleIds = findings.map { it.ruleId }
            ruleIds shouldContain "ReplaceJavaStaticMethodWithKotlinAnalog"
        } finally {
            dir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // CanBeParameter
    // -------------------------------------------------------------------------

    test("CanBeParameter: constructor property used only in init can be a plain parameter") {
        val dir = Files.createTempDirectory("krit-test").toFile()
        try {
            val fixture = writeFixture(dir, "CanBeParameter.kt", """
                package fixtures

                class Greeter(private val greeting: String) {
                    init {
                        java.util.Objects.requireNonNull(greeting)
                    }
                }
            """)
            val findings = analyze(listOf(fixture))
            val ruleIds = findings.map { it.ruleId }
            ruleIds shouldContain "CanBeParameter"
        } finally {
            dir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // LiftReturnOrAssignment
    // -------------------------------------------------------------------------

    test("LiftReturnOrAssignment: return inside if/else branches can be lifted out") {
        val dir = Files.createTempDirectory("krit-test").toFile()
        try {
            val fixture = writeFixture(dir, "LiftReturn.kt", """
                package fixtures

                fun sign(n: Int): String {
                    if (n > 0) {
                        return "positive"
                    } else {
                        return "non-positive"
                    }
                }
            """)
            val findings = analyze(listOf(fixture))
            val ruleIds = findings.map { it.ruleId }
            ruleIds shouldContain "LiftReturnOrAssignment"
        } finally {
            dir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // UsePropertyAccessSyntax
    // -------------------------------------------------------------------------

    test("UsePropertyAccessSyntax: Java getter call should use Kotlin property syntax") {
        val srcDir = Files.createTempDirectory("krit-java-src").toFile()
        val ktDir = Files.createTempDirectory("krit-test").toFile()
        try {
            // Compile Widget.java to a binary JAR so K2 can resolve it as a classpath dependency
            // (K2 standalone does not resolve Java source files in KtSourceModule)
            val javaSource = writeFixture(srcDir, "Widget.java", """
                package fixtures;
                public class Widget {
                    public String getName() { return "widget"; }
                }
            """)
            val widgetJar = compileJavaToJar(javaSource)
                ?: error("JavaCompiler not available — cannot compile Widget.java")
            val ktFixture = writeFixture(ktDir, "PropertyAccess.kt", """
                package fixtures

                fun Widget.showName(): String = getName()
            """)
            val findings = analyze(listOf(ktFixture), extraClasspath = listOf(widgetJar))
            val ruleIds = findings.map { it.ruleId }
            ruleIds shouldContain "UsePropertyAccessSyntax"
        } finally {
            srcDir.deleteRecursively()
            ktDir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // MayBeConstant
    // -------------------------------------------------------------------------

    test("MayBeConstant: object val with primitive/String literal could be const val") {
        val dir = Files.createTempDirectory("krit-test").toFile()
        try {
            val fixture = writeFixture(dir, "MayBeConstant.kt", """
                package fixtures

                object Config {
                    val TIMEOUT_MS = 5000
                    val APP_NAME = "krit"
                }
            """)
            val findings = analyze(listOf(fixture))
            findings.shouldNotBeEmpty()
            val ruleIds = findings.map { it.ruleId }
            ruleIds shouldContain "MayBeConstant"
        } finally {
            dir.deleteRecursively()
        }
    }
})
