#!/usr/bin/env python3
"""Create a build manifest and verify the manifest and DEX inside the signed APK."""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID = '{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android', ANDROID[1:-1])


def manifest(profile, target):
    tree = ET.parse('phone/AndroidManifest.xml')
    root = tree.getroot()
    release = profile == 'release'
    root.set('package', 'io.github.ishowguts.pixeltrackpad' if release else 'local.pixeltrackpad')
    app = root.find('application')
    app.set(ANDROID + 'debuggable', str(not release).lower())
    app.set(ANDROID + 'label', 'Pixel Trackpad' if release else 'Pixel Trackpad Dev')
    if release:
        for item in root.findall('instrumentation'):
            root.remove(item)
    tree.write(target, encoding='utf-8', xml_declaration=True)


def verify(tool, apk, profile, code, name):
    # Read compiled XML from the artifact, never the input manifest.
    dump = subprocess.check_output([tool, 'dump', 'xmltree', apk, '--file', 'AndroidManifest.xml'], text=True)
    elements = []
    current = None
    for line in dump.splitlines():
        match = re.search(r'E: ([\w-]+)', line)
        if match:
            current = (match[1], {})
            elements.append(current)
        match = re.search(r'A: (?:http://schemas.android.com/apk/res/android:)?([\w]+)(?:\([^)]*\))?=(.*)', line)
        if match and current:
            current[1][match[1]] = match[2]
    def attrs(tag):
        return [value for element, value in elements if element == tag]
    def string(value):
        return re.match(r'"([^"]*)"', value)[1]
    def integer(value):
        return int(value == 'true') if value in ('true', 'false') else int(value.split(')', 1)[-1], 0)
    release = profile == 'release'
    root, = attrs('manifest')
    app, = attrs('application')
    assert string(root['package']) == ('io.github.ishowguts.pixeltrackpad' if release else 'local.pixeltrackpad')
    assert integer(root['versionCode']) == int(code) and string(root['versionName']) == name
    assert bool(integer(app['debuggable'])) == (not release)
    assert integer(app['allowBackup']) == 0 and integer(app['usesCleartextTraffic']) == 0
    assert string(app['label']) == ('Pixel Trackpad' if release else 'Pixel Trackpad Dev')
    assert {string(p['name']) for p in attrs('uses-permission')} == {
        'android.permission.INTERNET', 'android.permission.WAKE_LOCK', 'android.permission.CAMERA'}
    assert {string(f['name']) for f in attrs('uses-feature')} == {
        'android.hardware.camera', 'android.hardware.camera.autofocus'}
    assert all(integer(f['required']) == 0 for f in attrs('uses-feature'))
    sdk, = attrs('uses-sdk')
    assert integer(sdk['minSdkVersion']) == 26 and integer(sdk['targetSdkVersion']) == 34
    activity, = attrs('activity')
    assert string(activity['name']) == 'local.pixeltrackpad.MainActivity'
    assert {(string(d['scheme']), string(d['host']), string(d['pathPrefix'])) for d in attrs('data')} == {
        ('local.pixeltrackpad', 'pair', '/v1/'), ('local.pixeltrackpad', 'pair', '/v2/')}
    instrumentation = attrs('instrumentation')
    if release:
        assert not instrumentation
    else:
        item, = instrumentation
        assert string(item['name']) == 'local.pixeltrackpad.MotionCheck'
        assert string(item['targetPackage']) == 'local.pixeltrackpad'
    with zipfile.ZipFile(apk) as archive:
        dex = [archive.read(n) for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
    assert dex
    has_check = any(b'Llocal/pixeltrackpad/MotionCheck' in data for data in dex)
    assert has_check == (not release), 'Unexpected instrumentation class in DEX'
    print(f'PASS: {profile} APK package, version, security flags, components, permissions and DEX verified.')


if __name__ == '__main__':
    if sys.argv[1] == 'manifest':
        manifest(*sys.argv[2:])
    elif sys.argv[1] == 'verify':
        verify(*sys.argv[2:])
    else:
        raise SystemExit('Expected manifest or verify')
