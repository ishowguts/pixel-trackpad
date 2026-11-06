#!/usr/bin/env python3
"""Non-injecting recognition, boundary, bridge, and local TLS smoke checks."""
import hashlib
import io
import json
import math
import os
import stat
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import unittest
from unittest.mock import Mock, patch

from receiver import Bridge, Recognizer, Session, context, decode, serve_client


class PrototypeCheck(unittest.TestCase):
    def test_idle_recovery_stop_and_surface(self):
        import receiver
        # Only in-memory native commands and disposable sockets; never launch an input helper.
        for capable, stop in ((False, True), (True, True), (True, False)):
            secret = secrets.token_hex(32)
            events, errors = [], []
            stopping = threading.Event()
            server, peer = socket.socketpair()
            def poll():
                if stopping.is_set():
                    raise receiver.StopRequested('Stopped by check')
            def run():
                try:
                    serve_client(server, secret, events.append, poll=poll)
                except receiver.StopRequested:
                    pass
                except Exception as error:
                    errors.append(type(error).__name__)
                finally:
                    server.close()
            thread = threading.Thread(target=run)
            thread.start()
            try:
                with peer, peer.makefile('rb') as replies:
                    peer.settimeout(2)
                    def send(**packet):
                        peer.sendall((json.dumps(packet)+'\n').encode())
                    send(type='hello', version=1, secret=secret, t=time.monotonic()*1000,
                         settings={'drag': True, 'gain': 21.6, 'scroll': 15}, recovery=capable)
                    welcome = json.loads(replies.readline())
                    self.assertEqual(welcome, dict(type='ready', stop=True, surfaceScale=True) if capable else dict(type='ready'))
                    # More than both the one-second receiver bound and three-second ACK deadline.
                    for seq in range(14):
                        time.sleep(.25)
                        send(type='heartbeat', seq=seq, t=time.monotonic()*1000)
                        self.assertEqual(replies.read(1), b'K')
                    # Equivalent physical travel across a resized surface keeps the configured gain.
                    for seq, x in ((14, .2), (15, .21)):
                        send(type='sample', seq=seq, t=time.monotonic()*1000,
                             surface=[2, 1.5], contacts=[dict(id=0, x=x, y=.2)])
                        self.assertEqual(replies.read(1), b'K')
                    self.assertAlmostEqual(sum(e['x'] for e in events if e['op'] == 'move'), 2.6*21.6)
                    if stop:
                        stopping.set()
                        self.assertEqual(replies.read(), b'S' if capable else b'')
                    else:
                        # Idle without heartbeats still expires and releases held input.
                        self.assertEqual(replies.read(), b'')
                thread.join(2)
                self.assertFalse(thread.is_alive())
                self.assertEqual(errors, [] if stop else ['ValueError'])
                self.assertEqual([e['down'] for e in events if e['op'] == 'button'], [1, 0])
            finally:
                peer.close()
                stopping.set()
                thread.join(2)
        # Surface fields remain validated at the trust boundary.
        session = Session(dict(type='hello', version=1, secret=secret, t=0), secret, lambda event: None, now=0)
        with self.assertRaises(ValueError):
            session.frame(dict(type='sample', seq=0, t=0, contacts=[], surface=[9, 1]), now=0)

    def test_helper_failure_reconnect(self):
        import receiver
        secret = secrets.token_hex(32)
        bridges = []

        class FailingBridge(Bridge):
            def __init__(self, dry):
                self.assert_dry = dry
                super().__init__(dry)
                bridges.append(self)

            def emit(self, event):
                # Reject a command after drag-down, exercising native failure cleanup.
                if self is bridges[0] and event['op'] == 'move':
                    event = dict(op='invalid')
                super().emit(event)

        with tempfile.TemporaryDirectory(prefix='pixel-trackpad-reconnect-') as directory:
            state = Path(directory)
            (state/'pairing.json').write_text(json.dumps(dict(host='127.0.0.1', port=45873, secret=secret)))
            first, client1 = socket.socketpair()
            second, client2 = socket.socketpair()
            with first, second, client1, client2:
                for client in (client1, client2):
                    packets = [dict(type='hello', version=1, secret=secret, t=time.monotonic()*1000,
                                    settings={'drag': True})]
                    for seq, x in enumerate((.4, .401)):
                        packets.append(dict(type='sample', seq=seq, t=time.monotonic()*1000,
                                            contacts=[dict(id=0, x=x, y=.4)]))
                    client.sendall(''.join(json.dumps(p)+'\n' for p in packets).encode())
                    client.shutdown(socket.SHUT_WR)
                    client.settimeout(3)
                listener = Mock()
                listener.__enter__ = Mock(return_value=listener)
                listener.__exit__ = Mock(return_value=False)
                listener.accept.side_effect = [(first, None), (second, None), KeyboardInterrupt]
                # TLS is exercised separately; use socket pairs to check the actual listener loop.
                tls = Mock()
                tls.wrap_socket.side_effect = lambda raw, **kwargs: raw
                output = io.StringIO()
                with patch.object(receiver, 'STATE', state), patch.object(receiver, 'context', return_value=tls), \
                     patch.object(receiver.socket, 'socket', return_value=listener), \
                     patch.object(receiver, 'Bridge', FailingBridge), \
                     patch('sys.argv', ['receiver.py', 'run', '--dry-run']), patch('sys.stdout', output):
                    receiver.main()
                with client1.makefile('rb') as replies1, client2.makefile('rb') as replies2:
                    self.assertEqual(replies1.read(), b'{"type":"ready"}\nK')
                    self.assertEqual(replies2.read(), b'{"type":"ready"}\nKK')
                self.assertIn('Native bridge cleanup failed; session ended.', output.getvalue())
                self.assertEqual(len(bridges), 2)
                self.assertEqual([b.failed for b in bridges], [True, False])
                for bridge in bridges:
                    self.assertTrue(bridge.assert_dry)
                    self.assertTrue(bridge.stdin.closed)
                    self.assertTrue(bridge.stdout.closed)
                    self.assertIsNotNone(bridge.launcher.poll())
                    self.assertFalse(Path(bridge.directory.name).exists())
                self.assertEqual(first.fileno(), -1)
                self.assertEqual(second.fileno(), -1)

    def test_recognition(self):
        events = []
        r = Recognizer({}, events.append)
        r.sample({0: (0., 0.)}, 1)
        for step in range(1, 11):
            r.sample({0: (step*.01, 0.)}, 1+step*.01)
        self.assertAlmostEqual(sum(e['x'] for e in events if e['op'] == 'move'), .6)
        before = len(events)
        r.sample({0: (.1, 0.), 3: (20., 0.)}, 1.2)
        self.assertEqual(len(events), before)  # No centroid jump on finger arrival.
        r.sample({0: (-3., 0.), 3: (23., 0.)}, 1.22)
        self.assertEqual(r.mode, 'pinch')
        self.assertFalse(any(e.get('test') == 0 for e in events))
        r.cancel()
        self.assertEqual(events[-1]['phase'], 8)
        r = Recognizer({}, events.append)
        r.sample({0: (0, 0), 1: (20, 0)}, 2)
        r.sample({0: (0, 4), 1: (20, 4)}, 2.02)
        self.assertEqual(r.mode, 'scroll')
        r.sample({}, 2.04)
        self.assertIsNotNone(r.momentum)
        r.tick(time.monotonic()+.02)
        r.sample({0: (0, 0)}, 2.06)
        self.assertIsNone(r.momentum)
        self.assertTrue(any(e.get('momentum') == 1 and e.get('phase') == 4 for e in events))
        r.cancel()
        r = Recognizer({'drag': True}, events.append)
        r.sample({0: (0, 0)}, 3)
        self.assertTrue(r.held)
        r.cancel()
        self.assertFalse(r.held)
        self.assertEqual(events[-1]['down'], 0)
        r = Recognizer({'threeDrag': True}, events.append)
        r.sample({i: (i*10, 0) for i in range(3)}, 4)
        r.sample({i: (i*10+20, 0) for i in range(3)}, 4.02)
        self.assertEqual(r.mode, 'drag')
        r.cancel()
        r = Recognizer({}, events.append)
        r.sample({i: (i*10, 0) for i in range(4)}, 5)
        r.sample({i: (i*10-24, 0) for i in range(4)}, 5.02)
        self.assertEqual(r.mode, 'desktop')
        self.assertAlmostEqual(r.progress, .6)
        r.sample({i: (i*10-3, 0) for i in range(4)}, 5.04)
        r.sample({}, 5.06)
        self.assertEqual(events[-1]['phase'], 8)
        self.assertAlmostEqual(events[-1]['value'], .075)
        r.sample({i: (i*10, 0) for i in range(3)}, 6)
        r.sample({i: (i*10+24, 0) for i in range(3)}, 6.02)
        r.sample({}, 6.04)
        self.assertEqual((events[-1]['phase'], events[-1]['value']), (4, -1.))
        # A physical multi-finger lift arrives as separate pointer-up transitions.
        events.clear()
        r.sample({i: (i*10, 0) for i in range(4)}, 7)
        r.sample({i: (i*10-24, 0) for i in range(4)}, 7.02)
        r.sample({i: (i*10-24, 0) for i in range(3)}, 7.03)
        self.assertEqual((events[-1]['phase'], events[-1]['value']), (4, 1.))
        before = len(events)
        r.sample({0: (100, 0)}, 7.04)
        r.sample({0: (110, 0)}, 7.05)
        r.sample({}, 7.06)
        self.assertEqual(len(events), before)
        events.clear()
        r = Recognizer({}, events.append)
        r.sample({0: (0, 0), 1: (20, 0)}, 8)
        r.sample({0: (0, 0), 1: (20*math.cos(.2), 20*math.sin(.2))}, 8.02)
        self.assertEqual(r.mode, 'rotate')
        r.cancel()
        self.assertEqual((events[-1]['test'], events[-1]['phase']), (2, 8))
        events.clear()
        r = Recognizer({}, events.append, clock=lambda: 10.)
        r.sample({0: (0, 0)}, 9)
        r.sample({0: (0, 0), 1: (20, 0)}, 9.01)
        r.sample({0: (0, 4), 1: (20, 4)}, 9.03)
        r.sample({0: (0, 4)}, 9.04)
        self.assertIsNone(r.momentum)
        self.assertEqual((events[-1]['test'], events[-1]['phase']), (0, 4))
        before = len(events)
        r.sample({0: (5, 4)}, 9.045)
        self.assertEqual(len(events), before)
        r.sample({}, 9.05)
        self.assertIsNotNone(r.momentum)
        r.tick(10.02)
        r.sample({0: (0, 0)}, 9.08)
        self.assertIsNone(r.momentum)
        r.cancel()
        self.assertEqual(sum(e.get('op') == 'anchor' for e in events), 2)

    def test_two_finger_selection(self):
        origin = {0: (0, 0), 1: (20, 0)}
        paths = [
            # Unequal translation previously latched pinch on the first sample.
            [(.02, {0: (0, 3), 1: (22.2, 4)})],
            # Asynchronous updates followed by translation with modest angle drift.
            [(.01, {0: (0, .4), 1: (20, 0)}),
             (.02, {0: (0, 3), 1: (20, 6)}),
             (.04, {0: (.2, 5), 1: (20.4, 8.2)})],
            # Entire slow swipe stays below the old 2 mm activation threshold.
            [(step*.08, {0: (.02*step, .12*step), 1: (20, .14*step)})
             for step in range(1, 11)],
        ]
        for path in paths:
            with self.subTest(path=path):
                events = []
                r = Recognizer({}, events.append, clock=lambda: 10.)
                r.sample(origin, 0)
                for t, points in path:
                    r.sample(points, t)
                self.assertEqual(r.mode, 'scroll')
                scroll = [e for e in events if e.get('test') == 0]
                self.assertEqual(scroll[0]['phase'], 1)
                self.assertTrue(any(e['phase'] == 2 and e['value'] > 0 for e in scroll))
                self.assertFalse(any(e.get('test') in (1, 2) for e in events))
                # Selection remains exclusive even when later separation dominates.
                r.sample({0: (-10, points[0][1]+1), 1: (30, points[1][1]+1)}, t+.01)
                self.assertEqual(r.mode, 'scroll')
                r.sample({0: (-10, points[0][1]+1)}, t+.02)
                self.assertEqual((events[-1]['test'], events[-1]['phase']), (0, 4))
                self.assertIsNone(r.momentum)
                before = len(events)
                r.sample({0: (-5, points[0][1]+1)}, t+.025)
                self.assertEqual(len(events), before)
                r.sample({}, t+.03)
                self.assertIsNotNone(r.momentum)
                r.tick(10.02)
                self.assertTrue(any(e.get('momentum') == 1 and e.get('phase') == 2 for e in events))
                r.cancel()
                self.assertEqual((events[-1]['phase'], events[-1]['momentum']), (4, 1))
                self.assertFalse(any(e['op'] in ('move', 'button') for e in events))

        events = []
        r = Recognizer({}, events.append, clock=lambda: 10.)
        for t in (1, 1.2):
            r.sample(origin, t)
            r.sample({0: (.1, .2), 1: (19.9, .1)}, t+.02)
            r.sample({}, t+.05)
        self.assertEqual([e.get('test') for e in events if e['op'] == 'gesture'], [3])
        r.sample(origin, 2)
        r.sample({0: (-.1, .1), 1: (20.1, -.1)}, 2.02)
        r.sample({}, 2.05)
        r.tick(10.33)
        self.assertEqual(events[-1].get('right'), 1)
        # A short scroll following a tap must cancel its deferred secondary click.
        r.sample(origin, 3)
        r.sample({}, 3.05)
        r.sample(origin, 3.2)
        r.sample({0: (0, 1), 1: (20, 1.1)}, 3.22)
        self.assertEqual(r.mode, 'scroll')
        before = len(events)
        r.tick(10.34)
        self.assertEqual(len(events), before)
        r.cancel()
        self.assertEqual((events[-1]['test'], events[-1]['phase']), (0, 8))

        for mode, test, points in (
            ('pinch', 1, {0: (-2, 0), 1: (22, 0)}),
            ('rotate', 2, {0: (10-10*math.cos(.2), -10*math.sin(.2)),
                           1: (10+10*math.cos(.2), 10*math.sin(.2))}),
        ):
            with self.subTest(mode=mode):
                events = []
                r = Recognizer({}, events.append)
                r.sample(origin, 0)
                r.sample(points, .02)
                self.assertEqual(r.mode, mode)
                r.sample({}, .04)
                self.assertEqual([(e['test'], e['phase']) for e in events if e['op'] == 'gesture'],
                                 [(test, 1), (test, 2), (test, 4)])

    def test_taps_and_transitions(self):
        events = []
        r = Recognizer({}, events.append, clock=lambda: 10.)
        for t in (1, 1.2):
            r.sample({0: (0, 0)}, t)
            r.sample({}, t+.05)
        self.assertEqual([e['count'] for e in events if e['op'] == 'button'], [1, 1, 2, 2])
        events.clear()
        for t in (2, 2.2):
            r.sample({0: (0, 0)}, t)
            r.sample({0: (0, 0), 1: (20, 0)}, t+.01)
            r.sample({0: (0, 0)}, t+.06)
            r.sample({}, t+.07)
        self.assertFalse(any(e.get('right') == 1 for e in events))
        self.assertEqual(events[-1].get('test'), 3)
        self.assertFalse(any(e['op'] == 'button' and e.get('right') == 0 for e in events))
        r.sample({0: (0, 0), 1: (20, 0)}, 2.6)
        r.sample({}, 2.65)
        before = len(events)
        r.tick(10.319)
        self.assertEqual(len(events), before)
        r.tick(10.321)
        self.assertEqual(events[-1].get('right'), 1)
        r.sample({0: (0, 0), 1: (20, 0)}, 2.8)
        r.sample({}, 2.85)
        r.cancel()
        before = len(events)
        r.tick(11.)
        self.assertEqual(len(events), before)
        events.clear()
        r.sample({0: (0, 0)}, 3)
        r.sample({0: (10, 0)}, 3.02)
        r.sample({0: (10, 0), 1: (20, 0)}, 3.04)
        r.sample({1: (20, 0)}, 3.06)
        r.sample({}, 3.08)
        self.assertFalse(any(e['op'] == 'button' for e in events))

    def test_packets(self):
        secret = secrets.token_hex(32)
        def session():
            return Session(dict(type='hello', version=1, secret=secret, t=1000, settings={}), secret, lambda _: None, now=10)
        valid = dict(type='sample', seq=0, t=1001, contacts=[dict(id=0, x=.1, y=.2)])
        s = session(); s.frame(valid, now=10.001)
        with self.assertRaises(ValueError): s.frame(valid, now=10.002)
        for field, value in [('seq', True), ('t', float('nan')), ('t', 1), ('seq', 4), ('contacts', [dict(id=0, x=2, y=0)]),
                             ('contacts', [dict(id=0, x=0, y=0)]*2), ('contacts', [dict(id=i, x=0, y=0) for i in range(6)]),
                             ('contacts', [dict(id=False, x=0, y=0)]), ('contacts', [dict(id=0, x=float('inf'), y=0)])]:
            with self.subTest(field=field, value=value):
                packet = valid | {field: value}
                with self.assertRaises(ValueError): session().frame(packet, now=10.001)
        with self.assertRaises(ValueError): session().frame(valid, now=11)
        for raw in ('{"a":1,"a":2}', '{"a":NaN}', '['*1100+'0'+']'*1100):
            with self.assertRaises(ValueError): decode(raw)
        for config in ({'gain': float('nan')}, {'natural': 1}, {'width': 0}):
            with self.assertRaises(ValueError): Recognizer(config, lambda _: None)

    def test_native_bridge(self):
        from receiver import BINARY
        result = subprocess.run([str(BINARY), '--self-check'], capture_output=True, text=True, check=True)
        self.assertIn('305 event frames', result.stdout)
        events = [dict(op='anchor'), dict(op='button', down=1, right=0, count=1), dict(op='move', x=.25, y=-.5),
                  dict(op='gesture', test=1, phase=1, value=0, x=0, momentum=0),
                  dict(op='gesture', test=1, phase=2, value=.1, x=0, momentum=0)]
        result = subprocess.run([str(BINARY), '--bridge-check'], input=''.join(json.dumps(e)+'\n' for e in events),
                                capture_output=True, text=True, check=True)
        self.assertEqual(result.stdout.count('ok\n'), len(events))
        self.assertIn('No events posted', result.stderr)
        result = subprocess.run([str(BINARY), '--bridge-check'], input='{"op":"move","x":1e999,"y":0}\n', capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)
        self.assertIn('No events posted', result.stderr)
        # Python dying while input is held must not let SIGPIPE bypass native cleanup.
        with subprocess.Popen([str(BINARY), '--bridge-check'], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True) as process:
            process.stdout.close()
            process.stdin.write(json.dumps(dict(op='button', down=1, right=0, count=1))+'\n')
            process.stdin.close()
            self.assertEqual(process.wait(timeout=4), 2)
            self.assertIn('No events posted', process.stderr.read())
        # Keep stdin open and stop sending: the native watchdog must still terminate and release.
        with subprocess.Popen([str(BINARY), '--bridge-check'], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, text=True) as process:
            for event in events:
                process.stdin.write(json.dumps(event)+'\n')
            process.stdin.flush()
            for _ in events:
                self.assertEqual(process.stdout.readline(), 'ok\n')
            self.assertEqual(process.wait(timeout=4), 0)
            self.assertIn('No events posted', process.stderr.read())

    def test_app_launch_bridge(self):
        # This is the live launch/IPC path, with only the native non-posting switch changed.
        bridge = Bridge(True)
        state = Path(bridge.directory.name)
        try:
            self.assertEqual(stat.S_IMODE(state.stat().st_mode), 0o700)
            for name in ('stdin', 'stdout'):
                self.assertTrue(stat.S_ISFIFO((state/name).stat().st_mode))
                self.assertEqual(stat.S_IMODE((state/name).stat().st_mode), 0o600)
            self.assertEqual(bridge.launcher.args[-1], '--bridge-check')
            bridge.emit(dict(op='button', down=1, right=0, count=1))
            bridge.emit(dict(op='gesture', test=1, phase=1, value=0, x=0, momentum=0))
            bridge.close()
            self.assertTrue(bridge.stdin.closed and bridge.stdout.closed)
            self.assertFalse(state.exists())
            self.assertIsNotNone(bridge.launcher.poll())
        finally:
            bridge.close()
        # The actual app watchdog closes the stream while the receiver keeps stdin open.
        bridge = Bridge(True)
        try:
            bridge.emit(dict(op='button', down=1, right=0, count=1))
            self.assertTrue(__import__('select').select([bridge.stdout], [], [], 4)[0])
            self.assertEqual(bridge.stdout.read(1), b'')
            self.assertIn('No events posted', (Path(bridge.directory.name)/'helper.stderr').read_text())
        finally:
            bridge.close()
        # A missing acknowledgement has a one-second bound; close still delivers EOF.
        bridge = Bridge(True)
        try:
            with patch('receiver.select.select', return_value=([], [bridge.stdin], [])):
                started = time.monotonic()
                with self.assertRaisesRegex(OSError, 'acknowledgement timeout'):
                    bridge.emit(dict(op='ping'))
                self.assertLess(time.monotonic()-started, 1.5)
            with self.assertRaisesRegex(OSError, 'stream failed'):
                bridge.close()
            self.assertFalse(Path(bridge.directory.name).exists())
        finally:
            bridge.close()
        # Startup failure must close descriptors, reap open, and remove private state.
        import receiver
        with patch.object(receiver, 'BINARY', Path('/nonexistent/Diagnostic.app/Contents/MacOS/Diagnostic')):
            with self.assertRaises(OSError):
                Bridge(True)

    def test_tls(self):
        with tempfile.TemporaryDirectory(prefix='pixel-trackpad-check-') as directory:
            state = Path(directory)
            subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(state/'key.pem'),
                            '-out', str(state/'cert.pem'), '-days', '1', '-subj', '/CN=Local test'],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            tls = context(state)
            pin = hashlib.sha256(ssl.PEM_cert_to_DER_cert((state/'cert.pem').read_text())).digest()
            secret = secrets.token_hex(32)
            outcomes = []
            events = []
            with socket.socket() as listener:
                listener.bind(('127.0.0.1', 0)); listener.listen(1); listener.settimeout(5)
                def server():
                    for _ in range(8):
                        bridge = None
                        try:
                            raw, _ = listener.accept()
                            with raw:
                                raw.settimeout(2)
                                with tls.wrap_socket(raw, server_side=True) as connection:
                                    def emit(event):
                                        nonlocal bridge
                                        self.assertNotEqual(connection.getsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY), 0)
                                        events.append(event)
                                        if bridge is None: bridge = Bridge(True)
                                        bridge.emit(event)
                                    serve_client(connection, secret, emit)
                            outcomes.append('closed')
                        except (ValueError, OSError):
                            outcomes.append('rejected')
                        finally:
                            if bridge: bridge.close()
                thread = threading.Thread(target=server, daemon=True); thread.start()
                def client(fingerprint=pin):
                    # Test client pins before sending application data, matching the phone's pin policy.
                    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                    ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
                    connection = ctx.wrap_socket(socket.create_connection(listener.getsockname()), server_hostname='Local test')
                    connection.settimeout(3)
                    if not secrets.compare_digest(hashlib.sha256(connection.getpeercert(binary_form=True)).digest(), fingerprint):
                        connection.close(); raise ValueError('Fingerprint rejected')
                    return connection
                def hello(connection, token=secret, config=None):
                    connection.sendall((json.dumps(dict(type='hello', version=1, secret=token, t=time.monotonic()*1000,
                                                         settings=config or {}))+'\n').encode())
                    with connection.makefile('rb') as replies:
                        return replies.readline()
                def sample(connection, seq, contacts):
                    connection.sendall((json.dumps(dict(type='sample', seq=seq, t=time.monotonic()*1000, contacts=contacts))+'\n').encode())
                    return connection.recv(1)
                with client() as connection:
                    self.assertIn(b'ready', hello(connection, config={'drag': True}))
                    self.assertEqual(sample(connection, 0, [dict(id=0, x=.4, y=.4)]), b'K')
                    self.assertEqual(sample(connection, 1, [dict(id=0, x=.401, y=.4)]), b'K')
                with client() as connection:
                    self.assertEqual(hello(connection, '0'*64), b'')
                with self.assertRaises(ValueError): client(b'0'*32)
                with client() as connection:
                    self.assertIn(b'ready', hello(connection, config={'drag': True}))
                    self.assertEqual(sample(connection, 0, [dict(id=0, x=.1, y=.1)]), b'K')
                    connection.sendall(b'{broken json}\n')
                    self.assertEqual(connection.recv(1), b'')
                with client() as connection:
                    self.assertIn(b'ready', hello(connection))
                    connection.sendall(b'x'*8193)
                    try: self.assertEqual(connection.recv(1), b'')
                    except ConnectionResetError: pass
                with client() as connection:
                    self.assertIn(b'ready', hello(connection, config={'drag': True}))
                    self.assertEqual(sample(connection, 0, [dict(id=0, x=.1, y=.1)]), b'K')
                    time.sleep(1.15)
                    self.assertEqual(connection.recv(1), b'')
                with client() as connection:
                    self.assertIn(b'ready', hello(connection))
                    connection.sendall((json.dumps(dict(type='sample', seq=0, t=1, contacts=[]))+'\n').encode())
                    self.assertEqual(connection.recv(1), b'')
                with client() as connection:
                    self.assertIn(b'ready', hello(connection))
                    sample(connection, 0, [dict(id=0, x=.1, y=.1), dict(id=1, x=.3, y=.1)])
                    sample(connection, 1, [dict(id=0, x=.05, y=.1), dict(id=1, x=.35, y=.1)])
                    connection.sendall((json.dumps(dict(type='cancel', seq=2, t=time.monotonic()*1000))+'\n').encode())
                    self.assertEqual(connection.recv(1), b'K')
                thread.join(5)
                self.assertFalse(thread.is_alive())
            self.assertEqual(len(outcomes), 8)
            self.assertGreaterEqual(outcomes.count('rejected'), 5)
            self.assertEqual(sum(e.get('down', 0) for e in events if e['op'] == 'button'), 3)
            self.assertEqual(sum(e.get('down') == 0 for e in events if e['op'] == 'button'), 3)
            self.assertTrue(any(e.get('test') == 1 and e.get('phase') == 8 for e in events))
            print('TLS PASS: valid pairing; bad secret/pin; malformed/oversized/stale frames; timeout; disconnect/cancel cleanup. Dry native sink only.')


if __name__ == '__main__':
    unittest.main(verbosity=2)
