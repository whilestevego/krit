package whilestevego.krit.engine

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.InspectionProfileEntry
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.lang.ASTNode
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.ModCommandService
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.pom.PomModel
import com.intellij.pom.PomModelAspect
import com.intellij.pom.PomTransaction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.codeStyle.Indent
import com.intellij.psi.impl.source.codeStyle.IndentHelper
import com.intellij.util.ThrowableRunnable
import java.util.function.Consumer

internal fun registerFallbackServices(project: Project) {
    val app = ApplicationManager.getApplication() ?: return
    if (app.getService(ModCommandService::class.java) == null) {
        tryRegisterService(app, ModCommandService::class.java, NoOpModCommandService())
    }
    if (app.getService(IndentHelper::class.java) == null) {
        tryRegisterService(app, IndentHelper::class.java, NoOpIndentHelper())
    }
    if (project.getService(CodeStyleManager::class.java) == null) {
        tryRegisterService(project, CodeStyleManager::class.java, NoOpCodeStyleManager(project))
    }
    if (project.getService(PomModel::class.java) == null) {
        tryRegisterService(project, PomModel::class.java, NoOpPomModel())
    }
    registerMissingExtensionPoints()
}

private fun registerMissingExtensionPoints() {
    runCatching {
        val area = Extensions.getRootArea()
        if (!area.hasExtensionPoint("com.intellij.treeCopyHandler")) {
            area.registerExtensionPoint(
                "com.intellij.treeCopyHandler",
                "com.intellij.psi.impl.source.tree.TreeCopyHandler",
                ExtensionPoint.Kind.INTERFACE,
            )
        }
    }
}

private fun tryRegisterService(container: Any, serviceClass: Class<*>, instance: Any) {
    container.javaClass.methods
        .filter { it.name == "registerServiceInstance" || it.name == "registerService" }
        .forEach { method ->
            runCatching {
                when (method.parameterCount) {
                    2 -> { method.invoke(container, serviceClass, instance); return }
                    3 -> { method.invoke(container, serviceClass, instance, container); return }
                }
            }
        }
}

private class NoOpModCommandService : ModCommandService {
    override fun wrap(action: ModCommandAction): IntentionAction = throw UnsupportedOperationException()

    override fun wrapToLocalQuickFixAndIntentionActionOnPsiElement(
        action: ModCommandAction, startElement: PsiElement
    ): LocalQuickFixAndIntentionActionOnPsiElement = throw UnsupportedOperationException()

    override fun wrapToQuickFix(action: ModCommandAction): LocalQuickFix = object : LocalQuickFix {
        override fun getFamilyName(): String = "Fix"
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) = Unit
    }

    override fun unwrap(fix: LocalQuickFix): ModCommandAction? = null

    override fun psiUpdate(context: ActionContext, updater: Consumer<ModPsiUpdater>): ModCommand =
        throw UnsupportedOperationException()

    override fun <T : InspectionProfileEntry> updateOption(
        element: PsiElement, option: T, updater: Consumer<T>
    ): ModCommand = throw UnsupportedOperationException()

    override fun getPreview(command: ModCommand, context: ActionContext): IntentionPreviewInfo =
        throw UnsupportedOperationException()
}

private class NoOpCodeStyleManager(private val proj: Project) : CodeStyleManager() {
    override fun getProject(): Project = proj
    override fun reformat(element: PsiElement): PsiElement = element
    override fun reformat(element: PsiElement, canChangeWhiteSpacesOnly: Boolean): PsiElement = element
    override fun reformatRange(element: PsiElement, startOffset: Int, endOffset: Int): PsiElement = element
    override fun reformatRange(
        element: PsiElement, startOffset: Int, endOffset: Int, canChangeWhiteSpacesOnly: Boolean
    ): PsiElement = element
    override fun reformatText(file: PsiFile, startOffset: Int, endOffset: Int) = Unit
    override fun reformatText(file: PsiFile, ranges: MutableCollection<out TextRange>) = Unit
    override fun adjustLineIndent(file: PsiFile, rangeToAdjust: TextRange) = Unit
    override fun adjustLineIndent(file: PsiFile, offset: Int): Int = offset
    override fun adjustLineIndent(document: Document, offset: Int): Int = offset
    override fun isLineToBeIndented(file: PsiFile, offset: Int): Boolean = false
    override fun getLineIndent(file: PsiFile, offset: Int): String = ""
    override fun getLineIndent(document: Document, offset: Int): String = ""
    override fun getIndent(text: String, fileType: FileType): Indent = NoOpIndent
    override fun fillIndent(indent: Indent, fileType: FileType): String = ""
    override fun zeroIndent(): Indent = NoOpIndent
    override fun reformatNewlyAddedElement(block: ASTNode, addedElement: ASTNode) = Unit
    override fun isSequentialProcessingAllowed(): Boolean = true
    override fun performActionWithFormatterDisabled(r: Runnable) = r.run()
    override fun <T : Throwable> performActionWithFormatterDisabled(r: ThrowableRunnable<T>) = r.run()
    override fun <T> performActionWithFormatterDisabled(r: Computable<T>): T = r.compute()
}

private class NoOpPomModel : PomModel {
    override fun <T : PomModelAspect> getModelAspect(aspect: Class<T>): T? = null
    override fun runTransaction(transaction: PomTransaction) = Unit
    override fun <T> getUserData(key: Key<T>): T? = null
    override fun <T> putUserData(key: Key<T>, value: T?) = Unit
}

private class NoOpIndentHelper : IndentHelper() {
    override fun getIndent(file: PsiFile, element: ASTNode): Int = 0
    override fun getIndent(file: PsiFile, element: ASTNode, includeNonSpace: Boolean): Int = 0
}

private object NoOpIndent : Indent {
    override fun isGreaterThan(indent: Indent): Boolean = false
    override fun min(indent: Indent): Indent = this
    override fun max(indent: Indent): Indent = this
    override fun add(indent: Indent): Indent = this
    override fun subtract(indent: Indent): Indent = this
    override fun isZero(): Boolean = true
}
