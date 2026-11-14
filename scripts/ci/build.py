"""云端无秘密编译；稳定证书只由另一台 runner 的 sign.py 使用。"""
import argparse
import json
import os
import shutil
import subprocess

from apk_verify import build_tools, certificate, inspect_apk, require_unsigned
from check import check
from ci_common import (ROOT, cloud_only, expected_identity, git, no_signing_secrets,
                       properties, require, run_tool, sha256, write_checksums, write_json)


def build(mode):
    cloud_only()
    no_signing_secrets()
    check(require_android=True, release_snapshot=mode != 'debug')
    lock = properties(ROOT / '.ci/toolchain.properties')
    version = properties(ROOT / 'version.properties')
    build_sha = git('rev-parse', 'HEAD')
    is_pr = os.environ['GITHUB_EVENT_NAME'] == 'pull_request'
    require(not is_pr or mode == 'debug', 'PR builds only temporary Debug APKs')
    if mode != 'debug':
        require(os.environ.get('CANDIDATE_OPERATION') == 'prepare-candidate', 'Use candidate preparation for optimized builds')
    variant = mode.title()
    tasks = [f':app:testWatchAndroid{variant}UnitTest', f':app:lintWatchAndroid{variant}',
             f':app:assembleWatchAndroid{variant}']
    has_instrumentation = mode == 'diagnostic' and (ROOT / 'android/app/src/androidTest').is_dir()
    if has_instrumentation:
        tasks.append(':app:assembleWatchAndroidDiagnosticAndroidTest')
    environment = dict(os.environ, CI_UNSIGNED_APK='false' if is_pr else 'true')
    # 不在 Gradle 子进程携带 API token；checkout 也必须 persist-credentials=false。
    for key in ('GH_TOKEN', 'GITHUB_TOKEN'):
        environment.pop(key, None)
    subprocess.run(['bash', './gradlew', '--no-daemon', '--stacktrace', '--console=plain',
                    '--dependency-verification=strict', '--init-script', '../scripts/ci/enforce.gradle', *tasks],
                   cwd=ROOT / 'android', env=environment, check=True)
    require(properties(ROOT / 'version.properties') == version, 'Build modified version metadata')
    tools = build_tools(lock)
    output = ROOT / 'artifacts'
    require(not output.exists() or not any(output.iterdir()), 'Build artifact directory must be empty')
    output.mkdir(exist_ok=True)
    apks = list((ROOT / f'android/app/build/outputs/apk/watchAndroid/{mode}').glob('*.apk'))
    require(len(apks) == 1, 'Expected exactly one universal watchAndroid APK')
    apk = apks[0]
    identity = inspect_apk(apk, tools, expected_identity(mode, version))
    entry = {'file': f'watch-{mode}.apk', **identity, 'sha256': sha256(apk)}
    if is_pr:
        digest = certificate(apk, tools)
        pins = json.loads((ROOT / '.ci/signing-certificates.json').read_text(encoding='utf-8'))
        require(digest not in pins.values(), 'PR APK must not use a stable certificate')
        entry.update(signing='ephemeral-debug', certificate_sha256=digest)
    else:
        require_unsigned(apk, tools)
        entry['signing'] = 'unsigned'
    shutil.copyfile(apk, output / entry['file'])
    entries = [entry]
    if has_instrumentation:
        test_apks = list((ROOT / 'android/app/build/outputs/apk/androidTest/watchAndroid/diagnostic').glob('*.apk'))
        require(len(test_apks) == 1, 'Expected one diagnostic instrumentation APK')
        test_apk = test_apks[0]
        require_unsigned(test_apk, tools)
        test_name = 'watch-diagnostic-test.apk'
        shutil.copyfile(test_apk, output / test_name)
        entries.append({'file': test_name, 'sha256': sha256(test_apk), 'signing': 'unsigned', 'role': 'instrumentation'})
    manifest = {
        'schema_version': 1, 'source_sha': os.environ.get('SOURCE_SHA', build_sha), 'build_sha': build_sha,
        'run_id': int(os.environ['GITHUB_RUN_ID']), 'run_attempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
        'repository': os.environ['GITHUB_REPOSITORY'], 'event': os.environ['GITHUB_EVENT_NAME'],
        'ref': os.environ['GITHUB_REF'], 'mode': mode, 'version': version, 'toolchain': lock,
        'tasks': tasks, 'artifacts': entries,
        'actual_tools': {'java': run_tool(['java', '--version']),
                         'build_tools': properties(tools / 'source.properties'),
                         'runner_image': os.environ.get('ImageVersion', 'unknown')},
        'instrumentation': 'compiled' if has_instrumentation else 'not-present',
        'device_validation': 'NOT RUN',
    }
    write_json(output / 'build-manifest.json', manifest)
    write_checksums(output)
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output_stream:
        output_stream.write('has-instrumentation=' + str(has_instrumentation).lower() + '\n')
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
        summary.write(f'\nCloud tests/lint/assembly completed for `{build_sha}`. '
                      f'Signing: `{entry["signing"]}`. Device validation: **NOT RUN**.\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['debug', 'release', 'diagnostic'])
    build(parser.parse_args().mode)
