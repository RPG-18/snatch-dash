-- Wireshark Lua dissector for the Tripper Dash "K1G" protocol.
--
-- Framing reverse-engineered by the better-dash project, re-derived here from
-- opendash_dash_engine's K1GPacket.kt / DashCommands.kt (snatch-dash).
--
-- OUTGOING (app -> dash), big-endian, UDP :2000 (broadcast):
--   [0:2]   outer_len  - total packet size including this field
--   [2:4]   seg_count  - 1 (fixed header) + N TLV segments
--   [4:8]   reserved   - always 00 00 00 00
--   [8:10]  flags      - always 02 01
--   [10:12] const      - always 00 05
--   [12:16] magic      - "K1G " (4B 31 47 20)
--   [16]    seq        - rolling 0-255
--   [17+]   TLV entries: (type:1)(sub:1)(len:2 BE)(value:len)
--
-- INCOMING (dash -> app), UDP :2002, shorter header (no magic/seq):
--   [0:2] outer_len  [2:4] seg_count  [4:8] reserved  [8+] TLVs
--
-- Install: Wireshark -> Help -> About Wireshark -> Folders -> "Personal Lua
-- Plugins", drop this file there and restart Wireshark. Or run once with:
--   wireshark -X lua_script:k1g_dissector.lua capture.pcap
--
-- Filters once loaded: k1g.tlv.type == 0x05, k1g.direction == "App -> Dash", ...

local k1g = Proto("k1g", "K1G Dash Protocol")

local f_outer_len  = ProtoField.uint16("k1g.outer_len", "Outer Length", base.DEC)
local f_seg_count  = ProtoField.uint16("k1g.seg_count", "Segment Count", base.DEC)
local f_reserved   = ProtoField.bytes ("k1g.reserved", "Reserved")
local f_flags      = ProtoField.bytes ("k1g.flags", "Flags")
local f_const      = ProtoField.bytes ("k1g.const", "Const")
local f_magic      = ProtoField.string("k1g.magic", "Magic")
local f_seq        = ProtoField.uint8 ("k1g.seq", "Sequence", base.HEX)
local f_direction  = ProtoField.string("k1g.direction", "Direction")
local f_tlv_type   = ProtoField.uint8 ("k1g.tlv.type", "TLV Type", base.HEX)
local f_tlv_sub    = ProtoField.uint8 ("k1g.tlv.sub", "TLV Sub", base.HEX)
local f_tlv_len    = ProtoField.uint16("k1g.tlv.len", "TLV Length", base.DEC)
local f_tlv_value  = ProtoField.bytes ("k1g.tlv.value", "TLV Value")
local f_tlv_name   = ProtoField.string("k1g.tlv.name", "TLV Meaning")

k1g.fields = {
    f_outer_len, f_seg_count, f_reserved, f_flags, f_const, f_magic, f_seq,
    f_direction, f_tlv_type, f_tlv_sub, f_tlv_len, f_tlv_value, f_tlv_name,
}

-- Known (type,sub) pairs, harvested from opendash_dash_engine's
-- DashCommands.kt / DashAuth.kt. Anything not listed here is genuinely
-- unknown and worth diffing against a baseline capture.
local KNOWN = {
    -- outgoing auth (app -> dash)
    ["08.04"] = "auth: request RSA pubkey",
    ["08.00"] = "auth: send RSA(SSID..AES key)",
    -- incoming auth (dash -> app)
    ["07.00"] = "auth: RSA modulus",
    ["07.03"] = "auth: RSA exponent",
    ["07.01"] = "auth: confirm(01)/reject",

    -- route card / nav info
    ["05.01"] = "route title (UTF-8, NUL-terminated)",
    ["05.02"] = "primary maneuver glyph",
    ["05.04"] = "primary distance to turn (u16)",
    ["05.05"] = "secondary distance (stale/unused)",
    ["05.06"] = "primary distance unit",
    ["05.08"] = "ETA HH:MM (4 ASCII digits)",
    ["05.09"] = "total distance remaining (u16)",
    ["05.0a"] = "decimal separator",
    ["05.0d"] = "media now-playing (title\\0album\\0artist)",
    ["05.22"] = "call caller name / clear (00=clear)",
    ["05.46"] = "total distance unit",

    -- projection / frame ack
    ["06.05"] = "projection flag (55=on, AA=off)",
    ["06.06"] = "time sync (HH, MM, SS)",
    ["06.0a"] = "nav placeholder",
    ["06.0b"] = "hostname announce (device name)",
    ["06.0d"] = "decimal format flag",
    ["06.10"] = "heartbeat temperature (deg C + 40)",
    ["06.11"] = "frame-decoded ACK (reply to IDR notify 09.06)",
    ["06.12"] = "frame-decoded ACK (reply to P-frame notify 09.04)",
    ["06.56"] = "projection keep-alive / on (55) / stop (AA)",
    ["06.80"] = "button/joystick event ACK (echoes code)",

    -- incoming button/joystick events
    ["09.00"] = "button/joystick event (see BTN_xx codes)",
    ["09.04"] = "P-frame decoded notify",
    ["09.06"] = "IDR-frame decoded notify",

    -- incoming device-identity telemetry, dash -> app, AES-256-CBC under the
    -- session key -- this dissector sees ciphertext only (no key here), so
    -- these labels are just a naming hint, not a decode. Hypothesis from an
    -- independent RE writeup of the same official app on different hardware,
    -- NOT from a packet dump -- verify against DashSession's own decrypted
    -- log (`adb logcat -s DashSession`), not from this capture. See
    -- docs/k1g_commands.md's "Incoming telemetry 0x0F" section.
    -- Source: https://www.mihaiblaga.dev/reverse-engineering-royal-enfields-connected-bike-stack
    ["0f.01"] = "HYPOTHESIS (external, unconfirmed, ciphertext here): chassis number",
    ["0f.02"] = "HYPOTHESIS (external, unconfirmed, ciphertext here): serial number",
    ["0f.05"] = "HYPOTHESIS (external, unconfirmed, ciphertext here): BSSID (6B)",
    ["0f.06"] = "HYPOTHESIS (external, unconfirmed, ciphertext here): manufacturing date",
    ["0f.07"] = "HYPOTHESIS (external, unconfirmed, ciphertext here): hardware version",
    ["0f.08"] = "HYPOTHESIS (external, unconfirmed, ciphertext here): part number/variant",
    ["0f.0a"] = "HYPOTHESIS (external, unconfirmed, ciphertext here): FOTA version",

    -- seen in the init burst / heartbeat template, meaning not yet confirmed
    ["05.1b"] = "unknown (init burst capability flag)",
    ["05.21"] = "unknown (init burst capability flag)",
    ["05.2d"] = "unknown (init burst, 2-byte value)",
    ["05.4c"] = "unknown (init burst capability flag)",
    ["05.4d"] = "unknown (init burst capability flag)",
    ["05.56"] = "unknown (init burst)",
    ["05.57"] = "unknown (init burst)",
    ["06.01"] = "unknown (init burst capability flag)",
    ["06.03"] = "unknown (init burst capability flag)",
    ["06.04"] = "unknown (init burst capability flag)",
    ["06.08"] = "unknown (init burst capability flag)",
    ["06.0f"] = "unknown (init burst capability flag)",
    ["06.17"] = "unknown (init burst)",
    ["0a.02"] = "unknown (init burst, 8-byte blob)",
}

