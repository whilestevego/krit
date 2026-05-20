package com.intellij.util.containers

// Reimplements com.intellij.util.containers.UtilKt from kotlin-compiler's bundled IntelliJ Platform,
// which only ships 5 utility functions (without, with, withAll, addIfNotNull, reverse) and lacks
// toArray. DuplicatesStrategy.EXCLUDE in the shadow JAR makes project classes win, so this file
// provides all 6 methods in one place.

@Suppress("UNCHECKED_CAST")
fun <K, V> Map<K, out V>.without(key: K): Map<K, V> {
    if (!containsKey(key)) return this as Map<K, V>
    if (size == 1) return emptyMap()
    val m = java.util.HashMap<K, V>(size)
    m.putAll(this as Map<K, V>)
    m.remove(key)
    return m
}

@Suppress("UNCHECKED_CAST")
fun <K, V> Map<K, out V>.with(key: K, value: V): Map<K, V> {
    val m = java.util.HashMap<K, V>(size + 1)
    m.putAll(this as Map<K, V>)
    m[key] = value
    return m
}

@Suppress("UNCHECKED_CAST")
fun <K, V> Map<K, out V>.withAll(other: Map<K, out V>): Map<K, V> {
    val m = java.util.HashMap<K, V>(size + other.size)
    m.putAll(this as Map<K, V>)
    m.putAll(other as Map<K, V>)
    return m
}

fun <T> MutableList<T>.addIfNotNull(value: T?) {
    if (value != null) add(value)
}

fun <K, V> Map<K, V>.reverse(): Map<V, K> {
    val m = java.util.HashMap<V, K>(size)
    for ((k, v) in this) m[v] = k
    return m
}

@Suppress("UNCHECKED_CAST")
fun <E> Collection<E>.toArray(empty: Array<E>): Array<E> =
    (this as java.util.Collection<Any?>).toArray(empty as Array<Any?>) as Array<E>
