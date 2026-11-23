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
from package_contract import (DEPENDENCY_FILES, HISTORICAL_SCHEMA_VERSIONS, ROLES, SCHEMA_DIRECTORY, SPECS,
                              committed_hash, current_schema, current_schemas, data_bytes, exact_data, input_hashes,
                              project_plan, required_inputs, room_declarations, safe_path, schema_output_paths)
from package_build import REQUEST_FILE, source_hashes

KIND = 'owner-pr-package-input-acquire-strict-compile'
REPORT = 'android/app/build/reports/package-input-acquisition'
ACQUIRE_TASK = ':ciAcquirePackageDependencyFiles'
COLLECTOR = ':ciResolveAndLockAll'
STRICT_GRAPH_TASK = ':ciReadPackageDependencyGraph'
TOOLS = ('scripts/ci/build.py', 'scripts/ci/build_process.py', 'scripts/ci/enforce.gradle',
         'scripts/ci/ci_common.py', 'scripts/ci/apk_verify.py', 'scripts/ci/package_contract.py',
         'scripts/ci/package_build.py', 'scripts/ci/package_migration.py', 'scripts/ci/package_test_evidence.py',
         'scripts/ci/dependency-acquire-packages.gradle')
VERSION_TOOLS = TOOLS + ('scripts/ci/package_lock_versions.py', 'scripts/ci/package_lock_graph.py')
RETIREMENT_TOOLS = VERSION_TOOLS + ('scripts/ci/package_lock_retirements.py',)
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


def version_source_bound(root, binding, request):
    require(git(root, 'rev-parse', binding['source_sha'] + ':' + REQUEST_FILE) ==
            git(root, 'rev-parse', 'HEAD:' + REQUEST_FILE), 'The frozen PR source must contain the exact upgrade request')
    result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', 'diff', '--name-only', '--no-renames', '-z',
                             binding['source_sha'], binding['build_sha'], '--'], cwd=root, capture_output=True)
    require(result.returncode == 0 and len(result.stdout) <= 4 * 1024 * 1024, 'Upgrade source/build comparison is unavailable')
    changed = set(filter(None, result.stdout.decode('utf-8').split('\0')))
    require(not changed & set(request['frozen_input_hashes']), 'The upgrade source and build must have identical frozen build inputs')


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


def request_uses_lock_graph(request):
    return request['schema_version'] in (6, 7)


def request_current_schemas(request):
    return [request['current_schema']] if request['schema_version'] == 3 else request['current_schemas']


def request_collected_schemas(request):
    if request['schema_version'] == 3:
        return [] if request['schema_to_collect'] is None else [request['schema_to_collect']]
    return request['schemas_to_collect']


def request_schema_fields(request):
    names = ('current_schema', 'schema_to_collect') if request['schema_version'] == 3 else ('current_schemas', 'schemas_to_collect')
    return {name: request[name] for name in names}


def schema_export_name(request, phase, relative):
    suffix = relative.rsplit('/', 1)[-1] if request['schema_version'] == 3 else relative
    return 'schema-exports/' + phase + '/' + suffix


