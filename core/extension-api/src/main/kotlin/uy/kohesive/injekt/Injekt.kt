@file:JvmName("InjektKt")

package uy.kohesive.injekt

import uy.kohesive.injekt.api.FullTypeReference
import uy.kohesive.injekt.api.InjektScope
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

private object DefaultInjektScope : InjektScope {
    private val instances = ConcurrentHashMap<String, Any>()

    private fun typeKey(type: Type): String = when (type) {
        is Class<*> -> type.name
        is ParameterizedType -> (type.rawType as? Class<*>)?.name ?: type.typeName
        else -> type.typeName
    }

    override fun <R : Any> registerInstance(forType: Type, instance: R) {
        instances[typeKey(forType)] = instance
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R : Any> getInstance(forType: Type): R {
        val key = typeKey(forType)
        val exact = instances[key]
        if (exact != null) return exact as R

        val rawClass = when (forType) {
            is Class<*> -> forType
            is ParameterizedType -> forType.rawType as? Class<*>
            else -> null
        }
        if (rawClass != null) {
            val assignable = instances.values.firstOrNull { rawClass.isInstance(it) }
            if (assignable != null) return assignable as R
        }
        error("No registered Injekt instance for $key")
    }
}

val Injekt: InjektScope = DefaultInjektScope

inline fun <reified T : Any> registerInjekt(instance: T) {
    Injekt.registerInstance(object : FullTypeReference<T>() {}.type, instance)
    Injekt.registerInstance(T::class.java, instance)
}

inline fun <reified T : Any> injectLazy(): Lazy<T> = lazy {
    Injekt.getInstance(object : FullTypeReference<T>() {}.type)
}
