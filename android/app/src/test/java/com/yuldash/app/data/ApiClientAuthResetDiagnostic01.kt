package com.yuldash.app.data

/** Temporary read-only DIAG: booleans/counts only; no waits, resets, prefs, or auth values. */
internal fun printAuthResetDiagnostic01(stage: String, loginResultKnown: Boolean = false, loginFailed: Boolean = false) {
    val snapshotType = OfflineTripSnapshots::class.java
    val resetPending = snapshotType.getDeclaredField("resetPending").apply { isAccessible = true }
        .getBoolean(OfflineTripSnapshots)
    fun children(owner: Any, ownerType: Class<*>, name: String): List<kotlinx.coroutines.Job> {
        val scope = ownerType.getDeclaredField(name).apply { isAccessible = true }.get(owner) as kotlinx.coroutines.CoroutineScope
        return requireNotNull(scope.coroutineContext[kotlinx.coroutines.Job]).children.toList()
    }
    val snapshotChildren = children(OfflineTripSnapshots, snapshotType, "jobs")
    val apiChildren = children(ApiClient, ApiClient::class.java, "bg")
    // In-memory readiness is NOT the durable ensureResetCommitted() result: calling
    // that method here could perform cleanup and change the observed ordering.
    println("AUTH_RESET_DIAG01 stage=$stage snapshotResetPending=$resetPending" +
        " snapshotCleanupReadyInMemory=${!resetPending}" +
        " snapshotPersistenceCommitted=${OfflineTripSnapshots.persistence.value == SnapshotPersistence.COMMITTED}" +
        " snapshotChildrenTotal=${snapshotChildren.size} snapshotChildrenActive=${snapshotChildren.count { it.isActive }}" +
        " snapshotChildrenCompleted=${snapshotChildren.count { it.isCompleted }}" +
        " apiChildrenTotal=${apiChildren.size} apiChildrenActive=${apiChildren.count { it.isActive }}" +
        " apiChildrenCompleted=${apiChildren.count { it.isCompleted }}" +
        " loginResultKnown=$loginResultKnown loginFailed=$loginFailed")
}
