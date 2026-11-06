#!/usr/bin/env python3
"""Explicit-run, single-client TLS touch receiver. No input in dry-run mode."""
import argparse
import hashlib
import hmac
import ipaddress
import json
import math
import os
from pathlib import Path
import secrets
import re
import select
import socket
import ssl
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parent
STATE = ROOT / '.local'
BINARY = ROOT / 'build/Pixel Trackpad.app/Contents/MacOS/GestureDiagnostic'
LIMIT = 8192


def number(value, low, high):
    if type(value) not in (int, float) or not math.isfinite(value) or not low <= value <= high:
        raise ValueError('Invalid numeric field')
    return value


def settings(raw):
    if not isinstance(raw, dict):
        raise ValueError('Settings must be an object')
    result = {}
    for key, default, low, high in [('width', 130, 30, 300), ('height', 60, 20, 200),
                                  ('gain', 6, .1, 30), ('scroll', 3, .1, 20),
                                  ('pinch', 1, .1, 5), ('rotation', 1, .1, 5)]:
        result[key] = number(raw.get(key, default), low, high)
    for key in ('acceleration', 'natural', 'drag', 'threeDrag', 'vertical'):
        value = raw.get(key, key == 'natural')
        if type(value) is not bool:
            raise ValueError('Invalid switch')
        result[key] = value
    return result


