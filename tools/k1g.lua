--
-- k1g.lua — Wireshark dissector for the Royal Enfield K1G dash protocol.
--
--   wireshark -X lua_script:tools/k1g.lua diag/ride-20260924-211625.pcapng
--   tshark    -X lua_script:tools/k1g.lua -r ride.pcapng -Y 'k1g.name == "Heartbeat"'
--
-- Or drop it in ~/.local/lib/wireshark/plugins/ (Help → About → Folders) to load it always.
--
-- Reads the captures `PacketCapture.kt` writes, and any sniff of the same traffic: the
-- direction is decided by the "K1G " magic, not by the port, so a monitor-mode capture with
-- both directions on :2000 dissects too.
--
-- WHERE THE NAMES COME FROM, and why that is written down. Two sources, and they are not
-- equally strong: `docs/k1g_commands.md`'s decode table, whose own Confidence column is the
-- one thing in this project that has never been wrong about a TLV, and `K1GCodec.encode`,
-- which is what we actually send. Every entry below carries the confidence the table gives
-- it. Where neither source says anything the name stays empty rather than becoming a guess
-- — spec/video.md records two field investigations built on a TLV name somebody invented.
--

local k1g = Proto("k1g", "Royal Enfield K1G dash protocol")

local CTRL_PORT, RX_PORT, RTP_PORT = 2000, 2002, 5000
local MAGIC = "K1G "

-- ── TLV vocabulary ────────────────────────────────────────────────────────
--
-- key = "TT:SS". Value = { name, confidence, decoder }. `decoder(value_tvb)` returns a short
-- string appended to the name, or nil.

local function u8(tvb) return tvb:len() > 0 and tvb(0, 1):uint() or nil end

-- A NUL-terminated string that cannot leave the TLV.
--
-- NOT the API's own range-to-NUL reader, which ignores the range: it scans from the range's
-- offset to the next NUL anywhere in the datagram and THROWS when there is none. Reproduced
-- in tshark 4.6.9 on 2026-09-24 — a `05 01` value with no terminator gave "Lua Error: out of
-- bounds" plus an Expert Info (Dissector bug), and the whole frame lost its K1G dissection.
-- The quiet variant is worse: when a NUL does exist further on, the displayed string bleeds
-- across the TLVs that follow. Our own encoder always appends the terminator, so this only
-- bites on dash-originated, truncated or third-party packets — which is what a dissector is
-- for.
local function zstr(t)
    if t:len() == 0 then return "" end
    local raw = t:string()
    local z = raw:find("\0", 1, true)
    return z and raw:sub(1, z - 1) or raw
end

local WEATHER = {
    [0x01] = "cloudy", [0x02] = "thunder", [0x03] = "rain",
    [0x04] = "snow/ice", [0x05] = "clear", [0xFF] = "no data",
}

local function onoff(tvb)
    local v = u8(tvb)
    if v == 0x55 then return "on" elseif v == 0xAA then return "off" end
    return nil
end

