package com.opendash.opendash_dash_engine

/**
 * One Pigeon event stream's sink, held between `onListen` and `onCancel`.
 *
 * Three streams need the same four lines, and the interesting part is the last one:
 * emitting has to tolerate a sink that has gone away. Dart cancels on its own
 * schedule — a provider disposing, the engine detaching mid-ride — and the producers
 * here are coroutines that do not know about any of that. A throw from
 * [PigeonEventSink.success] on a dead channel would surface as an uncaught exception
 * in the dash scope, which the plugin's `CoroutineExceptionHandler` logs and then
 * carries on from, one line per frame.
 */
internal class DashEventSink<T> {

    @Volatile
    private var sink: PigeonEventSink<T>? = null

    fun attach(sink: PigeonEventSink<T>) {
        this.sink = sink
    }

    fun detach() {
        sink = null
    }

    /** Deliver [value] if anyone is listening. Never throws. */
    fun emit(value: T) {
        val s = sink ?: return
        runCatching { s.success(value) }
    }
}
