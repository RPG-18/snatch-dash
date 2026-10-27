package com.opendash.opendash_dash_engine.media

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

/** Scope outlives provider.start/stop; stop the provider before cancelling that scope. */
internal class MediaEventStream(private val lock: Any, scope: CoroutineScope) {
    private val current = MutableStateFlow(MediaSnapshot())
    val snapshot = current.asStateFlow()
    private val published = MutableSharedFlow<MediaEvent>()
    val events = published.asSharedFlow()
    // Slow collectors backpressure emit, never Android callbacks. There is deliberately no
    // bounded/drop buffer: queued events retain memory until consumers catch up or scope ends.
    private val queue = Channel<MediaEvent>(Channel.UNLIMITED)
    private val pending = ArrayDeque<MediaEvent>()
    private var publishing = false

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (event in queue) published.emit(event)
        }.invokeOnCompletion { queue.cancel() }
    }

    suspend fun collectWithSnapshot(
        onSnapshot: suspend (MediaSnapshot) -> Unit,
        onEvent: suspend (MediaEvent) -> Unit,
    ): Nothing {
        var boundary = -1L
        events.onSubscription {
            // SharedFlow has already registered this collector. Older queued publications
            // are covered by the snapshot; events produced during replay remain queued.
            val initial = synchronized(lock) { current.value }
            boundary = initial.revision
            onSnapshot(initial)
        }.collect { event ->
            if (event.revision > boundary) onEvent(event)
        }
        awaitCancellation()
    }

    fun publish(event: MediaEvent, next: MediaSnapshot) = synchronized(lock) {
        check(event.revision == current.value.revision + 1)
        check(next.revision == event.revision)
        // Queue before exposing state: an Unconfined state collector may reenter. The
        // drain keeps those nested publications in revision order without calling emit here.
        pending.addLast(event)
        current.value = next
        if (!publishing) {
            publishing = true
            try {
                while (pending.isNotEmpty()) queue.trySend(pending.removeFirst()).getOrThrow()
            } finally {
                publishing = false
            }
        }
    }
}
