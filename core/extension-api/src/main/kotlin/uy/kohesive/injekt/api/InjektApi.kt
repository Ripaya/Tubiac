package uy.kohesive.injekt.api

import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

@Suppress("unused")
abstract class FullTypeReference<T> {
    val type: Type by lazy {
        val superClass = javaClass.genericSuperclass
        if (superClass is ParameterizedType) {
            superClass.actualTypeArguments[0]
        } else {
            Any::class.java
        }
    }
}

interface InjektFactory {
    fun <R : Any> getInstance(forType: Type): R
    fun <R : Any> getInstanceOrNull(forType: Type): R? = runCatching { getInstance<R>(forType) }.getOrNull()
}

interface InjektScope : InjektFactory {
    fun <R : Any> registerInstance(forType: Type, instance: R)
}

inline fun <reified T : Any> InjektFactory.get(): T =
    getInstance(object : FullTypeReference<T>() {}.type)
