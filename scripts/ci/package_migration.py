"""Explicit multi-package acquisition successor. Historical v2-v5 contracts stay separate."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import tomllib

from build import dependency_lock_snapshot, migration_json, verification_snapshot
from ci_common import require, sha256, write_json
from package_contract import (DEPENDENCY_FILES, ROLES, SCHEMA_DIRECTORY, SPECS, committed_hash, current_schema, exact_data, input_hashes,
                              project_plan, required_inputs, room_declarations, safe_path, schema_output_paths)
from package_build import REQUEST_FILE, source_hashes

KIND = 'owner-pr-package-input-acquire-strict-compile'
REPORT = 'android/app/build/reports/package-input-acquisition'
ACQUIRE_TASK = ':ciAcquirePackageDependencyFiles'
COLLECTOR = ':ciResolveAndLockAll'
TOOLS = ('scripts/ci/build.py', 'scripts/ci/build_process.py', 'scripts/ci/enforce.gradle',
         'scripts/ci/ci_common.py', 'scripts/ci/apk_verify.py', 'scripts/ci/package_contract.py',
         'scripts/ci/package_build.py', 'scripts/ci/package_migration.py', 'scripts/ci/package_test_evidence.py',
         'scripts/ci/dependency-acquire-packages.gradle')
GRAPH_FILES = ('.ci/toolchain.properties', 'android/settings.gradle.kts', 'android/build.gradle.kts',
               'android/app/build.gradle.kts', 'android/companion/build.gradle.kts',
               'android/gradle/libs.versions.toml', 'android/gradle.properties')


def git(root, *args):
    result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', *args], cwd=root, capture_output=True, text=True)
    require(result.returncode == 0, 'Package migration Git data is unavailable')
    return result.stdout.strip()


def graph_changed(root, base):
    for name in GRAPH_FILES:
        previous = subprocess.run(['git', 'rev-parse', base + ':' + name], cwd=root, capture_output=True, text=True)
        if previous.returncode != 0 or previous.stdout.strip() != git(root, 'rev-parse', 'HEAD:' + name):
            return True
    return False


def runtime_components(root):
    catalog = tomllib.loads(safe_path(root, 'android/gradle/libs.versions.toml', file=True).read_text(encoding='utf-8'))
    result = {}
    for name, alias in [('bundledSqliteHost', 'sqlite-bundled-host'), ('robolectricSdk', 'robolectric-sdk'),
                        ('robolectricSdk30', 'robolectric-sdk30')]:
        library = catalog['libraries'][alias]
        version = library['version']
        if isinstance(version, dict):
            version = catalog['versions'][version['ref']]
        parts = [*library['module'].split(':'), version]
        require(len(parts) == 3 and all(type(part) is str and re.fullmatch(r'[A-Za-z0-9_.+\-]{1,120}', part) for part in parts),
                'Runtime component coordinates are invalid')
        result[name] = parts
    return result


def make_request(root, base, variants=('debug',)):
    require(re.fullmatch(r'[0-9a-f]{40}', base), 'A frozen base source SHA is required')
    plan = project_plan(root)
    reviewed = input_hashes(root)
    require(all(name in reviewed for name in ('gradle/verification-metadata.xml', 'app/gradle.lockfile',
                                             SCHEMA_DIRECTORY + '/1.json')), 'Preserve the admitted Watch baseline first')
    for name, digest in reviewed.items():
        require(committed_hash(root, 'android/' + name, base) == digest, 'Reviewed package preimage differs from the base')
    current = current_schema(root)
    schema_to_collect = None if current in reviewed else current
    if schema_to_collect:
        require(not safe_path(root, 'android/' + current).exists(), 'Unreviewed schema output cannot be reused')
    variants = list(variants)
    require(variants and variants[0] == 'debug' and variants == list(dict.fromkeys(variants)) and
            set(variants) <= {'debug', 'release', 'diagnostic'}, 'Acquisition variants must be explicit and bounded')
    frozen = source_hashes(root)
    return {'schema_version': 3, 'kind': KIND, 'owner_login': 'OnlineMo', 'owner_id': 143324986,
            'base_source_sha': base, 'variants': variants, 'plan': plan, 'current_schema': current,
            'room_declarations': room_declarations(root),
            'schema_to_collect': schema_to_collect, 'reviewed_input_hashes': reviewed,
            'frozen_input_hashes': frozen, 'migration_tool_hashes': {name: frozen[name] for name in TOOLS},
            'allowed_dependency_outputs': list(DEPENDENCY_FILES), 'runtime_components': runtime_components(root),
            'graph_input_hashes': {name: frozen[name] for name in GRAPH_FILES}}


def admit(root, binding, plan):
    require(binding['event'] == 'pull_request' and re.fullmatch(r'refs/pull/[1-9][0-9]*/merge', binding['ref']),
            'Multi-package acquisition requires a PR merge ref')
    for key, value in {'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch', 'GITHUB_ACTOR': 'OnlineMo',
                       'GITHUB_ACTOR_ID': '143324986', 'GITHUB_TRIGGERING_ACTOR': 'OnlineMo'}.items():
        require(os.environ.get(key) == value, 'Only the owner may request package input acquisition')
    require(not os.environ.get('CANDIDATE_OPERATION'), 'Candidate builds cannot collect dependency inputs')
    path = safe_path(root, REQUEST_FILE, file=True)
    digest = committed_hash(root, REQUEST_FILE)
    request = migration_json(path.read_bytes())
    fields = {'schema_version', 'kind', 'owner_login', 'owner_id', 'base_source_sha', 'variants', 'plan',
              'current_schema', 'schema_to_collect', 'reviewed_input_hashes', 'frozen_input_hashes',
              'migration_tool_hashes', 'allowed_dependency_outputs', 'runtime_components', 'graph_input_hashes', 'room_declarations'}
    require(type(request) is dict and set(request) == fields and type(request['schema_version']) is int and
            request['schema_version'] == 3 and request['kind'] == KIND and request['owner_login'] == 'OnlineMo' and
            type(request['owner_id']) is int and request['owner_id'] == 143324986, 'Explicit package request v3 is required')
    base = request['base_source_sha']
    require(type(base) is str and re.fullmatch(r'[0-9a-f]{40}', base), 'Package migration base SHA is invalid')
    git(root, 'merge-base', '--is-ancestor', base, binding['source_sha'])
    git(root, 'merge-base', '--is-ancestor', binding['source_sha'], binding['build_sha'])
    require(exact_data(request, make_request(root, base, request['variants'])) and exact_data(request['plan'], plan),
            'Package request does not bind the current committed source and admitted baseline')
    require(request['schema_to_collect'] is not None or
            'companion/gradle.lockfile' not in request['reviewed_input_hashes'] or
            graph_changed(root, base),
            'Unchanged graph/schema cannot trigger another acquisition')
    report = safe_path(root, REPORT)
    require(not report.exists() or not any(report.iterdir()), 'Package acquisition report must be fresh')
    return {'request': request, 'request_sha256': digest, 'acquired_input_hashes': None,
            'writer_schema_sha256': None, 'strict_schema_sha256': None, 'acquisition_sha256': None}


def writer_tasks(request):
    tasks = [':app:prepareRobolectricSdk', ':app:prepareBundledSqliteHost', ':companion:prepareRobolectricSdk']
    for mode in request['variants']:
        for role in ROLES:
            spec = SPECS[role]
            variant = spec['flavor'][0].upper() + spec['flavor'][1:] + mode.title()
            tasks += [':' + spec['module'] + ':compile' + variant + suffix
                      for suffix in ('Kotlin', 'JavaWithJavac', 'UnitTestKotlin', 'UnitTestJavaWithJavac')]
            tasks.append(':' + spec['module'] + ':lint' + variant)
            if mode == 'diagnostic' and role in request['plan']['instrumentation_roles']:
                tasks += [':' + spec['module'] + ':compile' + variant + 'AndroidTest' + suffix
                          for suffix in ('Kotlin', 'JavaWithJavac')]
    return tasks


def consumers(request):
    result = {task: 'task-input-archives' for task in writer_tasks(request)}
    for mode in request['variants']:
        for role in ROLES:
            spec = SPECS[role]
            variant = spec['flavor'][0].upper() + spec['flavor'][1:] + mode.title()
            prefix = ':' + spec['module'] + ':'
            for first, last in [('lintAnalyze', ''), ('lintAnalyze', 'UnitTest'), ('lintReport', '')]:
                result[prefix + first + variant + last] = 'task-input-archives'
            result[prefix + 'test' + variant + 'UnitTest'] = 'test-classpath-archives'
    result.update({':app:' + name: 'configuration-artifacts' for name in ('bundledSqliteHost', 'robolectricSdk', 'robolectricSdk30')})
    result[':companion:robolectricSdk'] = 'configuration-artifacts'
    result.update({name: 'buildscript-artifacts' for name in (':buildscript:classpath', ':app:buildscript:classpath', ':companion:buildscript:classpath')})
    return result


def preserve(root, relative, data):
    path = safe_path(root, REPORT + '/' + relative)
    require(not path.exists(), 'Package input originals cannot be overwritten')
    path.parent.mkdir(parents=True, exist_ok=True)
    with safe_path(root, REPORT + '/' + relative).open('xb') as out:
        out.write(data)
    require(sha256(path) == hashlib.sha256(data).hexdigest(), 'Package input original changed while preserving')
    return hashlib.sha256(data).hexdigest()


def growth(before, after):
    require(set(before) <= set(after), 'Package acquisition removed an old input')
    left, old = verification_snapshot(before['gradle/verification-metadata.xml'])
    right, new = verification_snapshot(after['gradle/verification-metadata.xml'])
    require(left == right and all(new.get(key) == value for key, value in old.items()), 'Existing artifact trust pins changed')
    for name, data in before.items():
        if name.endswith('gradle.lockfile'):
            previous, updated = dependency_lock_snapshot(data), dependency_lock_snapshot(after[name])
            require(all(key in updated and all(updated[key].get(module) == version for module, version in modules.items())
                        for key, modules in previous.items()), 'Old configuration/module/version lock bindings changed')
        elif name != 'gradle/verification-metadata.xml':
            require(after[name] == data, 'An old Room schema changed')


def validate_acquisition(record, state):
    request = state['collection']['request']
    expected = consumers(request)
    optional = {}
    for mode in request['variants']:
        for role in request['plan']['instrumentation_roles']:
            spec = SPECS[role]
            optional[':' + spec['module'] + ':lintAnalyze' + spec['flavor'][0].upper() +
                     spec['flavor'][1:] + mode.title() + 'AndroidTest'] = 'task-input-archives'
    fields = {'schema_version', 'kind', 'request_sha256', 'variants', 'writer_tasks', 'test_task_count',
              'graph_task_count', 'consumers', 'runtime_classpaths', 'archive_budget', 'lock_capture'} | set(state['binding'])
    require(type(record) is dict and set(record) == fields and type(record.get('schema_version')) is int and record['schema_version'] == 3 and
            record.get('kind') == 'dependency-package-file-acquisition' and
            record.get('request_sha256') == state['collection']['request_sha256'] and
            record.get('variants') == request['variants'] and record.get('writer_tasks') == writer_tasks(request) and
            all(type(record.get(key)) is type(value) and record[key] == value for key, value in state['binding'].items()),
            'Package acquisition provenance or selected variants differ')
    require(type(record.get('test_task_count')) is int and record['test_task_count'] == 0,
            'Acquisition must not execute the business test suite')
    require(type(record.get('graph_task_count')) is int and 0 < record['graph_task_count'] <= 4096,
            'Package acquisition task graph count is invalid')
    observed = record.get('consumers')
    require(type(observed) is list and 0 < len(observed) <= 128 and
            all(type(item) is dict and type(item.get('id')) is str for item in observed), 'Package consumer inventory is invalid')
    ids = [item['id'] for item in observed]
    require(ids == sorted(set(ids)) and set(expected) <= set(ids) <= set(expected) | set(optional), 'Missing or foreign package consumer')
    files = []
    for consumer in observed:
        kind = (expected | optional)[consumer['id']]
        consumer_fields = {'id', 'kind', 'files'}
        if kind in ('task-input-archives', 'test-classpath-archives'):
            consumer_fields |= {'file_count', 'directory_count', 'excluded_file_count', 'missing_non_archive_count'}
        if kind == 'task-input-archives':
            consumer_fields.add('outcome')
        require(set(consumer) == consumer_fields and consumer.get('kind') == kind and
                type(consumer.get('files')) is list and len(consumer['files']) <= 4096,
                'Package consumer shape differs')
        if kind == 'task-input-archives':
            outcome = consumer.get('outcome')
            require(outcome == 'executed' or outcome == 'no-source' and
                    (consumer['id'].endswith('JavaWithJavac') or consumer['id'] in optional) or
                    outcome == 'up-to-date' and ':lint' in consumer['id'], 'A required compile/lint consumer did not complete')
        for item in consumer['files']:
            file_fields = {'name', 'size', 'sha256'} | ({'component'} if kind in ('configuration-artifacts', 'buildscript-artifacts') else set())
            require(type(item) is dict and set(item) == file_fields and type(item.get('name')) is str and
                    re.fullmatch(r'[A-Za-z0-9_.+\-]{1,200}\.(jar|aar)', item['name']) and
                    type(item.get('size')) is int and 0 < item['size'] <= 256 * 1024 * 1024 and
                    type(item.get('sha256')) is str and re.fullmatch(r'[0-9a-f]{64}', item['sha256']),
                    'Acquisition requires actual bounded archive bytes')
            if 'component' in file_fields:
                parts = item['component']
                require(type(parts) is list and len(parts) == 3 and
                        all(type(part) is str and re.fullmatch(r'[A-Za-z0-9_.+\-]{1,120}', part) for part in parts),
                        'Acquisition module coordinates are invalid')
            files.append((item['name'], item['size'], item['sha256']))
        ordering = [json.dumps(item, sort_keys=True, separators=(',', ':'), allow_nan=False) for item in consumer['files']]
        require(ordering == sorted(ordering), 'Package archive inventory is not canonical')
        if kind in ('task-input-archives', 'test-classpath-archives'):
            counts = [consumer.get(key) for key in ('file_count', 'directory_count', 'excluded_file_count', 'missing_non_archive_count')]
            require(all(type(count) is int and 0 <= count <= 4096 for count in counts) and
                    counts[0] == len(consumer['files']) + sum(counts[1:]), 'Package consumer file counts differ')
            require(not (kind == 'test-classpath-archives' or consumer['id'].endswith('Kotlin')) or consumer['files'],
                    'Real compiler/Test runtime archives were not consumed')
        if kind == 'configuration-artifacts':
            require(len(consumer['files']) == 1 and consumer['files'][0].get('component') ==
                    request['runtime_components'][consumer['id'].rsplit(':', 1)[1]], 'Runtime component coordinates differ')
    runtime = record.get('runtime_classpaths')
    expected_tests = {name for name, kind in expected.items() if kind == 'test-classpath-archives'}
    require(type(runtime) is list and len(runtime) == len(expected_tests) and
            all(type(item) is dict and set(item) == {'consumer_id', 'producer_tasks'} and
                type(item.get('consumer_id')) is str for item in runtime) and
            [item['consumer_id'] for item in runtime] == sorted(expected_tests),
            'Both packages need actual Test.classpath producers')
    for item in runtime:
        producers = item.get('producer_tasks')
        require(type(producers) is list and producers and len(producers) <= 4096 and
                all(type(value) is dict and set(value) == {'id', 'outcome'} and type(value.get('id')) is str and
                    re.fullmatch(r':(?:app|companion):[A-Za-z0-9_.+\-]{1,180}', value['id']) and
                    value['id'].split(':')[1] == item['consumer_id'].split(':')[1] and
                    value['id'] != item['consumer_id'] and
                    not value['id'].rsplit(':', 1)[-1].startswith('test') and
                    not any(flavor + mode.title() in value['id'] for flavor in ('WatchAndroid', 'PhoneAndroid')
                            for mode in ('debug', 'release', 'diagnostic') if mode not in request['variants']) and
                    value.get('outcome') in ('executed', 'up-to-date', 'no-source') for value in producers),
                'Package Test.classpath producer did not complete')
        require([value['id'] for value in producers] == sorted({value['id'] for value in producers}),
                'Package Test.classpath producer inventory is duplicate')
    budget = record.get('archive_budget', {})
    require(type(budget) is dict and set(budget) == {'unique_archive_count', 'unique_archive_bytes'} and
            type(budget.get('unique_archive_count')) is int and 0 < budget['unique_archive_count'] <= 4096 and
            type(budget.get('unique_archive_bytes')) is int and 0 < budget['unique_archive_bytes'] <= 2 * 1024 * 1024 * 1024 and
            len(set(files)) <= budget['unique_archive_count'] <= len(files) and
            sum(item[1] for item in set(files)) <= budget['unique_archive_bytes'] <= sum(item[1] for item in files),
            'Package archive reservation is inconsistent')
    capture = record.get('lock_capture', {})
    require(type(capture) is dict and set(capture) == {'task', 'preceding_file_capture_task', 'status',
            'unresolved_dependency_count', 'configuration_count', 'project_configurations'} and
            capture.get('task') == COLLECTOR and capture.get('preceding_file_capture_task') == ACQUIRE_TASK and
            capture.get('status') == 'completed' and type(capture.get('unresolved_dependency_count')) is int and
            capture['unresolved_dependency_count'] == 0 and type(capture.get('configuration_count')) is int and
            0 < capture['configuration_count'] <= 8192 and
            type(capture.get('project_configurations')) is dict and
            set(capture['project_configurations']) == {':', ':app', ':companion'}, 'Complete application lock graphs are required')
    for project, names in capture['project_configurations'].items():
        relative = {':': 'gradle.lockfile', ':app': 'app/gradle.lockfile', ':companion': 'companion/gradle.lockfile'}[project]
        require(type(names) is list and all(type(name) is str and re.fullmatch(r'[A-Za-z0-9_.\-]{1,200}', name) for name in names) and
                names == sorted(set(names)) and len(names) <= 4096, 'Lock graph names are invalid')
        if names:
            raw = safe_path(Path(state['_root']) / 'android', relative, file=True).read_bytes()
            require(set(names) <= set(dependency_lock_snapshot(raw)), 'A resolved configuration has no retained lock')
    require(sum(len(names) for names in capture['project_configurations'].values()) == capture['configuration_count'],
            'Package lock graph configuration count differs')


def acquire(root, state, legacy):
    admission = state['collection']
    request = admission['request']
    report = safe_path(root, REPORT)
    report.mkdir(parents=True, exist_ok=True)
    preserve(root, 'dependency-migration-request.json', safe_path(root, REQUEST_FILE, file=True).read_bytes())
    before = {name: safe_path(Path(root) / 'android', name, file=True).read_bytes() for name in request['reviewed_input_hashes']}
    for name, raw in before.items():
        preserve(root, 'reviewed-inputs/' + name, raw)
    environment = legacy.gradle_environment(unsigned=True)
    environment.pop('CI_PACKAGE_FRESH_ROOM_SCHEMA', None)
    environment.pop('CI_PACKAGE_DEBUG_BINDING', None)
    environment['CI_DEPENDENCY_PACKAGE_REQUEST_SHA256'] = admission['request_sha256']
    command = legacy.gradle_command(writer_tasks(request) + [ACQUIRE_TASK, COLLECTOR]) + [
        '--init-script', '../scripts/ci/dependency-acquire-packages.gradle', '--write-verification-metadata',
        'sha256', '--write-locks', '--rerun-tasks', '--no-build-cache', '--continue']
    # Collect independent compiler/lint failures in one bounded run; any failed consumer still rejects all inputs.
    legacy.run_gradle(command, cwd=Path(root) / 'android', env=environment, phase='package-acquire',
                      build_sha=state['binding']['build_sha'], total_deadline=state['deadline_monotonic_seconds'],
                      overall_limit_seconds=state['overall_limit_seconds'])
    require(source_hashes(root) == state['source_input_hashes'] and committed_hash(root, REQUEST_FILE) == admission['request_sha256'],
            'Acquisition changed frozen source/request inputs')
    observed = input_hashes(root)
    allowed = set(before) | set(DEPENDENCY_FILES) | {request['current_schema']}
    require(set(observed) <= allowed and all(name in observed for name in required_inputs(root)),
            'Package writer omitted required outputs or added another input')
    after = {name: safe_path(Path(root) / 'android', name, file=True).read_bytes() for name in observed}
    growth(before, {name: data for name, data in after.items() if name != request['schema_to_collect']})
    witness = safe_path(root, REPORT + '/acquisition.json', file=True)
    record = migration_json(witness.read_bytes())
    validate_acquisition(record, {**state, '_root': str(root)})
    admission['acquisition_sha256'] = sha256(witness)
    admission['acquired_input_hashes'] = observed
    if request['schema_to_collect'] is not None:
        admission['writer_schema_sha256'] = observed[request['current_schema']]
        preserve(root, 'schema-exports/acquire/' + request['current_schema'].rsplit('/', 1)[-1], after[request['current_schema']])
    for name, raw in after.items():
        preserve(root, 'acquired-inputs/' + name, raw)


def before_strict(root, state, stage):
    admission = state['collection']
    request = admission['request']
    require(committed_hash(root, REQUEST_FILE) == admission['request_sha256'] and
            sha256(safe_path(root, REPORT + '/acquisition.json', file=True)) == admission['acquisition_sha256'],
            'Acquisition originals changed before strict validation')
    if stage == 'strict-inputs' and request['schema_to_collect'] is not None:
        path = safe_path(Path(root) / 'android', request['schema_to_collect'], file=True)
        require(sha256(path) == admission['writer_schema_sha256'], 'Only the current run export may be regenerated')
        path.unlink()


def after_strict(root, state, stage):
    admission = state['collection']
    request = admission['request']
    observed = input_hashes(root)
    require(observed == admission['acquired_input_hashes'], 'Strict build changed dependency, lock or schema bytes')
    if stage == 'strict-inputs':
        if request['schema_to_collect'] is not None:
            raw = safe_path(Path(root) / 'android', request['current_schema'], file=True).read_bytes()
            admission['strict_schema_sha256'] = preserve(root, 'schema-exports/strict-inputs/' + request['current_schema'].rsplit('/', 1)[-1], raw)
            require(admission['strict_schema_sha256'] == admission['writer_schema_sha256'], 'Independent Room schema exports differ')
        admission['strict_inputs_completed'] = True


def strict_inputs(root, state, legacy):
    before_strict(root, state, 'strict-inputs')
    environment = legacy.gradle_environment(unsigned=True)
    for name in ('CI_DEPENDENCY_PACKAGE_REQUEST_SHA256', 'CI_DEPENDENCY_PACKAGE_VARIANTS', 'CI_PACKAGE_FRESH_ROOM_SCHEMA', 'CI_PACKAGE_DEBUG_BINDING'):
        environment.pop(name, None)
    if state['collection']['request']['schema_to_collect'] is not None:
        environment['CI_PACKAGE_FRESH_ROOM_SCHEMA'] = 'true'
    legacy.run_gradle(legacy.gradle_command(writer_tasks(state['collection']['request'])), cwd=Path(root) / 'android',
                      env=environment, phase='package-strict-inputs', build_sha=state['binding']['build_sha'],
                      total_deadline=state['deadline_monotonic_seconds'], overall_limit_seconds=state['overall_limit_seconds'])
    require(source_hashes(root) == state['source_input_hashes'], 'Strict input validation changed frozen source')
    after_strict(root, state, 'strict-inputs')


def seal(root, state):
    admission = state['collection']
    request = admission['request']
    require(admission.get('strict_inputs_completed') is True and input_hashes(root) == admission['acquired_input_hashes'],
            'Strict compiler/lint input validation and exact output bytes are required')
    require(state['debug_test_evidence'] is None, 'Input acquisition cannot claim application test evidence')
    files = {}
    for name, digest in admission['acquired_input_hashes'].items():
        raw = safe_path(Path(root) / 'android', name, file=True).read_bytes()
        require(hashlib.sha256(raw).hexdigest() == digest, 'Final package input drifted')
        files[name] = preserve(root, name, raw)
    manifest = {'schema_version': 6, 'collection_kind': KIND, **state['binding'], **state['plan'],
                'room_declarations': request['room_declarations'],
                'request_sha256': admission['request_sha256'], 'base_source_sha': request['base_source_sha'],
                'variants': request['variants'], 'current_schema': request['current_schema'],
                'schema_to_collect': request['schema_to_collect'], 'reviewed_input_hashes': request['reviewed_input_hashes'],
                'source_input_hashes': state['source_input_hashes'], 'acquired_input_hashes': admission['acquired_input_hashes'],
                'strict_input_hashes': input_hashes(root), 'acquisition_sha256': admission['acquisition_sha256'],
                'writer_schema_sha256': admission['writer_schema_sha256'], 'strict_schema_sha256': admission['strict_schema_sha256'],
                'input_validation_scope': 'strict-debug-compilation-and-lint', 'application_tests': 'NOT_RUN',
                'apk_assembly': 'NOT_RUN', 'release_diagnostic_full_suites': 'NOT REQUESTED',
                'overall_limit_seconds': state['overall_limit_seconds'], 'elapsed_seconds': time.monotonic() - state['started_monotonic_seconds'],
                'status': 'generated-unreviewed', 'files': files, 'accepted': False, 'apk_exported': False}
    write_json(safe_path(root, REPORT + '/package-manifest.json'), manifest)


def record_failure(root, state, stage, failure):
    """Keep partial writer bytes as rejected evidence without changing the original exception."""
    admission = state['collection']
    request = admission['request']
    report = safe_path(root, REPORT)
    report.mkdir(parents=True, exist_ok=True)
    partial = {}
    unavailable = []
    allowed = set(request['reviewed_input_hashes']) | set(DEPENDENCY_FILES) | {request['current_schema']}
    try:
        allowed |= set(schema_output_paths(root))
    except Exception:
        unavailable.append('app/schemas')
    for name in sorted(allowed):
        try:
            path = safe_path(Path(root) / 'android', name)
            if not path.exists():
                if name in request['reviewed_input_hashes']:
                    unavailable.append(name)
                continue
            require(path.is_file() and path.stat().st_size <= 4 * 1024 * 1024, 'Partial input is unavailable')
            partial[name] = preserve(root, 'failed-partial-inputs/' + name, path.read_bytes())
        except Exception:
            unavailable.append(name)
    originals = {}
    members = sorted(report.rglob('*'))
    require(len(members) <= 256, 'Partial report inventory exceeds its bound')
    for path in members:
        if path.is_dir():
            continue
        name = path.relative_to(report).as_posix()
        originals[name] = sha256(safe_path(report, name, file=True))
    manifest = {'schema_version': 6, 'collection_kind': KIND, **state['binding'], **state['plan'],
                'status': 'failed-partial', 'failed_stage': stage,
                'failure_type': type(failure).__name__, 'completed_stages': state['completed'],
                'request_sha256': admission['request_sha256'], 'base_source_sha': request['base_source_sha'],
                'source_input_hashes': state['source_input_hashes'],
                'room_declarations': request['room_declarations'],
                'reviewed_input_hashes': request['reviewed_input_hashes'], 'partial_input_hashes': partial,
                'partial_capture_unavailable': sorted(set(unavailable)), 'retained_originals': originals,
                'unexpected_schema_outputs': sorted(name for name in partial if name.startswith('app/schemas/') and
                    Path(name).parent.as_posix() != SCHEMA_DIRECTORY),
                'overall_limit_seconds': state['overall_limit_seconds'],
                'elapsed_seconds': time.monotonic() - state['started_monotonic_seconds'],
                'accepted': False, 'apk_exported': False}
    preserve(root, 'failure-manifest.json', (json.dumps(manifest, sort_keys=True, indent=2, allow_nan=False) + '\n').encode('utf-8'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=('make-request',))
    parser.add_argument('--base-sha', required=True)
    parser.add_argument('--variants', nargs='+', choices=('debug', 'release', 'diagnostic'), default=['debug'])
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    destination = Path(args.output)
    require(not destination.exists(), 'Owner request output is write-once')
    write_json(destination, make_request(root, args.base_sha, args.variants))
