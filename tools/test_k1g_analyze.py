"""Consumer tests for the K1G report, using real tshark and synthetic PCAPNG."""
import hashlib
import ipaddress
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest


CLI = Path(__file__).resolve().with_name("k1g_analyze.py")
TSHARK = shutil.which("tshark")
BASE_US = 1_700_000_000_000_000
APP = ("192.0.2.1", 31000)
DASH = ("192.0.2.2", 2002)
CONTROL = ("192.0.2.2", 2000)
TOP_KEYS = {
    "schema_version", "capture", "dissector", "tshark_version", "timezone",
    "thresholds", "segments", "rx_gaps", "rx_open_tails", "heartbeat_gaps",
    "tick_gaps", "tick_transitions", "tick_bursts", "protocol_events",
    "diagnostics", "limitations",
}


def tlv(tag, value):
    return bytes(tag) + struct.pack(">H", len(value)) + value


def packet(*values, outgoing=False):
    body = b"".join(values)
    prefix = bytes.fromhex("020100054b31472000") if outgoing else b""
    return struct.pack(">HHI", 8 + len(prefix) + len(body), len(values) + bool(prefix), 0) + prefix + body


def tick(counter):
    return tlv((1, 1), b"K1G" + struct.pack(">H", counter))


def hb(*extra):
    return packet(tlv((5, 0x56), b"\x55"), *extra, outgoing=True)


def block(kind, body):
    size = len(body) + 12
    return struct.pack("<II", kind, size) + body + struct.pack("<I", size)


def section():
    return (block(0x0A0D0D0A, struct.pack("<IHHq", 0x1A2B3C4D, 1, 0, -1))
            + block(1, struct.pack("<HHI", 228, 0, 65535)))


def datagram(payload, src, dst):
    """Endpoints are explicit, never derived from payload/magic."""
    header = struct.pack(">BBHHHBBH4s4s", 0x45, 0, 28 + len(payload), 0, 0x4000,
                         64, 17, 0, ipaddress.ip_address(src[0]).packed,
                         ipaddress.ip_address(dst[0]).packed)
    total = sum(struct.unpack(">10H", header))
    while total >> 16:
        total = (total & 65535) + (total >> 16)
    header = header[:10] + struct.pack(">H", (~total) & 65535) + header[12:]
    return header + struct.pack(">HHHH", src[1], dst[1], 8 + len(payload), 0) + payload


def epb(offset_us, payload, src, dst):
    raw = datagram(payload, src, dst)
    timestamp = BASE_US + offset_us
    return block(6, struct.pack("<IIIII", 0, timestamp >> 32, timestamp & 0xFFFFFFFF,
                                len(raw), len(raw)) + raw + b"\0" * (-len(raw) % 4))


def rx(offset_us, counter=1, peer=DASH):
    return epb(offset_us, packet(tick(counter)), peer, APP)


def tx(offset_us, payload=None):
    return epb(offset_us, hb() if payload is None else payload, APP, CONTROL)