local TLV = {
    -- Auth. Both directions.
    ["08:04"] = { "AuthRequest", "confirmed" },
    ["08:00"] = { "AuthSendKey", "confirmed",
        function(t) return string.format("%dB RSA ciphertext", t:len()) end },
    ["07:00"] = { "AuthModulus", "confirmed" },
    ["07:03"] = { "AuthExponent", "confirmed" },
    ["07:01"] = { "AuthResult", "confirmed",
        function(t) return u8(t) == 0x01 and "accepted" or "REJECTED" end },

    -- Housekeeping.
    ["06:06"] = { "TimeSync", "confirmed", function(t)
        if t:len() < 3 then return nil end
        return string.format("%02d:%02d:%02d", t(0,1):uint(), t(1,1):uint(), t(2,1):uint())
    end },
    ["06:0B"] = { "HostnameAnnounce", "confirmed",
        function(t) return '"' .. zstr(t) .. '"' end },

    -- The 1 Hz status block. Names and meanings from the decode table; the two that were
    -- wrong in this project's own docs until 2026-09-22 are 06 08 and 06 04, and they are
    -- the reason this file quotes the table rather than the old prose.
    ["06:08"] = { "Weather", "confirmed",
        function(t) local v = u8(t); return v and (WEATHER[v] or string.format("0x%02X?", v)) end },
    ["06:10"] = { "Temperature", "confirmed",
        function(t) local v = u8(t); return v and string.format("%d C", v - 40) end },
    ["06:03"] = { "GpsEnabled", "confirmed", onoff },
    ["06:04"] = { "BatteryLevel", "confirmed",
        function(t) local v = u8(t); return v and string.format("%d%%", v - 100) end },
    ["06:0F"] = { "Charging", "confirmed", onoff },
    ["06:01"] = { "CellSignal", "confirmed",
        function(t) return u8(t) == 1 and "present" or "none" end },
    ["05:4C"] = { "VolumeMusic", "confirmed" },
    ["05:1B"] = { "VolumeCall", "confirmed" },
    ["05:2D"] = { "", "" },
    ["05:21"] = { "", "" },
    ["05:4D"] = { "", "" },

    -- Navigation. Names from K1GCodec.encode; the decode table does not cover these.
    ["05:01"] = { "RouteTitle", "from our encoder",
        function(t) return '"' .. zstr(t) .. '"' end },
    ["05:02"] = { "Maneuver", "from our encoder",
        function(t) local v = u8(t); return v and string.format("glyph 0x%02X", v) end },
    ["05:04"] = { "PrimaryDist", "from our encoder",
        function(t) return t:len() >= 2 and tostring(t(0,2):uint()) or nil end },
    ["05:06"] = { "PrimaryUnit", "from our encoder" },
    ["05:08"] = { "Eta", "from our encoder", function(t) return t:string() end },
    ["05:09"] = { "TotalDist", "from our encoder",
        function(t) return t:len() >= 2 and tostring(t(0,2):uint()) or nil end },
    ["05:46"] = { "TotalUnit", "from our encoder" },
    ["05:05"] = { "SecondaryDist", "from our encoder" },
    ["05:0A"] = { "DecimalSeparator", "from our encoder" },
    ["05:0C"] = { "", "" },
    ["05:0B"] = { "", "" },
    ["05:03"] = { "", "" },
    ["05:07"] = { "", "" },
    ["05:54"] = { "", "" },
    ["05:55"] = { "", "" },
    ["05:2E"] = { "NavContext", "from our encoder" },
    ["05:2F"] = { "EmptyList", "from our encoder" },
    ["05:30"] = { "EmptyList", "from our encoder" },
    ["05:31"] = { "EmptyList", "from our encoder" },
    ["05:32"] = { "EmptyList", "from our encoder" },
    ["05:33"] = { "EmptyList", "from our encoder" },
    ["06:0A"] = { "NavPlaceholder", "from our encoder" },
    ["06:0D"] = { "DecimalFormat", "from our encoder", onoff },

    -- Projection.
    ["05:56"] = { nil, "from our encoder", nil, function(t)
        local v = u8(t)
        if v == 0x55 then return "ProjectionFrame" elseif v == 0xAA then return "ProjectionStop" end
        return "Projection?"
    end },
    ["06:05"] = { nil, "from our encoder", nil, function(t)
        local v = u8(t)
        if v == 0x55 then return "ProjectionOn" elseif v == 0xAA then return "ProjectionOff" end
        return "Projection?"
    end },

    -- Video. NOT per-frame acks — one-shot "the decoder opened", see spec/video.md.
    ["06:11"] = { "DecoderOpenedAck(IDR)", "confirmed" },
    ["06:12"] = { "DecoderOpenedAck(P)", "confirmed" },
    ["09:06"] = { "DecoderOpened(IDR)", "confirmed" },
    ["09:04"] = { "DecoderOpened(P)", "confirmed" },

    -- Buttons. 06 80 is two different things and only the value tells them apart.
    ["09:00"] = { "Button", "confirmed", function(t)
        -- Last byte, not first: the captures carry both `09 00 00 01 <code>` and longer forms.
        return t:len() > 0 and string.format("code 0x%02X", t(t:len() - 1, 1):uint()) or nil
    end },
    ["06:80"] = { nil, "confirmed", nil, function(t)
        return u8(t) == 0x0B and "NavStart" or "ButtonAck"
    end },
    ["06:0C"] = { "ZoomLimit", "confirmed", function(t)
        local v = u8(t)
        if v == 0x30 then return "applied" elseif v == 0x20 then return "at max"
        elseif v == 0x10 then return "at min" end
        return nil
    end },

    -- Media and calls.
    ["05:0D"] = { "NowPlaying", "confirmed" },
    ["05:17"] = { "MediaActive", "confirmed", onoff },
    ["05:19"] = { "MediaPlaying", "confirmed", onoff },
    ["05:22"] = { "CallCard", "confirmed",
        function(t) return t:len() <= 1 and "cleared" or '"' .. zstr(t) .. '"' end },
    ["05:58"] = { "AlbumArtFollows", "confirmed", onoff },
    ["05:40"] = { "AlbumArtChunk", "confirmed",
        function(t) return string.format("%dB", t:len()) end },
    ["04:01"] = { "TemperatureUnit", "likely", onoff },

    -- Dash → app, 1 Hz, and the reason this dissector was worth writing. Found by hand in a
    -- 9 MB hex dump on 2026-09-24: the dash sends "K1G" plus a counter that advances by one
    -- per message. A gap in the ARRIVALS whose counter still advanced by the gap length means
    -- the dash kept running and we lost its uplink; a gap whose counter advanced by one means
    -- the dash itself stalled. Both were observed in one ride, 90 s apart, and nothing else
    -- in the telemetry tells them apart. The MEANING of the counter is unestablished — this
    -- is an observation, not a decode.
    ["01:01"] = { "DashTick", "observed 2026-09-24, meaning unknown", function(t)
        if t:len() >= 5 and t(0, 3):string() == "K1G" then
            return string.format("counter %d", t(3, 2):uint())
        end
        return nil
    end },
}

