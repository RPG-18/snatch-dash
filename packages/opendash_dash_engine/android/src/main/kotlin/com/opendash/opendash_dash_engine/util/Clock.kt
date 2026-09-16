package com.opendash.opendash_dash_engine.util

import android.location.Location
import android.os.SystemClock

/**
 * The clock every duration in this module is measured on.
 *
 * `System.currentTimeMillis()` is the wall clock. Android's own documentation says not to use
 * it for this: it "can be set by the user or the phone network, so the time may jump backwards
 * or forwards unpredictably… Interval or elapsed time measurements should use a different
 * clock", and names [SystemClock.elapsedRealtime] as "the recommended basis for general purpose
 * interval timing".
 *
 * **This is not a theoretical concern here.** The phone rides on the dash's Wi-Fi, which has no
 * internet, while the cellular radio stays up — so a NITZ/NTP correction lands exactly when the
 * phone comes back from a dead zone, which is the same moment the code cares most about. A
 * forward step past a watchdog threshold tears down a healthy session; a backward step makes a
 * stale fix look fresh and a frame deadline sleep for an extra interval.
 *
 * **Why a shared function rather than a rule.** The rule was already written, in
 * `DashSession`'s RX loop: "elapsedRealtime, not currentTimeMillis, for every duration below".
 * It was written on 2026-09-13, hours AFTER `LocationTracker` and `PositionTrust` were
 * committed the same day with five wall-clock durations between them — and the review of the
 * commit that added the rule did not catch them, because they were not in its diff. A comment
 * cannot be applied to code that already exists; a function at least makes the right thing the
 * shortest thing to type. Found by the pipeline audit, 2026-09-14.
 *
 * Wall clock stays correct for exactly one thing: stamping a log line with a time a human will
 * compare against a clock on a wall.
 */
fun monotonicMs(): Long = SystemClock.elapsedRealtime()

/**
 * Age of a GPS fix, in milliseconds.
 *
 * Not `currentTimeMillis() - loc.time`, which compares two wall-clock readings and so carries
 * the fault twice over. [Location.getElapsedRealtimeNanos] is documented as the field that
 * "can be reliably compared to [SystemClock.elapsedRealtimeNanos], to calculate the age of a
 * fix", while [Location.getTime] is documented as "not monotonic".
 *
 * That difference is not academic for this app: the wall-clock version misreports a fix's age
 * precisely when a correction arrives, i.e. when the phone re-acquires signal — the scenario
 * `gpsLost` and `gpsWeak` exist to describe.
 */
fun Location.ageMs(): Long =
    (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000
