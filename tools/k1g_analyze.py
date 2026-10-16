#!/usr/bin/env python3
"""Reproducible observations from app-level K1G PCAPNG, decoded by tshark."""
import argparse
from bisect import bisect_left, bisect_right
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


class AnalysisError(Exception):
    pass


REQUIRED = {'frame.section_number', 'frame.interface_id', 'frame.time_epoch',
            'k1g.dir', 'k1g.tlv', 'k1g.type', 'k1g.sub', 'k1g.vlen',
            'k1g.value', 'k1g.tick.counter'}
LIMITATIONS = [
    'App-level control capture: synthetic headers are not wire evidence; RTP is absent.',
    'Captured TX does not prove sendto or delivery; captured RX does not localize delay.',
    'Capture queue loss, size limits and an unwritten tail can omit observations even without warnings.',
    'Tick suffix is BE16; its meaning and hardware modulus are unknown. Boundaries and bursts do not prove reboot, uptime, backlog or packet age.',
    'Protocol commands and auth results do not prove a complete session or dash reboot.',
    'Expert diagnostics do not cover every short or unparsed-tail K1G case.',
    'Combined RX statistics with multiple peers do not establish continuity of one dash.',
]


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            h.update(block)
    return {'file': path.name, 'sha256': h.hexdigest()}


def run_backend(command):
    with tempfile.TemporaryFile() as errors:
        try:
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=errors)
        except OSError as exc:
            raise AnalysisError(str(exc)) from exc
        errors.seek(0)
        stderr = errors.read().decode('utf-8', 'replace').strip()
    if result.returncode or 'lua' in stderr.lower() and ('error' in stderr.lower() or 'failed' in stderr.lower()):
        raise AnalysisError(stderr or 'tshark exited with status %s' % result.returncode)
    return result.stdout.decode('utf-8', 'replace'), stderr


def field(element, name):
    return next((f for f in element.iter('field') if f.get('name') == name), None)


def shown(element, name, required=True):
    f = field(element, name)
    if f is None or f.get('show') is None:
        if required:
            raise AnalysisError('Missing PDML field %s' % name)
        return None
    return f.get('show')


def number(element, name):
    text = shown(element, name)
    try:
        return int(text, 16) if text.lower().startswith('0x') else int(text)
    except ValueError as exc:
        raise AnalysisError('Unexpected numeric field %s: %s' % (name, text)) from exc


def identity(segment):
    return {k: segment[k] for k in ('section', 'interface', 'clock_segment')}


