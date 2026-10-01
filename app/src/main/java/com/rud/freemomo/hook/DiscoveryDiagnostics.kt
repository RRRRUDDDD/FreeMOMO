package com.rud.freemomo.hook

import com.rud.freemomo.util.ThrowablePolicy
import java.util.concurrent.atomic.AtomicBoolean

/** Debug-only evidence collection; never publishes results to the installer or cache. */
class DiscoveryDiagnostics {
    private val claimed = AtomicBoolean(false)

    fun run(
        debugBuild: Boolean,
        versionCode: Int,
        inventory: () -> ClassInventory,
        describeClass: (String) -> ClassDescriptor,
        log: (String) -> Unit
    ) {
        if (!debugBuild || versionCode != 904 || !claimed.compareAndSet(false, true)) return
        val started = System.nanoTime()
        var failedClasses = 0
        fun emit(message: String) {
            // Names come from a foreign APK; keep each record bounded and on a single line.
            log("FreeMOMO: diagnostic:904 " + message.take(1600).map {
                if (it.isISOControl()) ' ' else it
            }.joinToString(""))
        }
        try {
            emit("begin evidence-only; no installation or cache writes")
            val classes = inventory()
            val scanner = DiscoveryScanner { className ->
                try {
                    describeClass(className)
                } catch (error: Throwable) {
                    ThrowablePolicy.rethrowIfFatal(error)
                    failedClasses++
                    if (failedClasses <= 16) {
                        emit("reflection failed class=$className type=${error.javaClass.name}")
                    }
                    throw error
                }
            }
            val scan = scanner.scan(HookCapability.entries.toSet(), classes) { evidence ->
                report("word", evidence.wordLimit.status, evidence.wordLimit.candidates,
                    evidence.wordLimit.conflicts, evidence.wordLimit.rejectedCandidates, ::emit)
                report("display", evidence.display.status, evidence.display.candidates,
                    evidence.display.conflicts, evidence.display.rejectedCandidates, ::emit)
                report("privilege", evidence.privilege.status, evidence.privilege.candidates,
                    evidence.privilege.conflicts, evidence.privilege.rejectedCandidates, ::emit)
                report("cloud", evidence.cloud.status, evidence.cloud.candidates,
                    evidence.cloud.conflicts, evidence.cloud.rejectedCandidates, ::emit)
            }
            scan.metadata.forEach { (capability, metadata) ->
                emit("${capability.cacheKey} complete=${metadata.complete} " +
                    "retry=${metadata.retryReason} failed=${metadata.failedClasses.size}")
            }
            emit("end inventory=${classes.classNames.size} enumerated=${classes.complete} " +
                "described=${scan.describedClassCount} methods=${scan.describedMethodCount} " +
                "failed=$failedClasses omittedFailures=${(failedClasses - 16).coerceAtLeast(0)} " +
                "elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        } catch (error: Throwable) {
            ThrowablePolicy.rethrowIfFatal(error)
            emit("aborted type=${error.javaClass.name}")
        }
    }

    private fun report(
        capability: String,
        status: DiscoveryStatus,
        candidates: List<MethodSignature>,
        conflicts: List<MethodSignature>,
        rejected: List<MethodSignature>,
        emit: (String) -> Unit
    ) {
        val methods = when (status) {
            DiscoveryStatus.AMBIGUOUS -> conflicts
            DiscoveryStatus.REJECTED -> rejected
            else -> candidates
        }
        emit("$capability evidence=$status candidates=${candidates.size} " +
            "conflicts=${conflicts.size} rejected=${rejected.size} " +
            "omittedMethods=${(methods.size - 24).coerceAtLeast(0)}")
        methods.take(24).forEach { method ->
            emit("$capability declaration static=${method.isStatic} ${method.displayName}")
        }
    }
}