-- Whole types `DashMessage` names even where the sub is unmapped. Not a guess: `0C` really
-- is "the dash's own state" and `0F` really is an identity/cipher blob — what nobody has
-- established is which sub means what, and that is why the sub is printed rather than named.
-- The 2026-09-13 inventory found 26 subtypes of `0C` alone.
local BY_TYPE = {
    [0x0C] = { "Telemetry", "confirmed as a type, subs unmapped" },
    [0x0F] = { "Identity", "confirmed as a type, subs unmapped" },
}

local function tlv_meta(ty, sub, value_tvb)
    local e = TLV[string.format("%02X:%02X", ty, sub)]
    if not e then
        local t = BY_TYPE[ty]
        if t then return string.format("%s(0x%02X)", t[1], sub), t[2], nil end
        return nil, nil, nil
    end
    local name = e[1]
    if e[4] then name = e[4](value_tvb) end
    local detail = e[3] and e[3](value_tvb) or nil
    return name, e[2], detail
end

-- ── fields ────────────────────────────────────────────────────────────────

local f = k1g.fields
f.dir       = ProtoField.string("k1g.dir", "Direction")
f.outer_len = ProtoField.uint16("k1g.len", "Outer length", base.DEC)
f.seg_count = ProtoField.uint16("k1g.segs", "Segment count", base.DEC)
f.magic     = ProtoField.string("k1g.magic", "Magic")
f.seq       = ProtoField.uint8("k1g.seq", "Sequence", base.DEC)
f.tlv       = ProtoField.bytes("k1g.tlv", "TLV")
f.tlv_type  = ProtoField.uint8("k1g.type", "Type", base.HEX)
f.tlv_sub   = ProtoField.uint8("k1g.sub", "Sub", base.HEX)
f.tlv_len   = ProtoField.uint16("k1g.vlen", "Value length", base.DEC)
f.tlv_val   = ProtoField.bytes("k1g.value", "Value")
f.name      = ProtoField.string("k1g.name", "Name")
f.confid    = ProtoField.string("k1g.confidence", "Confidence")

local e_truncated = ProtoExpert.new(
    "k1g.truncated", "TLV runs past the end of the datagram",
    expert.group.MALFORMED, expert.severity.WARN)
local e_lenmismatch = ProtoExpert.new(
    "k1g.length_mismatch", "Outer length disagrees with the datagram",
    expert.group.PROTOCOL, expert.severity.NOTE)
k1g.experts = { e_truncated, e_lenmismatch }

-- ── dissector ─────────────────────────────────────────────────────────────