local function tlv_name(t, s)
    local key = string.format("%02x.%02x", t, s)
    return KNOWN[key] or "unknown -- new/unmapped, worth investigating"
end

local MAGIC = "K1G "

function k1g.dissector(buffer, pinfo, tree)
    local len = buffer:len()
    if len < 8 then return end

    pinfo.cols.protocol = "K1G"

    local subtree = tree:add(k1g, buffer(), "K1G Dash Protocol")
    local outer_len = buffer(0, 2):uint()
    local seg_count = buffer(2, 2):uint()
    subtree:add(f_outer_len, buffer(0, 2))
    subtree:add(f_seg_count, buffer(2, 2))

    local is_outgoing = false
    if len >= 16 and buffer(12, 4):string() == MAGIC then
        is_outgoing = true
    end

    local tlv_start
    local seq_val = nil
    if is_outgoing then
        subtree:add(f_direction, "App -> Dash"):set_generated()
        subtree:add(f_reserved, buffer(4, 4))
        subtree:add(f_flags, buffer(8, 2))
        subtree:add(f_const, buffer(10, 2))
        subtree:add(f_magic, buffer(12, 4))
        subtree:add(f_seq, buffer(16, 1))
        seq_val = buffer(16, 1):uint()
        tlv_start = 17
    else
        subtree:add(f_direction, "Dash -> App"):set_generated()
        subtree:add(f_reserved, buffer(4, math.min(4, len - 4)))
        tlv_start = 8
    end

    -- Walk the TLV chain, bounded by both seg_count and remaining buffer.
    local offset = tlv_start
    local n = 0
    local summary = {}
    while offset + 4 <= len and n < seg_count do
        local t = buffer(offset, 1):uint()
        local s = buffer(offset + 1, 1):uint()
        local l = buffer(offset + 2, 2):uint()
        local value_end = offset + 4 + l
        if value_end > len then value_end = len end
        local vlen = value_end - (offset + 4)
        local name = tlv_name(t, s)

        local tlv_tree = subtree:add(
            buffer(offset, 4 + vlen),
            string.format("TLV %02x/%02x  %s  (len=%d)", t, s, name, vlen)
        )
        tlv_tree:add(f_tlv_type, buffer(offset, 1))
        tlv_tree:add(f_tlv_sub, buffer(offset + 1, 1))
        tlv_tree:add(f_tlv_len, buffer(offset + 2, 2))
        if vlen > 0 then
            tlv_tree:add(f_tlv_value, buffer(offset + 4, vlen))
        end
        tlv_tree:add(f_tlv_name, name):set_generated()

        table.insert(summary, string.format("%02x/%02x", t, s))
        offset = offset + 4 + vlen
        n = n + 1
    end

    local dir_tag = is_outgoing and (">D seq=" .. (seq_val or "?")) or "<A"
    pinfo.cols.info = string.format("K1G %s  [%s]", dir_tag, table.concat(summary, " "))
end

local udp_table = DissectorTable.get("udp.port")
udp_table:add(2000, k1g)
udp_table:add(2002, k1g)
