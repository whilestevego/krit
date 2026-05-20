package whilestevego.krit.engine

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
import java.net.URL
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

data class InspectionError(
    val inspectionId: String,
    val filePath: String,
    val context: String,
    val throwable: Throwable,
)

class InspectionRunner(private val commonChecksOnly: Boolean = false) {
    private val _errors = mutableListOf<InspectionError>()
    val errors: List<InspectionError> get() = _errors

    // Deduplicates per-element errors globally by (inspectionId, exceptionClass) so a single
    // broken inspection doesn't flood the log with one entry per element per file.
    private val seenElementErrors = mutableSetOf<Pair<String, String>>()

    fun runInspections(psiFile: KtFile, document: Document): List<InspectionFinding> {
        val inspections = Loader.getInspections(commonChecksOnly)
        if (inspections.isEmpty()) return emptyList()

        registerFallbackServices(psiFile.project)

        val manager = MinimalInspectionManager(psiFile.project)
        val fileRange = psiFile.textRange
        // LocalInspectionToolSession's constructor is package-private; use reflection.
        val session = SESSION_CTOR.newInstance(psiFile, fileRange, fileRange, null) as LocalInspectionToolSession
        val filePath = psiFile.virtualFile?.path ?: psiFile.name

        val findings = mutableListOf<InspectionFinding>()

        for (inspection in inspections) {
            try {
                val holder = ProblemsHolder(manager, psiFile, false)
                val visitor = inspection.buildVisitor(holder, false, session)
                psiFile.accept(object : PsiElementVisitor() {
                    override fun visitElement(element: PsiElement) {
                        runCatching { element.accept(visitor) }.exceptionOrNull()?.let { ex ->
                            val key = inspection.id to ex.javaClass.name
                            if (seenElementErrors.add(key)) {
                                _errors += InspectionError(
                                    inspectionId = inspection.id,
                                    filePath = filePath,
                                    context = "${element.javaClass.simpleName}: ${element.text.take(60)}",
                                    throwable = ex,
                                )
                            }
                        }
                        element.acceptChildren(this)
                    }
                })
                for (problem in holder.results) {
                    findings += problem.toFinding(inspection.id, document)
                }
            } catch (e: Throwable) {
                _errors += InspectionError(
                    inspectionId = inspection.id,
                    filePath = filePath,
                    context = "setup/buildVisitor",
                    throwable = e,
                )
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

        private val pluginsJar: File? by lazy {
            extractResourceJar("language-server-plugins-kotlin.jar", "krit-plugins-")
        }

        // Companion JARs that provide base classes that language-server-plugins-kotlin.jar depends on
        // but does not bundle.
        private val companionJars: List<File> by lazy {
            listOf(
                "intellij.kotlin.codeInsight.api.jar",       // AbstractKotlinInspection, KotlinApplicableInspectionBase
                "intellij.kotlin.codeInsight.shared.jar",    // MayBeConstantInspectionBase, EnumValuesSoftDeprecateInspectionBase
                "intellij.kotlin.base.codeInsight.jar",      // PsiOnlyKotlinMainFunctionDetector
                "intellij.kotlin.codeInsight.utils.jar",     // PropertyModifierUtilsKt
                "intellij.kotlin.base.psi.jar",              // TextRangeUtilsKt
                "intellij.kotlin.base.projectStructure.jar", // LanguageVersionSettingsProviderUtils
                "intellij.kotlin.base.util.jar",             // CollectionUtilsKt
                "intellij.kotlin.base.analysis.utils.jar",   // SymbolUtilsKt
                "kotlin.fir.frontend-independent.jar",       // RenameIdentifierFix, AddConstModifierFix
                // IntelliJ Platform build 262: kotlin-compiler bundles an older platform version that
                // lacks ActionsKt.runReadAction. InspectionClassLoader uses child-first delegation for
                // ActionsKt so the build-262 implementation takes precedence.
                "intellij.platform.core.jar",                // ActionsKt.runReadAction
            ).mapNotNull { extractResourceJar(it, "krit-companion-") }
        }

        private fun extractResourceJar(resourceName: String, prefix: String): File? {
            val stream = InspectionRunner::class.java.getResourceAsStream(resourceName) ?: return null
            val tmp = File.createTempFile(prefix, ".jar")
            tmp.deleteOnExit()
            stream.use { it.copyTo(tmp.outputStream()) }
            return tmp
        }

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

        private fun load(): List<LocalInspectionTool> {
            val jar = pluginsJar ?: run {
                System.err.println("krit: language-server-plugins-kotlin.jar not bundled — IDE inspections skipped")
                return emptyList()
            }
            val urls = (listOf(jar) + companionJars).map { it.toURI().toURL() }.toTypedArray()
            val loader = InspectionClassLoader(urls, InspectionRunner::class.java.classLoader)
            return scanJarForInspectionClasses(jar)
                .filter { it !in BLACKLISTED_FQNS }
                .mapNotNull { fqn -> instantiate(fqn, loader) }
                .also { System.err.println("krit: Loaded ${it.size} IDE inspections") }
        }

        // Child-first classloader for ActionsKt: kotlin-compiler bundles an older platform version
        // that lacks ActionsKt.runReadAction; intellij.platform.core.jar (build 262) provides it.
        // All other classes use standard parent-first delegation so PSI/inspection API types remain
        // consistent between the main classpath and the inspection plugins.
        private class InspectionClassLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.intellij.openapi.application.ActionsKt") {
                    synchronized(getClassLoadingLock(name)) {
                        findLoadedClass(name)?.let { c ->
                            if (resolve) resolveClass(c)
                            return c
                        }
                        return try {
                            val c = findClass(name)
                            if (resolve) resolveClass(c)
                            c
                        } catch (_: ClassNotFoundException) {
                            super.loadClass(name, resolve)
                        }
                    }
                }
                return super.loadClass(name, resolve)
            }
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

        private fun instantiate(fqn: String, loader: ClassLoader): LocalInspectionTool? {
            return try {
                val cls = loader.loadClass(fqn)
                if (java.lang.reflect.Modifier.isAbstract(cls.modifiers)) return null
                if (!LocalInspectionTool::class.java.isAssignableFrom(cls)) return null
                if (isSuperclassBlacklisted(cls)) return null
                cls.getDeclaredConstructor().newInstance() as LocalInspectionTool
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
