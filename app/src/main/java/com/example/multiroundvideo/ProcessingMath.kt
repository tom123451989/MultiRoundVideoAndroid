package com.example.multiroundvideo

import java.math.BigInteger
import java.util.Locale
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Mirrors the notebook's format_number(): decimal below one million, scientific above. */
internal fun formatRepetitionMultiplier(base: Int, exponent: Int): String {
    require(base >= 0) { "Join count cannot be negative." }
    require(exponent >= 0) { "Repeat rounds cannot be negative." }
    if (exponent == 0) return "1"
    if (base == 0) return "0"
    if (base == 1) return "1"

    val log10Value = exponent.toDouble() * log10(base.toDouble())
    if (!log10Value.isFinite()) return "Too large to display"
    if (log10Value < 6.0) {
        return try {
            BigInteger.valueOf(base.toLong()).pow(exponent).toString()
        } catch (_: Exception) {
            formatScientific(log10Value)
        }
    }
    return formatScientific(log10Value)
}

/** Mirrors the notebook's f"{effective_repeats:.3e}" display without converting huge integers to Double. */
internal fun formatApproximateTotalCopies(base: Int, exponent: Int): String {
    require(base >= 0) { "Join count cannot be negative." }
    require(exponent >= 0) { "Repeat rounds cannot be negative." }
    if (exponent == 0 || base == 1) return "1.000e+00"
    if (base == 0) return "0.000e+00"
    val log10Value = exponent.toDouble() * log10(base.toDouble())
    if (!log10Value.isFinite()) return "Too large to display"
    val power = floor(log10Value).toLong()
    val mantissa = 10.0.pow(log10Value - power.toDouble())
    return String.format(Locale.US, "%.3fe%+03d", mantissa, power)
}

private fun formatScientific(log10Value: Double): String {
    val exponent = floor(log10Value).toLong()
    val mantissa = 10.0.pow(log10Value - exponent.toDouble())
    return String.format(Locale.US, "%.6f × 10^%d", mantissa, exponent)
}

/** Builds atempo stages that keep each individual tempo factor in FFmpeg's 0.5–2 range with aresample protection. */
internal fun buildAtempoFilter(speed: Double, volumePercent: Double): String {
    require(speed.isFinite() && speed > 0.0) { "Speed factor must be a finite positive number." }
    require(volumePercent.isFinite() && volumePercent >= 0.0) { "Audio boost must be a finite non-negative number." }

    val filters = mutableListOf<String>()
    filters.add("aresample=async=1")

    var remaining = speed
    while (remaining > 2.0) {
        filters.add("atempo=2.0")
        remaining /= 2.0
    }
    while (remaining < 0.5) {
        filters.add("atempo=0.5")
        remaining /= 0.5
    }
    filters.add("atempo=$remaining")
    filters.add("volume=${volumePercent / 100.0}")
    return filters.joinToString(",")
}

/** Calculates estimated remaining time in seconds based on elapsed time and current progress (0.0 .. 1.0). */
internal fun calculateEtaSeconds(elapsedMillis: Long, progress: Float): Long? {
    if (progress <= 0.01f || elapsedMillis <= 1000L) return null
    if (progress >= 1.0f) return 0L
    val remainingFraction = (1.0f - progress) / progress
    val estimatedRemainingMillis = (elapsedMillis * remainingFraction).toLong()
    return (estimatedRemainingMillis / 1000L).coerceAtLeast(0L)
}

/** Formats ETA seconds as a user-friendly string (e.g., "ETA: 01:23", "ETA: 45s", or "Calculating ETA…"). */
internal fun formatEta(etaSeconds: Long?): String {
    if (etaSeconds == null) return "Calculating ETA…"
    if (etaSeconds <= 0L) return "Finishing up…"
    val hours = etaSeconds / 3600L
    val minutes = (etaSeconds % 3600L) / 60L
    val seconds = etaSeconds % 60L
    return when {
        hours > 0 -> String.format(Locale.US, "ETA: %d:%02d:%02d", hours, minutes, seconds)
        minutes > 0 -> String.format(Locale.US, "ETA: %02d:%02d", minutes, seconds)
        else -> String.format(Locale.US, "ETA: %ds", seconds)
    }
}

/** Calculates the exact speed-up factor needed to force a joined video to match target duration, keeping joins untouched. */
internal fun calculateIntelliSpeed(currentInputDurationSec: Double, joins: Int, targetDurationSec: Double): Double {
    require(currentInputDurationSec > 0.0) { "Current duration must be positive." }
    require(joins >= 1) { "Joins must be at least 1." }
    require(targetDurationSec > 0.0) { "Target duration must be positive." }

    val rawRequiredSpeed = (currentInputDurationSec * joins.toDouble()) / targetDurationSec
    return rawRequiredSpeed.coerceAtLeast(0.001)
}

/** Calculates target encoding bitrate string (e.g. "4000k") to match original file size and duration. */
internal fun calculateTargetBitrate(fileSizeBytes: Long, durationSec: Double): String {
    if (fileSizeBytes <= 0L || durationSec <= 0.0) return "1500k"
    val totalBits = fileSizeBytes * 8L
    val bitrateBps = (totalBits.toDouble() / durationSec).toLong()
    val bitrateKbps = (bitrateBps / 1000L).coerceIn(300L, 50000L)
    return "${bitrateKbps}k"
}
