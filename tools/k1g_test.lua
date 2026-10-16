--
-- k1g_test.lua — runs tools/k1g.lua against real captured bytes, without Wireshark.
--
--   lua tools/k1g_test.lua
--
-- WHY THIS EXISTS. A dissector is the one kind of code in this repo that CI cannot reach:
-- it needs a Wireshark runtime, and a developer machine may not have one (the machine this
-- was written on does not). An unrun dissector is a 280-line guess, and the way it fails is
-- quiet — a wrong offset shows plausible TLVs with the wrong names, which is worse than
-- showing nothing, because somebody will then reason from them.
--
-- So: enough of the Wireshark API is stubbed here to execute the real file, and the packets
-- below are byte-for-byte the ones `K1GGoldenTest` compares the encoder against, plus one
-- dash-to-app packet copied out of a ride's app_log.txt. What is checked is what the dissector
-- decides: direction, TLV boundaries, names, and the decoded values.
--
-- What it does NOT check is how Wireshark renders any of it. Only Wireshark can do that.
--

-- ── Wireshark API, stubbed ────────────────────────────────────────────────

local registered = {}

local Tvb = {}
Tvb.__index = Tvb
local function tvb_new(s, off, len)
    off = off or 0
    len = len or (#s - off)
    return setmetatable({ s = s, off = off, n = len }, Tvb)
end
function Tvb:len() return self.n end
function Tvb:__call(o, l)
    -- `tvb()` with no arguments means the whole buffer, which the real API allows and the
    -- dissector uses for the top-level tree item.
    o = o or 0
    l = l or (self.n - o)
    -- Bounds-checked, because Wireshark is: an out-of-range slice throws there and used to
    -- be silently tolerated here.
    assert(o >= 0 and l >= 0 and o + l <= self.n,
        string.format("tvb(%d, %d) out of bounds in a %dB range", o, l, self.n))
    return tvb_new(self.s, self.off + o, l)
end
function Tvb:uint()
    local v = 0
    for i = 0, self.n - 1 do v = v * 256 + self.s:byte(self.off + i + 1) end
    return v
end
-- string() stops at NUL in real tshark; charset conversion is not modeled here.
-- Binary fields must use bytes():raw(), which preserves NUL and non-UTF-8 bytes.
function Tvb:string()
    return self.s:sub(self.off + 1, self.off + self.n):match("^[^%z]*")
end
-- Faithful to Wireshark, which is the whole point: `stringz` ignores the RANGE and scans to
-- the next NUL in the datagram, throwing when there is none. The old stub clipped to the
-- range and never threw, which is exactly why a dissector that crashed on every unterminated
-- string TLV passed these checks and only failed in tshark (2026-09-24). The dissector does
-- not call it any more — it uses its own bounded `zstr` — and this stays faithful so that
-- reintroducing the call fails here instead of in the field.
function Tvb:stringz()
    local whole = self.s:sub(self.off + 1)
    local z = whole:find("\0", 1, true)
    assert(z, "stringz: no NUL before the end of the datagram — Wireshark throws here")
    return whole:sub(1, z - 1)
end
function Tvb:bytes()
    local raw = self.s:sub(self.off + 1, self.off + self.n)
    return { raw = function() return raw end }
end

local Item = {}
Item.__index = Item
local function item_new(sink) return setmetatable({ sink = sink }, Item) end
-- Strict on purpose. The first version of this stub accepted anything, and so it passed a
-- call the real Wireshark rejects — `add(field, range, nil, label)`, which cost a run of
-- "Dissector bug … value argument is nil" on all 52 packets of the first tshark check
-- (2026-09-24). A stub that is more permissive than the thing it stands in for does not
-- reduce the risk of not having the real runtime; it hides it.
function Item:add(field, a, b, ...)
    assert(field ~= nil, "add() with a nil field")
    assert(a ~= nil, "add() with a nil range/value — Wireshark rejects this")
    assert(select("#", ...) == 0, "add() takes at most three arguments; use set_text for a label")
    if b ~= nil then assert(type(b) ~= "table" or b.s, "add(field, range, value): odd value") end
    local it = item_new(self.sink)
    self.sink[#self.sink + 1] = {
        field = field, item = it,
        range = type(a) == "table" and a.s and a or nil, value = b,
    }
    return it
end
function Item:set_text(label)
    for _, e in ipairs(self.sink) do if e.item == self then e.label = label end end
    return self
end
function Item:set_generated() return self end
function Item:add_proto_expert_info(e, msg)
    self.sink[#self.sink + 1] = { expert = e, msg = msg }
    return self
end

Proto = function(abbrev, name)
    local p = { fields = {}, experts = {}, _abbrev = abbrev, _name = name }
    return p
end
ProtoField = setmetatable({}, { __index = function(_, kind)
    return function(abbrev, name) return { kind = kind, abbrev = abbrev, name = name } end
end })
ProtoExpert = { new = function(abbrev) return { abbrev = abbrev } end }
expert = { group = { MALFORMED = 1, PROTOCOL = 2 }, severity = { WARN = 1, NOTE = 2 } }
base = { DEC = 10, HEX = 16 }
Dissector = { get = function(_) return nil end }
DissectorTable = { get = function(_)
    return { add = function(_, port, d) registered[port] = d end }
end }

-- ── the file under test ───────────────────────────────────────────────────

local here = arg[0]:match("^(.*)/[^/]*$") or "."
dofile(here .. "/k1g.lua")

local k1g_proto
do
    -- `k1g.dissector` was assigned onto the proto table the stub returned; the stub keeps no
    -- registry of protos, so recover it from the udp.port registration instead.
    k1g_proto = registered[2000]
    assert(k1g_proto, "k1g did not register on udp/2000")
    assert(registered[2002], "k1g did not register on udp/2002")
end

local function run(hex)
    local s = hex:gsub("%s", ""):gsub("%x%x", function(b) return string.char(tonumber(b, 16)) end)
    local tvb = tvb_new(s)
    local pinfo = { cols = {} }
    local sink = {}
    local n = k1g_proto.dissector(tvb, pinfo, item_new(sink))
    local names = {}
    local labels = {}
    for _, it in ipairs(sink) do
        if it.field and it.field.abbrev == "k1g.name" then names[#names + 1] = true end
        if it.label then labels[#labels + 1] = it.label end
    end
    return { consumed = n, info = pinfo.cols.info, labels = labels, sink = sink, size = #s }
end

-- ── checks ────────────────────────────────────────────────────────────────

local failures = 0
local function check(what, got, want)
    if got ~= want then
        failures = failures + 1
        print(string.format("FAIL  %s\n        got:  %s\n        want: %s", what, tostring(got), tostring(want)))
    else
        print("ok    " .. what)
    end
end
-- Nothing in a well-formed packet may raise expert info. This is the check that caught the
-- author writing a wrong outer length into the heartbeat fixture below: everything else here
-- measures the hex against itself, so only the dissector's own length arithmetic can notice.
local function clean(what, r)
    for _, it in ipairs(r.sink) do
        if it.expert then
            failures = failures + 1
            print(string.format("FAIL  %s\n        unexpected expert info: %s", what, tostring(it.msg)))
            return
        end
    end
    print("ok    " .. what)
end

local function has(what, labels, needle)
    for _, l in ipairs(labels) do if l:find(needle, 1, true) then print("ok    " .. what); return end end
    failures = failures + 1
    print(string.format("FAIL  %s\n        no TLV label contained: %s\n        labels: %s",
        what, needle, table.concat(labels, " | ")))
end

local function decoded(r, abbrev)
    for _, it in ipairs(r.sink) do
        if it.field and it.field.abbrev == abbrev then
            if it.value ~= nil then return it.value end
            if it.field.kind:match("^uint") then return it.range:uint() end
            if it.field.kind == "bytes" then return it.range:bytes():raw() end
            return it.range:string()
        end
    end
    return nil
end

-- Everything the checks below use, in one list so `--emit` writes exactly what was checked.
FIXTURES = {}
local function fixture(hex) FIXTURES[#FIXTURES + 1] = hex; return hex end

-- 1. Projection keep-alive, the packet the dash sees four times a second.
--    Straight out of K1GGoldenTest.
local FRAME = fixture("0016000200000000020100054B31472000" .. "0556000155")
local r = run(FRAME)
check("projection frame: whole datagram consumed", r.consumed, r.size)
clean("projection frame: no expert info on a golden packet", r)
check("projection frame: direction", r.info:sub(1, 6), "->dash")
has("projection frame: named by value, not by sub", r.labels, "ProjectionFrame")

-- 2. Projection stop — same TLV, other value, other name. If the value-dependent naming
--    ever regresses this is the pair that shows it.
has("projection stop", run(fixture("0016000200000000020100054B31472000" .. "05560001AA")).labels, "ProjectionStop")

-- 3. Heartbeat: eleven TLVs, and the seg_count field says 11 for 11 — not 1+N. The loop
--    must not read one TLV too few or run off the end.
-- Copied verbatim from `heartbeat at 25C carries 41 after marker 06 10 00 01`.
local HB = fixture("0049000b00000000020100054b314720"
    .. "0006080001050610000141060300015506040001a2060f0001aa"
    .. "0601000101054c000113052d00020000051b0001190521000132054d000132")
r = run(HB)
check("heartbeat: whole datagram consumed", r.consumed, r.size)
clean("heartbeat: no expert info on a golden packet", r)
has("heartbeat: weather decoded from the table, not from the old 'volume' reading", r.labels, "Weather = clear")
has("heartbeat: temperature is the wire byte minus 40", r.labels, "Temperature = 25 C")
has("heartbeat: battery, which the docs called volume until 2026-09-22", r.labels, "BatteryLevel")
has("heartbeat: GPS flag", r.labels, "GpsEnabled = on")

-- 4. Time sync, so the three-byte decoder is exercised.
r = run(fixture("0018000200000000020100054B314720000606000312233B"))
clean("time sync: no expert info on a well-formed packet", r)
has("time sync renders as a clock", r.labels, "TimeSync = 18:35:59")

-- 5. Dash to app: shorter header, TLVs at offset 8, no magic. Copied from a ride's
--    app_log.txt (24.09); suffix meaning and delay location remain unestablished.
r = run(fixture("0011000100000000" .. "010100054B31470008"))
check("dash to app: direction", r.info:sub(1, 6), "dash->")
check("dash to app: whole datagram consumed", r.consumed, r.size)
has("dash to app: the tick counter is read", r.labels, "DashTick = counter 8")

-- 6. Button event, where the code is the LAST byte and not the first.
r = run(fixture("000E00010000000009000002000B"))
clean("button: no expert info on a well-formed packet", r)
has("button code comes from the last byte", r.labels, "code 0x0B")

-- 7. Decoder-opened, which is emphatically not a per-frame ack.
has("decoder opened", run(fixture("000D0001000000000906000155")).labels, "DecoderOpened(IDR)")

-- 8. A TLV whose declared length runs past the datagram must be flagged, not swallowed.
--    The outer length is CORRECT here (15 bytes, declared 0x000F) so the only thing that can
--    raise an expert item is the truncation itself. With the wrong length it had before, the
--    length-mismatch note satisfied the check on its own and deleting the truncation branch
--    from the dissector still printed ok.
r = run("000F00010000000005010004414243")
local truncated = false
for _, it in ipairs(r.sink) do
    if it.expert and it.expert.abbrev == "k1g.truncated" then truncated = true end
end
check("a truncated TLV raises k1g.truncated specifically", truncated, true)

-- 9a. The counter value that used to be read as the outgoing magic. `01 01` carrying
--     "K1G" + counter 0x2008 puts the bytes "K1G " at offsets 12..15, and checking the magic
--     alone called this packet outgoing and then parsed TLVs from past its end. Only tshark
--     caught it the first time; this is the fixture that makes the harness catch it too.
r = run(fixture("0011000100000000010100054B31472008"))
check("a DashTick whose counter spells the magic is still incoming", r.info:sub(1, 6), "dash->")
has("...and is still read as a tick", r.labels, "DashTick = counter 8200")
check("typed suffix preserves both bytes", decoded(r, "k1g.tick.counter"), 8200)
check("typed suffix preserves incoming direction", decoded(r, "k1g.dir"), "dash to app")
check("short tick has no counter",
    decoded(run("0010000100000000010100044B314700"), "k1g.tick.counter"), nil)
check("non-K1G tick has no counter",
    decoded(run("0011000100000000010100054142430008"), "k1g.tick.counter"), nil)
check("truncated tick prefix has no typed counter",
    decoded(run("0011000100000000010100064B31470008"), "k1g.tick.counter"), nil)

-- 9. And a string TLV with no terminator must not take the frame down with it. This is the
--    one that only tshark caught.
r = run("0011000100000000050100054142434445")
clean("an unterminated string TLV dissects without an error", r)
has("an unterminated string TLV still reads", r.labels, 'RouteTitle = "ABCDE"')

-- Media fields are positional/raw: neither sender layout nor UTF-8 is tagged.
r = run(fixture("0027000200000000020100054B31472000"
    .. "050D0012414C42554D005449544C4500415254495354"))
check("card: raw field 1", decoded(r, "k1g.media.field1"), "ALBUM")
check("card: raw field 2", decoded(r, "k1g.media.field2"), "TITLE")
check("card: final field needs no NUL", decoded(r, "k1g.media.field3"), "ARTIST")
clean("card: valid framing", r)
r = run(fixture("0018000200000000020100054B31472000050D0003FF0000"))
check("card: FF is preserved, not decoded as UTF-8", decoded(r, "k1g.media.field1"), "\255")
check("card: empty second field", decoded(r, "k1g.media.field2"), "")
check("card: empty third field", decoded(r, "k1g.media.field3"), "")
r = run(fixture("001D000300000000020100054B31472000050D00034142430517000155"))
check("card: missing NUL cannot read the next TLV", decoded(r, "k1g.media.field1"), "ABC")
check("card: missing second field stays absent", decoded(r, "k1g.media.field2"), nil)
check("card: next media-active TLV still decoded", decoded(r, "k1g.media.active"), 0x55)

-- Art header bytes never become JPEG bytes; final FF may carry a full chunk.
r = run(fixture("001C000200000000020100054B31472000054000070007D05A5B5C5D"))
check("first art: BE16 total", decoded(r, "k1g.art.size"), 2000)
check("first art: excludes 3-byte prefix", decoded(r, "k1g.art.data"), "\90\91\92\93")
check("first art: image length", decoded(r, "k1g.art.data_len"), 4)
r = run(fixture("03FE000200000000020100054B31472000054003E9FF" .. ("5A"):rep(1000)))
check("final art: FF index", decoded(r, "k1g.art.index"), 0xFF)
check("final art: all 1000 image bytes preserved", decoded(r, "k1g.art.data"), ("\90"):rep(1000))
check("final art: image length excludes index", decoded(r, "k1g.art.data_len"), 1000)
check("final art: no total size field", decoded(r, "k1g.art.size"), nil)
r = run(fixture("001C000200000000020100054B3147200005400007FF41004200FF43"))
check("final art: embedded NUL and non-UTF-8 bytes preserved",
    decoded(r, "k1g.art.data"), "A\0B\0\255C")
check("final art: image length includes embedded NULs", decoded(r, "k1g.art.data_len"), 6)
r = run(fixture("0016000200000000020100054B3147200005400001FF"))
check("empty final art: zero image bytes", decoded(r, "k1g.art.data_len"), 0)
check("empty final art: no fabricated JPEG", decoded(r, "k1g.art.data"), nil)
r = run(fixture("0017000200000000020100054B31472000054000020007"))
check("short first art: index survives", decoded(r, "k1g.art.index"), 0)
check("short first art: no out-of-bounds BE16 read", decoded(r, "k1g.art.size"), nil)
check("short first art: size byte not treated as JPEG", decoded(r, "k1g.art.data"), nil)
r = run(fixture("0015000200000000020100054B3147200005400000"))
check("empty art: no invented index", decoded(r, "k1g.art.index"), nil)

-- Levels remain wire codes, not percentages or a guessed Android min/max.
r = run(fixture("001B000300000000020100054B31472000054C000113051B00011A"))
check("volume: music stream independent", decoded(r, "k1g.volume.music"), 0x13)
check("volume: call stream independent", decoded(r, "k1g.volume.call"), 0x1A)
r = run(fixture("0016000200000000020100054B31472000054C000100"))
check("volume: unknown code is not changed to silence", decoded(r, "k1g.volume.music"), 0)

-- ── optional: a .pcapng of these same fixtures, for checking in real Wireshark ──
--
--   lua tools/k1g_test.lua --emit /tmp/k1g-fixtures.pcapng
--   /Applications/Wireshark.app/Contents/MacOS/tshark \
--       -X lua_script:tools/k1g.lua -r /tmp/k1g-fixtures.pcapng
--
-- The stubs above can only check what the dissector DECIDES; they cannot check that
-- Wireshark accepts the calls it makes. That gap is not hypothetical — on 2026-09-24 the
-- first tshark run reported "Dissector bug … value argument is nil" on every packet of a
-- file these same checks had passed. The stub was tightened afterwards, but the honest
-- position is that only the real runtime settles it, so here is a file to settle it with,
-- built from fixtures and therefore safe to share.
--
-- The blocks are the ones `Pcapng.kt` writes, kept deliberately in step with it: section
-- header, one LINKTYPE_IPV4 interface, one enhanced packet per datagram.

local function emit(path)
    local function le32(v)
        return string.char(v % 256, math.floor(v / 256) % 256,
                           math.floor(v / 65536) % 256, math.floor(v / 16777216) % 256)
    end
    local function be16(v) return string.char(math.floor(v / 256) % 256, v % 256) end
    local function block(t, body)
        local total = 12 + #body
        return le32(t) .. le32(total) .. body .. le32(total)
    end
    local function ip4(payload, src, sport, dst, dport, id)
        local function addr(d)
            local o = {}
            for p in d:gmatch("%d+") do o[#o + 1] = string.char(tonumber(p)) end
            return table.concat(o)
        end
        local h = "\69\0" .. be16(20 + 8 + #payload) .. be16(id) .. be16(0x4000)
            .. "\64\17" .. "\0\0" .. addr(src) .. addr(dst)
        local sum = 0
        for i = 1, 20, 2 do sum = sum + h:byte(i) * 256 + h:byte(i + 1) end
        while sum >= 65536 do sum = (sum % 65536) + math.floor(sum / 65536) end
        sum = 65535 - sum
        h = h:sub(1, 10) .. be16(sum) .. h:sub(13)
        return h .. be16(sport) .. be16(dport) .. be16(8 + #payload) .. "\0\0" .. payload
    end

    local out = { block(0x0A0D0D0A, le32(0x1A2B3C4D) .. "\1\0\0\0" .. le32(0xFFFFFFFF) .. le32(0xFFFFFFFF)) }
    out[#out + 1] = block(0x00000001, be16(228):reverse() .. "\0\0" .. le32(0))
    for i, hex in ipairs(FIXTURES) do
        local s2 = hex:gsub("%s", ""):gsub("%x%x", function(b) return string.char(tonumber(b, 16)) end)
        local dash_to_app = s2:sub(13, 16) ~= "K1G "
        local d = dash_to_app
            and ip4(s2, "192.168.1.1", 49155, "192.168.1.2", 2002, i)
            or ip4(s2, "192.168.1.2", 2000, "192.168.1.255", 2000, i)
        local pad = ("\0"):rep((4 - #d % 4) % 4)
        out[#out + 1] = block(0x00000006,
            le32(0) .. le32(0) .. le32(1000000 * i) .. le32(#d) .. le32(#d) .. d .. pad)
    end
    local f = assert(io.open(path, "wb"))
    f:write(table.concat(out))
    f:close()
    print("wrote " .. path .. " (" .. #FIXTURES .. " packets)")
end

if arg[1] == "--emit" then emit(arg[2] or "/tmp/k1g-fixtures.pcapng") end

print(failures == 0 and "\nall checks passed" or string.format("\n%d FAILED", failures))
os.exit(failures == 0 and 0 or 1)
