package com.yuldash.app

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Успешный direct Result намеренно удержан: API сам его не отклоняет, owner обязан отказать. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FeatureBenefitOwnerBoundaryTest {
    private lateinit var job: Job
    private lateinit var scope: CoroutineScope
    private lateinit var owner: BenefitOwner

    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("benefit-owner-A")
        job = Job(); scope = CoroutineScope(job + Dispatchers.Unconfined)
        owner = BenefitOwner(ApiClient.queueSessionGeneration(), scope.coroutineContext)
    }
    @After fun cleanup() { scope.cancel(); ApiClient.resetForTest() }

    @Test fun currentOwnerAcceptsARealSuccessfulResult() {
        var accepted: String? = null
        launchBenefitOperation(scope, owner, { Result.success("A1B2C3") }) { accepted = it.getOrThrow() }
        assertEquals("A1B2C3", accepted)
    }
    @Test fun lateSuccessAfterOwnerChangeIsNotPublished() = held("owner")
    @Test fun lateSuccessAfterLogoutIsNotPublished() = held("logout")
    @Test fun lateSuccessAfterUnmountIsNotPublished() = held("unmount")
    @Test fun lateSuccessAfterHostCancellationIsNotPublished() = held("cancel")

    private fun held(retire: String) {
        val result = CompletableDeferred<Result<String>>()
        var entered = 0
        var accepted: String? = null
        launchBenefitOperation(scope, owner, { entered++; result.await() }) { accepted = it.getOrThrow() }
        assertEquals(1, entered)
        when (retire) {
            "owner" -> ApiClient.saveToken("benefit-owner-B")
            "logout" -> ApiClient.logout()
            "unmount" -> owner.alive = false
            "cancel" -> job.cancel()
        }
        result.complete(Result.success("private old code A1B2C3"))
        assertNull(accepted)
        assertFalse(owner.publish { accepted = "stale direct click" })
        assertNull(accepted)
    }

    @Test fun staleCallerCannotEnterOperationBeforeTheFirstSuspend() {
        var entered = 0
        ApiClient.saveToken("benefit-owner-B")
        launchBenefitOperation(scope, owner, { entered++; Result.success("old code") }) { fail("stale publication") }
        assertEquals(0, entered)
    }
}
