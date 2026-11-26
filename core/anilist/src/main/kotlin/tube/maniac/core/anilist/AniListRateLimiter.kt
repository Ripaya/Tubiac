package tube.maniac.core.anilist

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tube.maniac.core.model.RateLimitStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

/**
 * Rate-limit-aware coordinator for AniList GraphQL v2 API.
 * Inspects X-RateLimit-Remaining, X-RateLimit-Limit, and Retry-After headers,
 * dynamically adapting between 90 req/min standard and 30 req/min degraded windows.
 */
@Singleton
class AniListRateLimiter @Inject constructor() {

    private var clockMs: () -> Long = { System.currentTimeMillis() }
    private var sleeper: suspend (Long) -> Unit = { delay(it) }

    internal constructor(
        clockMs: () -> Long,
        sleeper: suspend (Long) -> Unit,
    ) : this() {
        this.clockMs = clockMs
        this.sleeper = sleeper
    }

    private val mutex = Mutex()
    private val _status = MutableStateFlow(RateLimitStatus())
    val status: StateFlow<RateLimitStatus> = _status.asStateFlow()

    private var consecutiveRateLimits = 0

    suspend fun acquirePermit() {
        val waitMs = mutex.withLock {
            val now = clockMs()
            val current = _status.value
            when {
                current.retryAfterEpochMs > now -> current.retryAfterEpochMs - now
                current.remaining <= LOW_WATERMARK -> {
                    if (current.isDegradedMode) DEGRADED_PACING_MS else STANDARD_PACING_MS
                }
                else -> 0L
            }
        }
        if (waitMs > 0L) {
            sleeper(waitMs)
        }
    }

    fun recordHeaders(
        limitHeader: String?,
        remainingHeader: String?,
        retryAfterSecondsHeader: String?,
        httpStatusCode: Int,
    ) {
        val now = clockMs()
        val limit = limitHeader?.toIntOrNull() ?: _status.value.limit
        val remaining = remainingHeader?.toIntOrNull() ?: (_status.value.remaining - 1).coerceAtLeast(0)
        val isDegraded = limit <= DEGRADED_LIMIT_THRESHOLD

        val retryAfterEpoch = if (httpStatusCode == 429) {
            consecutiveRateLimits++
            val serverSeconds = retryAfterSecondsHeader?.toLongOrNull()
            val backoffSeconds = serverSeconds ?: computeExponentialBackoffSeconds(consecutiveRateLimits)
            now + (backoffSeconds * 1_000L)
        } else {
            consecutiveRateLimits = 0
            0L
        }

        _status.value = _status.value.copy(
            remaining = if (httpStatusCode == 429) 0 else remaining,
            limit = limit,
            isDegradedMode = isDegraded,
            retryAfterEpochMs = retryAfterEpoch,
            lastSyncedAtEpochMs = if (httpStatusCode in 200..299) now else _status.value.lastSyncedAtEpochMs,
        )
    }

    fun updatePendingMutations(count: Int) {
        _status.value = _status.value.copy(pendingMutationsCount = count)
    }

    fun computeExponentialBackoffSeconds(attempt: Int): Long {
        val base = 2.0.pow(attempt.coerceIn(1, 6).toDouble()).toLong()
        return min(base, MAX_BACKOFF_SECONDS)
    }

    companion object {
        const val NORMAL_LIMIT = 90
        const val DEGRADED_LIMIT_THRESHOLD = 30
        const val LOW_WATERMARK = 3
        const val STANDARD_PACING_MS = 750L
        const val DEGRADED_PACING_MS = 2_100L
        const val MAX_BACKOFF_SECONDS = 64L
    }
}
