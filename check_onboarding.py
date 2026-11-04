#!/usr/bin/env python3
"""Dry-run TLS check using disposable app receiver state."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import select
import socket
import ssl
import subprocess
import tempfile
import time

from receiver import BINARY


def read(stream):
    assert select.select([stream], [], [], 10)[0], 'Receiver message timeout'
    return json.loads(stream.readline())


def check(state, bundle):
    # Exercise the packaged Python files and the same interpreter as the app.
    resources = bundle/'Contents/Resources'
    dev_path = resources/'python-path'
    python = dev_path.read_text() if dev_path.exists() else str(resources/'runtime/bin/python3.13')

    def start(short_expiry=False):
        entry = ['-c', ("import runpy,sys; sys.path.insert(0,sys.argv.pop(1)); "
                        "runpy.run_module('onboarding',run_name='__main__')"), str(resources)]
        if short_expiry:
            # Override only the interval in this disposable process.
            entry = ['-c', ('import sys,pathlib; sys.path.insert(0,sys.argv.pop(1)); import onboarding; '
                            'onboarding.INVITATION_SECONDS = 0.5; '
                            'onboarding.run(pathlib.Path(sys.argv[1]),pathlib.Path(sys.argv[2]),True)'),
                     str(resources)]
        return subprocess.Popen([python, '-I', '-B', '-u', *entry,
                                 str(state), str(bundle/'Contents/MacOS/GestureDiagnostic'), '--loopback'],
                                stdin=subprocess.PIPE, stdout=subprocess.PIPE, bufsize=0)

    def command(name, **fields):
        process.stdin.write(json.dumps(dict(command=name, **fields)).encode()+b'\n')

    def connect(wrong_pin=False):
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        tls.check_hostname = False
        tls.verify_mode = ssl.CERT_NONE
        with socket.create_connection(('127.0.0.1', listening['port']), timeout=3) as raw:
            # Duplicate the descriptor so the returned TLS socket owns its lifetime.
            peer = tls.wrap_socket(raw.dup())
        actual = hashlib.sha256(peer.getpeercert(binary_form=True)).hexdigest()
        if actual != ('0'*64 if wrong_pin else pin):
            peer.close()
            raise ValueError('Pin rejected before sending credentials')
        return peer

    def send(peer, **fields):
        peer.sendall(json.dumps(fields).encode()+b'\n')

    def hello(peer, token):
        send(peer, type='hello', version=1, secret=token, t=time.monotonic()*1000)

    def invite():
        command('pair', host='127.0.0.1')
        message = read(process.stdout)
        assert message['event'] == 'invitation'
        match = re.fullmatch(rf'local\.pixeltrackpad://pair/v2/127\.0\.0\.1/{listening["port"]}/([0-9a-f]{{64}})/([0-9a-f]{{64}})',
                             message['url'])
        assert match
        return match.groups()

    def request(peer, token):
        send(peer, type='pair', invite=token, code='123456')

    def close():
        process.stdin.close()
        try:
            assert process.wait(timeout=8) == 0
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            process.stdout.close()

    process = start()
    try:
        listening = read(process.stdout)
        assert listening['event'] == 'listening' and not listening['paired']
        initial_port = listening['port']
        command('pair', host='8.8.8.8')
        assert not select.select([process.stdout], [], [], .25)[0]
        pin, token = invite()
        assert listening['service'] == 'Pixel Trackpad '+pin[:24]
        try:
            connect(wrong_pin=True)
        except ValueError:
            pass
        else:
            raise AssertionError('Wrong pin accepted')
        with connect() as peer:
            request(peer, 'é'*64)
            assert not peer.recv(1)
        with connect() as peer, peer.makefile('rb') as replies:
            request(peer, token)
            assert json.loads(replies.readline()) == {'type': 'pending'}
            approval = read(process.stdout)
            assert approval['event'] == 'approval' and approval['code'] == '123456'
            command('approve', request='invalid')
            assert not select.select([peer], [], [], .3)[0]
            assert not (state/'phone').exists()
            command('disconnect')
            assert not replies.readline()
            # Disconnect publishes idle immediately, then pairing cleanup publishes idle.
            assert read(process.stdout)['event'] == 'idle'
            assert read(process.stdout)['event'] == 'idle'
        with connect() as peer:
            request(peer, token)
            assert not peer.recv(1)
        same_pin, token = invite()
        assert same_pin == pin
        with connect() as peer, peer.makefile('rb') as replies:
            request(peer, token)
            assert json.loads(replies.readline()) == {'type': 'pending'}
            approval = read(process.stdout)
            command('approve', request=approval['request'])
            paired = json.loads(replies.readline())
            assert paired['type'] == 'paired'
            secret = paired['secret']
            assert re.fullmatch(r'[0-9a-f]{64}', secret) and token != secret
            assert read(process.stdout) == {'event': 'idle', 'paired': True}
        with connect() as peer:
            request(peer, token)
            assert not peer.recv(1)
        with connect() as peer:
            hello(peer, 'é'*64)
            assert not peer.recv(1)
        with connect() as peer, peer.makefile('rb') as replies:
            hello(peer, secret)
            assert json.loads(replies.readline()) == {'type': 'ready'}
            assert read(process.stdout)['event'] == 'connected'
            send(peer, type='sample', seq=0, t=time.monotonic()*1000, contacts=[dict(id=0, x=.4, y=.4)])
            assert replies.read(1) == b'K'
            send(peer, type='sample', seq=1, t=time.monotonic()*1000, contacts=[dict(id=0, x=.41, y=.4)])
            assert replies.read(1) == b'K'
        assert read(process.stdout)['event'] == 'idle'
    finally:
        close()

    process = start()
    try:
        listening = read(process.stdout)
        assert listening['port'] == initial_port
        assert listening['paired'] and listening['service'] == 'Pixel Trackpad '+pin[:24]
        with connect() as peer, peer.makefile('rb') as replies:
            hello(peer, secret)
            assert json.loads(replies.readline()) == {'type': 'ready'}
            assert read(process.stdout)['event'] == 'connected'
            command('forget')
            assert not replies.readline()
            assert read(process.stdout) == {'event': 'idle', 'paired': False}
        with connect() as peer:
            hello(peer, secret)
            assert not peer.recv(1)
        assert not (state/'phone').exists()
    finally:
        close()

    with socket.socket() as occupied:
        occupied.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        occupied.bind(('127.0.0.1', initial_port))
        occupied.listen(1)
        process = start(short_expiry=True)
        listening = read(process.stdout)
        assert listening['port'] != initial_port
    try:
        assert not listening['paired']
        same_pin, expired_token = invite()
        assert same_pin == pin
        assert read(process.stdout) == {'event': 'idle', 'paired': False}
        with connect() as peer:
            request(peer, expired_token)
            assert not peer.recv(1)
    finally:
        close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--bundle', type=Path, default=BINARY.parents[2])
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='trackpad-onboarding-check-') as directory:
        check(Path(directory), args.bundle.resolve())
    print('Onboarding TLS check passed. No input posted.')
