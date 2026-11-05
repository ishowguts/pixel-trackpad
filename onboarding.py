"""App receiver. JSON over inherited pipes carries local UI commands and status."""
import argparse
import errno
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import queue
import re
import secrets
import select
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time

import receiver

INVITATION_SECONDS = 120
QR_NETWORKS = tuple(ipaddress.ip_network(subnet) for subnet in
                    ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', 'fc00::/7'))
LAN_NETWORKS = QR_NETWORKS + (ipaddress.ip_network('169.254.0.0/16'), ipaddress.ip_network('fe80::/10'))


def notify(event, **fields):
    print(json.dumps(dict(event=event, **fields)), flush=True)


def run(state, bundle_binary, loopback=False):
    os.umask(0o077)
    state.mkdir(mode=0o700, parents=True, exist_ok=True)
    if state.stat().st_uid != os.getuid() or state.stat().st_mode & 0o077:
        raise ValueError('Receiver storage must be private')
    # one app process owns one credential; use per-device records for multiple phones.
    with (state/'lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        serve(state, bundle_binary, loopback)


def serve(state, bundle_binary, loopback):
    receiver.BINARY = bundle_binary
    if not (state/'cert.pem').exists():
        if (state/'phone').exists():
            raise ValueError('Receiver identity missing')
        with tempfile.TemporaryDirectory(dir=state) as staging:
            receiver.make_certificate(Path(staging), days=3650)
            for name in ('key.pem', 'cert.pem'):
                os.replace(Path(staging)/name, state/name)
    tls = receiver.context(state)
    der = ssl.PEM_cert_to_DER_cert((state/'cert.pem').read_text())
    pin = hashlib.sha256(der).hexdigest()
    service = 'Pixel Trackpad '+pin[:24]
    credential = state/'phone'
    secret = credential.read_text() if credential.exists() else None
    if secret is not None and not receiver.credential_matches(secret, secret):
        raise ValueError('Invalid stored credential')
    commands = queue.Queue()
    invitation = pending = None
    expires = 0
    approved = False
    live = False

    def read_commands():
        try:
            for line in sys.stdin:
                commands.put(json.loads(line))
        finally:
            commands.put(dict(command='quit'))

    def poll(active=False):
        nonlocal invitation, expires, pending, approved, secret, live
        while True:
            if invitation and time.monotonic() >= expires:
                invitation = None
                notify('idle', paired=secret is not None)
            try:
                message = commands.get_nowait()
            except queue.Empty:
                return
            command = message.get('command')
            if command == 'quit':
                raise SystemExit(0)
            if command == 'pair' and secret is None and pending is None and invitation is None:
                host = message.get('host')
                if 'host' in message:
                    if not isinstance(host, str):
                        continue
                    try:
                        address = ipaddress.ip_address(host)
                    except ValueError:
                        continue
                    if not ((loopback and address == ipaddress.ip_address('127.0.0.1')) or
                            ('%' not in host and any(address in subnet for subnet in QR_NETWORKS))):
                        continue
                invitation, expires = secrets.token_hex(32), time.monotonic()+INVITATION_SECONDS
                url = (f'local.pixeltrackpad://pair/v2/{address}/{port}/{pin}/{invitation}' if 'host' in message
                       else f'local.pixeltrackpad://pair/v1/{pin}/{invitation}')
                notify('invitation', url=url)
            if command == 'approve' and pending and message.get('request') == pending:
                approved = True
            if command == 'forget':
                credential.unlink(missing_ok=True)
                secret = None
            if command in ('disconnect', 'forget', 'live', 'dry'):
                if command in ('live', 'dry'):
                    live = command == 'live' and not loopback
                invitation = pending = None
                notify('idle', paired=secret is not None)
                if active:
                    raise ConnectionAbortedError('Stopped on Mac')

    def pair(connection, packet):
        nonlocal invitation, pending, approved, secret
        code = packet.get('code')
        if (secret is not None or time.monotonic() >= expires
                or not receiver.credential_matches(packet.get('invite'), invitation)
                or not isinstance(code, str) or re.fullmatch(r'[0-9]{6}', code) is None):
            raise ValueError('Invitation rejected')
        invitation = None
        pending, approved = secrets.token_hex(16), False
        deadline = min(expires, time.monotonic()+30)
        try:
            connection.sendall(b'{"type":"pending"}\n')
            notify('approval', request=pending, code=code)
            while time.monotonic() < deadline:
                poll(active=True)
                if connection.pending() or select.select([connection], [], [], .05)[0]:
                    return
                if approved and time.monotonic() < deadline:
                    new_secret = secrets.token_hex(32)
                    with tempfile.NamedTemporaryFile(mode='w', dir=state, delete=False) as output:
                        path = Path(output.name)
                        try:
                            output.write(new_secret)
                            output.flush()
                            os.fsync(output.fileno())
                            os.replace(path, credential)
                        finally:
                            path.unlink(missing_ok=True)
                    secret = new_secret
                    connection.sendall((json.dumps(dict(type='paired', secret=secret))+'\n').encode())
                    return
        finally:
            pending = None
            notify('idle', paired=secret is not None)

    threading.Thread(target=read_commands, daemon=True).start()
    with socket.socket(socket.AF_INET if loopback else socket.AF_INET6) as listener:
        if not loopback:
            listener.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        port_file = state/('loopback-port' if loopback else 'port')
        saved_port = port_file.read_text().strip() if port_file.exists() else ''
        preferred = int(saved_port) if re.fullmatch(r'[0-9]{4,5}', saved_port) else 0
        host = '127.0.0.1' if loopback else '::'
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            listener.bind((host, preferred if 1024 <= preferred <= 65535 else 0))
        except OSError as error:
            if error.errno != errno.EADDRINUSE:
                raise
            listener.bind((host, 0))
        listener.listen(4)
        listener.settimeout(.15)
        port = listener.getsockname()[1]
        staging_port = port_file.with_suffix('.tmp')
        staging_port.write_text(str(port))
        staging_port.replace(port_file)
        notify('listening', service=service, port=port, paired=secret is not None)
        while True:
            poll()
            try:
                raw, remote = listener.accept()
            except socket.timeout:
                continue
            address = ipaddress.ip_address(remote[0])
            if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped:
                address = address.ipv4_mapped
            if not loopback and not any(address in subnet for subnet in LAN_NETWORKS):
                raw.close()
                continue
            bridge = None
            connected = False

            def ready():
                nonlocal connected
                connected = True
                notify('connected')

            try:
                with raw:
                    raw.settimeout(2)
                    with tls.wrap_socket(raw, server_side=True) as connection:
                        def emit(event):
                            nonlocal bridge
                            if bridge is None:
                                bridge = receiver.Bridge(not live)
                            bridge.emit(event)

                        receiver.serve_client(connection, secret, emit, lambda: poll(active=True), pair, ready)
            except (ValueError, OSError, subprocess.SubprocessError):
                pass
            finally:
                if bridge:
                    try:
                        bridge.close()
                    except (OSError, subprocess.SubprocessError):
                        pass
                if connected:
                    notify('idle', paired=secret is not None)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('state', type=Path)
    parser.add_argument('binary', type=Path)
    parser.add_argument('--loopback', action='store_true')
    args = parser.parse_args()
    try:
        run(args.state, args.binary, args.loopback)
    except (ValueError, OSError, subprocess.SubprocessError):
        notify('error')
        sys.exit(1)