class Recognizer:
    def __init__(self, config, emit, clock=time.monotonic):
        self.config, self.emit = settings(config), emit
        self.clock = clock
        self.points = {}
        self.mode = None
        self.held = False
        self.tap_ok = True
        self.last_tap = (0, 0)
        self.momentum = None
        self.scroll_remainder = [0., 0.]
        self.last_velocity = (0., 0.)
        self.pending_secondary = None
        self.suppressed = False
        self.pending_velocity = None

    def gesture(self, test, phase, value=0., x=0., momentum=False):
        self.emit(dict(op='gesture', test=test, phase=phase, value=value, x=x, momentum=int(momentum)))

    def button(self, down=False, right=False, count=1):
        self.emit(dict(op='button', down=int(down), right=int(right), count=count))
        if not right:
            self.held = down

    def stop_momentum(self):
        if self.momentum:
            self.gesture(0, 4, momentum=True)
            self.momentum = None

    def close_mode(self, cancel=False):
        if self.held:
            self.button()
        if self.mode in ('scroll', 'pinch', 'rotate', 'desktop', 'vertical'):
            test = {'scroll': 0, 'pinch': 1, 'rotate': 2, 'desktop': 5, 'vertical': 8}[self.mode]
            dock = self.mode in ('desktop', 'vertical')
            commit = dock and not cancel and abs(self.progress) >= .45
            self.gesture(test, 8 if cancel or (dock and not commit) else 4,
                         math.copysign(1., self.progress) if commit else self.progress if dock else 0.)
        self.mode = None

    def cancel(self):
        self.close_mode(True)
        self.stop_momentum()
        self.points = {}
        self.tap_ok = False
        self.last_tap = (0, 0)
        self.last_velocity = (0., 0.)
        self.pending_secondary = None
        self.pending_velocity = None
        self.suppressed = False

    def tick(self, now):
        if self.pending_secondary is not None and now-self.pending_secondary >= .32:
            self.button(right=True)
            self.pending_secondary = None
        if not self.momentum:
            return
        vx, vy, last = self.momentum
        dt = min(.05, now - last)
        if dt < .012:
            return
        vx *= math.exp(-dt * 8)
        vy *= math.exp(-dt * 8)
        self.scroll(vx * dt, vy * dt, True)
        self.momentum = (vx, vy, now)
        if math.hypot(vx, vy) < 2:
            self.stop_momentum()

    def scroll(self, dx, dy, momentum=False):
        self.scroll_remainder[0] += dx
        self.scroll_remainder[1] += dy
        x, y = (math.trunc(v) for v in self.scroll_remainder)
        self.scroll_remainder[0] -= x
        self.scroll_remainder[1] -= y
        self.gesture(0, 2, y, x, momentum)

    @staticmethod
    def geometry(points):
        values = list(points.values())
        x = sum(p[0] for p in values) / len(values)
        y = sum(p[1] for p in values) / len(values)
        if len(values) != 2:
            return x, y, 0., 0.
        a, b = values
        return x, y, math.hypot(b[0]-a[0], b[1]-a[1]), math.atan2(b[1]-a[1], b[0]-a[0])

    def sample(self, points, timestamp):
        ids = set(points)
        old_ids = set(self.points)
        if ids != old_ids:
            previous_mode = self.mode
            elapsed = max(0., timestamp-getattr(self, 'last_time', timestamp))
            velocity = tuple(v*math.exp(-elapsed*8) for v in self.last_velocity)
            if old_ids and ids and ids < old_ids and (self.mode or self.suppressed):
                if self.mode == 'scroll':
                    self.pending_velocity = (*velocity, timestamp)
                self.close_mode()
                self.stop_momentum()
                self.tap_ok = False
                self.suppressed = True
                self.points, self.last_time = points, timestamp
                return
            if old_ids:
                self.close_mode(cancel=bool(ids))
            self.stop_momentum()
            if not ids:
                if self.tap_ok and timestamp - self.started < .25 and self.travel < 2 and self.peak in (1, 2):
                    count = 2 if self.last_tap[1] == self.peak and timestamp-self.last_tap[0] < .32 else 1
                    if self.peak == 2 and count == 2:
                        self.pending_secondary = None
                        self.gesture(3, 0)
                    elif self.peak == 2:
                        self.pending_secondary = self.clock()
                    else:
                        self.button(True, count=count)
                        self.button(count=count)
                    self.last_tap = (timestamp, self.peak) if count == 1 else (0, 0)
                if self.pending_velocity:
                    vx, vy, ended = self.pending_velocity
                    decay = math.exp(-max(0., timestamp-ended)*8)
                    velocity = (vx*decay, vy*decay)
                if (previous_mode == 'scroll' or self.pending_velocity) and math.hypot(*velocity) > 20:
                    self.gesture(0, 1, momentum=True)
                    self.momentum = (*velocity, self.clock())
                self.pending_velocity = None
                self.suppressed = False
            elif not old_ids:
                self.emit(dict(op='anchor'))
                self.started, self.peak, self.travel = timestamp, len(ids), 0.
                self.tap_ok = True
                self.scroll_remainder = [0., 0.]
                self.last_velocity = (0., 0.)
            else:
                self.suppressed = False
                self.pending_velocity = None
                # Sequential finger landings are allowed only inside the tap slop window.
                self.tap_ok = self.tap_ok and self.travel < 2 and (
                    (timestamp-self.started < .12 and ids > old_ids) or
                    (timestamp-self.started < .25 and ids < old_ids))
                self.peak = max(self.peak, len(ids))
            self.points = points
            self.last_time = timestamp
            if ids:
                self.origin = self.geometry(points)
                self.previous = self.origin
                self.progress = 0.
                if (len(ids) == 1 and self.config['drag']) or (len(ids) == 3 and self.config['threeDrag']):
                    self.pending_secondary = None
                    self.mode = 'drag'
                    self.tap_ok = False
                    self.button(True)
            return
        if not ids:
            return
        if self.suppressed:
            self.points, self.last_time = points, timestamp
            return
        current = self.geometry(points)
        x, y, distance, angle = current
        dx, dy = x-self.previous[0], y-self.previous[1]
        total_x, total_y = x-self.origin[0], y-self.origin[1]
        self.travel = max(self.travel, *(math.hypot(points[k][0]-self.points[k][0], points[k][1]-self.points[k][1]) for k in ids), math.hypot(total_x, total_y))
        dt = max(.001, timestamp-self.last_time)
        if self.travel >= 2:
            self.tap_ok = False
            self.pending_secondary = None
        if len(ids) == 1 or self.mode == 'drag':
            speed = math.hypot(dx, dy)/dt
            gain = self.config['gain'] * (1 + min(speed/200, 2) if self.config['acceleration'] else 1)
            # Absolute CoreGraphics locations avoid applying system pointer acceleration again.
            self.emit(dict(op='move', x=max(-1000., min(1000., dx*gain)), y=max(-1000., min(1000., dy*gain))))
        elif len(ids) == 2:
            angle_total = math.atan2(math.sin(angle-self.origin[3]), math.cos(angle-self.origin[3]))
            if self.mode is None:
                # Compare per-finger travel in mm: translation, radial change, and rotation arc.
                translation = math.hypot(total_x, total_y)
                separation = abs(distance-self.origin[2]) / 2
                rotation = abs(angle_total) * (distance+self.origin[2]) / 4
                if translation > max(separation, rotation) and translation > .5:
                    self.mode = 'scroll'
                elif separation >= max(translation, rotation) and separation > 1:
                    self.mode = 'pinch'
                elif rotation > max(translation, separation) and abs(angle_total) > .12:
                    self.mode = 'rotate'
                if self.mode:
                    self.gesture({'scroll': 0, 'pinch': 1, 'rotate': 2}[self.mode], 1)
                    self.tap_ok = False
                    self.pending_secondary = None
            if self.mode == 'scroll':
                gain = self.config['scroll'] * (1 if self.config['natural'] else -1)
                sx, sy = dx*gain, dy*gain
                self.scroll(sx, sy)
                self.last_velocity = (max(-2000., min(2000., sx/dt)), max(-2000., min(2000., sy/dt)))
            elif self.mode == 'pinch':
                delta = (distance-self.previous[2])/max(5., self.previous[2]) * self.config['pinch']
                self.gesture(1, 2, max(-1., min(1., delta)))
            elif self.mode == 'rotate':
                delta = math.degrees(math.atan2(math.sin(angle-self.previous[3]), math.cos(angle-self.previous[3])))
                self.gesture(2, 2, delta*self.config['rotation'])
        elif len(ids) in (3, 4) and not (len(ids) == 3 and self.config['threeDrag']):
            if self.mode is None and max(abs(total_x), abs(total_y)) > 3:
                if abs(total_x) >= abs(total_y):
                    self.mode = 'desktop'
                elif self.config['vertical']:
                    self.mode = 'vertical'
                if self.mode:
                    self.gesture(5 if self.mode == 'desktop' else 8, 1)
                    self.tap_ok = False
            if self.mode in ('desktop', 'vertical'):
                self.progress = max(-1., min(1., (-total_x if self.mode == 'desktop' else -total_y)/40))
                self.gesture(5 if self.mode == 'desktop' else 8, 2, self.progress)
        self.previous, self.points, self.last_time = current, points, timestamp