def make_request(root, base, variants=('debug',), *, schema_version=None, allowed_dependency_upgrades=None,
                 allowed_dependency_retirements=None):
    require(re.fullmatch(r'[0-9a-f]{40}', base), 'A frozen base source SHA is required')
    plan = project_plan(root)
    reviewed = input_hashes(root)
    require(all(name in reviewed for name in ('gradle/verification-metadata.xml', 'app/gradle.lockfile',
                                             SCHEMA_DIRECTORY + '/1.json')), 'Preserve the admitted Watch baseline first')
    for name, digest in reviewed.items():
        require(committed_hash(root, 'android/' + name, base) == digest, 'Reviewed package preimage differs from the base')
    current = current_schemas(root)
    main = current_schema(root)
    history = {SCHEMA_DIRECTORY + '/' + str(value) + '.json' for value in HISTORICAL_SCHEMA_VERSIONS}
    # Frozen base bytes distinguish first generation from removal of an admitted export.
    base_history = {name: data_bytes(root, 'android/' + name, base, optional=True) for name in sorted(history)}
    require(all(raw is None or reviewed.get(name) == hashlib.sha256(raw).hexdigest()
                for name, raw in base_history.items()), 'Admitted Watch history cannot be removed or recollected')
    version = (4 if len(current) > 1 else 3) if schema_version is None else schema_version
    require(type(version) is int and version in (3, 4, 6, 7), 'Package schema request must be explicit v3/v4 or the independent v6/v7')
    require(version in (6, 7) or allowed_dependency_upgrades is None, 'Earlier requests cannot authorize version upgrades')
    require((version == 7) == (allowed_dependency_retirements is not None),
            'Only request 7 accepts and requires explicit auxiliary retirement permissions')
    if version == 3:
        require(len(current) == 1 and current[0] == main, 'Request v3 binds only one actual current database')
    if version in (4, 6, 7) or int(Path(main).stem) > max(HISTORICAL_SCHEMA_VERSIONS):
        required_history = history
        schema_six = SCHEMA_DIRECTORY + '/6.json'
        if main == schema_six and base_history[schema_six] is None and schema_six not in reviewed:
            required_history = history - {schema_six}
        require(required_history <= set(reviewed), 'Request must retain admitted Watch history1/2/3/6')
    if version in (6, 7):
        from package_lock_versions import KIND as upgrade_kind, validate_upgrades
        require(history | {'gradle/verification-metadata.xml', 'app/gradle.lockfile', 'companion/gradle.lockfile'} <= set(reviewed),
                'A version migration requires the complete admitted XML, dual locks and Watch history1/2/3/6')
        for name in DEPENDENCY_FILES:
            raw = data_bytes(root, 'android/' + name, base, optional=True)
            require(raw is None or reviewed.get(name) == hashlib.sha256(raw).hexdigest(),
                    'A version migration cannot remove an admitted baseline dependency file')
        upgrades = validate_upgrades(allowed_dependency_upgrades, data_bytes(root, 'android/app/gradle.lockfile', base))
        if version == 7:
            from package_lock_retirements import KIND as upgrade_kind, validate_retirements
            retirements = validate_retirements(allowed_dependency_retirements, data_bytes(root, 'android/app/gradle.lockfile', base))
    collected = [name for name in current if name not in reviewed]
    for name in collected:
        require(not safe_path(root, 'android/' + name).exists(), 'Unreviewed schema output cannot be reused')
    variants = list(variants)
    require(variants and variants[0] == 'debug' and variants == list(dict.fromkeys(variants)) and
            set(variants) <= {'debug', 'release', 'diagnostic'}, 'Acquisition variants must be explicit and bounded')
    frozen = source_hashes(root)
    declarations = room_declarations(root)
    if version in (4, 6, 7):
        require(all(frozen.get(name) == entry['source_sha256'] for name, entry in declarations.items()),
                'Multi-current Room declarations must be exact committed source inputs')
    return {'schema_version': version, 'kind': upgrade_kind if version in (6, 7) else KIND, 'owner_login': 'OnlineMo', 'owner_id': 143324986,
            'base_source_sha': base, 'variants': variants, 'plan': plan,
            **({'current_schema': current[0]} if version == 3 else {'current_schemas': current}),
            'room_declarations': declarations,
            **({'schema_to_collect': collected[0] if collected else None} if version == 3 else {'schemas_to_collect': collected}),
            'reviewed_input_hashes': reviewed, 'frozen_input_hashes': frozen,
            'migration_tool_hashes': {name: frozen[name] for name in (RETIREMENT_TOOLS if version == 7 else VERSION_TOOLS if version == 6 else TOOLS)},
            'allowed_dependency_outputs': list(DEPENDENCY_FILES), 'runtime_components': runtime_components(root),
            'graph_input_hashes': {name: frozen[name] for name in GRAPH_FILES},
            **({'allowed_dependency_upgrades': upgrades} if version in (6, 7) else {}),
            **({'allowed_dependency_retirements': retirements} if version == 7 else {})}


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
              'reviewed_input_hashes', 'frozen_input_hashes', 'migration_tool_hashes', 'allowed_dependency_outputs',
              'runtime_components', 'graph_input_hashes', 'room_declarations'}
    require(type(request) is dict and type(request.get('schema_version')) is int and request['schema_version'] in (3, 4, 6, 7),
            'Explicit package request v3/v4 or independent v6/v7 is required')
    fields |= {'current_schema', 'schema_to_collect'} if request['schema_version'] == 3 else {'current_schemas', 'schemas_to_collect'}
    expected_kind = KIND
    if request_uses_lock_graph(request):
        from package_lock_versions import KIND as expected_kind
        fields.add('allowed_dependency_upgrades')
        if request['schema_version'] == 7:
            from package_lock_retirements import KIND as expected_kind
            fields.add('allowed_dependency_retirements')
    require(set(request) == fields and request['kind'] == expected_kind and request['owner_login'] == 'OnlineMo' and
            type(request['owner_id']) is int and request['owner_id'] == 143324986, 'Package request owner or fields differ')
    base = request['base_source_sha']
    require(type(base) is str and re.fullmatch(r'[0-9a-f]{40}', base), 'Package migration base SHA is invalid')
    git(root, 'merge-base', '--is-ancestor', base, binding['source_sha'])
    git(root, 'merge-base', '--is-ancestor', binding['source_sha'], binding['build_sha'])
    if request_uses_lock_graph(request):
        version_source_bound(root, binding, request)
    require(exact_data(request, make_request(root, base, request['variants'], schema_version=request['schema_version'],
                                           allowed_dependency_upgrades=request.get('allowed_dependency_upgrades'),
                                           allowed_dependency_retirements=request.get('allowed_dependency_retirements'))) and exact_data(request['plan'], plan),
            'Package request does not bind the current committed source and admitted baseline')
    require(request_collected_schemas(request) or
            'companion/gradle.lockfile' not in request['reviewed_input_hashes'] or
            graph_changed(root, base),
            'Unchanged graph/schema cannot trigger another acquisition')
    report = safe_path(root, REPORT)
    require(not report.exists() or not any(report.iterdir()), 'Package acquisition report must be fresh')
    admission = {'request': request, 'request_sha256': digest, 'acquired_input_hashes': None}
    if request['schema_version'] == 3:
        admission.update(writer_schema_sha256=None, strict_schema_sha256=None)
    else:
        admission.update(writer_schema_hashes={}, strict_schema_hashes={})
    admission['acquisition_sha256'] = None
    if request_uses_lock_graph(request):
        admission.update(actual_dependency_upgrades=[], acquired_graph_sha256=None, strict_graph_sha256=None)
        if request['schema_version'] == 7:
            admission['actual_dependency_retirements'] = []
    return admission


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


