package com.opendash.opendash_dash_engine.dash.protocol

/**
 * The dash's own vocabulary of numbers: which glyph, which unit, which button.
 *
 * Separate from the commands that carry them because these are *findings*, not code — the
 * glyph table below is a hardware sweep transcribed from `spec/glyph.md`, and it outlived
 * `DashCommands`, which was deleted when [K1GCodec] took over building packets. A caller
 * picking a maneuver code needs this and nothing else.
 */
object DashGlyphs {

    // ── Active navigation info (0x007E-family, ~1 Hz while guiding) ───────
    // Ported from better-dash build_active_nav_packet. Drives the dash's
    // instruction bubble: primary maneuver glyph + distance-to-turn + total.
    //
    // Full glyph table for the `maneuver` byte below (`05 02` field), swept
    // and hardware-confirmed byte-by-byte against this port's physical dash
    // — see `spec/glyph.md` for the raw sweep notes this was transcribed
    // from; that file is the source of truth, this object mirrors it into
    // named constants for use from code. Range 0x5B..0xFF showed no glyph
    // at all during the sweep — likely reserved/unused.
    //
    //   0x00  destination, straight ahead (final stretch)
    //   0x01  destination, on the left (final stretch)
    //   0x02  destination, on the right (final stretch)
    //   0x03  merge — left road blinks, 90°
    //   0x04  merge — right road blinks, 90°
    //   0x05  fork into two parallel roads — right arrow blinks
    //   0x06  fork into two parallel roads — left arrow blinks
    //   0x07  exit onto a parallel road, right, 90°
    //   0x08  exit onto a parallel road, left, 90°
    //   0x09  straight ahead                                    [NAV_MANEUVER_STRAIGHT]
    //   0x0A  roundabout, clockwise, no exit number              [ROUNDABOUT_CW_BASE]
    //   0x0B..0x13  roundabout, clockwise, exit 1..9 (base + exit number)
    //   0x14  turn left                                          [NAV_MANEUVER_TURN_LEFT]
    //   0x15  turn right                                         [NAV_MANEUVER_TURN_RIGHT]
    //   0x16  sharp left
    //   0x17  sharp right
    //   0x18  slight left
    //   0x19  slight right
    //   0x1A  U-turn, clockwise (→ right)
    //   0x1B  merge — both roads blink red
    //   0x1C  compass searching for a heading (re-route in progress)
    //   0x1D  merge — left road blinks (no angle given)
    //   0x1E  merge — right road blinks
    //   0x1F  merge — left road blinks, smooth line
    //   0x20  merge — right road blinks, smooth line
    //   0x21  merge — left road blinks, 90° (distinct from 0x03)
    //   0x22  merge — right road blinks, 90° (distinct from 0x04)
    //   0x23  exit onto the main road, left
    //   0x24  exit onto the main road, right
    //   0x25  exit onto the main road, left, 60° between roads
    //   0x26  exit onto the main road, right, 60° between roads
    //   0x27  circular exit, right
    //   0x28  circular exit, left
    //   0x29  exit right, 60°
    //   0x2A  exit left, 60°
    //   0x2B  three lanes — leftmost blinks (keep left)
    //   0x2C  three lanes — rightmost (3rd) blinks (keep right)
    //   0x2D  exit onto a parallel road, right (no angle given)
    //   0x2E  exit onto a parallel road, left (no angle given)
    //   0x2F  exit onto a parallel road, right, 90° (distinct from 0x07)
    //   0x30  exit onto a parallel road, left, 90° (distinct from 0x08)
    //   0x31  roundabout, counterclockwise, no exit number        [ROUNDABOUT_CCW_BASE]
    //   0x32..0x3A  roundabout, counterclockwise, exit 1..9 (base + exit number)
    //   0x3B  straight ahead (second, distinct byte — no known difference from 0x09)
    //   0x3C  map marker (rendered for DEPART in a captured real route card)
    //   0x3D  U-turn, counterclockwise (→ left)
    //   0x3E  ferry crossing
    //   0x3F  train (railway crossing, or train-ferry boarding)
    //   0x40  map marker + 3 dots (walking segment?)
    //   0x41  empty bubble
    //   0x42  wifi symbol with a left arrow (mobile-data indicator?)
    //   0x43  empty bubble
    //   0x44  empty bubble (table position matches a "low battery" notion —
    //         may need real battery telemetry alongside the byte to render)
    //   0x46..0x4F  roundabout, clockwise, exit 10..19 (0x46 + (exit-10))  [ROUNDABOUT_CW_EXIT10_BASE]
    //   0x50..0x59  roundabout, counterclockwise, exit 10..19 (0x50 + (exit-10)) [ROUNDABOUT_CCW_EXIT10_BASE]
    //
    // 0x0B specifically ("clockwise, exit 1") directly contradicts the value
    // inherited from the original open-dash project, which claimed 0x0B was
    // hardware-verified as CONTINUE (a neutral straight-ahead arrow) — that
    // claim is now known-wrong on this dash/firmware. 0x09 is the real
    // neutral glyph, confirmed above.
    const val NAV_MANEUVER_STRAIGHT = 0x09
    const val NAV_MANEUVER_TURN_LEFT = 0x14
    const val NAV_MANEUVER_TURN_RIGHT = 0x15
    const val ROUNDABOUT_CW_BASE = 0x0A         // + exit number, 1..9 (0x0B..0x13)
    const val ROUNDABOUT_CW_EXIT10_BASE = 0x46  // + (exit number - 10), 10..19 (0x46..0x4F)
    const val ROUNDABOUT_CCW_BASE = 0x31        // + exit number, 1..9 (0x32..0x3A) — UNCONFIRMED, assumed by symmetry
    const val ROUNDABOUT_CCW_EXIT10_BASE = 0x50 // + (exit number - 10), 10..19 (0x50..0x59) — UNCONFIRMED
    const val NAV_UNIT_KM_TENTHS = 0x10   // distance field = km × 10
    const val NAV_UNIT_METERS    = 0x30

    // Joystick codes seen in the 09 00 event family (clk.D/E/F/c0/d0/u)
    const val BTN_05: Byte = 0x05
    const val BTN_06: Byte = 0x06
    const val BTN_07: Byte = 0x07
    const val BTN_09: Byte = 0x09
    const val BTN_0A: Byte = 0x0A
    const val BTN_22: Byte = 0x22
}
