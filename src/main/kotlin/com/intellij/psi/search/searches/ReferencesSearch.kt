package com.intellij.psi.search.searches

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.SearchScope
import com.intellij.util.Processor
import com.intellij.util.Query

// In standalone mode there is no project-wide index, so we restrict search to the
// containing file. We walk the PSI tree looking for leaf elements whose primary
// reference (getReference()) matches the parameter name. This is enough for
// CanBeParameterInspection, which just needs to know whether there are any usages.
@Suppress("UNUSED_PARAMETER")
object ReferencesSearch {
    @JvmStatic fun search(element: PsiElement, scope: SearchScope): Query<PsiReference> {
        val name = (element as? PsiNamedElement)?.name ?: return EmptyQuery()
        val file = element.containingFile ?: return EmptyQuery()
        val refs = mutableListOf<PsiReference>()
        file.accept(object : PsiElementVisitor() {
            override fun visitElement(e: PsiElement) {
                if (e !== element && e.text == name) {
                    val ref = e.reference
                    if (ref != null) refs.add(ref)
                }
                e.acceptChildren(this)
            }
        })
        return SimpleQuery(refs)
    }

    @JvmStatic fun search(element: PsiElement): Query<PsiReference> =
        search(element, element.useScope)
}

private class SimpleQuery<T>(private val results: List<T>) : Query<T> {
    override fun findAll(): Collection<T> = results
    override fun findFirst(): T? = results.firstOrNull()
    override fun forEach(consumer: Processor<in T>): Boolean {
        results.forEach { consumer.process(it) }
        return true
    }
}

private class EmptyQuery<T> : Query<T> {
    override fun findAll(): Collection<T> = emptyList()
    override fun findFirst(): T? = null
    override fun forEach(consumer: Processor<in T>): Boolean = true
}