class ArgumentsTest(unittest.TestCase):
    def test_invalid_arguments(self):
        for args in (["--rx-gap-ms", "0"], ["--rx-gap-ms", "-1"],
                     ["--heartbeat-gap-ms", "1.5"], ["--burst-gap-ms", "0"],
                     ["--timezone", "No/Such_Zone"]):
            with self.subTest(args=args):
                result = subprocess.run([sys.executable, str(CLI), "missing.pcapng", *args],
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertEqual(result.stdout, "")
                self.assertTrue(result.stderr)


@unittest.skipUnless(TSHARK, "real tshark is not available on PATH")
class AnalyzeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.capture = self.directory / "fixture.pcapng"

    def invoke(self, data, *args, cli=CLI, expected=0):
        self.capture.write_bytes(data)
        result = subprocess.run([sys.executable, str(cli), str(self.capture),
                                 "--tshark", TSHARK, "--json", *args],
                                capture_output=True, text=True, cwd=self.directory)
        self.assertEqual(result.returncode, expected, result.stderr)
        if expected:
            self.assertEqual(result.stdout, "")
            self.assertTrue(result.stderr)
            return result
        report = json.loads(result.stdout)
        self.assertEqual(set(report), TOP_KEYS)
        self.assertEqual(report["schema_version"], 1)
        self.assertEqual(report["capture"], {"file": self.capture.name,
                                           "sha256": hashlib.sha256(data).hexdigest()})
        self.assertEqual(report["dissector"]["file"], "k1g.lua")
        self.assertEqual(report["dissector"]["sha256"],
                         hashlib.sha256(Path(cli).with_name("k1g.lua").read_bytes()).hexdigest())
        self.assertTrue(report["tshark_version"])
        self.assertTrue(report["limitations"])
        return report

    def assert_bounds(self, event, first, last, duration):
        self.assertEqual(event["start"]["frame"], first)
        self.assertEqual(event["end"]["frame"], last)
        self.assertEqual(event["duration_ns"], duration)
        self.assertEqual(event["end"]["timestamp_ns"] - event["start"]["timestamp_ns"], duration)

    def test_rx_gap_counts_boundaries_and_determinism(self):
        data = (section() + tx(0) + rx(0) + tx(1_000_000)
                + tx(2_000_000, packet(tlv((8, 4), b"\x01"), outgoing=True))
                + tx(4_000_000, hb(tlv((5, 0x56), b"\x55")))
                + rx(5_000_000, 2) + tx(5_000_000))
        report = self.invoke(data)
        self.assertEqual(report, self.invoke(data))
        self.assertEqual(report["timezone"], "UTC")
        self.assertEqual(report["thresholds"], {"rx_gap_ms": 2000,
                         "heartbeat_gap_ms": 1000, "burst_gap_ms": 100})
        gap, = report["rx_gaps"]
        self.assert_bounds(gap, 2, 6, 5_000_000_000)
        self.assertEqual((gap["tx_count"], gap["heartbeat_count"]), (3, 2))
        self.assertEqual(gap["heartbeat_first_10s"],
                         {"duration_ns": 5_000_000_000, "count": 2, "max_silence_ns": 3_000_000_000})
        self.assertEqual(self.invoke(data, "--rx-gap-ms", "5000")["rx_gaps"], [])
        self.assertEqual(len(self.invoke(data, "--rx-gap-ms", "4999")["rx_gaps"]), 1)
        summary = report["segments"][0]["rx_intervals"]
        self.assertEqual(summary, {"count": 1, "p50_ns": 5_000_000_000,
                                   "p95_ns": 5_000_000_000, "max_ns": 5_000_000_000})

    def test_first_ten_seconds_window_and_heartbeat_threshold(self):
        data = section() + rx(0) + tx(1_000_000) + tx(2_000_000) + tx(10_000_000) + tx(11_000_000) + rx(20_000_000, 2)
        report = self.invoke(data)
        gap, = report["rx_gaps"]
        self.assertEqual(gap["heartbeat_first_10s"],
                         {"duration_ns": 10_000_000_000, "count": 3, "max_silence_ns": 8_000_000_000})
        self.assertEqual(len(report["heartbeat_gaps"]), 1)
        self.assert_bounds(report["heartbeat_gaps"][0], 3, 4, 8_000_000_000)
        report = self.invoke(section() + rx(0) + rx(5_000_000, 2))
        self.assertEqual(report["rx_gaps"][0]["heartbeat_first_10s"]["max_silence_ns"], 5_000_000_000)

    def test_sections_empty_and_non_k1g(self):
        report = self.invoke(section() + rx(0, 255) + section() + rx(100_000_000, 0))
        self.assertEqual([(s["section"], s["interface"], s["clock_segment"]) for s in report["segments"]],
                         [(1, 0, 0), (2, 0, 0)])
        for name in ("rx_gaps", "tick_gaps", "tick_transitions", "tick_bursts"):
            self.assertEqual(report[name], [])
        self.assertEqual(len(section()), 48)
        empty = self.invoke(section())
        self.assertEqual(empty["segments"], [])
        self.assertIn("no_k1g_packets", [d["code"] for d in empty["diagnostics"]])
        plain = self.invoke(section() + epb(0, b"ordinary UDP", ("192.0.2.3", 34001), ("192.0.2.4", 34002)))
        self.assertIn("no_k1g_packets", [d["code"] for d in plain["diagnostics"]])
        self.assertEqual(plain["segments"][0]["rx_intervals"]["max_ns"], None)

    def test_time_regression_on_tx_and_equal_times(self):
        data = section() + rx(1_000_000, 255) + tx(0) + rx(0, 0) + rx(0, 1)
        report = self.invoke(data)
        regressions = [d for d in report["diagnostics"] if d["code"] == "time_regression"]
        self.assertEqual(len(regressions), 1)
        self.assertEqual(regressions[0]["frames"], [1, 2])
        self.assertEqual([s["clock_segment"] for s in report["segments"]], [0, 1])
        for name in ("rx_gaps", "tick_gaps", "tick_transitions", "tick_bursts"):
            self.assertEqual(report[name], [])

    def test_multitlv_indices_counts_and_flags(self):
        incoming = packet(tlv((0xFE, 0xFE), b"?"), tlv((0xFE, 1), b""),
                          tick(8199), tick(8200), tick(8201),
                          tlv((7, 1), b"\x01"), tlv((7, 1), b"\x00"))
        outgoing = packet(tlv((5, 0x56), b"\x55"), tlv((5, 0x56), b"\x55"),
                          tlv((6, 5), b"\x55"), tlv((6, 5), b"\xaa"), outgoing=True)
        report = self.invoke(section() + epb(0, incoming, DASH, APP) + tx(10_000, outgoing))
        seg, = report["segments"]
        self.assertEqual((seg["packets"], seg["rx_packets"], seg["tx_packets"], seg["tlv_count"]), (2, 1, 1, 11))
        self.assertEqual((seg["tick_suffix"]["min"], seg["tick_suffix"]["max"]), (8199, 8201))
        self.assertEqual(seg["tick_suffix"]["high_byte_counts"], {"20": 3})
        self.assertEqual(report["tick_bursts"], [])
        auth = [e for e in report["protocol_events"] if e["tag"] == "07:01"]
        self.assertEqual([(e["at"]["tlv_index"], e["value_hex"]) for e in auth], [(5, "01"), (6, "00")])
        flags = [e for e in report["protocol_events"] if e["tag"] == "06:05"]
        self.assertEqual([(e["at"]["tlv_index"], e["value_hex"]) for e in flags], [(2, "55"), (3, "aa")])
        self.assertEqual(len([e for e in report["protocol_events"] if e["tag"] == "05:56"]), 1)
        self.assertEqual(seg["heartbeat_intervals"]["count"], 0)
        self.assertEqual((seg["heartbeat_packets"], seg["tick_messages"]), (1, 3))

    def test_full_counter_boundaries_and_nonunit_observations(self):
        for counters, kind in (([65534, 65535, 0, 1], "observed_ffff_to_0000"),
                               ([254, 255, 0, 1], "observed_00ff_to_0000")):
            with self.subTest(kind=kind):
                report = self.invoke(section() + b"".join(rx(i * 10_000, n) for i, n in enumerate(counters)))
                event, = report["tick_transitions"]
                self.assertEqual(event["kind"], kind)
                self.assertEqual((event["previous"], event["current"]), (counters[1], 0))
                self.assertEqual(event["signed_delta"], -counters[1])
                self.assertEqual(event["delta_mod65536"], (-counters[1]) % 65536)
                burst, = report["tick_bursts"]
                self.assertEqual([t["counter"] for t in burst["ticks"]], counters)
                self.assertEqual([b["kind"] for b in burst["boundaries"]], [kind])
                self.assertEqual(report["segments"][0]["tick_suffix"]["boundary_counts"][kind], 1)
        report = self.invoke(section() + rx(0, 8200) + rx(10_000, 8200) + rx(20_000, 8205))
        self.assertEqual([e["kind"] for e in report["tick_transitions"]], ["duplicate_value", "nonunit_change"])
        self.assertEqual(report["segments"][0]["rx_packets"], 3)
        self.assertEqual(report["segments"][0]["tick_suffix"]["min"], 8200)

    def test_eleven_arrivals_burst_and_inclusive_boundary(self):
        offsets = [i * 11_700 for i in range(11)]
        report = self.invoke(section() + b"".join(rx(t, 8200 + i) for i, t in enumerate(offsets)))
        burst, = report["tick_bursts"]
        self.assert_bounds(burst, 1, 11, 117_000_000)
        self.assertEqual((burst["messages"], burst["distinct_frames"]), (11, 11))
        self.assertEqual([t["at"]["frame"] for t in burst["ticks"]], list(range(1, 12)))
        self.assertEqual([t["counter"] for t in burst["ticks"]], list(range(8200, 8211)))
        report = self.invoke(section() + rx(0, 1) + rx(100_000, 2) + rx(200_000, 3)
                             + rx(300_001, 4) + rx(310_001, 5) + rx(320_001, 6))
        self.assertEqual([(b["start"]["frame"], b["end"]["frame"]) for b in report["tick_bursts"]], [(1, 3), (4, 6)])
        report = self.invoke(section() + rx(100_000, 1) + rx(110_000, 2) + rx(0, 3) + rx(10_000, 4))
        self.assertEqual(report["tick_bursts"], [])

    def test_malformed_tlv_diagnostics_and_recovery(self):
        malformed = bytes.fromhex("000F00010000000005010004414243")
        short_tick = bytes.fromhex("0011000100000000010100064b31470001")
        mismatch = bytearray(packet(tick(42)))
        mismatch[0:2] = b"\0\x01"
        report = self.invoke(section() + epb(0, malformed, DASH, APP)
                             + epb(10_000, short_tick, DASH, APP)
                             + epb(20_000, bytes(mismatch), DASH, APP) + rx(30_000, 43))
        codes = {d["code"] for d in report["diagnostics"]}
        self.assertIn("k1g.truncated", codes)
        self.assertIn("k1g.length_mismatch", codes)
        self.assertTrue(all(d["frames"] for d in report["diagnostics"] if d["code"].startswith("k1g.")))
        suffix = report["segments"][0]["tick_suffix"]
        self.assertEqual((suffix["min"], suffix["max"]), (42, 43))
        self.assertEqual(report["segments"][0]["tick_intervals"]["count"], 1)
        # An incomplete auth TLV is still a timeline observation, never a result byte.
        short_auth = bytes.fromhex("000c00010000000007010001")
        report = self.invoke(section() + epb(0, short_auth, DASH, APP))
        event, = report["protocol_events"]
        self.assertEqual((event["tag"], event["value_length"], event["value_hex"]),
                         ("07:01", 1, None))
        self.assertEqual(event["at"]["frame"], 1)

    def test_open_tail_and_no_rx(self):
        report = self.invoke(section() + rx(0) + tx(15_000_000))
        self.assertEqual(report["rx_gaps"], [])
        tail, = report["rx_open_tails"]
        self.assert_bounds(tail, 1, 2, 15_000_000_000)
        self.assertIs(tail["right_censored"], True)
        self.assertIsNone(report["segments"][0]["rx_intervals"]["max_ns"])
        report = self.invoke(section() + tx(0) + tx(15_000_000))
        self.assertIn("no_rx_packets", [d["code"] for d in report["diagnostics"]])
        self.assertEqual(report["rx_open_tails"], [])
        self.assertIsNone(report["segments"][0]["rx_intervals"]["p50_ns"])

    def test_multiple_peers_and_endpoint_refs(self):
        other = ("192.0.2.9", 2002)
        report = self.invoke(section() + rx(0) + rx(3_000_000, 2, peer=other))
        self.assertEqual(report["segments"][0]["rx_peers"], ["192.0.2.2", "192.0.2.9"])
        self.assertIn("multiple_rx_peers", [d["code"] for d in report["diagnostics"]])
        gap, = report["rx_gaps"]
        self.assertEqual(gap["start"]["src"], "192.0.2.2:2002")
        self.assertEqual(gap["end"]["src"], "192.0.2.9:2002")
        self.assertEqual(gap["end"]["dst"], "192.0.2.1:31000")
        self.assertIsNone(gap["start"]["tlv_index"])

    def test_protocol_timeline_redacts_blobs_and_resets(self):
        payload = packet(tlv((8, 4), b"\x01"), tlv((7, 1), b"\x01"),
                         tlv((11, 2), b"secret blob"), tlv((6, 5), b"\x55"))
        report = self.invoke(section() + epb(10_000, payload, DASH, APP)
                             + epb(20_000, payload, DASH, APP)
                             + epb(0, payload, DASH, APP))
        events = report["protocol_events"]
        self.assertEqual(len([e for e in events if e["tag"] == "06:05"]), 2)
        blobs = [e for e in events if e["tag"] == "0b:02"]
        self.assertEqual(len(blobs), 3)
        self.assertTrue(all(e["value_hex"] is None and e["value_length"] == 11 for e in blobs))
        self.assertEqual([e["at"]["frame"] for e in events], sorted(e["at"]["frame"] for e in events))

    def test_container_and_backend_failures(self):
        good = section() + rx(0)
        for invalid in (b"", b"not pcapng", good[:-1],
                        section() + struct.pack("<II", 6, 4)):
            with self.subTest(size=len(invalid)):
                self.invoke(invalid, expected=1)
        self.invoke(good, "--tshark", str(self.directory / "absent-tshark"), expected=1)
        nonexec = self.directory / "not-executable"
        nonexec.write_text("not executable")
        nonexec.chmod(0o600)
        self.invoke(good, "--tshark", str(nonexec), expected=1)
        missing = subprocess.run([sys.executable, str(CLI), str(self.directory / "absent.pcapng"),
                                  "--tshark", TSHARK, "--json"], capture_output=True, text=True)
        self.assertEqual(missing.returncode, 1)
        self.assertEqual(missing.stdout, "")
        self.assertTrue(missing.stderr)

    def test_counter_validation_and_tick_gap_threshold(self):
        values = packet(tlv((1, 1), b"K1"), tlv((1, 1), b"BAD\x00\x09"),
                        tlv((1, 1), b"K1G\x00\x07extra"))
        report = self.invoke(section() + epb(0, values, DASH, APP)
                             + rx(2_000_000, 8) + rx(4_001_000, 9))
        self.assertEqual(report["segments"][0]["tick_suffix"]["min"], 7)
        self.assertEqual(report["segments"][0]["tick_suffix"]["max"], 9)
        gap, = report["tick_gaps"]
        self.assert_bounds(gap, 2, 3, 2_001_000_000)
        summary = report["segments"][0]["tick_intervals"]
        self.assertEqual(summary, {"count": 2, "p50_ns": 2_000_000_000,
                                   "p95_ns": 2_001_000_000, "max_ns": 2_001_000_000})

    def test_real_lua_load_and_runtime_failure(self):
        copied = self.directory / "k1g_analyze.py"
        shutil.copyfile(CLI, copied)
        lua = self.directory / "k1g.lua"
        lua.write_text("error('pcap-analysis test Lua load failure')\n")
        result = self.invoke(section() + rx(0), cli=copied, expected=1)
        self.assertIn("pcap-analysis test Lua load failure", result.stderr)
        lua.write_text("-- Intentionally registers no K1G fields.\n")
        self.invoke(section() + rx(0), cli=copied, expected=1)
        original = CLI.with_name("k1g.lua").read_text()
        # The original dissector's local Proto is not globally accessible. Inject at
        # its actual declaration instead, preserving real field registration.
        lua.write_text(original.replace("function k1g.dissector(tvb, pinfo, tree)",
                       "function k1g.dissector(tvb, pinfo, tree)\n error('pcap-analysis test runtime failure')", 1))
        result = self.invoke(section() + rx(0), cli=copied, expected=1)
        self.assertIn("pcap-analysis test runtime failure", result.stderr)


if __name__ == "__main__":
    unittest.main()
