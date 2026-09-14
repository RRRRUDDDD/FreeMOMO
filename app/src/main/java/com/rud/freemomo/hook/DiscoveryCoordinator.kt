package com.rud.freemomo.hook

import com.rud.freemomo.util.DiscoverySnapshot
import com.rud.freemomo.util.ThrowablePolicy

data class DiscoveryState(val snapshot: DiscoverySnapshot, val installed: HookTargets)

/**
 * The only writer of discovery, installation, and persistence state. Scans may run outside the
 * monitor; their publication always merges into the latest state. Hook side effects run once,
 * never inside a retryable CAS function. Nested callbacks commit at the outer transaction boundary.
 */
class DiscoveryCoordinator(
    initial: DiscoverySnapshot,
    private val installTargets: (HookTargets) -> HookTargets,
    private val installWordObservers: (WordLimitDiscovery, WordObserverGroup) -> Boolean,
    private val persist: (DiscoverySnapshot) -> Unit,
    private val onChange: (DiscoveryState?, DiscoveryState) -> Unit = { _, _ -> },
    private val onError: (Throwable) -> Unit = {}
) {
    private val monitor = Any()
    private var current = DiscoveryState(initial, HookTargets())
    private var lastCommitted: DiscoveryState? = null
    private var observerGroup: WordObserverGroup? = null
    private var transactionDepth = 0
    private var committing = false
    private var started = false

    fun state(): DiscoveryState = synchronized(monitor) { current }

    fun start(): DiscoveryState = transaction {
        if (!started) {
            started = true
            installMissing()
            ensureWordObservers()
        }
    }

    fun publish(scan: DiscoveryScan): DiscoveryState = transaction {
        check(started)
        current = current.copy(snapshot = current.snapshot.fillUnknown(scan))
        installMissing()
        ensureWordObservers()
    }

    private fun installMissing() {
        val targets = current.snapshot.targets
        val requested = HookTargets(
            wordLimit = targets.wordLimit.takeIf { current.installed.wordLimit == null },
            display = targets.display.takeIf { current.installed.display == null },
            privilege = targets.privilege.takeIf { current.installed.privilege == null },
            cloud = targets.cloud.takeIf { current.installed.cloud == null }
        )
        if (requested.isEmpty()) return
        val installed = try {
            installTargets(requested)
        } catch (error: Throwable) {
            ThrowablePolicy.rethrowIfFatal(error)
            onError(error)
            HookTargets()
        }
        // An installer may synchronously invoke an existing observer. Re-read current afterwards.
        current = DiscoveryState(
            current.snapshot.reconcileInstall(requested, installed),
            HookTargets(
                wordLimit = current.installed.wordLimit ?: installed.wordLimit
                    .takeIf { it == requested.wordLimit },
                display = current.installed.display ?: installed.display
                    .takeIf { it == requested.display },
                privilege = current.installed.privilege ?: installed.privilege
                    .takeIf { it == requested.privilege },
                cloud = current.installed.cloud ?: installed.cloud
                    .takeIf { it == requested.cloud }
            )
        )
    }

    private fun ensureWordObservers() {
        if (!current.snapshot.hasPendingWordObservation || observerGroup != null) return
        val pending = requireNotNull(current.snapshot.wordLimit)
        lateinit var group: WordObserverGroup
        group = WordObserverGroup(
            monitor,
            onResult = { method, result, replace -> observe(group, method, result, replace) },
            onError = onError
        )
        observerGroup = group
        val installed = try {
            installWordObservers(pending, group)
        } catch (error: Throwable) {
            group.close()
            ThrowablePolicy.rethrowIfFatal(error)
            onError(error)
            false
        }
        val closedByAmbiguity = observerGroup == null &&
            current.snapshot.wordLimit?.status == DiscoveryStatus.AMBIGUOUS
        if (!installed || (!group.active && !closedByAmbiguity)) {
            group.close()
            observerGroup = null
            current = current.copy(
                snapshot = current.snapshot.installationFailed(
                    mapOf(HookCapability.WORD_LIMIT to pending.candidates.mapTo(linkedSetOf()) {
                        it.className
                    })
                ),
                installed = current.installed.copy(wordLimit = null)
            )
        }
    }

    private fun observe(
        group: WordObserverGroup,
        method: MethodSignature,
        result: Int,
        replace: (Int) -> Unit
    ) = transaction {
        if (observerGroup === group && group.active) {
            val previous = requireNotNull(current.snapshot.wordLimit)
            val observed = previous.observe(method, result)
            // Out-of-range runtime values can change later; they are not structural negatives.
            val next = if (observed.status == DiscoveryStatus.REJECTED) {
                observed.copy(status = DiscoveryStatus.PENDING)
            } else {
                observed
            }
            val target = next.target.takeIf { next.status == DiscoveryStatus.DISCOVERED }
            current = DiscoveryState(
                current.snapshot.copy(wordLimit = next),
                current.installed.copy(wordLimit = target)
            )
            if (next.status == DiscoveryStatus.AMBIGUOUS) {
                observerGroup = null
                group.close()
            } else if (target == method) {
                // Replacement and terminal-state checks share one monitor, including in-flight calls.
                replace(HookDiscoveryPolicy.WORD_LIMIT_REPLACEMENT)
            }
        }
    }

    private inline fun transaction(action: () -> Unit): DiscoveryState = synchronized(monitor) {
        transactionDepth++
        try {
            action()
        } finally {
            transactionDepth--
            if (transactionDepth == 0) commit()
        }
        current
    }

    private fun commit() {
        if (committing) return
        committing = true
        try {
            while (current != lastCommitted) {
                val next = current
                val previous = lastCommitted
                if (previous?.snapshot != next.snapshot) persist(next.snapshot)
                lastCommitted = next
                onChange(previous, next)
                // Reentrant persistence/listener callbacks may have queued a newer state.
            }
        } finally {
            committing = false
        }
    }
}
