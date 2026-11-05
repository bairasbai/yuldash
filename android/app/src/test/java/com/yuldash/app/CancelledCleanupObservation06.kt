package com.yuldash.app

import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.Job
import okhttp3.mockwebserver.MockWebServer
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

private const val CLEANUP_OBSERVATION_LIMIT06 = 8

/** Observation of this test's existing fields, strictly after its original cleanup failure. */
internal fun cancelledCleanupObservation06(fixture: CancelledInTransitTest, stage: Int): String = runCatching {
    require(stage in 0..5)
    val server = cleanupField06(fixture, "server") as MockWebServer
    val observedJobs = requireNotNull(cleanupField06(fixture, "requestJobs"))
    val role = cleanupField06(fixture, "roleServed") as CountDownLatch
    val details = cleanupField06(fixture, "detailsServed") as CountDownLatch
    val gate = cleanupField06(fixture, "detailsGate") as CountDownLatch?
    val ownTrace = requireNotNull(cleanupField06(fixture, "ownTrace"))
    val serverSocket = cleanupField06(server, "serverSocket") as ServerSocket
    val queues = sampledCleanupQueues06(server)
    val jobs = sampledCleanupJobs06(observedJobs)
    "CANCEL_CLEANUP_DIAG06 scope=NONATOMIC_OBSERVATION stage=" + stage + " " + queues + " " + jobs +
        " serverSocketClosed=" + serverSocket.isClosed +
        " roleServed=" + (role.count == 0L) +
        " detailsServed=" + (details.count == 0L) +
        " gatePresent=" + (gate != null) +
        " gateReleased=" + (gate?.count == 0L) +
        " ownsTrace=" + (ApiClient.testTrace === ownTrace)
}.getOrElse {
    "CANCEL_CLEANUP_DIAG06 scope=NONATOMIC_OBSERVATION stage=" + stage +
        " queueState=INCOMPLETE_UNAVAILABLE observedJobState=INCOMPLETE_UNAVAILABLE"
}

private fun cleanupField06(value: Any, name: String): Any? =
    value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)

/** Exactly one budget is shared across BOTH existing queue lists; a ninth reference is never read. */
private fun sampledCleanupQueues06(server: MockWebServer): String {
    val runner = requireNotNull(cleanupField06(server, "taskRunner"))
    val busy = cleanupField06(runner, "busyQueues") as ArrayList<*>
    val ready = cleanupField06(runner, "readyQueues") as ArrayList<*>
    var group = 0
    var refs = 0
    var serveRefs = 0
    var closedServeSockets = 0
    var acceptRefs = 0
    var otherRefs = 0
    var foreignRefs = 0
    var refsWithFuture = 0
    var truncated = false
    while (group < 2 && !truncated) {
        val iterator = if (group == 0) busy.iterator() else ready.iterator()
        while (iterator.hasNext()) {
            if (refs == CLEANUP_OBSERVATION_LIMIT06) {
                truncated = true
                break
            }
            val queue = requireNotNull(iterator.next())
            refs++
            val task = cleanupField06(queue, "activeTask")
            val kind = task?.javaClass?.name.orEmpty()
            when {
                kind.startsWith("okhttp3.mockwebserver.MockWebServer\$serveConnection") -> {
                    serveRefs++
                    val serveTask = requireNotNull(task)
                    val taskOwner = cleanupField06(serveTask, "this\$0")
                    if (taskOwner === server) {
                        val socket = cleanupField06(serveTask, "\$raw\$inlined") as Socket
                        if (socket.isClosed) {
                            closedServeSockets++
                        }
                    } else {
                        foreignRefs++
                    }
                }
                kind.startsWith("okhttp3.mockwebserver.MockWebServer\$start") -> acceptRefs++
                kind.isNotEmpty() -> otherRefs++
            }
            val future = cleanupField06(queue, "futureTasks") as ArrayList<*>
            if (future.iterator().hasNext()) {
                refsWithFuture++
            }
        }
        group++
    }
    val empty = refs == 0
    val incomplete = empty || truncated || otherRefs > 0 || foreignRefs > 0
    val state = if (incomplete) "INCOMPLETE" else "OBSERVED_WITHIN_BOUND"
    return "queueState=" + state +
        " queueRefsSampled=" + refs +
        " queueSampleEmpty=" + empty +
        " queueSampleTruncated=" + truncated +
        " sampledServeRefs=" + serveRefs +
        " sampledClosedServeSockets=" + closedServeSockets +
        " sampledAcceptRefs=" + acceptRefs +
        " sampledOtherRefs=" + otherRefs +
        " sampledForeignRefs=" + foreignRefs +
        " sampledRefsWithFuture=" + refsWithFuture
}

/** These are only previously observed transport/parent references; they are not all process IO. */
private fun sampledCleanupJobs06(owner: Any): String {
    val observed = cleanupField06(owner, "started") as CopyOnWriteArrayList<*>
    val missingParent = cleanupField06(owner, "missingParent") as Boolean
    val iterator = observed.iterator()
    var refs = 0
    var completedRefs = 0
    while (refs < CLEANUP_OBSERVATION_LIMIT06 && iterator.hasNext()) {
        val job = iterator.next() as Job
        refs++
        if (job.isCompleted) {
            completedRefs++
        }
    }
    val truncated = iterator.hasNext()
    val empty = refs == 0
    val incomplete = empty || truncated || missingParent
    val state = if (incomplete) "INCOMPLETE" else "OBSERVED_WITHIN_BOUND"
    return "observedJobState=" + state +
        " observedJobRefsSampled=" + refs +
        " observedCompletedRefs=" + completedRefs +
        " observedJobSampleEmpty=" + empty +
        " observedJobSampleTruncated=" + truncated +
        " observedParentMissing=" + missingParent
}
