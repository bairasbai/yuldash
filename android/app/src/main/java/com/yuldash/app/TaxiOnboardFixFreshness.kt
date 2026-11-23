package com.yuldash.app

/**
 * Boot-local source identity for an onboard fix. Never persist it across boot
 * or replace a buffered fix's original scope with the new socket's scope.
 */
internal data class TaxiOnboardFixScope(
    val orderId: Int,
    val sessionGeneration: Long,
    val reconnectGeneration: Long,
    val assignmentGeneration: Long,
    val captureOpenedElapsedNanos: Long,
)

/** Original OS fix time and the later sampling time, both elapsedRealtime. */
internal data class TaxiOnboardFixTimes(
    val scope: TaxiOnboardFixScope,
    val originalFixElapsedNanos: Long?,
    val sampledElapsedNanos: Long?,
    val horizontalAccuracyMeters: Float?,
)

internal data class TaxiOnboardFixPolicy(
    val maxAgeNanos: Long,
    val maxHorizontalAccuracyMeters: Float,
)

/**
 * Inert local validator for an honest caller. No wall/send time fallback, GPS
 * attestation, or protocol. Revalidate retained input with a new elapsed clock
 * after async work; scope checks apply on both passes. Policy bounds are inclusive.
 */
internal fun onboardFixAgeNanos(
    fix: TaxiOnboardFixTimes?,
    current: TaxiOnboardFixScope,
    observedElapsedNanos: Long,
    policy: TaxiOnboardFixPolicy,
): Long? {
    if (current.orderId <= 0 || current.sessionGeneration < 0L ||
        current.reconnectGeneration < 0L || current.assignmentGeneration < 0L ||
        current.captureOpenedElapsedNanos <= 0L || policy.maxAgeNanos < 0L ||
        !policy.maxHorizontalAccuracyMeters.isFinite() || policy.maxHorizontalAccuracyMeters < 0f
    ) return null
    if (fix == null || fix.scope != current) return null
    val original = fix.originalFixElapsedNanos ?: return null
    val sampled = fix.sampledElapsedNanos ?: return null
    val accuracy = fix.horizontalAccuracyMeters ?: return null
    if (original < current.captureOpenedElapsedNanos || sampled < original ||
        observedElapsedNanos < sampled || !accuracy.isFinite() ||
        accuracy < 0f || accuracy > policy.maxHorizontalAccuracyMeters
    ) return null
    val age = observedElapsedNanos - original
    return age.takeIf { it <= policy.maxAgeNanos }
}
