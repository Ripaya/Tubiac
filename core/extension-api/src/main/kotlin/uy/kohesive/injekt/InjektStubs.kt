package uy.kohesive.injekt

import java.util.concurrent.ConcurrentHashMap

object Injekt {
    @PublishedApi
    internal val instances = ConcurrentHashMap<Class<*>, Any>()

    fun <T : Any> register(cls: Class<T>, instance: T) {
        instances[cls] = instance
    }

    inline fun <reified T : Any> register(instance: T) {
        register(T::class.java, instance)
    }

    fun <T : Any> get(cls: Class<T>): T {
        val found = instances[cls]
        if (found != null) {
            @Suppress("UNCHECKED_CAST")
            return found as T
        }
        error("No registered Injekt instance for ${cls.name}")
    }

    inline fun <reified T : Any> get(): T = get(T::class.java)
}

inline fun <reified T : Any> injectLazy(): Lazy<T> = lazy { Injekt.get(T::class.java) }