def summary(refs):
    values = sorted(b['timestamp_ns'] - a['timestamp_ns'] for a, b in zip(refs, refs[1:]))
    n = len(values)
    return {'count': n, 'p50_ns': values[(n * 50 + 99) // 100 - 1] if n else None,
            'p95_ns': values[(n * 95 + 99) // 100 - 1] if n else None,
            'max_ns': values[-1] if n else None}


def interval(segment, start, end):
    return dict(identity(segment), start=start, end=end,
                duration_ns=end['timestamp_ns'] - start['timestamp_ns'])


def diagnostic(report, code, segment=None, frames=None, message=''):
    report['diagnostics'].append(dict(identity(segment) if segment else
                                     {'section': None, 'interface': None, 'clock_segment': None},
                                     code=code, frames=[f['frame'] if isinstance(f, dict) else f for f in (frames or [])], message=message))


def transition(previous, current):
    if current == previous + 1:
        return 'unit_increment'
    if (previous, current) == (255, 0):
        return 'observed_00ff_to_0000'
    if (previous, current) == (65535, 0):
        return 'observed_ffff_to_0000'
    return 'duplicate_value' if previous == current else 'nonunit_change'


def finalize(report, s, args):
    rx, tx, hb, ticks = (s.pop(k) for k in ('_rx', '_tx', '_hb', '_ticks'))
    s.pop('_states')
    s['heartbeat_packets'] = len(hb)
    s['tick_messages'] = len(ticks)
    s['rx_intervals'] = summary(rx)
    s['heartbeat_intervals'] = summary(hb)
    s['tick_intervals'] = summary([t['at'] for t in ticks])
    values = [t['counter'] for t in ticks]
    boundaries = Counter({'observed_00ff_to_0000': 0, 'observed_ffff_to_0000': 0})
    s['tick_suffix'] = {'min': min(values) if values else None,
                        'max': max(values) if values else None,
                        'high_byte_counts': dict(sorted(Counter('%02x' % (v >> 8) for v in values).items())),
                        'boundary_counts': {}}
    s['rx_peers'] = sorted(s['rx_peers'])
    if not rx:
        diagnostic(report, 'no_rx_packets', s, [s['first'], s['last']], 'No RX in observed segment coverage.')
    if len(s['rx_peers']) > 1:
        diagnostic(report, 'multiple_rx_peers', s, [s['first'], s['last']], 'Combined RX statistics cover multiple peer IPs.')
    txtimes = [r['timestamp_ns'] for r in tx]
    hbtimes = [r['timestamp_ns'] for r in hb]
    for start, end in zip(rx, rx[1:]):
        record = interval(s, start, end)
        if record['duration_ns'] <= args.rx_gap_ms * 1000000:
            continue
        a, b = start['timestamp_ns'], end['timestamp_ns']
        left, right = bisect_right(hbtimes, a), bisect_left(hbtimes, b)
        window_end = min(b, a + 10000000000)
        points = [a] + hbtimes[left:min(right, bisect_right(hbtimes, window_end))] + [window_end]
        record.update(tx_count=bisect_left(txtimes, b) - bisect_right(txtimes, a),
                      heartbeat_count=right - left,
                      heartbeat_first_10s={'duration_ns': window_end - a, 'count': len(points) - 2,
                                           'max_silence_ns': max(y - x for x, y in zip(points, points[1:]))})
        report['rx_gaps'].append(record)
    if rx and s['last']['timestamp_ns'] - rx[-1]['timestamp_ns'] > args.rx_gap_ms * 1000000:
        report['rx_open_tails'].append(dict(interval(s, rx[-1], s['last']), right_censored=True))
    for name, refs, threshold in [('heartbeat_gaps', hb, args.heartbeat_gap_ms),
                                   ('tick_gaps', [t['at'] for t in ticks], args.rx_gap_ms)]:
        for start, end in zip(refs, refs[1:]):
            record = interval(s, start, end)
            if record['duration_ns'] > threshold * 1000000:
                report[name].append(record)
    burst = []
    burst_boundaries = []

    def finish_burst():
        distinct = len({t['at']['frame'] for t in burst})
        if distinct >= 3:
            report['tick_bursts'].append(dict(interval(s, burst[0]['at'], burst[-1]['at']),
                                             messages=len(burst), distinct_frames=distinct,
                                             ticks=list(burst), boundaries=list(burst_boundaries)))

    for tick in ticks:
        if burst:
            previous = burst[-1]
            kind = transition(previous['counter'], tick['counter'])
            record = dict(interval(s, previous['at'], tick['at']), previous=previous['counter'],
                          current=tick['counter'], signed_delta=tick['counter'] - previous['counter'],
                          delta_mod65536=(tick['counter'] - previous['counter']) % 65536, kind=kind)
            if kind != 'unit_increment':
                report['tick_transitions'].append(record)
            if kind.startswith('observed_'):
                boundaries[kind] += 1
            compatible = kind == 'unit_increment' or kind.startswith('observed_')
            if not compatible or record['duration_ns'] > args.burst_gap_ms * 1000000:
                finish_burst()
                burst = []
                burst_boundaries = []
            elif kind.startswith('observed_'):
                burst_boundaries.append({'at': tick['at'], 'kind': kind})
        burst.append(tick)
    finish_burst()
    s['tick_suffix']['boundary_counts'] = dict(sorted(boundaries.items()))


def consume(packet, report, groups, args):
    for f in packet.iter('field'):
        if f.get('name') in ('_ws.lua.error', '_ws.lua.proto.error'):
            raise AnalysisError('Lua decode error: ' + (f.get('show') or f.get('showname') or 'unknown'))
    section, interface = number(packet, 'frame.section_number'), number(packet, 'frame.interface_id')
    try:
        epoch = Decimal(shown(packet, 'frame.time_epoch')) * 1000000000
        if not epoch.is_finite() or epoch != epoch.to_integral_value():
            raise ValueError('timestamp not integral nanoseconds')
        timestamp = int(epoch)
    except (InvalidOperation, ValueError) as exc:
        raise AnalysisError('Unexpected frame.time_epoch') from exc
    srcip, dstip = shown(packet, 'ip.src', False), shown(packet, 'ip.dst', False)
    srcport, dstport = shown(packet, 'udp.srcport', False), shown(packet, 'udp.dstport', False)
    ref = {'frame': number(packet, 'frame.number'), 'timestamp_ns': timestamp, 'tlv_index': None,
           'src': srcip + ':' + srcport if srcip and srcport else None,
           'dst': dstip + ':' + dstport if dstip and dstport else None}
    key = (section, interface)
    old = groups.get(key)
    rollback = old is not None and timestamp < old['last']['timestamp_ns']
    if old is None or rollback:
        s = {'section': section, 'interface': interface, 'clock_segment': old['clock_segment'] + 1 if old else 0,
             'first': ref, 'last': ref, 'packets': 0, 'tx_packets': 0, 'rx_packets': 0, 'tlv_count': 0,
             'rx_peers': set(), '_rx': [], '_tx': [], '_hb': [], '_ticks': [], '_states': {}}
        groups[key] = s
        report['segments'].append(s)
        if rollback:
            diagnostic(report, 'time_regression', s, [old['last'], ref], 'Timestamp decreased in file order; new clock segment.')
    else:
        s = old
    s['last'] = ref
    s['packets'] += 1
    direction = shown(packet, 'k1g.dir', False)
    if direction not in (None, 'app to dash', 'dash to app'):
        raise AnalysisError('Unexpected k1g.dir: ' + direction)
    if direction == 'dash to app':
        s['rx_packets'] += 1
        s['_rx'].append(ref)
        if srcip:
            s['rx_peers'].add(srcip)
    elif direction == 'app to dash':
        s['tx_packets'] += 1
        s['_tx'].append(ref)
    heartbeat = False
    for index, tlv in enumerate(f for f in packet.iter('field') if f.get('name') == 'k1g.tlv'):
        s['tlv_count'] += 1
        at = dict(ref, tlv_index=index)
        tag = '%02x:%02x' % (number(tlv, 'k1g.type'), number(tlv, 'k1g.sub'))
        length = number(tlv, 'k1g.vlen')
        value_field = field(tlv, 'k1g.value')
        raw = value_field.get('value', '') if value_field is not None else ''
        try:
            rawbytes = bytes.fromhex(raw)
        except ValueError as exc:
            raise AnalysisError('Unexpected raw TLV value') from exc
        complete = len(rawbytes) == length and field(tlv, 'k1g.truncated') is None
        if complete and direction == 'app to dash' and tag == '05:56' and rawbytes == b'\x55':
            heartbeat = True
        counter = field(tlv, 'k1g.tick.counter')
        if complete and direction == 'dash to app' and counter is not None:
            s['_ticks'].append({'at': at, 'counter': number(tlv, 'k1g.tick.counter')})
        always_event = tag in ('08:04', '07:01', '0b:02')
        changed_flag = False
        if complete and tag in ('05:56', '06:05'):
            changed_flag = tag not in s['_states'] or s['_states'][tag] != raw
            s['_states'][tag] = raw
        if always_event or changed_flag:
            report['protocol_events'].append(dict(identity(s), at=at, tag=tag,
                                                  value_length=length, value_hex=raw.lower() if complete and length == 1 else None))
    if heartbeat:
        s['_hb'].append(ref)
    for f in packet.iter('field'):
        name = f.get('name', '')
        if name.startswith('k1g.') and name in ('k1g.truncated', 'k1g.length_mismatch'):
            diagnostic(report, name, s, [ref], f.get('showname') or f.get('show') or name)
        elif name == '_ws.expert.message':
            # Preserve all experts, including container/network warnings, with frame context.
            diagnostic(report, 'expert', s, [ref], f.get('show') or f.get('showname') or '')


def analyze(args):
    capture = Path(args.capture).resolve()
    dissector = Path(__file__).resolve().with_name('k1g.lua')
    try:
        with capture.open('rb') as source:
            if source.read(4) != b'\x0a\x0d\x0d\x0a':
                raise AnalysisError('Input is not a PCAPNG file')
        metadata = digest(capture)
        lua_metadata = digest(dissector)
    except OSError as exc:
        raise AnalysisError(str(exc)) from exc
    prefix = [args.tshark, '-n', '-X', 'lua_script:' + str(dissector)]
    fields, _ = run_backend(prefix + ['-G', 'fields'])
    registered = {parts[2] for line in fields.splitlines() if line.startswith('F\t')
                  for parts in [line.split('\t')] if len(parts) > 2}
    missing = REQUIRED - registered
    if missing:
        raise AnalysisError('Missing required tshark/Lua fields: ' + ', '.join(sorted(missing)))
    version, _ = run_backend([args.tshark, '--version'])
    report = {'schema_version': 1, 'capture': metadata, 'dissector': lua_metadata,
              'tshark_version': version.splitlines()[0] if version.splitlines() else '',
              'timezone': args.timezone,
              'thresholds': {k: getattr(args, k) for k in ('rx_gap_ms', 'heartbeat_gap_ms', 'burst_gap_ms')},
              'segments': [], **{k: [] for k in ('rx_gaps', 'rx_open_tails', 'heartbeat_gaps', 'tick_gaps',
                                                   'tick_transitions', 'tick_bursts', 'protocol_events', 'diagnostics')},
              'limitations': LIMITATIONS}
    groups = {}
    with tempfile.TemporaryFile() as stderr:
        try:
            process = subprocess.Popen(prefix + ['-l', '-r', str(capture), '-T', 'pdml'],
                                       stdout=subprocess.PIPE, stderr=stderr)
        except OSError as exc:
            raise AnalysisError(str(exc)) from exc
        failure = None
        try:
            root = None
            for event, element in ET.iterparse(process.stdout, events=('start', 'end')):
                if root is None:
                    root = element
                    if root.tag != 'pdml':
                        raise AnalysisError('Unexpected tshark output: expected PDML')
                if event == 'end' and element.tag == 'packet':
                    consume(element, report, groups, args)
                    root.remove(element)
                    element.clear()
        except Exception as exc:
            failure = exc
            process.terminate()
        finally:
            process.stdout.close()
            try:
                code = process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                code = process.wait()
            stderr.seek(0)
            upstream = stderr.read().decode('utf-8', 'replace').strip()
        if failure is not None:
            raise AnalysisError('%s%s' % (failure, '\n' + upstream if upstream else '')) from failure
        if code or 'lua' in upstream.lower() and ('error' in upstream.lower() or 'failed' in upstream.lower()):
            raise AnalysisError(upstream or 'tshark exited with status %s' % code)
    for s in report['segments']:
        finalize(report, s, args)
    if not any(s['tx_packets'] or s['rx_packets'] for s in report['segments']):
        diagnostic(report, 'no_k1g_packets', message='No decoded K1G datagrams in observed capture.')
    # Segment finalization is grouped, but event order remains capture file order.
    for key in ('rx_gaps', 'rx_open_tails', 'heartbeat_gaps', 'tick_gaps', 'tick_transitions', 'tick_bursts'):
        report[key].sort(key=lambda r: (r['end']['frame'], r['end']['tlv_index'] if r['end']['tlv_index'] is not None else -1))
    report['diagnostics'].sort(key=lambda r: r['frames'][-1] if r['frames'] else -1)
    return report


def text_report(report):
    zone = ZoneInfo(report['timezone'])

    def render(value):
        if isinstance(value, dict):
            result = {k: render(v) for k, v in value.items()}
            if 'timestamp_ns' in value:
                seconds, ns = divmod(value['timestamp_ns'], 1000000000)
                result['local_time'] = datetime.fromtimestamp(seconds, timezone.utc).astimezone(zone).strftime('%Y-%m-%dT%H:%M:%S') + '.%09d' % ns + datetime.fromtimestamp(seconds, timezone.utc).astimezone(zone).strftime('%z')
            return result
        if isinstance(value, list):
            return [render(v) for v in value]
        return value

    # A labelled, indented rendering retains every measurement and event (no hidden truncation).
    return 'K1G capture observations (not causal diagnoses)\n' + json.dumps(render(report), ensure_ascii=False, indent=2)


def positive(text):
    try:
        result = int(text)
    except ValueError as exc:
        raise argparse.ArgumentTypeError('must be a positive integer') from exc
    if result <= 0:
        raise argparse.ArgumentTypeError('must be a positive integer')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('capture')
    parser.add_argument('--tshark', default='tshark')
    parser.add_argument('--json', action='store_true')
    parser.add_argument('--timezone', default='UTC')
    parser.add_argument('--rx-gap-ms', type=positive, default=2000)
    parser.add_argument('--heartbeat-gap-ms', type=positive, default=1000)
    parser.add_argument('--burst-gap-ms', type=positive, default=100)
    args = parser.parse_args()
    try:
        ZoneInfo(args.timezone)
    except (ZoneInfoNotFoundError, ValueError):
        parser.error('unknown timezone: ' + args.timezone)
    try:
        report = analyze(args)
        output = json.dumps(report, ensure_ascii=False, indent=2) if args.json else text_report(report)
    except (AnalysisError, OSError, ValueError) as exc:
        print('k1g_analyze: ' + str(exc), file=sys.stderr)
        return 1
    print(output)
    return 0


if __name__ == '__main__':
    sys.exit(main())