def validate_archive_consumers(record, request, *, strict=False):
    expected = consumers(request)
    optional = {}
    for mode in request['variants']:
        for role in request['plan']['instrumentation_roles']:
            spec = SPECS[role]
            optional[':' + spec['module'] + ':lintAnalyze' + spec['flavor'][0].upper() +
                     spec['flavor'][1:] + mode.title() + 'AndroidTest'] = 'task-input-archives'
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
                    outcome == 'up-to-date' and (strict or ':lint' in consumer['id']), 'A required compile/lint consumer did not complete')
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
    return files


def validate_archive_budget(budget, files):
    require(type(budget) is dict and set(budget) == {'unique_archive_count', 'unique_archive_bytes'} and
            type(budget.get('unique_archive_count')) is int and 0 < budget['unique_archive_count'] <= 4096 and
            type(budget.get('unique_archive_bytes')) is int and 0 < budget['unique_archive_bytes'] <= 2 * 1024 * 1024 * 1024 and
            len(set(files)) <= budget['unique_archive_count'] <= len(files) and
            sum(item[1] for item in set(files)) <= budget['unique_archive_bytes'] <= sum(item[1] for item in files),
            'Package archive reservation is inconsistent')


def validate_acquisition(record, state):
    request = state['collection']['request']
    version_upgrade = request_uses_lock_graph(request)
    fields = {'schema_version', 'kind', 'request_sha256', 'variants', 'writer_tasks', 'test_task_count',
              'graph_task_count', 'consumers', 'runtime_classpaths', 'archive_budget', 'lock_capture'} | set(state['binding'])
    if version_upgrade:
        fields.add('acquired_graph_sha256')
    require(type(record) is dict and set(record) == fields and type(record.get('schema_version')) is int and
            record['schema_version'] == (4 if version_upgrade else 3) and
            record.get('kind') == 'dependency-package-file-acquisition' and
            record.get('request_sha256') == state['collection']['request_sha256'] and
            record.get('variants') == request['variants'] and record.get('writer_tasks') == writer_tasks(request) and
            all(type(record.get(key)) is type(value) and record[key] == value for key, value in state['binding'].items()),
            'Package acquisition provenance or selected variants differ')
    require(type(record.get('test_task_count')) is int and record['test_task_count'] == 0,
            'Acquisition must not execute the business test suite')
    require(type(record.get('graph_task_count')) is int and 0 < record['graph_task_count'] <= 4096,
            'Package acquisition task graph count is invalid')
    if version_upgrade:
        require(type(record['acquired_graph_sha256']) is str and re.fullmatch(r'[0-9a-f]{64}', record['acquired_graph_sha256']),
                'Versioned acquisition must bind its actual resolved graph original')
    files = validate_archive_consumers(record, request)
    validate_archive_budget(record.get('archive_budget', {}), files)
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
        'sha256', '--write-locks', '--rerun-tasks', '--no-build-cache', '--continue', '--info']
    # Collect independent compiler/lint failures in one bounded run; any failed consumer still rejects all inputs.
    # Expose configuration and dependency progress before tasks start; the no-output guard excludes CI heartbeats.
    legacy.run_gradle(command, cwd=Path(root) / 'android', env=environment, phase='package-acquire',
                      build_sha=state['binding']['build_sha'], total_deadline=state['deadline_monotonic_seconds'],
                      overall_limit_seconds=state['overall_limit_seconds'])
    require(source_hashes(root) == state['source_input_hashes'] and committed_hash(root, REQUEST_FILE) == admission['request_sha256'],
            'Acquisition changed frozen source/request inputs')
    observed = input_hashes(root)
    allowed = set(before) | set(DEPENDENCY_FILES) | set(request_current_schemas(request))
    require(set(observed) <= allowed and all(name in observed for name in required_inputs(root)),
            'Package writer omitted required outputs or added another input')
    after = {name: safe_path(Path(root) / 'android', name, file=True).read_bytes() for name in observed}
    comparable = {name: data for name, data in after.items() if name not in request_collected_schemas(request)}
    if request['schema_version'] == 7:
        from package_lock_retirements import growth as retirement_growth
        changes, retirements = retirement_growth(before, comparable, request['allowed_dependency_upgrades'],
                                                request['allowed_dependency_retirements'])
    elif request['schema_version'] == 6:
        from package_lock_versions import growth as upgrade_growth
        changes = upgrade_growth(before, comparable, request['allowed_dependency_upgrades'])
    else:
        growth(before, comparable)
    witness = safe_path(root, REPORT + '/acquisition.json', file=True)
    record = migration_json(witness.read_bytes())
    validate_acquisition(record, {**state, '_root': str(root)})
    if request_uses_lock_graph(request):
        from package_lock_graph import FILES, validate
        graph = safe_path(root, REPORT + '/' + FILES['acquire'], file=True)
        digest = sha256(graph)
        require(record['acquired_graph_sha256'] == digest, 'Acquisition does not bind its actual graph original')
        validate(root, migration_json(graph.read_bytes()), state, 'acquire', changes,
                 retirements=retirements if request['schema_version'] == 7 else None)
        admission['actual_dependency_upgrades'] = changes
        if request['schema_version'] == 7:
            admission['actual_dependency_retirements'] = retirements
        admission['acquired_graph_sha256'] = digest
    admission['acquisition_sha256'] = sha256(witness)
    admission['acquired_input_hashes'] = observed
    for name in request_collected_schemas(request):
        digest = preserve(root, schema_export_name(request, 'acquire', name), after[name])
        if request['schema_version'] == 3:
            admission['writer_schema_sha256'] = digest
        else:
            admission['writer_schema_hashes'][name] = digest
    for name, raw in after.items():
        preserve(root, 'acquired-inputs/' + name, raw)


