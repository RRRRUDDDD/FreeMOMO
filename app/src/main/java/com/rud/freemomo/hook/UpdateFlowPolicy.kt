package com.rud.freemomo.hook

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.UUID

/** Authorization belongs to a user operation and its callbacks, never to a visible page. */
class UpdateFlowPolicy {
    enum class Kind { CHECK, UPGRADE }

    class Operation internal constructor(val kind: Kind) {
        internal var active = true
    }

    private class IdentityReference(value: Any, queue: ReferenceQueue<Any>? = null) :
        WeakReference<Any>(value, queue) {
        private val identityHash = System.identityHashCode(value)
        override fun hashCode(): Int = identityHash
        override fun equals(other: Any?): Boolean = this === other ||
            (other is IdentityReference && get() != null && get() === other.get())
    }

    private class Frame(val operation: Operation?)
    private val lock = Any()
    private val queue = ReferenceQueue<Any>()
    private val bindings = mutableMapOf<IdentityReference, Operation>()
    private val downloads = mutableSetOf<Operation>()
    private val transfers = mutableMapOf<String, Operation>()
    private val frames = ThreadLocal<MutableList<Frame>>()

    inner class Scope internal constructor(private val closeScope: () -> Unit) : AutoCloseable {
        private var closed = false
        override fun close() {
            if (closed) return
            closeScope()
            closed = true
        }
    }

    fun begin(kind: Kind): Operation = Operation(kind)

    fun enter(operation: Operation?): Scope {
        val stack = frames.get() ?: mutableListOf<Frame>().also(frames::set)
        val frame = Frame(operation)
        stack.add(frame)
        return Scope {
            check(frames.get() === stack && stack.lastOrNull() === frame) {
                "Update scopes must close on their originating thread in reverse order"
            }
            stack.removeAt(stack.lastIndex)
            if (stack.isEmpty()) frames.remove()
        }
    }

    /** An unbound callback masks outer scopes, including synchronous/reentrant invocations. */
    fun enterBound(callback: Any): Scope = enter(operationFor(callback))

    fun currentOperation(): Operation? = synchronized(lock) {
        frames.get()?.lastOrNull()?.operation?.takeIf { it.active }
    }

    fun capture(callback: Any): Boolean = synchronized(lock) {
        val operation = currentOperation() ?: return false
        expungeCollectedCallbacks()
        val old = bindings[IdentityReference(callback)]
        if (old != null && old.active && old !== operation) return false
        bindings[IdentityReference(callback, queue)] = operation
        true
    }

    fun operationFor(callback: Any): Operation? = synchronized(lock) {
        expungeCollectedCallbacks()
        bindings[IdentityReference(callback)]?.takeIf { it.active }
    }

    fun finish(operation: Operation?) = synchronized(lock) {
        if (operation == null) return@synchronized
        operation.active = false
        downloads.remove(operation)
        transfers.entries.removeAll { it.value === operation }
        bindings.entries.removeAll { it.value === operation }
        expungeCollectedCallbacks()
    }

    fun markDownload(operation: Operation) = synchronized(lock) {
        if (operation.active && operation.kind == Kind.UPGRADE) downloads.add(operation)
    }

    /** The verified helper stops the app's single download service. This only revokes tokens. */
    fun stopDownloads() = synchronized(lock) {
        downloads.toList().forEach(::finish)
    }

    /** Explicit transfer through a verified PendingIntent; an absent token never borrows a scope. */
    fun exportUpgrade(operation: Operation): String? = synchronized(lock) {
        if (!operation.active || operation.kind != Kind.UPGRADE) return null
        transfers.entries.firstOrNull { it.value === operation }?.key
            ?: UUID.randomUUID().toString().also { transfers[it] = operation }
    }

    fun importedUpgrade(token: String?): Operation? = synchronized(lock) {
        transfers[token]?.takeIf { it.active && it.kind == Kind.UPGRADE }
    }

    /** Used only at verified callback arguments of this operation's network/download APIs. */
    fun wrapRunnable(
        callback: Runnable,
        operation: Operation,
        finishAfter: Boolean = false
    ): Runnable = Runnable {
        enter(operation).use {
            try {
                callback.run()
            } finally {
                if (finishAfter) finish(operation)
            }
        }
    }

    fun mayDispatchUpgrade(immediateDownload: Boolean = false): Boolean =
        currentOperation()?.let { !immediateDownload || it.kind == Kind.UPGRADE } == true

    fun mayCheck(): Boolean = currentOperation()?.kind == Kind.CHECK

    fun mayDownload(): Boolean = currentOperation()?.kind == Kind.UPGRADE

    /** These two NotificationManager types are automatic; manual checks use their own callback. */
    fun isAutomaticUpgradeNotification(type: String?): Boolean =
        type == "app_upgrade" || type == "app_upgrade_dialog"

    internal val bindingCount: Int
        get() = synchronized(lock) {
            expungeCollectedCallbacks()
            bindings.size
        }

    private fun expungeCollectedCallbacks() {
        while (true) {
            val reference = queue.poll() ?: return
            bindings.remove(reference)
        }
    }
}
