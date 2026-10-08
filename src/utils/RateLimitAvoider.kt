package com.fantamomo.slack.approver.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration.Companion.milliseconds

class RateLimitAvoider(
    private val maxRequestsPerMinute: Int,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    init {
        require(maxRequestsPerMinute > 0) {
            "maxRequestsPerMinute must be greater than 0"
        }
    }

    private data class Request<T>(
        val low: Boolean,
        val permit: CompletableDeferred<Unit> = CompletableDeferred()
    )

    private val mutex = Mutex()

    private val normalQueue = ArrayDeque<Request<*>>()
    private val lowQueue = ArrayDeque<Request<*>>()

    private val requestTimes = ArrayDeque<Long>()

    private var requestRunning = false

    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    private val schedulerJob: Job = scope.launch {
        schedulerLoop()
    }

    @OptIn(ExperimentalContracts::class)
    suspend fun <T> withLimit(
        low: Boolean = false,
        block: suspend () -> T
    ): T {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        val request = Request<T>(low = low)

        mutex.withLock {
            if (low) {
                lowQueue.addLast(request)
            } else {
                normalQueue.addLast(request)
            }
        }

        wakeUp.trySend(Unit)

        request.permit.await()

        return try {
            block()
        } finally {
            mutex.withLock {
                requestRunning = false
            }

            wakeUp.trySend(Unit)
        }
    }

    private suspend fun schedulerLoop() {
        while (true) {
            var delayMillis: Long? = null

            mutex.withLock {
                removeExpiredRequestTimes()

                if (requestRunning) {
                    return@withLock
                }

                if (normalQueue.isEmpty() && lowQueue.isEmpty()) {
                    return@withLock
                }

                if (requestTimes.size >= maxRequestsPerMinute) {
                    val oldest = requestTimes.first()
                    val waitUntil = oldest + 60_000L
                    delayMillis = (waitUntil - System.currentTimeMillis())
                        .coerceAtLeast(1L)

                    return@withLock
                }

                val request = if (normalQueue.isNotEmpty()) {
                    normalQueue.removeFirst()
                } else {
                    lowQueue.removeFirst()
                }

                requestRunning = true

                requestTimes.addLast(System.currentTimeMillis())

                request.permit.complete(Unit)
            }

            if (delayMillis != null) {
                delay(delayMillis.milliseconds)
            } else {
                wakeUp.receive()
            }
        }
    }

    private fun removeExpiredRequestTimes() {
        val now = System.currentTimeMillis()
        val limit = now - 60_000L

        while (requestTimes.isNotEmpty() && requestTimes.first() <= limit) {
            requestTimes.removeFirst()
        }
    }

    fun close() {
        schedulerJob.cancel()
        wakeUp.close()
    }
}