def credential_matches(value, expected):
    return (isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value) is not None
            and isinstance(expected, str) and expected.isascii() and hmac.compare_digest(value, expected))


class Session:
    def __init__(self, hello, secret, emit, now=None):
        if not isinstance(hello, dict) or hello.get('type') != 'hello' or type(hello.get('version')) is not int or hello.get('version') != 1:
            raise ValueError('Invalid handshake')
        token = hello.get('secret')
        if not credential_matches(token, secret):
            raise ValueError('Authentication rejected')
        self.start_phone = number(hello.get('t'), 0, 1e15) / 1000
        self.start_local = time.monotonic() if now is None else now
        self.last_phone, self.seq = self.start_phone, -1
        self.recognizer = Recognizer(hello.get('settings', {}), emit)

    def frame(self, packet, now=None):
        if not isinstance(packet, dict) or packet.get('type') not in ('sample', 'cancel', 'heartbeat'):
            raise ValueError('Invalid frame')
        seq = number(packet.get('seq'), 0, 2**53)
        timestamp = number(packet.get('t'), 0, 1e15) / 1000
        local = time.monotonic() if now is None else now
        age = local-self.start_local-(timestamp-self.start_phone)
        if type(seq) is not int or seq != self.seq+1 or timestamp < self.last_phone or not -.25 <= age <= .5:
            raise ValueError('Stale or unordered frame')
        self.seq = seq
        if packet['type'] == 'heartbeat':
            return
        self.last_phone = timestamp
        if packet['type'] == 'cancel':
            self.recognizer.cancel()
            return
        contacts = packet.get('contacts')
        if not isinstance(contacts, list) or len(contacts) > 5:
            raise ValueError('Invalid contacts')
        scale = packet.get('surface', [1, 1])
        if not isinstance(scale, list) or len(scale) != 2:
            raise ValueError('Invalid surface scale')
        width = self.recognizer.config['width'] * number(scale[0], .25, 8)
        height = self.recognizer.config['height'] * number(scale[1], .25, 8)
        points = {}
        for contact in contacts:
            if not isinstance(contact, dict):
                raise ValueError('Invalid contact')
            identity = number(contact.get('id'), 0, 31)
            if type(identity) is not int or identity in points:
                raise ValueError('Duplicate contact ID')
            points[identity] = (number(contact.get('x'), 0, 1)*width,
                                number(contact.get('y'), 0, 1)*height)
        self.recognizer.sample(dict(sorted(points.items())), timestamp)


