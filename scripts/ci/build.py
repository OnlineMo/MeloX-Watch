"""云端无秘密编译；稳定证书只由另一台 runner 的 sign.py 使用。"""
import argparse
import json
import os
import re
import shutil
import time

from apk_verify import build_tools, certificate, inspect_apk, require_unsigned
from build_process import run_gradle, TOTAL_BUILD_SECONDS
from check import check
from ci_common import (ROOT, cloud_only, expected_identity, git, no_signing_secrets,
                       properties, require, run_tool, sha256, write_checksums, write_json)


DEPENDENCY_INPUTS = ('gradle/verification-metadata.xml', 'app/gradle.lockfile')
DEPENDENCY_REPORT_FILES = (*DEPENDENCY_INPUTS, 'gradle.lockfile')
LOCK_COLLECTION_TASK = ':ciResolveAndLockAll'
DEPENDENCY_DIAGNOSTIC_PHASES = ('verification-collection', 'normal-lock-collection',
                               'strict-debug', 'strict-release', 'strict-diagnostic')
ROOM_DATABASE_SOURCE = 'app/src/main/kotlin/io/github/onlinemo/meloxwatch/data/database/MeloXDatabase.kt'
ROOM_SCHEMA_DIRECTORY = 'app/schemas/io.github.onlinemo.meloxwatch.data.database.MeloXDatabase'


def required_build_inputs():
    source = ROOT / 'android' / ROOM_DATABASE_SOURCE
    schemas = ()
    if source.is_file():
        # 初始与当前 schema 均须由云端生成并审阅，升级不能落入普通构建后才发现漂移。
        versions = re.findall(r'@Database\s*\([\s\S]*?\bversion\s*=\s*([0-9]+)\s*[,)]',
                              source.read_text(encoding='utf-8'))
        require(len(versions) == 1 and int(versions[0]) > 0,
                'Room database must declare one positive literal schema version')
        schemas = tuple(ROOM_SCHEMA_DIRECTORY + f'/{version}.json'
                        for version in dict.fromkeys((1, int(versions[0]))))
    return (*DEPENDENCY_INPUTS, *schemas)


def room_schema_hashes():
    android = (ROOT / 'android').resolve()
    directory = android / ROOM_SCHEMA_DIRECTORY
    files = {}
    for source in sorted(directory.glob('*.json')):
        require(source.is_file() and not source.is_symlink() and source.resolve().is_relative_to(android),
                'Room schema must be a regular file within the Android project')
        require(source.stem.isdecimal() and int(source.stem) > 0, 'Room schema version must be positive')
        files[source.relative_to(android).as_posix()] = sha256(source)
    return files


def gradle_tasks(mode):
    variant = mode.title()
    tasks = [f':app:testWatchAndroid{variant}UnitTest', f':app:lintWatchAndroid{variant}',
             f':app:assembleWatchAndroid{variant}']
    if mode == 'diagnostic' and (ROOT / 'android/app/src/androidTest').is_dir():
        tasks.append(':app:assembleWatchAndroidDiagnosticAndroidTest')
    return tasks


def gradle_environment(unsigned, diagnostic_phase=None):
    environment = dict(os.environ, CI_UNSIGNED_APK=str(unsigned).lower())
    # 编译不需要仓库/产物 API 或 OIDC token；它们不能进入应用构建脚本。
    for key in ('GH_TOKEN', 'GITHUB_TOKEN', 'ACTIONS_RUNTIME_TOKEN', 'ACTIONS_ID_TOKEN_REQUEST_TOKEN'):
        environment.pop(key, None)
    # 普通构建不安装图观察器；外层runner继承的诊断标记不能改变此边界。
    environment.pop('CI_DEPENDENCY_DIAGNOSTIC_PHASE', None)
    if diagnostic_phase is not None:
        require(unsigned and os.environ.get('GITHUB_EVENT_NAME') == 'pull_request' and
                diagnostic_phase in DEPENDENCY_DIAGNOSTIC_PHASES,
                'Dependency diagnostics require an unsigned PR bootstrap phase')
        environment['CI_DEPENDENCY_DIAGNOSTIC_PHASE'] = diagnostic_phase
    return environment


def gradle_command(tasks):
    return ['bash', './gradlew', '--no-daemon', '--stacktrace', '--console=plain',
            '--dependency-verification=strict', '--init-script', '../scripts/ci/enforce.gradle', *tasks]


def missing_dependency_inputs():
    missing = []
    for relative in required_build_inputs():
        path = ROOT / 'android' / relative
        if not path.is_file() or path.is_symlink():
            missing.append(relative)
            continue
        try:
            git('cat-file', '-e', 'HEAD:android/' + relative)
        except ValueError:
            missing.append(relative)
    return missing