function k1g.dissector(tvb, pinfo, tree)
    local len = tvb:len()
    if len < 8 then return 0 end

    -- Direction from the magic, not the port. Outgoing packets carry "K1G " at offset 12 and
    -- start their TLVs at 17; the dash's own packets have a shorter header and start at 8.
    -- Deciding by port instead would mislabel every packet of a monitor-mode capture, where
    -- both directions are seen on the wire rather than on our own two sockets.
    -- BOTH halves are checked, and the magic alone is not enough — reproduced 2026-09-24. A
    -- dash-to-app tick is `.. 01 01 00 05 4B 31 47 <counter-hi> <counter-lo>`, so offsets
    -- 12..15 hold "K1G" plus the counter's high byte; when that byte is 0x20 (counters
    -- 8192..8447, about four minutes of dash uptime) they spell "K1G " exactly, and the packet
    -- dissected as outgoing with its TLVs read from past the end. Four minutes of the one
    -- signal that separates "the dash stalled" from "we lost its uplink", silently inverted.
    -- Offset 8 differs (`01` against `02`) and settles it.
    local outgoing = len >= 17 and
        tvb(8, 4):uint() == 0x02010005 and
        tvb(12, 4):string() == MAGIC
    local tlv_at = outgoing and 17 or 8

    pinfo.cols.protocol = "K1G"
    local root = tree:add(k1g, tvb(), "K1G, " .. (outgoing and "app to dash" or "dash to app"))
    -- Zero-length range plus an explicit value: this field describes the packet, not any of
    -- its bytes. `add(field, value)` without a range is what the stub in k1g_test.lua used to
    -- accept and the real Wireshark rejects with "value argument is nil" — 2026-09-24.
    root:add(f.dir, tvb(0, 0), outgoing and "app to dash" or "dash to app"):set_generated()

    local declared = tvb(0, 2):uint()
    local lt = root:add(f.outer_len, tvb(0, 2))
    if declared ~= len then
        lt:add_proto_expert_info(e_lenmismatch,
            string.format("declares %d, datagram is %d", declared, len))
    end
    local segs = tvb(2, 2):uint()
    root:add(f.seg_count, tvb(2, 2))
    if outgoing then
        root:add(f.magic, tvb(12, 4))
        root:add(f.seq, tvb(16, 1))
    end

    local names = {}
    local at = tlv_at
    local n = 0
    -- Bounded by the datagram, not only by seg_count: the two directions do not agree on
    -- what that field counts (we send 1+N, the dash sends N, and one captured heartbeat
    -- sends N for N), so it cannot be trusted as a loop bound. K1GPacket.parseIncoming makes
    -- the same choice for the same reason.
    while at + 4 <= len and n < segs do
        local ty = tvb(at, 1):uint()
        local sub = tvb(at + 1, 1):uint()
        local vlen = tvb(at + 2, 2):uint()
        local avail = math.min(vlen, len - at - 4)
        local value = tvb(at + 4, avail)

        local name, conf, detail = tlv_meta(ty, sub, value)
        local label = string.format("%02X %02X", ty, sub)
        if name and name ~= "" then label = label .. "  " .. name end
        if detail then label = label .. " = " .. detail end

        local item = root:add(f.tlv, tvb(at, 4 + avail))
        item:set_text(label)
        item:add(f.tlv_type, tvb(at, 1))
        item:add(f.tlv_sub, tvb(at + 1, 1))
        item:add(f.tlv_len, tvb(at + 2, 2))
        if avail > 0 then item:add(f.tlv_val, value) end
        if name and name ~= "" then
            item:add(f.name, tvb(at, 2), name):set_generated()
            names[#names + 1] = name
        end
        if conf and conf ~= "" then item:add(f.confid, tvb(at, 2), conf):set_generated() end
        if avail < vlen then
            item:add_proto_expert_info(e_truncated,
                string.format("declares %dB, only %dB left", vlen, avail))
        end

        at = at + 4 + avail
        n = n + 1
    end

    pinfo.cols.info = string.format("%s  %s",
        outgoing and "->dash" or "dash->",
        #names > 0 and table.concat(names, ", ") or string.format("%d TLV", n))
    return len
end

local udp = DissectorTable.get("udp.port")
udp:add(CTRL_PORT, k1g)
udp:add(RX_PORT, k1g)
-- The video stream is ordinary RTP/H.264 (RFC 6184) and Wireshark already dissects it far
-- better than anything written here would; this only tells it where to look, since :5000 is
-- not a registered RTP port and heuristic RTP detection is off by default.
local rtp = Dissector.get("rtp")
if rtp then udp:add(RTP_PORT, rtp) end