class Bridge:
    def __init__(self, dry):
        self.directory = tempfile.TemporaryDirectory(prefix='pixel-trackpad-bridge-')
        self.stdin = self.stdout = self.launcher = None
        self.failed = False
        guards = []
        try:
            state = Path(self.directory.name)
            for name in ('stdin', 'stdout'):
                os.mkfifo(state/name, 0o600)
            # Temporary peers let LaunchServices open both pipes without blocking.
            guards.append(os.open(state/'stdin', os.O_RDONLY | os.O_NONBLOCK))
            self.stdin = os.fdopen(os.open(state/'stdin', os.O_WRONLY | os.O_NONBLOCK), 'wb', buffering=0)
            self.stdout = os.fdopen(os.open(state/'stdout', os.O_RDONLY | os.O_NONBLOCK), 'rb', buffering=0)
            guards.append(os.open(state/'stdout', os.O_WRONLY | os.O_NONBLOCK))
            with open(state/'launcher.stderr', 'wb') as errors:
                self.launcher = subprocess.Popen([
                    '/usr/bin/open', '-n', '-W', '-g', '--stdin', str(state/'stdin'),
                    '--stdout', str(state/'stdout'), '--stderr', str(state/'helper.stderr'),
                    str(BINARY.parents[2]), '--args', '--bridge-check' if dry else '--bridge'],
                    stdout=subprocess.DEVNULL, stderr=errors)
            self._exchange(dict(op='ping'), 5)
        except BaseException:
            for fd in guards:
                os.close(fd)
            guards.clear()
            try:
                self.close()
            except (OSError, subprocess.SubprocessError):
                pass
            raise
        finally:
            for fd in guards:
                os.close(fd)

    def _exchange(self, event, timeout):
        data = (json.dumps(event, allow_nan=False)+'\n').encode()
        if len(data) > 2048:
            raise ValueError('Native bridge command too large')
        deadline = time.monotonic()+timeout
        try:
            # Each bounded command fits PIPE_BUF, so a nonblocking write is atomic.
            if not select.select([], [self.stdin], [], max(0, deadline-time.monotonic()))[1]:
                raise OSError('Native bridge write timeout')
            if self.stdin.write(data) != len(data):
                raise OSError('Native bridge short write')
            reply = b''
            while len(reply) < 3:
                if not select.select([self.stdout], [], [], max(0, deadline-time.monotonic()))[0]:
                    raise OSError('Native bridge acknowledgement timeout')
                part = self.stdout.read(3-len(reply))
                if not part:
                    raise OSError('Native bridge stopped')
                reply += part
            if reply != b'ok\n':
                raise OSError('Native bridge rejected command')
        except OSError:
            self.failed = True
            raise

    def emit(self, event):
        self._exchange(event, 1)

    def close(self):
        if self.stdin is not None and self.stdin.closed:
            return
        try:
            if self.stdin is not None:
                self.stdin.close()  # EOF triggers native cancellation and held-button release.
            if self.stdout is not None:
                deadline = time.monotonic()+3
                while True:
                    # A prior read can consume FIFO readiness even though its writer is gone.
                    chunk = self.stdout.read(2048)
                    if chunk == b'':
                        break
                    if chunk is not None:
                        if time.monotonic() >= deadline:
                            raise OSError('Native bridge EOF timeout')
                        continue
                    if not select.select([self.stdout], [], [], max(0, deadline-time.monotonic()))[0]:
                        raise OSError('Native bridge EOF timeout')
            # open is a launcher, not the native child. Its code is never a helper exit code.
            if self.launcher is not None and self.launcher.wait(timeout=3):
                raise OSError('Native bridge launcher failed')
            if self.failed:
                raise OSError('Native bridge stream failed')
        finally:
            if self.stdout is not None:
                self.stdout.close()
            if self.launcher is not None and self.launcher.poll() is None:
                self.launcher.terminate()
                try:
                    self.launcher.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    self.launcher.kill()
                    self.launcher.wait(timeout=3)
            self.directory.cleanup()