def version_originals(root, admission):
    """A later stage must retain both complete raw preimages, not only a hash summary."""
    from package_lock_graph import FILES
    require(sha256(safe_path(root, REPORT + '/dependency-migration-request.json', file=True)) == admission['request_sha256'],
            'The preserved version upgrade request changed')
    for directory, hashes in (('reviewed-inputs', admission['request']['reviewed_input_hashes']),
                              ('acquired-inputs', admission['acquired_input_hashes'])):
        for name, digest in hashes.items():
            require(sha256(safe_path(root, REPORT + '/' + directory + '/' + name, file=True)) == digest,
                    'A complete baseline/acquired input original changed')
    require(sha256(safe_path(root, REPORT + '/' + FILES['acquire'], file=True)) == admission['acquired_graph_sha256'],
            'The actual acquisition graph original changed')
    request = admission['request']
    if request['schema_version'] == 7:
        from package_lock_retirements import growth as retirement_growth
        before = {name: safe_path(root, REPORT + '/reviewed-inputs/' + name, file=True).read_bytes()
                  for name in request['reviewed_input_hashes']}
        after = {name: safe_path(root, REPORT + '/acquired-inputs/' + name, file=True).read_bytes()
                 for name in admission['acquired_input_hashes'] if name not in request_collected_schemas(request)}
        changes, retirements = retirement_growth(before, after, request['allowed_dependency_upgrades'],
                                                request['allowed_dependency_retirements'])
        require(exact_data(changes, admission['actual_dependency_upgrades']) and
                exact_data(retirements, admission['actual_dependency_retirements']),
                'The preserved actual upgrade or retirement set changed')


