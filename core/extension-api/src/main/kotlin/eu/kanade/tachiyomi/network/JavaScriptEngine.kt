package eu.kanade.tachiyomi.network

import android.content.Context
import app.cash.quickjs.QuickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mihon-compatible JavaScript evaluation engine backed by QuickJS for extension sources.
 */
@Suppress("UNUSED", "UNCHECKED_CAST")
open class JavaScriptEngine(@Suppress("UNUSED_PARAMETER") context: Context) {

    open suspend fun <T> evaluate(script: String): T = withContext(Dispatchers.Default) {
        QuickJs.create().use { qjs ->
            qjs.evaluate(script) as T
        }
    }
}
