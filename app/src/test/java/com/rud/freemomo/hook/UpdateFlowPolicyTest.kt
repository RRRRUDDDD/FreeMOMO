package com.rud.freemomo.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UpdateFlowPolicyTest {
    @Test fun automaticNotificationsAreFilteredExactly() {
        val policy = UpdateFlowPolicy()
        assertTrue(policy.isAutomaticUpgradeNotification("app_upgrade"))
        assertTrue(policy.isAutomaticUpgradeNotification("app_upgrade_dialog"))
        listOf(null, "sync", "APP_UPGRADE", "app_upgrade_progress", "user_upgrade", "").forEach {
            assertFalse(policy.isAutomaticUpgradeNotification(it))
        }
    }

    @Test fun manualCheckDoesNotAuthorizeDownload() {
        val policy = UpdateFlowPolicy()
        assertFalse(policy.mayDispatchUpgrade())
        assertFalse(policy.mayDownload())
        policy.enter(policy.begin(UpdateFlowPolicy.Kind.CHECK)).use {
            assertTrue(policy.mayDispatchUpgrade())
            assertFalse(policy.mayDownload())
        }
        assertFalse(policy.mayDispatchUpgrade())
    }

    @Test fun explicitUpgradePropagatesOnlyToItsOwnAsyncCallbackAndRetry() {
        val policy = UpdateFlowPolicy()
        val download = Any()
        val automatic = Any()
        val retry = Any()
        val operation = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        policy.enter(operation).use { assertTrue(policy.capture(download)) }
        assertFalse(policy.mayDownload())
        policy.enterBound(download).use {
            assertTrue(policy.mayDownload())
            assertTrue(policy.capture(retry))
            // An automatic callback is denied even when nested inside a manual callback.
            policy.enterBound(automatic).use { assertFalse(policy.mayDownload()) }
            assertTrue(policy.mayDownload())
        }
        policy.enterBound(retry).use { assertTrue(policy.mayDownload()) }
        assertFalse(policy.mayDownload())
    }

    @Test fun checkCannotDispatchAnImmediateDownloadWithoutApproval() {
        val policy = UpdateFlowPolicy()
        assertFalse(policy.mayDispatchUpgrade(immediateDownload = true))
        policy.enter(policy.begin(UpdateFlowPolicy.Kind.CHECK)).use {
            assertTrue(policy.mayDispatchUpgrade())
            assertFalse(policy.mayDispatchUpgrade(immediateDownload = true))
        }
        policy.enter(policy.begin(UpdateFlowPolicy.Kind.UPGRADE)).use {
            assertTrue(policy.mayDispatchUpgrade())
            assertTrue(policy.mayDispatchUpgrade(immediateDownload = true))
        }
    }

    @Test fun notificationRetryResumesTheOriginalUpgradeOnAnotherThread() {
        val policy = UpdateFlowPolicy()
        val operation = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        val token = requireNotNull(policy.exportUpgrade(operation))
        assertEquals(token, policy.exportUpgrade(operation))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Boolean> {
                assertFalse(policy.mayDownload())
                val imported = policy.importedUpgrade(token)
                assertSame(operation, imported)
                policy.enter(imported).use { policy.mayDownload() }
            }
            assertTrue(result.get(5, TimeUnit.SECONDS))
        } finally { executor.shutdownNow() }
        assertFalse(policy.mayDownload())
    }

    @Test fun missingOrForeignRetryTokenCannotBorrowTheCurrentOperation() {
        val policy = UpdateFlowPolicy()
        val operation = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        val token = requireNotNull(policy.exportUpgrade(operation))
        assertNull(UpdateFlowPolicy().importedUpgrade(token))
        assertNull(policy.exportUpgrade(policy.begin(UpdateFlowPolicy.Kind.CHECK)))
        policy.enter(operation).use {
            listOf(null, "", "unknown-token").forEach { invalid ->
                policy.enter(policy.importedUpgrade(invalid)).use {
                    assertFalse(policy.mayDownload())
                    assertFalse(policy.mayDispatchUpgrade())
                }
            }
            assertTrue(policy.mayDownload())
        }
    }

    @Test fun completionRevokesTransferredRetryAndPreservesOtherOperations() {
        val policy = UpdateFlowPolicy()
        val completed = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        val ongoing = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        val revokedToken = requireNotNull(policy.exportUpgrade(completed))
        val ongoingToken = requireNotNull(policy.exportUpgrade(ongoing))
        policy.enter(policy.importedUpgrade(revokedToken)).use {
            policy.finish(completed)
            assertFalse(policy.mayDownload())
        }
        assertNull(policy.importedUpgrade(revokedToken))
        assertNull(policy.exportUpgrade(completed))
        assertSame(ongoing, policy.importedUpgrade(ongoingToken))
    }

    @Test fun stopDownloadRevokesItsCallbacksAndNotificationRetry() {
        val policy = UpdateFlowPolicy()
        val download = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        val waitingApproval = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        val check = policy.begin(UpdateFlowPolicy.Kind.CHECK)
        val retry = Any()
        policy.enter(download).use { policy.capture(retry) }
        val token = requireNotNull(policy.exportUpgrade(download))
        policy.markDownload(download)
        policy.markDownload(check) // A check is never part of the download service.
        policy.stopDownloads()
        assertNull(policy.operationFor(retry))
        assertNull(policy.importedUpgrade(token))
        policy.enter(download).use { assertFalse(policy.mayDownload()) }
        policy.enter(waitingApproval).use { assertTrue(policy.mayDownload()) }
        policy.enter(check).use { assertTrue(policy.mayCheck()) }
        policy.stopDownloads()
        assertEquals(0, policy.bindingCount)
    }

    @Test fun callbackIdentityCannotBeBorrowedThroughEqualsOrCurrentPage() {
        data class Callback(val value: Int)
        val policy = UpdateFlowPolicy()
        val manual = Callback(1)
        val automatic = Callback(1)
        val operation = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        policy.enter(operation).use { policy.capture(manual) }
        assertSame(operation, policy.operationFor(manual))
        assertNull(policy.operationFor(automatic))
        policy.enter(operation).use {
            policy.enterBound(automatic).use { assertFalse(policy.mayDownload()) }
        }
    }

    @Test fun completionOrCancellationRevokesEveryDescendantAndInFlightScope() {
        val policy = UpdateFlowPolicy()
        val operation = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        val callback = Any()
        val retry = Any()
        policy.enter(operation).use {
            policy.capture(callback)
            policy.capture(retry)
            policy.finish(operation)
            assertFalse(policy.mayDownload())
            assertFalse(policy.capture(Any()))
        }
        assertNull(policy.operationFor(callback))
        assertNull(policy.operationFor(retry))
        policy.enterBound(retry).use { assertFalse(policy.mayDownload()) }
        policy.finish(operation) // Duplicate terminal events are harmless.
        assertEquals(0, policy.bindingCount)
    }

    @Test fun independentOperationsDoNotCancelEachOther() {
        val policy = UpdateFlowPolicy()
        val first = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        val second = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        val firstCallback = Any()
        val secondCallback = Any()
        policy.enter(first).use { policy.capture(firstCallback) }
        policy.enter(second).use { policy.capture(secondCallback) }
        policy.finish(first)
        policy.enterBound(firstCallback).use { assertFalse(policy.mayDownload()) }
        policy.enterBound(secondCallback).use { assertTrue(policy.mayDownload()) }
    }

    @Test fun callbackReuseCannotChangeAnActiveOperationIdentity() {
        val policy = UpdateFlowPolicy()
        val callback = Any()
        val first = policy.begin(UpdateFlowPolicy.Kind.CHECK)
        val second = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        policy.enter(first).use { assertTrue(policy.capture(callback)) }
        policy.enter(second).use { assertFalse(policy.capture(callback)) }
        policy.enterBound(callback).use { assertFalse(policy.mayDownload()) }
        policy.finish(first)
        policy.enter(second).use { assertTrue(policy.capture(callback)) }
    }

    @Test fun threadScopesAreIsolatedButCapturedCallbackCanResumeOnAnotherThread() {
        val policy = UpdateFlowPolicy()
        val executor = Executors.newSingleThreadExecutor()
        val ready = CountDownLatch(1)
        val callback = Any()
        try {
            policy.enter(policy.begin(UpdateFlowPolicy.Kind.UPGRADE)).use {
                policy.capture(callback)
                val task = executor.submit<Boolean> {
                    assertFalse(policy.mayDownload())
                    policy.enterBound(callback).use {
                        ready.countDown()
                        policy.mayDownload()
                    }
                }
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                assertTrue(task.get(5, TimeUnit.SECONDS))
                assertTrue(policy.mayDownload())
            }
        } finally { executor.shutdownNow() }
        assertFalse(policy.mayDownload())
    }

    @Test fun exceptionRestoresOuterScopeWithoutLeakingAnAuthorization() {
        val policy = UpdateFlowPolicy()
        val operation = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        policy.enter(operation).use {
            runCatching { policy.enter(null).use { error("callback failed") } }
            assertTrue(policy.mayDownload())
        }
        assertFalse(policy.mayDownload())
    }

    @Test fun networkConfirmationAndCancelCallbacksCarryAndReleaseOnlyTheirOperation() {
        val policy = UpdateFlowPolicy()
        val operation = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        var proceeded = 0
        var canceled = 0
        val proceed = policy.wrapRunnable(Runnable {
            assertTrue(policy.mayDownload())
            proceeded++
        }, operation)
        val cancel = policy.wrapRunnable(Runnable {
            assertTrue(policy.mayDownload())
            canceled++
        }, operation, finishAfter = true)
        assertFalse(policy.mayDownload())
        proceed.run()
        cancel.run()
        assertEquals(1, proceeded)
        assertEquals(1, canceled)
        policy.enter(operation).use { assertFalse(policy.mayDownload()) }
        assertFalse(policy.mayDownload())
    }

    @Test fun failingTerminalCallbackStillRevokesAnInflightRetry() {
        val policy = UpdateFlowPolicy()
        val operation = policy.begin(UpdateFlowPolicy.Kind.UPGRADE)
        val retry = Any()
        policy.enter(operation).use { policy.capture(retry) }
        val terminal = policy.wrapRunnable(Runnable { error("cancellation callback failed") },
            operation, finishAfter = true)
        assertTrue(runCatching { terminal.run() }.isFailure)
        policy.enterBound(retry).use { assertFalse(policy.mayDownload()) }
        assertFalse(policy.mayDownload())
    }
}