def before_strict(root, state, stage):
    admission = state['collection']
    request = admission['request']
    require(committed_hash(root, REQUEST_FILE) == admission['request_sha256'] and
            sha256(safe_path(root, REPORT + '/acquisition.json', file=True)) == admission['acquisition_sha256'],
            'Acquisition originals changed before strict validation')
    if request_uses_lock_graph(request):
        version_originals(root, admission)
    if stage == 'strict-inputs':
        for name in request_collected_schemas(request):
            path = safe_path(Path(root) / 'android', name, file=True)
            expected = admission['writer_schema_sha256'] if request['schema_version'] == 3 else admission['writer_schema_hashes'][name]
            require(sha256(path) == expected, 'Only an exact current-run export may be regenerated')
            path.unlink()


def after_strict(root, state, stage):
    admission = state['collection']
    request = admission['request']
    observed = input_hashes(root)
    require(observed == admission['acquired_input_hashes'], 'Strict build changed dependency, lock or schema bytes')
    if stage == 'strict-inputs':
        for name in request_collected_schemas(request):
            raw = safe_path(Path(root) / 'android', name, file=True).read_bytes()
            digest = preserve(root, schema_export_name(request, 'strict-inputs', name), raw)
            expected = admission['writer_schema_sha256'] if request['schema_version'] == 3 else admission['writer_schema_hashes'][name]
            require(digest == expected, 'Independent Room schema exports differ for one current database')
            if request['schema_version'] == 3:
                admission['strict_schema_sha256'] = digest
            else:
                admission['strict_schema_hashes'][name] = digest
        if request_uses_lock_graph(request):
            from package_lock_graph import FILES, compare, validate
            version_originals(root, admission)
            require(committed_hash(root, REQUEST_FILE) == admission['request_sha256'], 'Strict validation changed the frozen upgrade request')
            acquired = safe_path(root, REPORT + '/' + FILES['acquire'], file=True)
            strict = safe_path(root, REPORT + '/' + FILES['strict-inputs'], file=True)
            acquired_record, strict_record = migration_json(acquired.read_bytes()), migration_json(strict.read_bytes())
            validate(root, acquired_record, state, 'acquire', admission['actual_dependency_upgrades'],
                     retirements=admission.get('actual_dependency_retirements') if request['schema_version'] == 7 else None)
            validate(root, strict_record, state, 'strict-inputs', admission['actual_dependency_upgrades'],
                     retirements=admission.get('actual_dependency_retirements') if request['schema_version'] == 7 else None)
            compare(acquired_record, strict_record)
            admission['strict_graph_sha256'] = sha256(strict)
        admission['strict_inputs_completed'] = True