def decode(line):
    def duplicate(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON field')
            result[key] = value
        return result
    try:
        result = json.loads(line, object_pairs_hook=duplicate,
                            parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Non-finite JSON')))
        if not isinstance(result, dict):
            raise ValueError('Frame must be a JSON object')
        return result
    except RecursionError as error:
        raise ValueError('JSON nesting too deep') from error


class StopRequested(ConnectionAbortedError):
    """An explicit receiver control, distinct from a transport failure."""


def serve_client(connection, secret, emit, poll=lambda: None, pair=None, ready=lambda: None, admit=lambda hello: None):
    """Bounded newline input; timeouts cancel held state even with partial frames."""
    if connection.family in (socket.AF_INET, socket.AF_INET6):
        connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    connection.settimeout(.016)
    buffer = b''
    session = None
    stop_supported = welcomed = False
    received = time.monotonic()
    pinged = received
    try:
        while True:
            poll()
            now = time.monotonic()
            if now-received > 1:
                raise ValueError('Phone timeout')
            if session:
                session.recognizer.tick(now)
                if now-pinged > .25:
                    emit(dict(op='ping'))
                    pinged = now
            try:
                chunk = connection.recv(4096)
            except socket.timeout:
                continue
            if not chunk:
                return
            buffer += chunk
            if len(buffer) > LIMIT:
                raise ValueError('Frame too large')
            while b'\n' in buffer:
                poll()
                line, buffer = buffer.split(b'\n', 1)
                packet = decode(line)
                if session is None:
                    if pair is not None and packet.get('type') == 'pair':
                        pair(connection, packet)
                        return
                    session = Session(packet, secret, emit)
                    stop_supported = packet.get('recovery') is True
                    admit(packet)
                    response = b'{"type":"ready","stop":true,"surfaceScale":true}\n' if stop_supported else b'{"type":"ready"}\n'
                    connection.sendall(response)
                    welcomed = True
                    ready()
                else:
                    session.frame(packet)
                    connection.sendall(b'K')
                received = time.monotonic()
    except StopRequested:
        # Only authenticated, opted-in clients receive this byte. Old clients still see EOF.
        if session and stop_supported:
            try:
                connection.sendall(b'S' if welcomed else b'{"type":"stopped"}\n')
            except OSError:
                pass
        raise
    finally:
        if session:
            session.recognizer.cancel()


def context(state=STATE):
    result = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    result.minimum_version = ssl.TLSVersion.TLSv1_2
    result.load_cert_chain(state/'cert.pem', state/'key.pem')
    return result


def provision(host, port):
    address = ipaddress.ip_address(host)
    if not address.is_private or address.is_unspecified or address.is_multicast:
        raise ValueError('Use an explicit private LAN address')
    STATE.mkdir(mode=0o700, exist_ok=True)
    os.chmod(STATE, 0o700)
    if any((STATE/name).exists() for name in ('pairing.json', 'cert.pem', 'key.pem')):
        raise ValueError('Pairing already exists; retain it or move .local aside explicitly')
    make_certificate(STATE)
    der = ssl.PEM_cert_to_DER_cert((STATE/'cert.pem').read_text())
    pairing = dict(host=host, port=port, fingerprint=hashlib.sha256(der).hexdigest(), secret=secrets.token_hex(32))
    with (STATE/'pairing.json').open('x') as output:
        json.dump(pairing, output)
    print('Private pairing file created at .local/pairing.json. Contents not logged.')


def make_certificate(state, days=365):
    subprocess.run(['/usr/bin/openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-sha256', '-nodes',
                    '-keyout', str(state/'key.pem'), '-out', str(state/'cert.pem'), '-days', str(days),
                    '-subj', '/CN=Pixel Trackpad Local'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    os.chmod(state/'key.pem', 0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['init', 'run'])
    parser.add_argument('--host', help='Explicit private LAN IP, required for init')
    parser.add_argument('--port', type=int, default=45873)
    parser.add_argument('--dry-run', action='store_true', help='Construct native events without posting')
    args = parser.parse_args()
    os.umask(0o077)
    if args.command == 'init':
        if not args.host or not 1024 <= args.port <= 65535:
            parser.error('init requires --host LAN_IP and port 1024..65535')
        provision(args.host, args.port)
        return
    pairing = json.loads((STATE/'pairing.json').read_text())
    address = ipaddress.ip_address(pairing['host'])
    if not address.is_private or address.is_unspecified or address.is_multicast:
        raise ValueError('Refusing non-private bind')
    tls = context()
    with socket.socket(socket.AF_INET6 if address.version == 6 else socket.AF_INET) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind((pairing['host'], pairing['port']))
        listener.listen(1)
        print('Receiver running in '+('DRY RUN' if args.dry_run else 'LIVE INPUT')+' mode. Ctrl-C stops and releases input.', flush=True)
        try:
            while True:
                raw, _ = listener.accept()
                bridge = None
                try:
                    raw.settimeout(3)
                    with tls.wrap_socket(raw, server_side=True) as connection:
                        # Bridge starts only after TLS. Pairing validation precedes all input commands.
                        def emit(event):
                            nonlocal bridge
                            if bridge is None:
                                bridge = Bridge(args.dry_run)
                            bridge.emit(event)
                        serve_client(connection, pairing['secret'], emit)
                except (ValueError, OSError):
                    print('Session closed or rejected; closing input helper.', flush=True)
                finally:
                    raw.close()
                    if bridge:
                        try:
                            bridge.close()
                        except (OSError, subprocess.SubprocessError):
                            print('Native bridge cleanup failed; session ended.', flush=True)
        except KeyboardInterrupt:
            print('Stopped.', flush=True)


if __name__ == '__main__':
    main()
