package wile.tools.krit.engine

import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalInspectionToolSession
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.annotation.ProblemGroup
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.psi.KtFile
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarInputStream

data class InspectionFinding(
    val inspectionId: String,
    val message: String,
    val severity: ProblemHighlightType,
    val filePath: String,
    val line: Int,
    val column: Int,
)

class InspectionRunner(private val commonChecksOnly: Boolean = false) {
    fun runInspections(psiFile: KtFile, document: Document): List<InspectionFinding> {
        val inspections = Loader.getInspections(commonChecksOnly)
        if (inspections.isEmpty()) return emptyList()

        val manager = MinimalInspectionManager(psiFile.project)
        val fileRange = psiFile.textRange
        // LocalInspectionToolSession's constructor is package-private; use reflection.
        val session = SESSION_CTOR.newInstance(psiFile, fileRange, fileRange, null) as LocalInspectionToolSession

        val findings = mutableListOf<InspectionFinding>()

        for (inspection in inspections) {
            try {
                val holder = ProblemsHolder(manager, psiFile, false)
                val visitor = inspection.buildVisitor(holder, false, session)
                psiFile.accept(object : PsiElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        runCatching { element.accept(visitor) }
                        element.acceptChildren(this)
                    }
                })
                for (problem in holder.results) {
                    findings += problem.toFinding(inspection.id, document)
                }
            } catch (_: Throwable) {
                // Inspection not compatible with standalone session — skip silently.
            }
        }
        return findings
    }

    private fun ProblemDescriptor.toFinding(inspectionId: String, document: Document): InspectionFinding {
        val elementRange = psiElement?.textRange
        val startOffset = textRangeInElement
            ?.let { it.startOffset + (elementRange?.startOffset ?: 0) }
            ?: (elementRange?.startOffset ?: 0)
        val lineIndex = document.getLineNumber(startOffset)
        val col = startOffset - document.getLineStartOffset(lineIndex) + 1
        val filePath = psiElement?.containingFile?.virtualFile?.path ?: ""
        val elementName = psiElement?.let {
            (it as? com.intellij.psi.PsiNamedElement)?.name ?: it.text?.take(40)
        } ?: "?"
        val message = descriptionTemplate
            .replace("<[^>]+>".toRegex(), "")
            .replace("#ref", elementName)
        return InspectionFinding(
            inspectionId = inspectionId,
            message = message,
            severity = highlightType,
            filePath = filePath,
            line = lineIndex + 1,
            column = col,
        )
    }

    // --- Minimal InspectionManager ------------------------------------------

    private class MinimalInspectionManager(private val project: Project) : InspectionManager() {
        override fun getProject(): Project = project

        private fun make(
            element: PsiElement,
            description: String,
            type: ProblemHighlightType,
            rangeInElement: TextRange? = null,
        ): ProblemDescriptor = object : ProblemDescriptor {
            override fun getPsiElement(): PsiElement = element
            override fun getStartElement(): PsiElement = element
            override fun getEndElement(): PsiElement = element
            override fun getDescriptionTemplate(): String = description
            override fun getFixes() = emptyArray<com.intellij.codeInspection.QuickFix<*>>()
            override fun getHighlightType(): ProblemHighlightType = type
            override fun isAfterEndOfLine(): Boolean = false
            override fun showTooltip(): Boolean = false
            override fun getLineNumber(): Int = -1
            override fun getTextRangeInElement(): TextRange? = rangeInElement
            override fun getProblemGroup(): ProblemGroup? = null
            override fun setProblemGroup(g: ProblemGroup?) {}
            override fun setTextAttributes(k: TextAttributesKey) {}
        }

        override fun createProblemDescriptor(e: PsiElement, d: String, f: LocalQuickFix?, t: ProblemHighlightType, onTheFly: Boolean): ProblemDescriptor = make(e, d, t)
        override fun createProblemDescriptor(e: PsiElement, d: String, onTheFly: Boolean, fixes: Array<out LocalQuickFix>?, t: ProblemHighlightType): ProblemDescriptor = make(e, d, t)
        override fun createProblemDescriptor(e: PsiElement, d: String, fixes: Array<out LocalQuickFix>?, t: ProblemHighlightType, onTheFly: Boolean, isAfterEndOfLine: Boolean): ProblemDescriptor = make(e, d, t)
        override fun createProblemDescriptor(s: PsiElement, end: PsiElement, d: String, t: ProblemHighlightType, onTheFly: Boolean, vararg fixes: LocalQuickFix): ProblemDescriptor = make(s, d, t)
        override fun createProblemDescriptor(e: PsiElement, range: TextRange?, d: String, t: ProblemHighlightType, onTheFly: Boolean, vararg fixes: LocalQuickFix): ProblemDescriptor = make(e, d, t, range)
        override fun createProblemDescriptor(e: PsiElement, d: String, onTheFly: Boolean, t: ProblemHighlightType, isAfterEndOfLine: Boolean, vararg fixes: LocalQuickFix): ProblemDescriptor = make(e, d, t)
        override fun createProblemDescriptor(e: PsiElement, d: String, f: LocalQuickFix?, t: ProblemHighlightType): ProblemDescriptor = make(e, d, t)
        override fun createProblemDescriptor(e: PsiElement, d: String, fixes: Array<out LocalQuickFix>?, t: ProblemHighlightType): ProblemDescriptor = make(e, d, t)
        override fun createProblemDescriptor(e: PsiElement, d: String, fixes: Array<out LocalQuickFix>?, t: ProblemHighlightType, onTheFly: Boolean): ProblemDescriptor = make(e, d, t)
        override fun createProblemDescriptor(s: PsiElement, end: PsiElement, d: String, t: ProblemHighlightType, vararg fixes: LocalQuickFix): ProblemDescriptor = make(s, d, t)
        @Suppress("SpreadOperator")
        override fun createProblemDescriptor(d: String, vararg fixes: com.intellij.codeInspection.QuickFix<*>): com.intellij.codeInspection.CommonProblemDescriptor = throw UnsupportedOperationException()
        @Suppress("SpreadOperator")
        override fun createProblemDescriptor(d: String, m: Module, vararg fixes: com.intellij.codeInspection.QuickFix<*>): com.intellij.codeInspection.ModuleProblemDescriptor = throw UnsupportedOperationException()
        override fun createNewGlobalContext(): GlobalInspectionContext = throw UnsupportedOperationException()
        override fun createNewGlobalContext(reuse: Boolean): GlobalInspectionContext = throw UnsupportedOperationException()
        override fun defaultProcessFile(tool: LocalInspectionTool, file: PsiFile): List<ProblemDescriptor> = throw UnsupportedOperationException()
    }

    // --- Lazy inspection loader ---------------------------------------------

    internal object Loader {
        val inspections: List<LocalInspectionTool> by lazy { load() }

        private data class InspectionMeta(val enabledByDefault: Boolean, val level: String)

        private val xmlMeta: Map<String, InspectionMeta> by lazy {
            pluginsJar?.let { parseInspectionMeta(it) } ?: emptyMap()
        }

        fun getInspections(commonChecksOnly: Boolean): List<LocalInspectionTool> {
            val all = inspections
            if (!commonChecksOnly) return all
            return all.filter { tool ->
                val meta = xmlMeta[tool::class.java.name] ?: return@filter true
                meta.enabledByDefault && meta.level != "INFORMATION" && meta.level != "DO_NOT_SHOW"
            }
        }

        private val BLACKLISTED_FQNS = setOf(
            "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.RemoveRedundantQualifierNameInspection",
            "org.jetbrains.kotlin.idea.codeInsight.inspections.shared.KotlinUnusedImportInspection",
            "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.UnusedVariableInspection",
            "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.KotlinUnreachableCodeInspection",
            "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.RemoveExplicitTypeArgumentsInspection",
            "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.K2MemberVisibilityCanBePrivateInspection",
            "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.VariableNeverReadInspection",
            "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.diagnosticBased.AssignedValueIsNeverReadInspection",
            "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.PublicApiImplicitTypeInspection",
            "org.jetbrains.kotlin.idea.k2.codeinsight.inspections.UnusedSymbolInspection",
        )

        private val BLACKLISTED_SUPERCLASS_FQNS = setOf(
            "org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinKtDiagnosticBasedInspectionBase",
            "org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinPsiDiagnosticBasedInspectionBase",
        )

        private val INSPECTION_PACKAGES = listOf(
            "org/jetbrains/kotlin/idea/k2/codeinsight/inspections/",
            "org/jetbrains/kotlin/idea/codeInsight/inspections/shared/",
        )

        private val lsLibDir: File? by lazy { findLsLibDir() }

        private val pluginsJar: File? by lazy {
            lsLibDir?.listFiles { f -> f.extension == "jar" }
                ?.firstOrNull { it.name == "language-server-plugins-kotlin.jar" }
        }

        private fun load(): List<LocalInspectionTool> {
            val libDir = lsLibDir
            if (libDir == null) {
                System.err.println("krit: Kotlin LS not found — IDE inspections skipped")
                return emptyList()
            }

            val pluginJars = libDir.listFiles { f -> f.extension == "jar" } ?: return emptyList()
            val jar = pluginsJar ?: return emptyList()
            // Inspection classes also need the server's main lib/ JARs (e.g. util-8.jar for
            // InvalidDataException). libDir is plugins/kotlin/lib/ — server lib/ is 3 levels up.
            val serverJars = libDir.parentFile?.parentFile?.parentFile
                ?.let { File(it, "lib") }
                ?.takeIf { it.isDirectory }
                ?.listFiles { f -> f.extension == "jar" }
                ?: emptyArray()
            val allJars = pluginJars + serverJars

            val classNames = scanJarForInspectionClasses(jar)
            val loader = URLClassLoader(
                allJars.map { it.toURI().toURL() }.toTypedArray(),
                InspectionRunner::class.java.classLoader,
            )

            return classNames
                .filter { it !in BLACKLISTED_FQNS }
                .mapNotNull { fqn -> instantiate(fqn, loader) }
                .also { System.err.println("krit: Loaded ${it.size} IDE inspections") }
        }

        private val INSPECTION_ELEMENT_RE = Regex("""<localInspection\b([^>]*)>""")
        private val ATTR_RE = Regex("""\b(\w+)="([^"]*)"""")

        private fun parseInspectionMeta(jar: File): Map<String, InspectionMeta> {
            val result = mutableMapOf<String, InspectionMeta>()
            try {
                JarInputStream(jar.inputStream()).use { jarIn ->
                    var entry = jarIn.nextJarEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".xml")) {
                            extractMeta(jarIn.readBytes().toString(Charsets.UTF_8), result)
                        }
                        entry = jarIn.nextJarEntry
                    }
                }
            } catch (_: Exception) {}
            return result
        }

        private fun extractMeta(xml: String, result: MutableMap<String, InspectionMeta>) {
            INSPECTION_ELEMENT_RE.findAll(xml).forEach { match ->
                val attrs = ATTR_RE.findAll(match.groupValues[1])
                    .associate { it.groupValues[1] to it.groupValues[2] }
                val fqn = attrs["implementationClass"] ?: return@forEach
                result[fqn] = InspectionMeta(
                    enabledByDefault = attrs["enabledByDefault"] != "false",
                    level = attrs["level"] ?: "WARNING",
                )
            }
        }

        private fun findLsLibDir(): File? {
            // macOS: ~/Library/Application Support/Zed/extensions/work/kotlin/
            val mac = File(System.getProperty("user.home"), "Library/Application Support/Zed/extensions/work/kotlin")
            if (mac.exists()) findServerLib(mac)?.let { return it }
            // Linux: ~/.local/share/zed/extensions/work/kotlin/
            val linux = File(System.getProperty("user.home"), ".local/share/zed/extensions/work/kotlin")
            if (linux.exists()) findServerLib(linux)?.let { return it }
            return null
        }

        private fun findServerLib(base: File): File? {
            val lsDir = base.listFiles()?.maxByOrNull { it.name } ?: return null
            val serverName = lsDir.name.replace("kotlin-lsp-", "kotlin-server-")
            return File(lsDir, "$serverName/plugins/kotlin/lib").takeIf { it.exists() }
        }

        private fun scanJarForInspectionClasses(jar: File): List<String> {
            val names = mutableListOf<String>()
            try {
                JarInputStream(jar.inputStream()).use { jarIn ->
                    var entry = jarIn.nextJarEntry
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && name.endsWith(".class") && !name.contains('$') &&
                            INSPECTION_PACKAGES.any { name.startsWith(it) }
                        ) {
                            names += name.removeSuffix(".class").replace('/', '.')
                        }
                        entry = jarIn.nextJarEntry
                    }
                }
            } catch (_: Exception) {}
            return names
        }

        private var javaVersionWarningEmitted = false

        private fun instantiate(fqn: String, loader: ClassLoader): LocalInspectionTool? {
            return try {
                val cls = loader.loadClass(fqn)
                if (java.lang.reflect.Modifier.isAbstract(cls.modifiers)) return null
                if (!LocalInspectionTool::class.java.isAssignableFrom(cls)) return null
                if (isSuperclassBlacklisted(cls)) return null
                cls.getDeclaredConstructor().newInstance() as LocalInspectionTool
            } catch (_: UnsupportedClassVersionError) {
                if (!javaVersionWarningEmitted) {
                    javaVersionWarningEmitted = true
                    System.err.println(
                        "krit: IDE inspections require Java 25 — use the JBR bundled with" +
                            " the Kotlin LS (see 'make analyze-semantic')"
                    )
                }
                null
            } catch (_: Throwable) { null }
        }

        private fun isSuperclassBlacklisted(cls: Class<*>): Boolean {
            var cur: Class<*>? = cls.superclass
            while (cur != null) {
                if (cur.name in BLACKLISTED_SUPERCLASS_FQNS) return true
                cur = cur.superclass
            }
            return false
        }
    }

    companion object {
        private val SESSION_CTOR by lazy {
            LocalInspectionToolSession::class.java.declaredConstructors[0].also { it.isAccessible = true }
        }
    }
}
