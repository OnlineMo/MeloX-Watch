"""Cost-bounded paired APK builds; input acquisition compiles without running application tests."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

from apk_verify import build_tools, certificate, inspect_apk, inspect_test_apk, require_unsigned
from ci_common import ROOT, cloud_only, expected_identity, no_signing_secrets, properties, require, run_tool, sha256, write_checksums, write_json
from package_contract import (DEPENDENCY_FILES, ROLES, SPECS, allowed_schema_paths, artifact_layout, committed_hash, exact_data, input_hashes,
                              native_abis, project_plan, required_inputs, room_declarations, safe_path, validate_artifacts)

# These are hard admissions, not predictions that a build fits. They lower the old 2700/5400 budgets.
NORMAL_SECONDS = 1200
COLLECTION_SECONDS = 1800
CANDIDATE_SECONDS = 1800
SESSION_FILE = 'android/build/ci-package-session.json'
REQUEST_FILE = '.ci/dependency-migration-request.json'


def source_hashes(root):
    result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', 'ls-tree', '-r', '-z', 'HEAD'],
                            cwd=root, capture_output=True)
    require(result.returncode == 0, 'Package source inventory cannot be read')
    names = result.stdout.decode('utf-8').split('\0')
    result = {}
    schema_inputs = None
    for row in filter(None, names):
        metadata, name = row.split('\t', 1)
        mode, kind, expected_blob = metadata.split(' ')
        android = name.startswith('android/')
        policy = name.startswith('scripts/ci/') or name.startswith('.github/')
        fixed = name in ('version.properties', '.ci/toolchain.properties', '.ci/signing-certificates.json',
                         '.ci/upstream-lock.json')
        if not (android or policy or fixed):
            continue
        require(mode in ('100644', '100755') and kind == 'blob', 'Build inputs must be ordinary Git files')
        if android and name.removeprefix('android/') in DEPENDENCY_FILES:
            continue
        if name.startswith('android/app/schemas/'):
            if schema_inputs is None:
                schema_inputs = allowed_schema_paths(root)
            require(name.removeprefix('android/') in schema_inputs, 'Unregistered package schema input')
            continue
        require('/build/' not in name and '/.gradle/' not in name, 'Generated files cannot be committed build inputs')
        path = safe_path(root, name, file=True)
        before = path.stat()
        require(before.st_size <= 256 * 1024 * 1024, 'A committed package input exceeds its bound')
        blob = hashlib.sha1(b'blob ' + str(before.st_size).encode() + b'\0')
        data_hash = hashlib.sha256()
        count = 0
        with path.open('rb') as stream:
            while block := stream.read(64 * 1024):
                count += len(block)
                require(count <= before.st_size, 'A source input grew while hashing')
                blob.update(block)
                data_hash.update(block)
        after = safe_path(root, name, file=True).stat()
        require(count == before.st_size and (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) ==
                (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) and blob.hexdigest() == expected_blob,
                'Package input changed or differs from its exact Git bytes')
        result[name] = data_hash.hexdigest()
    require(0 < len(result) <= 4096, 'Package source input inventory is empty or too large')
    return result


def context(root, mode):
    cloud_only()
    no_signing_secrets()
    require(mode in ('debug', 'candidate'), 'Paired builds require Debug or the single compile-candidate producer')
    event = os.environ.get('GITHUB_EVENT_NAME')
    require(event != 'pull_request' or mode == 'debug', 'PRs may build temporary Debug packages only')
    if mode != 'debug':
        from ci_common import trusted_context
        trusted_context('Owner release')
        require(os.environ.get('CANDIDATE_OPERATION') == 'prepare-candidate', 'Optimized packages require candidate preparation')
    for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
        require(re.fullmatch(r'[1-9][0-9]{0,19}', os.environ.get(key, '')), 'Invalid package run identity')
    result = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, capture_output=True, text=True)
    require(result.returncode == 0 and re.fullmatch(r'[0-9a-f]{40}', result.stdout.strip()), 'Invalid package build revision')
    build = result.stdout.strip()
    source = os.environ.get('SOURCE_SHA', build)
    require(re.fullmatch(r'[0-9a-f]{40}', source), 'Invalid package source revision')
    return {'source_sha': source, 'build_sha': build, 'run_id': int(os.environ['GITHUB_RUN_ID']),
            'run_attempt': int(os.environ['GITHUB_RUN_ATTEMPT']), 'repository': os.environ['GITHUB_REPOSITORY'],
            'event': event, 'ref': os.environ['GITHUB_REF']}


def write_state(root, state):
    target = safe_path(root, SESSION_FILE)
    target.parent.mkdir(parents=True, exist_ok=True)
    safe_path(root, SESSION_FILE)
    write_json(target, state)
    digest = sha256(safe_path(root, SESSION_FILE, file=True))
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as out:
            out.write('session-sha256=' + digest + '\n')
    return digest


def start(mode, root=ROOT, legacy=None):
    started = time.monotonic()
    binding = context(root, mode)
    plan = project_plan(root, binding['build_sha'])
    declarations = room_declarations(root, binding['build_sha'])
    require(plan['application_roles'] == list(ROLES), 'Both packages must exist before a multi-package build')
    path = safe_path(root, SESSION_FILE)
    require(not path.exists(), 'Package session must start in a fresh runner')
    request = safe_path(root, REQUEST_FILE)
    collection = request.exists() or request.is_symlink()
    if collection:
        require(mode == 'debug', 'Trusted builds cannot collect unreviewed inputs')
        from package_migration import admit
        admission = admit(root, binding, plan)
    else:
        admission = None
        inputs = input_hashes(root)
        missing = [name for name in required_inputs(root) if name not in inputs]
        require(not missing, 'Strict package inputs are missing; use an explicit owner acquisition request')
        for name, digest in inputs.items():
            require(committed_hash(root, 'android/' + name, binding['build_sha']) == digest,
                    'Normal dependency/schema inputs must match their frozen commit')
    if legacy is not None:
        legacy.check(require_android=True, release_snapshot=mode != 'debug', unit_checks=False)
    limit = COLLECTION_SECONDS if collection else NORMAL_SECONDS if mode == 'debug' else CANDIDATE_SECONDS
    state = {'schema_version': 1, 'mode': mode, 'binding': binding, 'plan': plan,
             'room_declarations': declarations,
             'version': properties(Path(root) / 'version.properties'), 'toolchain': properties(Path(root) / '.ci/toolchain.properties'),
             'source_input_hashes': source_hashes(root), 'input_hashes': input_hashes(root),
             'started_monotonic_seconds': started, 'deadline_monotonic_seconds': started + limit,
             'overall_limit_seconds': limit, 'collection': admission, 'completed': [], 'status': 'active'}
    state['debug_test_evidence'] = None
    require(time.monotonic() < state['deadline_monotonic_seconds'], 'Package admission exhausted its cost budget')
    digest = write_state(root, state)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as out:
            out.write('input-collection=' + str(collection).lower() + '\n')
    return state, digest


def load_state(root, digest):
    require(type(digest) is str and re.fullmatch(r'[0-9a-f]{64}', digest), 'Package session hash is required')
    path = safe_path(root, SESSION_FILE, file=True)
    require(path.stat().st_size <= 4 * 1024 * 1024 and sha256(path) == digest, 'Package session changed')
    state = json.loads(path.read_text(encoding='utf-8'))
    require(type(state.get('schema_version')) is int and state['schema_version'] == 1 and
            state.get('status') == 'active', 'Package session is inactive')
    require(exact_data(state['binding'], context(root, state['mode'])) and
            exact_data(state['plan'], project_plan(root, state['binding']['build_sha'])),
            'Package stage uses a different source or run')
    require(exact_data(state['room_declarations'], room_declarations(root, state['binding']['build_sha'])),
            'Package Room declaration binding changed')
    require(state['source_input_hashes'] == source_hashes(root), 'Package source inputs changed between stages')
    expected_limit = COLLECTION_SECONDS if state['collection'] is not None else NORMAL_SECONDS if state['mode'] == 'debug' else CANDIDATE_SECONDS
    require(type(state['overall_limit_seconds']) is int and state['overall_limit_seconds'] == expected_limit,
            'Package cost admission is invalid')
    require(type(state['started_monotonic_seconds']) in (int, float) and
            type(state['deadline_monotonic_seconds']) in (int, float) and
            math.isfinite(state['started_monotonic_seconds']) and math.isfinite(state['deadline_monotonic_seconds']) and
            state['deadline_monotonic_seconds'] == state['started_monotonic_seconds'] + state['overall_limit_seconds'] and
            time.monotonic() < state['deadline_monotonic_seconds'], 'Package overall cost deadline expired')
    return state


def stage_order(state):
    require(state['mode'] in ('debug', 'candidate'), 'Paired optimized builds require one compile-candidate producer')
    if state['collection'] is not None:
        return ['acquire', 'strict-inputs', 'seal']
    return ['test-debug', 'lint-build', 'seal']


def run_stage(stage, digest, root=ROOT, legacy=None):
    if legacy is None:
        import build as legacy
    state = load_state(root, digest)
    order = stage_order(state)
    require(state['completed'] == order[:len(state['completed'])] and
            len(state['completed']) < len(order) and stage == order[len(state['completed'])],
            'Package stages cannot be skipped, repeated or reordered')
    before = input_hashes(root)
    require(before == state['input_hashes'], 'Package dependency/schema inputs changed between stages')
    try:
        execute_stage(root, state, stage, before, legacy)
        require(time.monotonic() < state['deadline_monotonic_seconds'], 'Package overall cost deadline expired')
    except Exception as failure:
        state['status'] = 'failed'
        state['failed_stage'] = stage
        if state['collection'] is not None:
            try:
                from package_migration import record_failure
                record_failure(root, state, stage, failure)
            except Exception:
                # Evidence preservation is secondary to the original failure/timeout.
                pass
        try:
            write_state(root, state)
        except Exception:
            pass
        raise
    state['input_hashes'] = input_hashes(root)
    state['completed'].append(stage)
    if stage == 'seal':
        state['status'] = 'generated-unreviewed' if state['collection'] is not None else 'completed'
    result = write_state(root, state)
    if state['collection'] is not None and stage == 'seal':
        raise ValueError('PACKAGE_INPUT_REPORT_ONLY_REJECTION: independently review original inputs, remove the request, then build strictly')
    return result


def execute_stage(root, state, stage, before, legacy):
    if stage == 'acquire':
        from package_migration import acquire
        acquire(root, state, legacy)
    elif stage == 'strict-inputs':
        from package_migration import strict_inputs
        strict_inputs(root, state, legacy)
    elif stage in ('test-debug', 'lint-build'):
        from package_test_evidence import begin as begin_debug, collect as collect_debug, validate_archive as validate_debug, REPORT as DEBUG_REPORT, ARCHIVE_FILE
        if stage == 'test-debug':
            begin_debug(root)
        else:
            validate_debug(safe_path(root, DEBUG_REPORT + '/' + ARCHIVE_FILE, file=True), state['binding'], state['debug_test_evidence'])
        tasks = (tasks_debug_tests(state['plan']) if stage == 'test-debug' else tasks_outputs(state))
        environment = legacy.gradle_environment(unsigned=state['binding']['event'] != 'pull_request' or state['collection'] is not None)
        for name in ('CI_DEPENDENCY_PACKAGE_REQUEST_SHA256', 'CI_DEPENDENCY_PACKAGE_VARIANTS', 'CI_PACKAGE_FRESH_ROOM_SCHEMA', 'CI_PACKAGE_DEBUG_BINDING'):
            environment.pop(name, None)
        if stage == 'test-debug':
            environment['CI_PACKAGE_DEBUG_BINDING'] = json.dumps(state['binding'], sort_keys=True, separators=(',', ':'), allow_nan=False)
        legacy.run_gradle(legacy.gradle_command(tasks), cwd=Path(root) / 'android', env=environment,
                          phase='package-' + stage, build_sha=state['binding']['build_sha'],
                          total_deadline=state['deadline_monotonic_seconds'],
                          overall_limit_seconds=state['overall_limit_seconds'])
        require(source_hashes(root) == state['source_input_hashes'], 'Package build changed its frozen source')
        if stage == 'test-debug':
            state['debug_test_evidence'] = collect_debug(root, state['binding'])
        require(input_hashes(root) == before, 'Normal package build changed strict dependency/schema bytes')
    else:
        if state['collection'] is not None:
            from package_migration import seal
            seal(root, state)
        else:
            export(root, state)


def tasks_debug_tests(plan):
    from package_contract import tasks_for
    return tasks_for('debug', plan, tests=True, outputs=False)


def tasks_outputs(state):
    from package_contract import tasks_for
    mode = state['mode']
    variants = ['release', 'diagnostic'] if mode == 'candidate' else ['debug']
    return [task for variant in variants for task in tasks_for(variant, state['plan'], tests=False)]


def export(root, state):
    require(state['binding'] == context(root, state['mode']) and state['source_input_hashes'] == source_hashes(root),
            'Package export binding changed')
    from package_test_evidence import ARCHIVE_FILE, REPORT as DEBUG_REPORT, validate_archive as validate_debug
    debug_archive = safe_path(root, DEBUG_REPORT + '/' + ARCHIVE_FILE, file=True)
    validate_debug(debug_archive, state['binding'], state['debug_test_evidence'])
    tools = build_tools(state['toolchain'])
    mode = state['mode']
    variants = ['release', 'diagnostic'] if mode == 'candidate' else [mode]
    output_root = safe_path(root, 'artifacts')
    require(not output_root.exists() or not any(output_root.iterdir()), 'Package artifact destination must be empty')
    output_root.mkdir(exist_ok=True)
    pins = json.loads(safe_path(root, '.ci/signing-certificates.json', file=True).read_text(encoding='utf-8'))
    for variant in variants:
        destination = output_root / variant if mode == 'candidate' else output_root
        destination.mkdir(exist_ok=True)
        shutil.copyfile(debug_archive, destination / ARCHIVE_FILE)
        entries = []
        for layout in artifact_layout(variant, state['plan']):
            role = layout['role']
            spec = SPECS[role]
            relative = ('android/' + spec['module'] + '/build/outputs/apk/' +
                        ('androidTest/' + spec['flavor'] + '/diagnostic' if layout['kind'] == 'instrumentation'
                         else spec['flavor'] + '/' + variant))
            directory = safe_path(root, relative)
            apks = list(directory.glob('*.apk'))
            require(len(apks) == 1, 'Expected one universal APK for each declared role and kind')
            apk = safe_path(root, apks[0].relative_to(root).as_posix(), file=True)
            expected = expected_identity(variant, state['version'], role)
            if layout['kind'] == 'instrumentation':
                actual = inspect_test_apk(apk, tools, expected['package'])
                actual['target_package'] = expected['package']
            else:
                actual = inspect_apk(apk, tools, expected)
            entry = {**layout, **actual, 'abis': native_abis(apk), 'sha256': sha256(apk)}
            if state['binding']['event'] == 'pull_request':
                digest = certificate(apk, tools)
                require(digest not in pins.values(), 'PR APKs cannot use stable certificates')
                entry.update(signing='ephemeral-debug', certificate_sha256=digest)
            else:
                require_unsigned(apk, tools)
                entry['signing'] = 'unsigned'
            shutil.copyfile(apk, destination / layout['file'])
            entries.append(entry)
        manifest = {'schema_version': 2, **state['binding'], **state['plan'], 'mode': variant,
                    'version': state['version'], 'toolchain': state['toolchain'], 'artifacts': entries,
                    'source_input_hashes': state['source_input_hashes'], 'strict_input_hashes': state['input_hashes'],
                    'room_declarations': state['room_declarations'],
                    'debug_test_evidence': state['debug_test_evidence'],
                    'tasks': tasks_debug_tests(state['plan']) + tasks_outputs(state) if 'test-debug' in state['completed'] else tasks_outputs(state),
                    'test_scope': 'full-debug-once',
                    'device_validation': 'NOT RUN',
                    'actual_tools': {'java': run_tool(['java', '--version']),
                                     'build_tools': properties(tools / 'source.properties'),
                                     'runner_image': os.environ.get('ImageVersion', 'unknown')}}
        validate_artifacts(manifest, variant, state['version'], state['plan'],
                           signing='ephemeral-debug' if state['binding']['event'] == 'pull_request' else 'unsigned')
        write_json(destination / 'build-manifest.json', manifest)
        write_checksums(destination)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as out:
            out.write('has-instrumentation=' + str(bool(state['plan']['instrumentation_roles'])).lower() + '\n')


def build_packages(mode, legacy):
    root = legacy.ROOT
    state, digest = start(mode, root, legacy)
    for stage in stage_order(state):
        digest = run_stage(stage, digest, root, legacy)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=('debug', 'candidate'))
    parser.add_argument('--stage', choices=('start', 'acquire', 'strict-inputs', 'test-debug', 'lint-build', 'seal'), required=True)
    parser.add_argument('--session-sha256')
    args = parser.parse_args()
    if args.stage == 'start':
        start(args.mode)
    else:
        run_stage(args.stage, args.session_sha256)