def strict_inputs(root, state, legacy):
    before_strict(root, state, 'strict-inputs')
    environment = legacy.gradle_environment(unsigned=True)
    for name in ('CI_DEPENDENCY_PACKAGE_REQUEST_SHA256', 'CI_DEPENDENCY_PACKAGE_VARIANTS', 'CI_PACKAGE_FRESH_ROOM_SCHEMA', 'CI_PACKAGE_DEBUG_BINDING'):
        environment.pop(name, None)
    request = state['collection']['request']
    if request_collected_schemas(request):
        environment['CI_PACKAGE_FRESH_ROOM_SCHEMA'] = 'true'
    command = legacy.gradle_command(writer_tasks(request))
    if request_uses_lock_graph(request):
        environment['CI_DEPENDENCY_PACKAGE_REQUEST_SHA256'] = state['collection']['request_sha256']
        command += [STRICT_GRAPH_TASK, '--init-script', '../scripts/ci/dependency-acquire-packages.gradle']
    legacy.run_gradle(command, cwd=Path(root) / 'android',
                      env=environment, phase='package-strict-inputs', build_sha=state['binding']['build_sha'],
                      total_deadline=state['deadline_monotonic_seconds'], overall_limit_seconds=state['overall_limit_seconds'])
    require(source_hashes(root) == state['source_input_hashes'], 'Strict input validation changed frozen source')
    after_strict(root, state, 'strict-inputs')


def report_version(request):
    return 9 if request['schema_version'] == 7 else 8 if request['schema_version'] == 6 else 6 if request['schema_version'] == 3 else 7


def version_report_fields(admission):
    if not request_uses_lock_graph(admission['request']):
        return {}
    fields = {'allowed_dependency_upgrades': admission['request']['allowed_dependency_upgrades'],
            'actual_dependency_upgrades': admission['actual_dependency_upgrades'],
            'acquired_graph_sha256': admission['acquired_graph_sha256'],
            'strict_graph_sha256': admission['strict_graph_sha256']}
    if admission['request']['schema_version'] == 7:
        fields.update(allowed_dependency_retirements=admission['request']['allowed_dependency_retirements'],
                      actual_dependency_retirements=admission['actual_dependency_retirements'])
    return fields


