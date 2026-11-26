package uy.kohesive.injekt

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

object Injekt {
    @PublishedApi
    internal val instances = ConcurrentHashMap<KClass<*>, Any>()

    inline fun <reified T : Any> register(instance: T) {
        instances[T::class] = instance
    }

    inline fun <reified T : Any> get(): T {
        val found = instances[T::class]
        if (found != null) {
            @Suppress("UNCHECKED_CAST")
            return found as T
        }
        error("No registered Injekt instance for ${T::class.qualifiedName}")
    }
}

inline fun <reified T : Any> injectLazy(): Lazy<T> = lazy { Injekt.get<T>() }
