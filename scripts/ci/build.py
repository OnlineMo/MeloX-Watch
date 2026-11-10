"""Compile only on GitHub; package signed APKs with provenance for local testing."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

from check import ROOT, check, properties


def cloud_only():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('CI') != 'true':
        raise SystemExit('Android compilation is cloud-only. Push code and run Cloud build.')


def run(command, **kwargs):
    return subprocess.run(command, check=True, text=True, **kwargs)


def build(mode):
    cloud_only()
    check(require_android=True, release_snapshot=mode == 'release')
    lock = properties(ROOT / '.ci/toolchain.properties')
    version = properties(ROOT / 'version.properties')  # Snapshot before Gradle executes.
    build_sha = run(['git', 'rev-parse', 'HEAD'], capture_output=True).stdout.strip()
    tasks = [f':app:testWatchAndroid{mode.title()}UnitTest', f':app:lintWatchAndroid{mode.title()}',
             f':app:assembleWatchAndroid{mode.title()}']
    key = os.environ.get('SIGNING_KEY_B64', '')
    pins = json.loads((ROOT / '.ci/signing-certificates.json').read_text(encoding='utf-8'))
    if mode == 'release' and not key:
        raise SystemExit('Release signing key is required')
    if os.environ.get('GITHUB_EVENT_NAME') != 'pull_request' and not key:
        raise SystemExit('Trusted branch builds require the stable signing certificate; no silent fallback')
    run(['bash', './gradlew', '--no-daemon', '--stacktrace', '--console=plain',
         '--init-script', '../scripts/ci/enforce.gradle', *tasks], cwd=ROOT / 'android')
    # Do not trust version files rewritten by a build script.
    if properties(ROOT / 'version.properties') != version:
        raise SystemExit('Build modified version metadata')
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    tools = sdk / 'build-tools' / lock['android.buildTools']
    output = ROOT / 'artifacts'
    output.mkdir(exist_ok=True)
    apks = sorted((ROOT / f'android/app/build/outputs/apk/watchAndroid/{mode}').glob('*.apk'))
    if not apks:
        raise SystemExit('Expected watchAndroid APK missing')
    entries = []
    with tempfile.TemporaryDirectory(prefix='melox-sign-', dir=os.environ.get('RUNNER_TEMP')) as tmp:
        keystore = Path(tmp) / 'signing.p12'
        if key:
            keystore.write_bytes(base64.b64decode(key, validate=True))
            keystore.chmod(0o600)
        for apk in apks:
            badging = run([str(tools / 'aapt'), 'dump', 'badging', str(apk)], capture_output=True).stdout
            info = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
            if not info or info.group(2) != version['versionCode'] or info.group(3) != version['versionName']:
                raise SystemExit('APK version differs from owner-controlled version.properties')
            if (mode == 'debug') != info.group(1).endswith('.dev'):
                raise SystemExit('Debug must use .dev applicationId; release must not')
            destination = output / apk.name.replace('-unsigned', '')
            if key:
                run([str(tools / 'apksigner'), 'sign', '--ks', str(keystore), '--ks-type', 'PKCS12',
                     '--ks-key-alias', os.environ['SIGNING_ALIAS'], '--ks-pass', 'env:SIGNING_PASSWORD',
                     '--key-pass', 'env:SIGNING_PASSWORD', '--out', str(destination), str(apk)])
            else:
                shutil.copyfile(apk, destination)
            verified = run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(destination)], capture_output=True).stdout
            fingerprint = re.search(r'certificate SHA-256 digest: ([0-9a-fA-F]+)', verified)
            if not fingerprint:
                raise SystemExit('APK signature verification did not return a certificate digest')
            if key and fingerprint.group(1).lower() != pins.get(mode):
                raise SystemExit('APK certificate differs from the pinned ' + mode + ' certificate')
            entries.append({'file': destination.name, 'sha256': hashlib.sha256(destination.read_bytes()).hexdigest(),
                            'package': info.group(1), 'certificate_sha256': fingerprint.group(1),
                            'signing': 'stable' if key else 'ephemeral-debug'})
    manifest = {'source_sha': os.environ.get('SOURCE_SHA') or build_sha, 'build_sha': build_sha,
                'run_id': os.environ['GITHUB_RUN_ID'], 'run_attempt': os.environ['GITHUB_RUN_ATTEMPT'],
                'repository': os.environ['GITHUB_REPOSITORY'], 'mode': mode, 'version': version,
                'toolchain': lock, 'tasks': tasks, 'artifacts': entries,
                'device_validation': 'NOT RUN; install these cloud artifacts on the target watch'}
    (output / 'build-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    files = [output / entry['file'] for entry in entries] + [output / 'build-manifest.json']
    (output / 'SHA256SUMS').write_text(''.join(hashlib.sha256(file.read_bytes()).hexdigest() + '  ' + file.name + '\n' for file in files), encoding='utf-8')
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
        summary.write(f'\n## Cloud APK\nBuild SHA: `{build_sha}`\n\nGradle tests, lint and assembly passed. Signatures and checksums verified. Device validation has NOT run.\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['debug', 'release'])
    build(parser.parse_args().mode)
