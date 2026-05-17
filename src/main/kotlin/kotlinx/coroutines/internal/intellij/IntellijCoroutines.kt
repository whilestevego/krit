package kotlinx.coroutines.internal.intellij

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

// Stub for IntelliJ's patched kotlinx-coroutines class, which is only present in IntelliJ's
// internal coroutines JAR. ThreadContext references INSTANCE.currentThreadCoroutineContext()
// on a background thread; without this class the JVM throws NoClassDefFoundError.
class IntellijCoroutines {
    fun currentThreadCoroutineContext(): CoroutineContext = EmptyCoroutineContext

    companion object {
        @JvmField
        val INSTANCE: IntellijCoroutines = IntellijCoroutines()
    }
}