def generated_input_hashes():
    android = (ROOT / 'android').resolve()
    files = {}
    for relative in DEPENDENCY_REPORT_FILES:
        source = android / relative
        if source.is_file() and not source.is_symlink() and source.resolve().is_relative_to(android):
            files[relative] = sha256(source)
    files.update(room_schema_hashes())
    return files


def bootstrap_dependencies(build_sha, missing, total_deadline=None):
    """PR-only资料采集；无论生成成功与否，都不能导出可验收APK。"""
    android = ROOT / 'android'
    total_deadline = time.monotonic() + TOTAL_BUILD_SECONDS if total_deadline is None else total_deadline
    tasks = [task for mode in ('debug', 'release', 'diagnostic') for task in gradle_tasks(mode)]
    phases = [
        {'name': 'verification-collection', 'command': gradle_command(tasks) +
         ['--write-verification-metadata', 'sha256', '--write-locks']},
        # 真实任务先消费正常图；末尾补齐配置，避免采集任务提前固定消费者的图。
        {'name': 'normal-lock-collection', 'command': gradle_command([*tasks, LOCK_COLLECTION_TASK]) + ['--write-locks']},
        *({'name': 'strict-' + mode, 'command': gradle_command(gradle_tasks(mode))}
          for mode in ('debug', 'release', 'diagnostic')),
    ]
    completed = False
    strict_input_hashes = None
    try:
        for phase in phases:
            phase['status'] = 'started'
            # verification writer 会额外解析全配置；最终锁须来自独立的正常任务解析。
            run_gradle(phase['command'], cwd=android, phase=phase['name'], build_sha=build_sha, total_deadline=total_deadline,
                       env=gradle_environment(unsigned=True, diagnostic_phase=phase['name']))
            if phase['name'] == 'normal-lock-collection':
                strict_input_hashes = generated_input_hashes()
                require(all(name in strict_input_hashes for name in required_build_inputs()),
                        'Dependency collection did not produce all required inputs')
            elif phase['name'].startswith('strict-'):
                require(generated_input_hashes() == strict_input_hashes,
                        'Independent strict validation changed dependency input bytes')
            phase['status'] = 'completed'
        completed = True
    finally:
        # Gradle失败也可能写出部分资料；仅作为失败报告留存，不能自动晋升为构建输入。
        report = android / 'app/build/reports/dependency-bootstrap'
        report.mkdir(parents=True, exist_ok=True)
        files = {}
        for relative in (*DEPENDENCY_REPORT_FILES, *room_schema_hashes()):
            source = android / relative
            if source.is_file() and not source.is_symlink() and source.resolve().is_relative_to(android.resolve()):
                target = report / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
                files[relative] = sha256(target)
        report_matches_strict_inputs = files == strict_input_hashes if strict_input_hashes is not None else None
        if completed and not report_matches_strict_inputs:
            completed = False
        write_json(report / 'bootstrap-manifest.json', {
            'schema_version': 1, 'source_sha': os.environ.get('SOURCE_SHA', build_sha), 'build_sha': build_sha,
            'run_id': int(os.environ['GITHUB_RUN_ID']), 'run_attempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
            'event': os.environ['GITHUB_EVENT_NAME'], 'ref': os.environ['GITHUB_REF'], 'tasks': tasks,
            'phases': phases, 'strict_input_hashes': strict_input_hashes,
            'report_matches_strict_inputs': report_matches_strict_inputs,
            'status': 'generated-unreviewed' if completed else 'failed-partial',
            'missing_inputs_at_start': missing,
            'missing_outputs': [name for name in required_build_inputs() if name not in files],
            'files': files, 'accepted': False, 'apk_exported': False,
        })
    require(completed, 'Dependency collection did not preserve the strict input bytes in reports')
    require(False, 'Dependency bootstrap is not an accepted build. Review and commit the generated '
            'verification metadata, lockfile and generated Room schemas from dependency-bootstrap reports, '
            'then rebuild strictly.')


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
    tasks = gradle_tasks(mode)
    total_deadline = time.monotonic() + TOTAL_BUILD_SECONDS
    has_instrumentation = mode == 'diagnostic' and (ROOT / 'android/app/src/androidTest').is_dir()
    missing = missing_dependency_inputs()
    if missing:
        require(is_pr and mode == 'debug',
                'Committed strict dependency inputs are missing: ' + ', '.join(missing) + '. '
                'Only a temporary Debug PR may bootstrap dependency reports; trusted builds cannot.')
        bootstrap_dependencies(build_sha, missing, total_deadline)
    schemas_before = room_schema_hashes()
    environment = gradle_environment(unsigned=not is_pr)
    run_gradle(gradle_command(tasks), cwd=ROOT / 'android', env=environment,
               phase=mode + '-build', build_sha=build_sha, total_deadline=total_deadline)
    require(properties(ROOT / 'version.properties') == version, 'Build modified version metadata')
    require(room_schema_hashes() == schemas_before,
            'Build changed the reviewed Room schema. Collect and review schema changes in a PR bootstrap.')
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
