package com.gigagochi.app.core.database

internal const val PetStatFullDecayMillis = 24L * 60L * 60L * 1_000L
private const val PetStatMax = 100

private data class DecayedStat(
    val value: Int,
    val tickAtEpochMillis: Long,
)

internal fun PetSnapshotEntity.withDecayedStats(nowEpochMillis: Long): PetSnapshotEntity {
    val hunger = decayStat(hunger, hungerTickAtEpochMillis, nowEpochMillis)
    val happiness = decayStat(happiness, happinessTickAtEpochMillis, nowEpochMillis)
    val energy = decayStat(energy, energyTickAtEpochMillis, nowEpochMillis)
    val nextMood = petMood(hunger.value, happiness.value)
    return copy(
        hunger = hunger.value,
        happiness = happiness.value,
        energy = energy.value,
        mood = nextMood,
        hungerTickAtEpochMillis = hunger.tickAtEpochMillis,
        happinessTickAtEpochMillis = happiness.tickAtEpochMillis,
        energyTickAtEpochMillis = energy.tickAtEpochMillis,
    )
}

private fun decayStat(value: Int, tickAtEpochMillis: Long, nowEpochMillis: Long): DecayedStat {
    val safeValue = value.coerceIn(0, PetStatMax)
    if (safeValue == 0 || tickAtEpochMillis <= 0L || nowEpochMillis <= tickAtEpochMillis) {
        return DecayedStat(safeValue, tickAtEpochMillis.coerceAtLeast(0L))
    }
    val elapsed = nowEpochMillis - tickAtEpochMillis
    val points = ((elapsed * PetStatMax) / PetStatFullDecayMillis)
        .coerceAtMost(safeValue.toLong())
        .toInt()
    if (points == 0) return DecayedStat(safeValue, tickAtEpochMillis)
    val consumedMillis = points.toLong() * PetStatFullDecayMillis / PetStatMax
    return DecayedStat(safeValue - points, tickAtEpochMillis + consumedMillis)
}

private fun petMood(hunger: Int, happiness: Int): String = when {
    hunger < 30 -> "hungry"
    happiness < 30 -> "sad"
    happiness > 75 && hunger > 60 -> "happy"
    else -> "idle"
}
