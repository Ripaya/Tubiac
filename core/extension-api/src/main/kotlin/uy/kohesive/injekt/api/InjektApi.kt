package uy.kohesive.injekt.api

import uy.kohesive.injekt.Injekt

interface InjektScope {
    fun <T : Any> get(cls: Class<T>): T
}

inline fun <reified T : Any> Injekt.get(): T = Injekt.get(T::class.java)
