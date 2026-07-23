package com.gigagochi.app.core.webview

/** Monotonic time source kept injectable so bridge throttling is deterministic in JVM tests. */
internal fun interface BridgeMonotonicClock {
    fun nowNanos(): Long
}

internal const val BridgeRateLimitBurst = 128
internal const val BridgeRateLimitSustainedPerSecond = 32

/**
 * Constant-memory token bucket scoped to one native WebView document generation.
 *
 * Fractional tokens are represented as integer nanosecond units. This avoids wall-clock input,
 * floating-point drift and timer/sleep based tests while still refilling continuously.
 */
internal class BridgeRequestRateLimiter(
    private val clock: BridgeMonotonicClock,
    private val burst: Int = BridgeRateLimitBurst,
    private val sustainedPerSecond: Int = BridgeRateLimitSustainedPerSecond,
) {
    init {
        require(burst > 0)
        require(sustainedPerSecond > 0)
        require(burst.toLong() <= Long.MAX_VALUE / UnitsPerToken)
    }

    private val capacityUnits = burst.toLong() * UnitsPerToken
    private var availableUnits = capacityUnits
    private var lastRefillNanos = clock.nowNanos()

    @Synchronized
    fun tryAcquire(): Boolean {
        refill(clock.nowNanos())
        if (availableUnits < UnitsPerToken) return false
        availableUnits -= UnitsPerToken
        return true
    }

    /** Called only when the native host invalidates its current document generation. */
    @Synchronized
    fun reset() {
        availableUnits = capacityUnits
        lastRefillNanos = clock.nowNanos()
    }

    private fun refill(nowNanos: Long) {
        // Subtraction, unlike direct ordering, remains correct across nanoTime's signed wraparound
        // as long as two samples are less than 2^63 ns apart. A genuinely backwards test clock
        // produces a non-positive delta and therefore cannot mint tokens.
        val elapsedNanos = nowNanos - lastRefillNanos
        if (elapsedNanos <= 0L) return
        lastRefillNanos = nowNanos
        val missingUnits = capacityUnits - availableUnits
        if (missingUnits == 0L) return

        // Comparing before multiplying makes the calculation saturating and overflow-safe.
        val nanosUntilFull = divideRoundingUp(missingUnits, sustainedPerSecond.toLong())
        availableUnits = if (elapsedNanos >= nanosUntilFull) {
            capacityUnits
        } else {
            availableUnits + elapsedNanos * sustainedPerSecond
        }
    }

    private fun divideRoundingUp(value: Long, divisor: Long): Long =
        value / divisor + if (value % divisor == 0L) 0L else 1L

    private companion object {
        const val UnitsPerToken = 1_000_000_000L
    }
}
