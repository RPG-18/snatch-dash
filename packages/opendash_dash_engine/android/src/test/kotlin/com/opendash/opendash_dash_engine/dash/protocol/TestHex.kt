package com.opendash.opendash_dash_engine.dash.protocol

/**
 * Lower-case hex, for stating expectations the way the captures are written.
 *
 * Assertions in these tests compare hex STRINGS rather than arrays: a failure then names
 * the offset in a form that can be diffed against a capture by eye, instead of printing
 * two 126-element byte arrays.
 */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
