package whilestevego.krit.engine

import com.intellij.ide.plugins.DataLoader
import com.intellij.ide.plugins.PathResolver
import com.intellij.ide.plugins.PluginXmlPathResolver
import com.intellij.ide.plugins.RawPluginDescriptor
import com.intellij.ide.plugins.ReadModuleContext
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.components.KaDiagnosticCheckerFilter
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtFile
import java.io.File

data class DiagnosticMessage(
    val factoryName: String,
    val message: String,
    val severity: KaSeverity,
    val filePath: String,
    val line: Int,
    val column: Int,
)

open class PsiEngine(
    private val extraClasspath: List<File> = emptyList(),
    private val commonChecksOnly: Boolean = false,
) : AutoCloseable {
    private val disposable = Disposer.newDisposable("krit")
    private val _messages = mutableListOf<DiagnosticMessage>()
    val messages: List<DiagnosticMessage> get() = _messages
    private val _inspectionFindings = mutableListOf<InspectionFinding>()
    val inspectionFindings: List<InspectionFinding> get() = _inspectionFindings
    val inspectionErrors: List<InspectionError> get() = inspectionRunner.errors
    private val inspectionRunner = InspectionRunner(commonChecksOnly)

    /**
     * Analyzes [allSourceFiles] for diagnostics.
     *
     * @param inputs The original `--input` paths as specified by the caller (directories or
     *   individual files). Used as source roots for the analysis module so that cross-file
     *   references within each root resolve correctly.
     * @param allSourceFiles The fully expanded list of `.kt` and `.java` source files collected
     *   from [inputs]. Only `.kt` files receive diagnostic reporting; `.java` files are included
     *   as source roots for Java type resolution.
     */
    fun analyze(inputs: List<File>, allSourceFiles: List<File>) {
        val ktFilePaths = allSourceFiles.filter { it.extension == "kt" }
            .map { it.canonicalPath }.toSet()
        if (ktFilePaths.isEmpty()) return

        val sourceRoots = inputs
            .map { if (it.isDirectory) it else it.parentFile }
            .map { it.canonicalFile }
            .toSet()

        val platform = JvmPlatforms.defaultJvmPlatform
        val jdkHome = File(System.getProperty("java.home"))
        val stdlib = stdlibJar()

        patchDefaultPathResolver()

        val session = buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                this.platform = platform
                val sdk = addModule(buildKtSdkModule {
                    this.platform = platform
                    addBinaryRootsFromJdkHome(jdkHome.toPath(), isJre = false)
                    libraryName = "JDK"
                })

                val deps = addModule(buildKtLibraryModule {
                    this.platform = platform
                    stdlib?.let { addBinaryRoot(it.toPath()) }
                    extraClasspath.forEach { addBinaryRoot(it.toPath()) }
                    libraryName = "classpath"
                })

                addModule(buildKtSourceModule {
                    this.platform = platform
                    moduleName = "main"
                    sourceRoots.forEach { addSourceRoot(it.toPath()) }
                    addRegularDependency(sdk)
                    addRegularDependency(deps)
                })
            }
        }

        val ktFiles = mutableListOf<Pair<KtFile, com.intellij.openapi.editor.Document>>()
        for ((_, psiFiles) in session.modulesWithFiles) {
            for (psiFile in psiFiles) {
                if (psiFile !is KtFile) continue
                val filePath = psiFile.virtualFile?.path ?: continue
                if (filePath !in ktFilePaths) continue

                val document = psiFile.viewProvider.document ?: continue
                analyze(psiFile) {
                    val checkerFilter = if (commonChecksOnly)
                        KaDiagnosticCheckerFilter.ONLY_COMMON_CHECKERS
                    else
                        KaDiagnosticCheckerFilter.EXTENDED_AND_COMMON_CHECKERS
                    psiFile.collectDiagnostics(checkerFilter)
                        .forEach { diag ->
                            val range = diag.textRanges.firstOrNull() ?: return@forEach
                            val offset = range.startOffset
                            val lineIndex = document.getLineNumber(offset)
                            val col = offset - document.getLineStartOffset(lineIndex) + 1
                            _messages += DiagnosticMessage(
                                factoryName = diag.factoryName,
                                message = diag.defaultMessage,
                                severity = diag.severity,
                                filePath = filePath,
                                line = lineIndex + 1,
                                column = col,
                            )
                        }
                }
                ktFiles += psiFile to document
            }
        }
        // Run IDE inspections outside of analyze{} — KotlinApplicableInspectionBase creates its
        // own analysis session per element; nesting inside an outer analyze{} blocks it.
        for ((psiFile, document) in ktFiles) {
            _inspectionFindings += inspectionRunner.runInspections(psiFile, document)
        }
    }

    override fun close() {
        Disposer.dispose(disposable)
    }

    companion object {
        private var cachedStdlibJar: File? = null

        /**
         * Replaces [PluginXmlPathResolver.DEFAULT_PATH_RESOLVER] with a resolver backed by
         * pre-loaded XML content from the fat JAR.
         *
         * During [buildStandaloneAnalysisAPISession], [PluginStructureProvider] resolves plugin
         * XML via the default [PluginXmlPathResolver], which calls
         * `ResourceDataLoader.load(path)` → `classLoader.getResource(path).openStream()`.
         * Internal setup inside the session can invalidate JAR URL connections, causing
         * `openStream()` to throw `IOException` that is silently swallowed, making xi:include
         * resolution fail with "Cannot resolve /META-INF/analysis-api/...".
         *
         * The fix: read all XML bytes from the fat JAR *before* the session starts, then
         * install a [PathResolver] that falls back to those cached bytes when the runtime
         * loader returns null or throws.
         */
        // XmlReader is @ApiStatus.Internal so we call it via reflection.
        private val readModuleDescriptor: java.lang.reflect.Method by lazy {
            Class.forName("com.intellij.ide.plugins.XmlReader")
                .getDeclaredMethod(
                    "readModuleDescriptor",
                    java.io.InputStream::class.java,
                    ReadModuleContext::class.java,
                    PathResolver::class.java,
                    DataLoader::class.java,
                    String::class.java,
                    RawPluginDescriptor::class.java,
                    String::class.java,
                )
        }

        private fun callReadModuleDescriptor(
            stream: java.io.InputStream,
            context: ReadModuleContext,
            resolver: PathResolver,
            loader: DataLoader,
            base: String?,
            descriptor: RawPluginDescriptor?,
        ): RawPluginDescriptor? =
            readModuleDescriptor.invoke(null, stream, context, resolver, loader, base, descriptor, null)
                as? RawPluginDescriptor

        private fun patchDefaultPathResolver() {
            val xmlCache = mutableMapOf<String, ByteArray>()
            val selfJar = PsiEngine::class.java.protectionDomain?.codeSource?.location?.toURI()
                ?.let { File(it) }?.takeIf { it.isFile && it.extension == "jar" }
            if (selfJar != null) {
                java.util.jar.JarInputStream(selfJar.inputStream()).use { jarIn ->
                    var entry = jarIn.nextJarEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".xml")) {
                            xmlCache[entry.name] = jarIn.readBytes()
                        }
                        entry = jarIn.nextJarEntry
                    }
                }
            }

            val patchedResolver = object : PathResolver {
                // Mirrors PluginXmlPathResolver$Companion.toLoadPath():
                // strips leading '/', or prepends base/"META-INF" for bare names.
                private fun toLoadPath(relativePath: String, base: String?): String = when {
                    relativePath.startsWith('/') -> relativePath.substring(1)
                    relativePath.startsWith("intellij.") || relativePath.startsWith("kotlin.") ->
                        relativePath
                    else -> "${base ?: "META-INF"}/$relativePath"
                }

                // Mirrors PluginXmlPathResolver$Companion.getChildBase$intellij_platform_core_impl():
                // computes the directory prefix for resolving nested xi:includes.
                private fun childBase(base: String?, resolvedPath: String): String? {
                    val idx = resolvedPath.lastIndexOf('/')
                    if (idx <= 0 || resolvedPath.startsWith("META-INF/")) return base
                    val dir = resolvedPath.substring(0, idx)
                    return if (base == null) dir else "$base/$dir"
                }

                private fun openStream(dataLoader: DataLoader, path: String, required: Boolean): java.io.InputStream? =
                    try { dataLoader.load(path, required) } catch (_: Exception) { null }
                        ?: xmlCache[path]?.inputStream()

                // PathResolver.loadXIncludeReference parameter order (from Kotlin metadata):
                // (readInto, readContext, dataLoader, base, relativePath)
                override fun loadXIncludeReference(
                    readInto: RawPluginDescriptor,
                    readContext: ReadModuleContext,
                    dataLoader: DataLoader,
                    base: String?,
                    relativePath: String,
                ): Boolean {
                    val path = toLoadPath(relativePath, base)
                    val stream = openStream(dataLoader, path, false) ?: return false
                    stream.use {
                        callReadModuleDescriptor(it, readContext, this, dataLoader, childBase(base, path), readInto)
                    }
                    return true
                }

                override fun resolvePath(
                    readContext: ReadModuleContext,
                    dataLoader: DataLoader,
                    relativePath: String,
                    descriptor: RawPluginDescriptor?,
                ): RawPluginDescriptor? {
                    val path = toLoadPath(relativePath, null)
                    val stream = openStream(dataLoader, path, false) ?: return null
                    return stream.use {
                        callReadModuleDescriptor(it, readContext, this, dataLoader, null, descriptor)
                    }
                }

                override fun resolveModuleFile(
                    readContext: ReadModuleContext,
                    dataLoader: DataLoader,
                    path: String,
                    descriptor: RawPluginDescriptor?,
                ): RawPluginDescriptor {
                    val stream = openStream(dataLoader, path, true)
                        ?: throw RuntimeException("Cannot resolve $path (dataLoader=$dataLoader)")
                    return stream.use {
                        callReadModuleDescriptor(it, readContext, this, dataLoader, null, descriptor)
                    } ?: error("readModuleDescriptor returned null for $path")
                }
            }

            val field = PluginXmlPathResolver::class.java.getDeclaredField("DEFAULT_PATH_RESOLVER")
            field.isAccessible = true
            // Trigger class initialization so the static initializer runs before our write;
            // without this, a later getstatic would overwrite what we wrote.
            field.get(null)
            // sun.misc.Unsafe.staticFieldBase is terminally deprecated in Java 23+.
            // Use jdk.internal.misc.Unsafe instead (requires --add-opens java.base/jdk.internal.misc=ALL-UNNAMED).
            val internalUnsafeClass = Class.forName("jdk.internal.misc.Unsafe")
            val getUnsafe = internalUnsafeClass.getDeclaredMethod("getUnsafe")
            getUnsafe.isAccessible = true
            val unsafe = getUnsafe.invoke(null)
            val base = internalUnsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
            val offset = internalUnsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field) as Long
            internalUnsafeClass.getMethod("putReference", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                .invoke(unsafe, base, offset, patchedResolver)
        }

        /** Extracts kotlin-stdlib classes from the running fat JAR into a temp JAR so the
         *  analysis module can resolve Kotlin built-ins during type checking. */
        private fun stdlibJar(): File? {
            cachedStdlibJar?.let { if (it.exists()) return it }
            val selfJar = PsiEngine::class.java.protectionDomain?.codeSource?.location?.toURI()
                ?.let { File(it) }?.takeIf { it.isFile && it.extension == "jar" }
                ?: return null
            val tmpJar = File.createTempFile("krit-stdlib-", ".jar")
            tmpJar.deleteOnExit()
            java.util.jar.JarOutputStream(tmpJar.outputStream()).use { out ->
                java.util.jar.JarInputStream(selfJar.inputStream()).use { jarIn ->
                    var entry = jarIn.nextJarEntry
                    val stdlibPrefixes = listOf(
                        "kotlin/", "kotlinx/",
                        "META-INF/kotlin-stdlib",
                        "META-INF/proguard/kotlin-stdlib",
                    )
                    val seen = HashSet<String>()
                    while (entry != null) {
                        val name = entry.name
                        if (!entry.isDirectory && stdlibPrefixes.any { name.startsWith(it) } && seen.add(name)) {
                            out.putNextEntry(java.util.jar.JarEntry(name))
                            jarIn.copyTo(out)
                            out.closeEntry()
                        }
                        entry = jarIn.nextJarEntry
                    }
                }
            }
            cachedStdlibJar = tmpJar
            return tmpJar
        }
    }
}