def seal(root, state):
    admission = state['collection']
    request = admission['request']
    require(admission.get('strict_inputs_completed') is True and input_hashes(root) == admission['acquired_input_hashes'],
            'Strict compiler/lint input validation and exact output bytes are required')
    require(state['debug_test_evidence'] is None, 'Input acquisition cannot claim application test evidence')
    if request_uses_lock_graph(request):
        from package_lock_graph import FILES
        version_originals(root, admission)
        require(admission['strict_graph_sha256'] is not None and
                sha256(safe_path(root, REPORT + '/' + FILES['strict-inputs'], file=True)) == admission['strict_graph_sha256'],
                'An independently validated strict graph original is required')
    files = {}
    for name, digest in admission['acquired_input_hashes'].items():
        raw = safe_path(Path(root) / 'android', name, file=True).read_bytes()
        require(hashlib.sha256(raw).hexdigest() == digest, 'Final package input drifted')
        files[name] = preserve(root, name, raw)
    schema_hashes = {name: admission[name] for name in (('writer_schema_sha256', 'strict_schema_sha256')
                     if request['schema_version'] == 3 else ('writer_schema_hashes', 'strict_schema_hashes'))}
    manifest = {'schema_version': report_version(request), 'collection_kind': request['kind'], **state['binding'], **state['plan'],
                'room_declarations': request['room_declarations'],
                'request_sha256': admission['request_sha256'], 'base_source_sha': request['base_source_sha'],
                'variants': request['variants'], **request_schema_fields(request), 'reviewed_input_hashes': request['reviewed_input_hashes'],
                'source_input_hashes': state['source_input_hashes'], 'acquired_input_hashes': admission['acquired_input_hashes'],
                'strict_input_hashes': input_hashes(root), 'acquisition_sha256': admission['acquisition_sha256'],
                **schema_hashes, **version_report_fields(admission),
                'input_validation_scope': ('strict-requested-variant-compilation-lint-full-lock-graph-and-exact-transitive-retirements'
                                           if request['schema_version'] == 7 else
                                           'strict-requested-variant-compilation-lint-and-full-lock-graph' if request['schema_version'] == 6
                                           else 'strict-debug-compilation-and-lint'), 'application_tests': 'NOT_RUN',
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
    admitted_schema_outputs = set(request_current_schemas(request)) | {
        SCHEMA_DIRECTORY + '/' + str(value) + '.json' for value in HISTORICAL_SCHEMA_VERSIONS}
    allowed = set(request['reviewed_input_hashes']) | set(DEPENDENCY_FILES) | admitted_schema_outputs
    try:
        allowed |= set(schema_output_paths(root, allow_unexpected=True))
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
    manifest = {'schema_version': report_version(request), 'collection_kind': request['kind'], **state['binding'], **state['plan'],
                **({} if request['schema_version'] == 3 else request_schema_fields(request)),
                **version_report_fields(admission),
                'status': 'failed-partial', 'failed_stage': stage,
                'failure_type': type(failure).__name__, 'completed_stages': state['completed'],
                'request_sha256': admission['request_sha256'], 'base_source_sha': request['base_source_sha'],
                'source_input_hashes': state['source_input_hashes'],
                'room_declarations': request['room_declarations'],
                'reviewed_input_hashes': request['reviewed_input_hashes'], 'partial_input_hashes': partial,
                'partial_capture_unavailable': sorted(set(unavailable)), 'retained_originals': originals,
                'unexpected_schema_outputs': sorted(name for name in partial if name.startswith('app/schemas/') and
                    name not in admitted_schema_outputs),
                'overall_limit_seconds': state['overall_limit_seconds'],
                'elapsed_seconds': time.monotonic() - state['started_monotonic_seconds'],
                'accepted': False, 'apk_exported': False}
    preserve(root, 'failure-manifest.json', (json.dumps(manifest, sort_keys=True, indent=2, allow_nan=False) + '\n').encode('utf-8'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=('make-request', 'make-version-request', 'make-retirement-request'))
    parser.add_argument('--base-sha', required=True)
    parser.add_argument('--variants', nargs='+', choices=('debug', 'release', 'diagnostic'), default=['debug'])
    parser.add_argument('--output', required=True)
    parser.add_argument('--dependency-upgrades', help='Exact owner grant JSON array for the independent schema 6 request')
    parser.add_argument('--dependency-retirements', help='Exact admitted-base auxiliary retirement permissions for request 7')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    destination = Path(args.output)
    require(not destination.exists(), 'Owner request output is write-once')
    require((args.operation in ('make-version-request', 'make-retirement-request')) == (args.dependency_upgrades is not None),
            'Versioned request operations require the exact dependency upgrade grant file')
    require((args.operation == 'make-retirement-request') == (args.dependency_retirements is not None),
            'Only make-retirement-request accepts and requires exact retirement permissions')
    grants = migration_json(Path(args.dependency_upgrades).read_bytes()) if args.dependency_upgrades is not None else None
    retirements = migration_json(Path(args.dependency_retirements).read_bytes()) if args.dependency_retirements is not None else None
    write_json(destination, make_request(root, args.base_sha, args.variants,
                                        schema_version=7 if retirements is not None else 6 if grants is not None else None,
                                        allowed_dependency_upgrades=grants, allowed_dependency_retirements=retirements))
