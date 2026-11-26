"""云端无秘密编译；稳定证书只由另一台 runner 的 sign.py 使用。"""
import argparse
import hashlib
import json
import os
from pathlib import PurePosixPath
import re
import shutil
import sys
import time
import xml.etree.ElementTree as ET

from apk_verify import build_tools, certificate, inspect_apk, require_unsigned
from build_process import run_gradle, TOTAL_BUILD_SECONDS
from check import check
from ci_common import (ROOT, cloud_only, expected_identity, git, no_signing_secrets,
                       properties, require, run_tool, sha256, write_checksums, write_json)


DEPENDENCY_INPUTS = ('gradle/verification-metadata.xml', 'app/gradle.lockfile')
DEPENDENCY_REPORT_FILES = (*DEPENDENCY_INPUTS, 'gradle.lockfile')
LOCK_COLLECTION_TASK = ':ciResolveAndLockAll'
# Keep the previous tag available for historical diagnostic tooling; new writers identify their variant.
DEPENDENCY_DIAGNOSTIC_PHASES = ('verification-collection', 'verification-debug', 'verification-release',
                               'verification-diagnostic', 'normal-lock-collection',
                               'strict-debug', 'strict-release', 'strict-diagnostic')
ROOM_DATABASE_SOURCE = 'app/src/main/kotlin/io/github/onlinemo/meloxwatch/data/database/MeloXDatabase.kt'
ROOM_SCHEMA_DIRECTORY = 'app/schemas/io.github.onlinemo.meloxwatch.data.database.MeloXDatabase'
ROOM_SCHEMA_COLLECTION_KIND = 'room-schema-strict-variants'
ROOM_SCHEMA_COLLECTION_PHASES = ('strict-debug', 'strict-release', 'strict-diagnostic')
ROOM_SCHEMA_REPORT_DIRECTORY = 'app/build/reports/dependency-bootstrap'
DEPENDENCY_MIGRATION_REQUEST = '.ci/dependency-migration-request.json'
DEPENDENCY_MIGRATION_KIND = 'owner-pr-dependency-acquire-strict'
DEPENDENCY_MIGRATION_PHASES = ('migration-acquire', *ROOM_SCHEMA_COLLECTION_PHASES)
DEPENDENCY_ACQUISITION_FILE = 'dependency-acquisition.json'
DEPENDENCY_ACQUISITION_TASK = ':ciAcquireDependencyFiles'
MIGRATION_FROZEN_INPUTS = (
    '.ci/toolchain.properties', 'version.properties', 'android/settings.gradle.kts',
    'android/build.gradle.kts', 'android/app/build.gradle.kts', 'android/gradle.properties',
    'android/gradle/libs.versions.toml', 'android/gradlew', 'android/gradlew.bat',
    'android/gradle/wrapper/gradle-wrapper.properties', 'android/gradle/wrapper/gradle-wrapper.jar',
    'android/' + ROOM_DATABASE_SOURCE, 'scripts/ci/enforce.gradle', 'scripts/ci/build_process.py',
)
MIGRATION_TOOLS = ('scripts/ci/build.py', 'scripts/ci/dependency-acquire.gradle')
DEPENDENCY_VARIANT_MIGRATION_KIND = 'owner-pr-dependency-variant-acquire-strict'
DEPENDENCY_VARIANT_ACQUIRE_PHASES = tuple('migration-acquire-' + mode for mode in ('debug', 'release', 'diagnostic'))
DEPENDENCY_VARIANT_MIGRATION_PHASES = (*DEPENDENCY_VARIANT_ACQUIRE_PHASES, *ROOM_SCHEMA_COLLECTION_PHASES)
DEPENDENCY_VARIANT_ACQUISITION_TASK = ':ciAcquireVariantDependencyFiles'
DEPENDENCY_VARIANT_MERGED_FILE = 'dependency-variant-acquisition.json'
# v5 pins the revised monitor as a migration tool; v4 keeps its historical base-byte contract.
MIGRATION_VARIANT_FROZEN_INPUTS = tuple(name for name in MIGRATION_FROZEN_INPUTS
                                      if name != 'scripts/ci/build_process.py')
MIGRATION_VARIANT_TOOLS = ('scripts/ci/build.py', 'scripts/ci/build_process.py',
                         'scripts/ci/dependency-acquire-variants.gradle')
DEPENDENCY_VARIANT_MIGRATION_SECONDS = 90 * 60


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
    environment.pop('CI_DEPENDENCY_VARIANT_REQUEST_SHA256', None)
    environment.pop('CI_DEPENDENCY_VARIANT_PREDECESSORS', None)
    if diagnostic_phase is not None:
        require(unsigned and os.environ.get('GITHUB_EVENT_NAME') == 'pull_request' and
                diagnostic_phase in DEPENDENCY_DIAGNOSTIC_PHASES,
                'Dependency diagnostics require an unsigned PR bootstrap phase')
        environment['CI_DEPENDENCY_DIAGNOSTIC_PHASE'] = diagnostic_phase
    return environment


def gradle_command(tasks):
    command = ['bash', './gradlew', '--no-daemon', '--stacktrace', '--console=plain',
               '--dependency-verification=strict', '--init-script', '../scripts/ci/enforce.gradle', *tasks]
    # The paired Test stage needs repository failures that a plugin stack trace omits.
    if ':app:testWatchAndroidDebugUnitTest' in command and ':companion:testPhoneAndroidDebugUnitTest' in command:
        command.append('--info')
    return command


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


def current_room_schema():
    inputs = required_build_inputs()
    return inputs[-1] if len(inputs) > len(DEPENDENCY_INPUTS) else None


def schema_project_path(relative):
    require(isinstance(relative, str), 'Schema-only paths must be canonical relative paths')
    name = PurePosixPath(relative)
    require(not name.is_absolute() and name.as_posix() == relative and '\\' not in relative and
            all(part not in ('', '.', '..') for part in name.parts),
            'Schema-only paths must be canonical relative paths')
    require(not (ROOT / 'android').is_symlink(), 'Schema-only Android root must not be a symbolic link')
    android = ROOT / 'android'
    source = android.joinpath(*name.parts)
    require(source.resolve().is_relative_to(android.resolve()) and not source.is_symlink() and
            not any(parent.is_symlink() for parent in source.parents if parent.is_relative_to(android)),
            'Schema-only paths must stay within the project without symbolic links')
    return source


def schema_input_path(relative):
    source = schema_project_path(relative)
    require(source.is_file(), 'Schema-only inputs must be regular files within the Android project')
    return source


def schema_report_path(relative=None):
    report = schema_project_path(ROOM_SCHEMA_REPORT_DIRECTORY)
    require(not report.exists() or report.is_dir(), 'Schema-only report root must be a directory')
    if relative is None:
        return report
    target = schema_project_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + relative)
    require(target.is_relative_to(report) and (not target.exists() or target.is_file()),
            'Schema-only report destination must be a regular file within the report')
    return target


def schema_checked_input_hashes():
    # Validate every parent before hashing, then recheck the complete set at each strict boundary.
    directory = schema_project_path(ROOM_SCHEMA_DIRECTORY)
    require(not directory.exists() or directory.is_dir(), 'Schema-only schema root must be a directory')
    for relative in DEPENDENCY_REPORT_FILES:
        candidate = schema_project_path(relative)
        if candidate.exists():
            schema_input_path(relative)
    files = generated_input_hashes()
    for relative, digest in files.items():
        data = schema_input_path(relative).read_bytes()
        require(hashlib.sha256(data).hexdigest() == digest,
                'Schema-only input bytes changed during the path checkpoint')
    return files


def reviewed_schema_inputs(current, missing):
    require(current is not None and missing == [current],
            'Schema-only collection requires exactly the missing current Room schema')
    path = schema_project_path(current)
    require(not path.exists(),
            'Schema-only collection cannot consume an existing unreviewed current schema')
    files = schema_checked_input_hashes()
    require(all(name in files for name in required_build_inputs() if name != current),
            'Schema-only collection requires the committed dependency inputs and old Room schemas')
    # Optional root locks and every old schema are part of the frozen set, including symlink/error cases.
    for relative in dict.fromkeys((*DEPENDENCY_REPORT_FILES, *files)):
        candidate = ROOT / 'android' / relative
        if not candidate.exists() and not candidate.is_symlink():
            continue
        source = schema_input_path(relative)
        data = source.read_bytes()
        require(hashlib.sha256(data).hexdigest() == files.get(relative),
                'Schema-only inputs changed before collection')
        blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
        require(git('rev-parse', '--verify', 'HEAD:android/' + relative, cwd=ROOT) == blob,
                'Schema-only inputs must match the committed HEAD bytes')
    return files


def bootstrap_room_schema(build_sha, missing, total_deadline=None):
    """Only a new schema is generated; strict dependency failures never enter a writer."""
    require(os.environ.get('GITHUB_EVENT_NAME') == 'pull_request',
            'Schema-only collection requires an unsigned PR bootstrap')
    no_signing_secrets()
    android = ROOT / 'android'
    current = current_room_schema()
    reviewed = reviewed_schema_inputs(current, missing)
    total_deadline = time.monotonic() + TOTAL_BUILD_SECONDS if total_deadline is None else total_deadline
    tasks = [task for mode in ('debug', 'release', 'diagnostic') for task in gradle_tasks(mode)]
    phases = [{'name': name, 'command': gradle_command(gradle_tasks(mode)) + ['--info']}
              for mode, name in zip(('debug', 'release', 'diagnostic'), ROOM_SCHEMA_COLLECTION_PHASES)]
    report = schema_report_path()
    require(not report.exists() or not any(report.iterdir()), 'Schema-only report directory must be empty')
    report.mkdir(parents=True, exist_ok=True)
    schema_report_path()
    strict_input_hashes, exports, completed = None, {}, False
    primary_failure = None
    try:
        for phase in phases:
            phase['status'] = 'started'
            schema_report_path()
            if strict_input_hashes is not None:
                require(schema_checked_input_hashes() == strict_input_hashes,
                        'Schema-only inputs changed between strict variants')
                # Only this run's new, unreviewed export is removed. Each variant must export its own bytes.
                schema_input_path(current).unlink()
            require(schema_checked_input_hashes() == reviewed,
                    'Schema-only collection changed reviewed dependency or old schema bytes')
            run_gradle(phase['command'], cwd=android, phase=phase['name'], build_sha=build_sha,
                       total_deadline=total_deadline,
                       env=gradle_environment(unsigned=True, diagnostic_phase=phase['name']))
            after = schema_checked_input_hashes()
            require(all(name in after for name in required_build_inputs()),
                    'Schema-only strict variant did not export the required current schema')
            require({name: digest for name, digest in after.items() if name != current} == reviewed,
                    'Schema-only strict validation changed reviewed dependency or old schema bytes')
            if strict_input_hashes is None:
                strict_input_hashes = after
            require(after == strict_input_hashes, 'Strict variants exported different current Room schema bytes')
            export_path = 'schema-exports/' + phase['name'] + '/' + current.rsplit('/', 1)[-1]
            target = schema_report_path(export_path)
            target.parent.mkdir(parents=True, exist_ok=True)
            target = schema_report_path(export_path)
            data = schema_input_path(current).read_bytes()
            require(hashlib.sha256(data).hexdigest() == after[current],
                    'Current schema changed while preserving a strict variant export')
            with target.open('xb') as stream:
                stream.write(data)
            exports[phase['name']] = {'file': export_path, 'sha256': sha256(target)}
            phase['status'] = 'completed'
        completed = True
    except BaseException as error:
        primary_failure = error
        raise
    finally:
        # A failed call can leave files. They remain failure evidence; no fallback or import is attempted.
        try:
            schema_report_path()
            files, report_errors = {}, []
            try:
                names = (*DEPENDENCY_REPORT_FILES, *room_schema_hashes())
            except (ValueError, OSError):
                names = tuple(dict.fromkeys((*DEPENDENCY_REPORT_FILES, *reviewed, current)))
                report_errors.append('unsafe-or-unreadable-schema-output')
            for relative in names:
                source = android / relative
                if not source.exists() and not source.is_symlink():
                    continue
                try:
                    source = schema_input_path(relative)
                    target = schema_report_path(relative)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target = schema_report_path(relative)
                    shutil.copyfile(source, target)
                    files[relative] = sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + relative))
                except (ValueError, OSError):
                    report_errors.append('input-report-copy-failed')
            report_matches = files == strict_input_hashes if strict_input_hashes is not None else None
            exports_match = False
            if strict_input_hashes is not None and exports.keys() == set(ROOM_SCHEMA_COLLECTION_PHASES):
                try:
                    exports_match = all(sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' +
                                                                export['file'])) ==
                                        export['sha256'] == strict_input_hashes[current]
                                        for export in exports.values())
                except (ValueError, OSError):
                    report_errors.append('variant-export-check-failed')
            if completed and (not report_matches or not exports_match or report_errors):
                completed = False
            write_json(schema_report_path('bootstrap-manifest.json'), {
                'schema_version': 3, 'collection_kind': ROOM_SCHEMA_COLLECTION_KIND,
                'source_sha': os.environ.get('SOURCE_SHA', build_sha), 'build_sha': build_sha,
                'run_id': int(os.environ['GITHUB_RUN_ID']), 'run_attempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
                'event': os.environ['GITHUB_EVENT_NAME'], 'ref': os.environ['GITHUB_REF'], 'tasks': tasks,
                'phases': phases, 'current_schema': current, 'reviewed_input_hashes': reviewed,
                'schema_exports': exports, 'schema_exports_match': exports_match,
                'strict_input_hashes': strict_input_hashes,
                'report_matches_strict_inputs': report_matches, 'report_errors': report_errors,
                'status': 'generated-unreviewed' if completed else 'failed-partial',
                'missing_inputs_at_start': missing,
                'missing_outputs': [name for name in required_build_inputs() if name not in files],
                'files': files, 'accepted': False, 'apk_exported': False,
            })
        except Exception:
            if primary_failure is None:
                raise
            # Report availability is secondary to the exact natural failure or timeout already observed.
            primary_failure.add_note('Schema-only bootstrap report preservation failed')
    require(completed, 'Schema-only collection did not preserve complete strict input bytes in reports')
    require(False, 'Schema-only bootstrap is not an accepted build. Independently review and commit the '
            'generated Room schema from dependency-bootstrap reports, then rebuild strictly.')


def migration_json(data):
    def unique(pairs):
        value = {}
        for key, item in pairs:
            require(key not in value, 'Dependency migration JSON has duplicate keys')
            value[key] = item
        return value
    require(len(data) <= 4 * 1024 * 1024, 'Dependency migration JSON exceeds its bound')
    return json.loads(data, object_pairs_hook=unique,
                      parse_constant=lambda _: require(False, 'Dependency migration JSON must be finite'))


def migration_repo_file(relative):
    require(type(relative) is str, 'Dependency migration paths must be canonical')
    name = PurePosixPath(relative)
    require(not name.is_absolute() and name.as_posix() == relative and
            '\\' not in relative and all(part not in ('', '.', '..') for part in name.parts),
            'Dependency migration paths must be canonical')
    path = ROOT.joinpath(*name.parts)
    require(not ROOT.is_symlink() and path.is_file() and not path.is_symlink() and
            path.resolve().is_relative_to(ROOT.resolve()) and
            not any(parent.is_symlink() for parent in path.parents if parent.is_relative_to(ROOT)),
            'Dependency migration inputs must be regular files without linked parents')
    return path


def migration_committed_hash(relative, revision='HEAD'):
    data = migration_repo_file(relative).read_bytes()
    blob = hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
    require(git('rev-parse', '--verify', revision + ':' + relative, cwd=ROOT) == blob,
            'Dependency migration preimages must match the committed source bytes')
    return hashlib.sha256(data).hexdigest()


def migration_build_hashes(tools=MIGRATION_TOOLS):
    return {name: migration_committed_hash(name) for name in (*MIGRATION_FROZEN_INPUTS, *tools,
                                                            DEPENDENCY_MIGRATION_REQUEST)}


def dependency_acquire_tasks(diagnostic_android_test):
    tasks = [':app:prepareRobolectricSdk', ':app:prepareBundledSqliteHost']
    for variant in ('Debug', 'Release', 'Diagnostic'):
        tasks.extend(f':app:compileWatchAndroid{variant}{suffix}' for suffix in
                     ('Kotlin', 'JavaWithJavac', 'UnitTestKotlin', 'UnitTestJavaWithJavac'))
        tasks.append(f':app:lintWatchAndroid{variant}')
    if diagnostic_android_test:
        tasks.extend(':app:compileWatchAndroidDiagnosticAndroidTest' + suffix
                     for suffix in ('Kotlin', 'JavaWithJavac'))
    return tasks


def dependency_acquire_consumers(diagnostic_android_test):
    consumers = {name: 'task-input-archives' for name in dependency_acquire_tasks(diagnostic_android_test)}
    for variant in ('Debug', 'Release', 'Diagnostic'):
        for prefix, suffix in (('lintAnalyze', ''), ('lintAnalyze', 'UnitTest'), ('lintReport', '')):
            consumers[f':app:{prefix}WatchAndroid{variant}{suffix}'] = 'task-input-archives'
        consumers[f':app:testWatchAndroid{variant}UnitTest'] = 'test-classpath-archives'
    consumers.update({':app:' + name: 'configuration-artifacts' for name in
                      ('bundledSqliteHost', 'robolectricSdk', 'robolectricSdk30')})
    consumers.update({name: 'buildscript-artifacts' for name in
                      (':buildscript:classpath', ':app:buildscript:classpath')})
    return consumers


def dependency_migration_request(build_sha, mode, missing):
    cloud_only()
    no_signing_secrets()
    require(mode == 'debug' and os.environ.get('GITHUB_EVENT_NAME') == 'pull_request' and
            not os.environ.get('CANDIDATE_OPERATION'), 'Dependency migration requires an unsigned owner PR')
    for key, value in {'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch', 'GITHUB_ACTOR': 'OnlineMo',
                       'GITHUB_ACTOR_ID': '143324986', 'GITHUB_TRIGGERING_ACTOR': 'OnlineMo'}.items():
        require(os.environ.get(key) == value, 'Dependency migration requires the repository owner')
    source_sha = os.environ.get('SOURCE_SHA', '')
    require(re.fullmatch(r'[0-9a-f]{40}', source_sha) and re.fullmatch(r'[0-9a-f]{40}', build_sha) and
            os.environ.get('GITHUB_SHA') == build_sha and git('rev-parse', 'HEAD') == build_sha,
            'Dependency migration source/build binding is invalid')
    require(re.fullmatch(r'refs/pull/[1-9][0-9]*/merge', os.environ.get('GITHUB_REF', '')),
            'Dependency migration requires the PR merge ref')
    for key in ('GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT'):
        require(re.fullmatch(r'[1-9][0-9]{0,19}', os.environ.get(key, '')),
                'Dependency migration run identity is invalid')
    request_path = migration_repo_file(DEPENDENCY_MIGRATION_REQUEST)
    request_digest = migration_committed_hash(DEPENDENCY_MIGRATION_REQUEST)
    request = migration_json(request_path.read_bytes())
    require(type(request) is dict and set(request) == {
        'schema_version', 'kind', 'owner_login', 'owner_id', 'base_source_sha', 'current_schema',
        'diagnostic_android_test', 'reviewed_input_hashes', 'frozen_input_hashes', 'migration_tool_hashes',
        'allowed_dependency_outputs', 'runtime_components',
    } and type(request['schema_version']) is int and
        ((request['schema_version'] == 1 and request['kind'] == DEPENDENCY_MIGRATION_KIND) or
         (request['schema_version'] == 2 and request['kind'] == DEPENDENCY_VARIANT_MIGRATION_KIND)) and
        request['owner_login'] == 'OnlineMo' and
        type(request['owner_id']) is int and request['owner_id'] == 143324986,
        'Dependency migration needs an explicit valid owner request')
    base = request['base_source_sha']
    require(type(base) is str and re.fullmatch(r'[0-9a-f]{40}', base), 'Dependency migration base SHA is invalid')
    git('merge-base', '--is-ancestor', base, source_sha, cwd=ROOT)
    git('merge-base', '--is-ancestor', source_sha, build_sha, cwd=ROOT)
    current = current_room_schema()
    require(type(current) is str and request['current_schema'] == current and
            git('ls-tree', '--name-only', '--full-tree', base, '--', 'android/' + current, cwd=ROOT) == '',
            'Dependency migration requires a genuinely absent current schema at the base')
    reviewed = reviewed_schema_inputs(current, missing)
    tools = MIGRATION_VARIANT_TOOLS if request['schema_version'] == 2 else MIGRATION_TOOLS
    frozen_inputs = MIGRATION_VARIANT_FROZEN_INPUTS if request['schema_version'] == 2 else MIGRATION_FROZEN_INPUTS
    for key, names in (('reviewed_input_hashes', reviewed), ('frozen_input_hashes', frozen_inputs),
                       ('migration_tool_hashes', tools)):
        values = request[key]
        require(type(values) is dict and set(values) == set(names) and
                all(type(value) is str and re.fullmatch(r'[0-9a-f]{64}', value) for value in values.values()),
                'Dependency migration request input hashes are invalid')
    require(request['reviewed_input_hashes'] == reviewed and
            request['allowed_dependency_outputs'] == list(DEPENDENCY_REPORT_FILES),
            'Dependency migration request preimages or output scope differ from the frozen source')
    for name, digest in reviewed.items():
        require(migration_committed_hash('android/' + name, base) == digest,
                'Dependency migration reviewed preimages differ from the base')
    for name, digest in request['frozen_input_hashes'].items():
        require(migration_committed_hash(name, base) == migration_committed_hash(name) == digest,
                'Dependency migration build inputs differ from the frozen base')
    for name, digest in request['migration_tool_hashes'].items():
        require(migration_committed_hash(name) == digest, 'Dependency migration tools differ from the owner request')
    instrumentation = schema_project_path('app/src/androidTest')
    require(type(request['diagnostic_android_test']) is bool and
            request['diagnostic_android_test'] == instrumentation.is_dir(),
            'Dependency migration instrumentation scope differs from the frozen source')
    runtime = request['runtime_components']
    require(type(runtime) is dict and set(runtime) == {'bundledSqliteHost', 'robolectricSdk', 'robolectricSdk30'} and
            all(type(parts) is list and len(parts) == 3 and all(type(part) is str and
                re.fullmatch(r'[A-Za-z0-9_.+\-]{1,120}', part) for part in parts) for parts in runtime.values()),
            'Dependency migration runtime coordinates are invalid')
    binding = {'source_sha': source_sha, 'build_sha': build_sha,
               'run_id': int(os.environ['GITHUB_RUN_ID']), 'run_attempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
               'event': 'pull_request', 'ref': os.environ['GITHUB_REF']}
    return request, request_digest, reviewed, binding


def verification_snapshot(data):
    require(len(data) <= 4 * 1024 * 1024 and b'<!' not in data, 'Dependency verification XML is unsafe or over bound')
    try:
        root = ET.fromstring(data)
    except ET.ParseError as error:
        raise ValueError('Dependency verification XML is malformed') from error
    namespace = '{https://schema.gradle.org/dependency-verification}'
    require(root.tag == namespace + 'verification-metadata' and
            [element.tag for element in root] == [namespace + 'configuration', namespace + 'components'],
            'Dependency verification XML structure differs from the reviewed contract')
    def tree(element):
        return element.tag, sorted(element.attrib.items()), (element.text or '').strip(), [tree(child) for child in element]
    configuration = root[0]
    require([element.tag for element in configuration] == [namespace + 'verify-metadata', namespace + 'verify-signatures'] and
            [(element.text or '').strip() for element in configuration] == ['true', 'false'],
            'Dependency verification cannot add a trust bypass or disable metadata checks')
    pins = {}
    for component in root[1]:
        require(component.tag == namespace + 'component' and set(component.attrib) == {'group', 'name', 'version'},
                'Dependency verification component is invalid')
        for artifact in component:
            require(artifact.tag == namespace + 'artifact' and set(artifact.attrib) == {'name'} and len(artifact) == 1 and
                    artifact[0].tag == namespace + 'sha256' and len(artifact[0]) == 0 and
                    re.fullmatch(r'[0-9a-f]{64}', artifact[0].get('value', '')),
                    'Dependency verification requires exactly one SHA-256 per artifact')
            key = (*(component.get(name) for name in ('group', 'name', 'version')), artifact.get('name'))
            require(key not in pins, 'Dependency verification has duplicate artifact pins')
            pins[key] = tree(artifact)
    require(pins, 'Dependency verification has no artifact pins')
    return tree(configuration), pins


def dependency_lock_snapshot(data):
    require(len(data) <= 4 * 1024 * 1024, 'Dependency lock exceeds its bound')
    configurations = {}
    for line in data.decode('utf-8').splitlines():
        if not line or line.startswith('#'):
            continue
        coordinate, separator, names = line.partition('=')
        require(separator and (coordinate == 'empty' or re.fullmatch(
            r'[A-Za-z0-9_.+\-]+:[A-Za-z0-9_.+\-]+:[A-Za-z0-9_.+\-]+', coordinate)),
            'Dependency lock coordinate is invalid')
        for name in names.split(','):
            require(re.fullmatch(r'[A-Za-z0-9_.\-]{1,200}', name), 'Dependency lock configuration is invalid')
            entries = configurations.setdefault(name, {})
            if coordinate != 'empty':
                module, version = coordinate.rsplit(':', 1)
                require(module not in entries, 'Dependency lock has duplicate module bindings')
                entries[module] = version
    require(configurations, 'Dependency lock has no configuration bindings')
    return configurations


def validate_dependency_acquisition(record, binding, request, locks):
    require(type(record) is dict and set(record) == {
        'schema_version', 'kind', 'phase', *binding, 'writer_tasks', 'test_task_count', 'consumers', 'lock_capture',
    } and type(record['schema_version']) is int and record['schema_version'] == 1 and
        record['kind'] == 'dependency-file-acquisition' and record['phase'] == 'migration-acquire' and
        type(record['test_task_count']) is int and record['test_task_count'] == 0,
        'Dependency acquisition must prove real files without running business tests')
    require(all(type(record[key]) is type(value) and record[key] == value for key, value in binding.items()),
            'Dependency acquisition source/run binding differs from this run')
    instrumentation = request['diagnostic_android_test']
    expected = dependency_acquire_consumers(instrumentation)
    optional = {f':app:lintAnalyzeWatchAndroid{variant}AndroidTest': 'task-input-archives'
                for variant in ('Debug', 'Release', 'Diagnostic')}
    require(record['writer_tasks'] == dependency_acquire_tasks(instrumentation), 'Dependency acquisition writer tasks differ')
    consumers = record['consumers']
    require(type(consumers) is list and len(consumers) <= 64 and
            all(type(value) is dict and type(value.get('id')) is str for value in consumers),
            'Dependency acquisition consumer records are invalid')
    observed = {value['id']: value for value in consumers}
    require(len(observed) == len(consumers) and set(expected) <= set(observed) <= set(expected) | set(optional),
            'Dependency acquisition has missing, duplicate or unapproved consumers')
    for name, value in observed.items():
        kind = (expected | optional)[name]
        fields = {'id', 'kind', 'files'}
        if kind in ('task-input-archives', 'test-classpath-archives'):
            fields |= {'file_count', 'directory_count', 'excluded_file_count', 'missing_non_archive_count'}
        if kind == 'task-input-archives':
            fields.add('outcome')
            outcome = value.get('outcome')
            require(outcome == 'executed' or
                    (outcome == 'no-source' and (name.endswith('JavaWithJavac') or name in optional)) or
                    (outcome == 'up-to-date' and name.startswith(':app:lintWatchAndroid')),
                    'Dependency acquisition did not execute the required compile or lint consumer')
        require(set(value) == fields and value['kind'] == kind and type(value['files']) is list and
                len(value['files']) <= 4096, 'Dependency acquisition file record shape is invalid')
        for item in value['files']:
            file_fields = {'name', 'size', 'sha256'}
            if kind in ('configuration-artifacts', 'buildscript-artifacts'):
                file_fields.add('component')
                parts = item.get('component') if type(item) is dict else None
                require(type(parts) is list and len(parts) == 3 and all(type(part) is str and
                        re.fullmatch(r'[A-Za-z0-9_.+\-]{1,120}', part) for part in parts),
                        'Dependency acquisition requires public Maven component identities')
            require(type(item) is dict and set(item) == file_fields and type(item['name']) is str and
                    re.fullmatch(r'[A-Za-z0-9_.+\-]{1,200}\.(jar|aar)', item['name']) and
                    type(item['size']) is int and 0 < item['size'] <= 256 * 1024 * 1024 and
                    type(item['sha256']) is str and re.fullmatch(r'[0-9a-f]{64}', item['sha256']),
                    'Dependency acquisition requires bounded hashes of actual archive bytes')
        if kind in ('task-input-archives', 'test-classpath-archives'):
            counts = [value[key] for key in ('file_count', 'directory_count', 'excluded_file_count', 'missing_non_archive_count')]
            require(all(type(count) is int and 0 <= count <= 4096 for count in counts) and
                    counts[0] == len(value['files']) + sum(counts[1:]), 'Dependency acquisition file counts differ')
            require(not (kind == 'test-classpath-archives' or name.endswith('Kotlin')) or value['files'],
                    'Dependency acquisition did not fetch compile or test runtime archives')
        if kind == 'configuration-artifacts':
            require(len(value['files']) == 1 and value['files'][0]['component'] ==
                    request['runtime_components'][name.removeprefix(':app:')],
                    'Dependency acquisition runtime bytes differ from the requested coordinates')
    require(observed[':buildscript:classpath']['files'], 'Dependency acquisition did not fetch plugin archives')
    capture = record['lock_capture']
    require(type(capture) is dict and set(capture) == {'kind', 'task', 'status', 'configuration_count',
                                                    'unresolved_dependency_count', 'project_configurations'} and
            capture['kind'] == 'all-resolvable-configuration-graphs' and capture['task'] == LOCK_COLLECTION_TASK and
            capture['status'] == 'completed' and type(capture['unresolved_dependency_count']) is int and
            capture['unresolved_dependency_count'] == 0 and type(capture['configuration_count']) is int and
            0 < capture['configuration_count'] <= 8192 and type(capture['project_configurations']) is dict and
            set(capture['project_configurations']) == {':', ':app'}, 'Dependency acquisition lock capture is incomplete')
    count = 0
    for project, names in capture['project_configurations'].items():
        require(type(names) is list and len(names) <= 4096 and
                all(type(name) is str and re.fullmatch(r'[A-Za-z0-9_.\-]{1,200}', name) for name in names) and
                names == sorted(set(names)), 'Dependency acquisition configuration inventory is invalid')
        count += len(names)
        if names:
            require(project in locks and set(names) <= dependency_lock_snapshot(locks[project]).keys(),
                    'Dependency acquisition did not preserve every resolved configuration lock')
    require(count == capture['configuration_count'], 'Dependency acquisition lock configuration count differs')


def preserve_migration_bytes(relative, data):
    target = schema_report_path(relative)
    require(not target.exists(), 'Dependency migration report destinations cannot be overwritten')
    target.parent.mkdir(parents=True, exist_ok=True)
    target = schema_report_path(relative)
    with target.open('xb') as stream:
        stream.write(data)
    require(sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + relative)) == hashlib.sha256(data).hexdigest(),
            'Dependency migration report bytes changed while preserving the original')
    return {'file': relative, 'sha256': hashlib.sha256(data).hexdigest()}


def dependency_variant_acquire_tasks(mode, diagnostic_android_test):
    require(mode in ('debug', 'release', 'diagnostic'), 'Dependency acquisition variant is invalid')
    tasks = [':app:prepareRobolectricSdk', ':app:prepareBundledSqliteHost']
    variant = mode.title()
    tasks.extend(f':app:compileWatchAndroid{variant}{suffix}' for suffix in
                 ('Kotlin', 'JavaWithJavac', 'UnitTestKotlin', 'UnitTestJavaWithJavac'))
    tasks.append(f':app:lintWatchAndroid{variant}')
    if mode == 'diagnostic' and diagnostic_android_test:
        tasks.extend(':app:compileWatchAndroidDiagnosticAndroidTest' + suffix
                     for suffix in ('Kotlin', 'JavaWithJavac'))
    return tasks


def dependency_variant_acquire_consumers(mode, diagnostic_android_test):
    consumers = {name: 'task-input-archives' for name in dependency_variant_acquire_tasks(mode, diagnostic_android_test)}
    variant = mode.title()
    for prefix, suffix in (('lintAnalyze', ''), ('lintAnalyze', 'UnitTest'), ('lintReport', '')):
        consumers[f':app:{prefix}WatchAndroid{variant}{suffix}'] = 'task-input-archives'
    consumers[f':app:testWatchAndroid{variant}UnitTest'] = 'test-classpath-archives'
    consumers.update({':app:' + name: 'configuration-artifacts' for name in
                      ('bundledSqliteHost', 'robolectricSdk', 'robolectricSdk30')})
    consumers.update({name: 'buildscript-artifacts' for name in
                      (':buildscript:classpath', ':app:buildscript:classpath')})
    return consumers


def dependency_variant_shared_consumers():
    return {':app:prepareRobolectricSdk', ':app:prepareBundledSqliteHost', ':app:bundledSqliteHost',
            ':app:robolectricSdk', ':app:robolectricSdk30', ':buildscript:classpath', ':app:buildscript:classpath'}


def dependency_variant_original(phase):
    require(phase in DEPENDENCY_VARIANT_ACQUIRE_PHASES, 'Dependency acquisition phase is invalid')
    return 'acquisitions/' + phase + '.json'


def dependency_variant_command(mode, diagnostic_android_test):
    tail = [DEPENDENCY_VARIANT_ACQUISITION_TASK]
    if mode == 'diagnostic':
        tail.append(LOCK_COLLECTION_TASK)
    return gradle_command([*dependency_variant_acquire_tasks(mode, diagnostic_android_test), *tail]) + [
        '--init-script', '../scripts/ci/dependency-acquire-variants.gradle', '--write-verification-metadata', 'sha256',
        '--write-locks', '--rerun-tasks', '--no-build-cache']


def dependency_variant_budget(records):
    return {'cumulative_archive_count': sum(value['archive_budget']['unique_archive_count'] for value in records.values()),
            'cumulative_archive_bytes': sum(value['archive_budget']['unique_archive_bytes'] for value in records.values()),
            'cumulative_consumer_count': sum(len(value['consumers']) for value in records.values())}


def validate_dependency_variant_acquisition(record, binding, request, request_digest, phase, predecessors, previous):
    fields = {'schema_version', 'kind', 'phase', 'variant', *binding, 'request_sha256', 'predecessor_acquisitions',
              'writer_tasks', 'test_task_count', 'graph_task_count', 'runtime_classpath', 'consumers', 'archive_budget',
              'file_capture'}
    mode = phase.removeprefix('migration-acquire-')
    require(phase in DEPENDENCY_VARIANT_ACQUIRE_PHASES and type(record) is dict and set(record) == fields and
            type(record['schema_version']) is int and record['schema_version'] == 2 and
            record['kind'] == 'dependency-variant-file-acquisition' and record['phase'] == phase and record['variant'] == mode and
            record['request_sha256'] == request_digest and record['predecessor_acquisitions'] == predecessors and
            type(record['predecessor_acquisitions']) is dict and
            all(type(value) is str and re.fullmatch(r'[0-9a-f]{64}', value) for value in predecessors.values()),
            'Dependency variant acquisition contract or predecessor binding differs')
    require(all(type(record[key]) is type(value) and record[key] == value for key, value in binding.items()) and
            type(record['test_task_count']) is int and record['test_task_count'] == 0 and
            type(record['graph_task_count']) is int and 0 < record['graph_task_count'] <= 4096 and
            record['writer_tasks'] == dependency_variant_acquire_tasks(mode, request['diagnostic_android_test']) and
            record['file_capture'] == {'task': DEPENDENCY_VARIANT_ACQUISITION_TASK, 'phase': phase, 'status': 'completed'},
            'Dependency variant acquisition did not complete its selected Test-free file stage')
    runtime = record['runtime_classpath']
    test_id = f':app:testWatchAndroid{mode.title()}UnitTest'
    require(type(runtime) is dict and set(runtime) == {'consumer_id', 'producer_tasks'} and runtime['consumer_id'] == test_id and
            type(runtime['producer_tasks']) is list and 0 < len(runtime['producer_tasks']) <= 4096,
            'Dependency variant acquisition has no real Test.classpath producer inventory')
    producer_ids = []
    for producer in runtime['producer_tasks']:
        require(type(producer) is dict and set(producer) == {'id', 'outcome'} and type(producer['id']) is str and
                re.fullmatch(r':app:[A-Za-z0-9_.+\-]{1,180}', producer['id']) and producer['id'] != test_id and
                producer['outcome'] in ('executed', 'up-to-date', 'no-source') and
                not any('WatchAndroid' + variant in producer['id'] for variant in ('Debug', 'Release', 'Diagnostic')
                        if variant != mode.title()), 'Dependency variant acquisition has an uncompleted or foreign producer')
        producer_ids.append(producer['id'])
    require(producer_ids == sorted(set(producer_ids)) and len(producer_ids) <= record['graph_task_count'],
            'Dependency variant acquisition producer inventory is duplicate or inconsistent')
    expected = dependency_variant_acquire_consumers(mode, request['diagnostic_android_test'])
    optional = {f':app:lintAnalyzeWatchAndroid{mode.title()}AndroidTest': 'task-input-archives'}
    consumers = record['consumers']
    require(type(consumers) is list and 0 < len(consumers) <= 64 and
            all(type(value) is dict and type(value.get('id')) is str for value in consumers),
            'Dependency variant acquisition consumer records are invalid')
    observed = {value['id']: value for value in consumers}
    require([value['id'] for value in consumers] == sorted(observed) and len(observed) == len(consumers) and
            set(expected) <= set(observed) <= set(expected) | set(optional),
            'Dependency variant acquisition has missing, duplicate or foreign consumers')
    all_archives = []
    for name, value in observed.items():
        kind = (expected | optional)[name]
        file_fields = {'id', 'kind', 'files'}
        if kind in ('task-input-archives', 'test-classpath-archives'):
            file_fields |= {'file_count', 'directory_count', 'excluded_file_count', 'missing_non_archive_count'}
        if kind == 'task-input-archives':
            file_fields.add('outcome')
            outcome = value.get('outcome')
            require(outcome == 'executed' or
                    (outcome == 'no-source' and (name.endswith('JavaWithJavac') or name in optional)) or
                    (outcome == 'up-to-date' and name.startswith(':app:lintWatchAndroid')),
                    'Dependency variant acquisition did not execute its required compile or lint consumer')
        require(set(value) == file_fields and value['kind'] == kind and type(value['files']) is list and
                len(value['files']) <= 4096, 'Dependency variant acquisition file record shape is invalid')
        for item in value['files']:
            archive_fields = {'name', 'size', 'sha256'}
            if kind in ('configuration-artifacts', 'buildscript-artifacts'):
                archive_fields.add('component')
                parts = item.get('component') if type(item) is dict else None
                require(type(parts) is list and len(parts) == 3 and all(type(part) is str and
                        re.fullmatch(r'[A-Za-z0-9_.+\-]{1,120}', part) for part in parts),
                        'Dependency variant acquisition requires public Maven identities')
            require(type(item) is dict and set(item) == archive_fields and type(item['name']) is str and
                    re.fullmatch(r'[A-Za-z0-9_.+\-]{1,200}\.(jar|aar)', item['name']) and
                    type(item['size']) is int and 0 < item['size'] <= 256 * 1024 * 1024 and
                    type(item['sha256']) is str and re.fullmatch(r'[0-9a-f]{64}', item['sha256']),
                    'Dependency variant acquisition requires bounded actual archive hashes')
            all_archives.append((item['name'], item['size'], item['sha256']))
        require(value['files'] == sorted(value['files'], key=lambda item: json.dumps(item, sort_keys=True, separators=(',', ':'))),
                'Dependency variant acquisition archive inventory is not canonical')
        if kind in ('task-input-archives', 'test-classpath-archives'):
            counts = [value[key] for key in ('file_count', 'directory_count', 'excluded_file_count', 'missing_non_archive_count')]
            require(all(type(count) is int and 0 <= count <= 4096 for count in counts) and
                    counts[0] == len(value['files']) + sum(counts[1:]), 'Dependency variant acquisition file counts differ')
            require(not (kind == 'test-classpath-archives' or name.endswith('Kotlin')) or value['files'],
                    'Dependency variant acquisition did not fetch compiler or Test runtime archives')
        if kind == 'configuration-artifacts':
            require(len(value['files']) == 1 and value['files'][0]['component'] ==
                    request['runtime_components'][name.removeprefix(':app:')],
                    'Dependency variant acquisition runtime coordinates differ from the request')
    require(observed[':buildscript:classpath']['files'], 'Dependency variant acquisition did not fetch plugin archives')
    budget = record['archive_budget']
    require(type(budget) is dict and set(budget) == {'unique_archive_count', 'unique_archive_bytes',
            'cumulative_archive_count', 'cumulative_archive_bytes', 'cumulative_consumer_count'} and
            all(type(value) is int and value >= 0 for value in budget.values()),
            'Dependency variant acquisition reservation counters are invalid')
    distinct = set(all_archives)
    require(len(distinct) <= budget['unique_archive_count'] <= min(4096, len(all_archives)) and
            sum(value[1] for value in distinct) <= budget['unique_archive_bytes'] <=
            min(2 * 1024 * 1024 * 1024, sum(value[1] for value in all_archives)),
            'Dependency variant acquisition archive reservation is inconsistent')
    cumulative = dependency_variant_budget({**previous, phase: record})
    require(all(budget[key] == value for key, value in cumulative.items()) and
            cumulative['cumulative_archive_count'] <= 4096 and cumulative['cumulative_archive_bytes'] <= 4 * 1024 * 1024 * 1024 and
            cumulative['cumulative_consumer_count'] <= 64,
            'Dependency variant acquisition exceeded the shared archive or consumer reservation')


def dependency_variant_merged_consumers(records):
    merged = {}
    shared = dependency_variant_shared_consumers()
    for phase in DEPENDENCY_VARIANT_ACQUIRE_PHASES:
        for consumer in records[phase]['consumers']:
            name = consumer['id']
            if name in merged:
                require(name in shared and merged[name]['record'] == consumer,
                        'Dependency variant acquisition has an unexpected duplicate or shared consumer byte conflict')
                merged[name]['capture_phases'].append(phase)
            else:
                merged[name] = {'id': name, 'kind': consumer['kind'], 'capture_phases': [phase], 'record': consumer}
    require(len(merged) <= 64 and all(merged[name]['capture_phases'] == list(DEPENDENCY_VARIANT_ACQUIRE_PHASES)
                                    for name in shared), 'Dependency variant acquisition omitted a shared consumer capture')
    return [merged[name] for name in sorted(merged)]


def validate_dependency_variant_merge(record, binding, request_digest, acquisitions, records, locks):
    phase = DEPENDENCY_VARIANT_ACQUIRE_PHASES[-1]
    require(type(record) is dict and set(record) == {'schema_version', 'kind', 'phase', *binding, 'request_sha256',
            'variant_acquisitions', 'consumers', 'archive_budget', 'lock_capture'} and
            type(record['schema_version']) is int and record['schema_version'] == 2 and
            record['kind'] == 'dependency-variant-acquisition-merge' and record['phase'] == phase and
            record['request_sha256'] == request_digest and
            all(type(record[key]) is type(value) and record[key] == value for key, value in binding.items()) and
            record['variant_acquisitions'] == acquisitions and set(records) == set(DEPENDENCY_VARIANT_ACQUIRE_PHASES),
            'Dependency variant acquisition has no correctly bound complete merge')
    require(json.dumps(record['consumers'], sort_keys=True, separators=(',', ':'), allow_nan=False) ==
            json.dumps(dependency_variant_merged_consumers(records), sort_keys=True, separators=(',', ':'), allow_nan=False) and
            type(record['archive_budget']) is dict and all(type(value) is int for value in record['archive_budget'].values()) and
            record['archive_budget'] == dependency_variant_budget(records),
            'Dependency variant acquisition merge discarded or changed a capture or reservation')
    capture = record['lock_capture']
    require(type(capture) is dict and set(capture) == {'kind', 'task', 'phase', 'preceding_file_capture_task', 'status',
            'configuration_count', 'unresolved_dependency_count', 'project_configurations'} and
            capture['kind'] == 'all-resolvable-configuration-graphs' and capture['task'] == LOCK_COLLECTION_TASK and
            capture['phase'] == phase and capture['preceding_file_capture_task'] == DEPENDENCY_VARIANT_ACQUISITION_TASK and
            capture['status'] == 'completed' and type(capture['unresolved_dependency_count']) is int and
            capture['unresolved_dependency_count'] == 0 and type(capture['configuration_count']) is int and
            0 < capture['configuration_count'] <= 8192 and type(capture['project_configurations']) is dict and
            set(capture['project_configurations']) == {':', ':app'}, 'Dependency variant acquisition full collector is incomplete')
    count = 0
    for project, names in capture['project_configurations'].items():
        require(type(names) is list and len(names) <= 4096 and
                all(type(name) is str and re.fullmatch(r'[A-Za-z0-9_.\-]{1,200}', name) for name in names) and
                names == sorted(set(names)), 'Dependency variant acquisition full configuration inventory is invalid')
        count += len(names)
        if names:
            require(project in locks and set(names) <= dependency_lock_snapshot(locks[project]).keys(),
                    'Dependency variant acquisition full collector omitted a configuration lock')
    require(count == capture['configuration_count'], 'Dependency variant acquisition configuration count differs')


def validate_dependency_variant_accumulation(previous, updated):
    require(set(previous) <= set(updated), 'Dependency variant writer removed an acquired input')
    old_configuration, old_pins = verification_snapshot(previous[DEPENDENCY_INPUTS[0]])
    new_configuration, new_pins = verification_snapshot(updated[DEPENDENCY_INPUTS[0]])
    require(old_configuration == new_configuration and all(new_pins.get(key) == value for key, value in old_pins.items()),
            'Dependency variant writer removed or changed a previously captured verification pin')
    for name, data in previous.items():
        if name in ('app/gradle.lockfile', 'gradle.lockfile'):
            before = dependency_lock_snapshot(data)
            after = dependency_lock_snapshot(updated[name])
            # Keep even empty configurations, and every old module/version binding inside them.
            # An acquired dependency may add a module to an existing configuration.
            require(all(key in after and all(after[key].get(module) == version for module, version in modules.items())
                        for key, modules in before.items()),
                    'Dependency variant writer changed a previously captured configuration lock binding')
        elif name not in DEPENDENCY_REPORT_FILES:
            require(updated[name] == data, 'Dependency variant writer changed an old schema')


def bootstrap_dependency_variant_migration(build_sha, missing, request, request_digest, reviewed, binding, total_deadline):
    """v5 consumes one real variant per writer process; only the last process captures the full lock graph."""
    frozen = migration_build_hashes(MIGRATION_VARIANT_TOOLS)
    current = request['current_schema']
    android = ROOT / 'android'
    writer_tasks = {phase: dependency_variant_acquire_tasks(mode, request['diagnostic_android_test'])
                    for phase, mode in zip(DEPENDENCY_VARIANT_ACQUIRE_PHASES, ('debug', 'release', 'diagnostic'))}
    tasks = [task for mode in ('debug', 'release', 'diagnostic') for task in gradle_tasks(mode)]
    phases = [*({'name': phase, 'command': dependency_variant_command(mode, request['diagnostic_android_test']), 'status': 'pending'}
                for phase, mode in zip(DEPENDENCY_VARIANT_ACQUIRE_PHASES, ('debug', 'release', 'diagnostic'))),
              *({'name': phase, 'command': gradle_command(gradle_tasks(mode)) + ['--info'], 'status': 'pending'}
                for phase, mode in zip(ROOM_SCHEMA_COLLECTION_PHASES, ('debug', 'release', 'diagnostic')))]
    report = schema_report_path()
    require(not report.exists() or not any(report.iterdir()), 'Dependency variant migration report directory must be empty')
    report.mkdir(parents=True, exist_ok=True)
    schema_report_path()
    completed, primary_failure = False, None
    acquired, strict_inputs, merged = None, None, None
    acquisitions, records, checkpoints, writer_exports, exports, preimages, preserved = {}, {}, {}, {}, {}, {}, {}
    previous_bytes = {name: schema_input_path(name).read_bytes() for name in reviewed}
    expected = reviewed

    def preserve(relative, data):
        entry = preserve_migration_bytes(relative, data)
        preserved[relative] = entry['sha256']
        return entry

    def originals_match():
        return all(sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + relative)) == digest
                   for relative, digest in preserved.items())

    try:
        preserve('dependency-migration-request.json', migration_repo_file(DEPENDENCY_MIGRATION_REQUEST).read_bytes())
        for name, data in previous_bytes.items():
            require(hashlib.sha256(data).hexdigest() == reviewed[name], 'Dependency variant migration preimage changed')
            preimages[name] = preserve('reviewed-inputs/' + name, data)
        for phase in phases:
            name = phase['name']
            schema_report_path()
            require(migration_build_hashes(MIGRATION_VARIANT_TOOLS) == frozen,
                    'Dependency variant migration build/request inputs changed between calls')
            require(schema_checked_input_hashes() == expected and originals_match(),
                    'Dependency variant migration input set or saved original changed between calls')
            if current in expected:
                # The complete checkpoint above proves this file was generated by this run, never an old committed schema.
                require(bool(writer_exports or exports), 'Dependency variant migration cannot remove an unproved current schema')
                schema_input_path(current).unlink()
                expected = {key: value for key, value in expected.items() if key != current}
                require(schema_checked_input_hashes() == expected, 'Dependency variant migration inputs changed while removing its export')
            phase['status'] = 'started'
            if name in DEPENDENCY_VARIANT_ACQUIRE_PHASES:
                environment = gradle_environment(unsigned=True)
                environment['CI_DEPENDENCY_VARIANT_REQUEST_SHA256'] = request_digest
                environment['CI_DEPENDENCY_VARIANT_PREDECESSORS'] = json.dumps(
                    {key: entry['sha256'] for key, entry in acquisitions.items()}, sort_keys=True, separators=(',', ':'))
            else:
                require(acquired is not None and merged is not None, 'Dependency variant migration cannot enter STRICT without its complete merge')
                environment = gradle_environment(unsigned=True, diagnostic_phase=name)
            run_gradle(phase['command'], cwd=android, phase=name, build_sha=build_sha,
                       total_deadline=total_deadline, overall_limit_seconds=DEPENDENCY_VARIANT_MIGRATION_SECONDS,
                       env=environment)
            require(migration_build_hashes(MIGRATION_VARIANT_TOOLS) == frozen and originals_match(),
                    'Dependency variant migration frozen inputs or saved originals changed during a call')
            after = schema_checked_input_hashes()
            require(all(key in after for key in required_build_inputs() if key != current) and
                    set(after) <= set(reviewed) | set(DEPENDENCY_REPORT_FILES) | {current} and
                    all(after.get(key) == digest for key, digest in reviewed.items() if key not in DEPENDENCY_REPORT_FILES),
                    'Dependency variant migration changed an old schema or added an unrequested input')
            if name in DEPENDENCY_VARIANT_ACQUIRE_PHASES:
                witness_relative = dependency_variant_original(name)
                witness_bytes = schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + witness_relative).read_bytes()
                witness_record = migration_json(witness_bytes)
                validate_dependency_variant_acquisition(witness_record, binding, request, request_digest, name,
                    {key: entry['sha256'] for key, entry in acquisitions.items()}, records)
                now_bytes = {key: schema_input_path(key).read_bytes() for key in after if key != current}
                validate_dependency_variant_accumulation(previous_bytes, now_bytes)
                entry = {'file': witness_relative, 'sha256': hashlib.sha256(witness_bytes).hexdigest()}
                require(sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + witness_relative)) == entry['sha256'],
                        'Dependency variant migration acquisition original changed while validating')
                acquisitions[name], records[name] = entry, witness_record
                preserved[witness_relative] = entry['sha256']
                input_originals = {key: preserve('writer-checkpoints/' + name + '/' + key, data) for key, data in now_bytes.items()}
                input_hashes = {key: hashlib.sha256(data).hexdigest() for key, data in now_bytes.items()}
                schema_export = None
                if current in after:
                    schema_export = preserve('schema-exports/' + name + '/' + current.rsplit('/', 1)[-1], schema_input_path(current).read_bytes())
                    require(schema_export['sha256'] == after[current] and
                            all(value['sha256'] == after[current] for value in writer_exports.values()),
                            'Dependency variant migration writer exports differ across processes')
                    writer_exports[name] = schema_export
                checkpoints[name] = {'input_hashes': input_hashes, 'input_originals': input_originals, 'schema_export': schema_export}
                previous_bytes = now_bytes
                if name == DEPENDENCY_VARIANT_ACQUIRE_PHASES[-1]:
                    merged_bytes = schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + DEPENDENCY_VARIANT_MERGED_FILE).read_bytes()
                    locks = {project: now_bytes[key] for project, key in ((':app', 'app/gradle.lockfile'), (':', 'gradle.lockfile')) if key in now_bytes}
                    validate_dependency_variant_merge(migration_json(merged_bytes), binding, request_digest, acquisitions, records, locks)
                    merged = {'file': DEPENDENCY_VARIANT_MERGED_FILE, 'sha256': hashlib.sha256(merged_bytes).hexdigest()}
                    preserved[DEPENDENCY_VARIANT_MERGED_FILE] = merged['sha256']
                    acquired = input_hashes
            else:
                require(current in after and {key: value for key, value in after.items() if key != current} == acquired,
                        'Dependency variant migration STRICT changed acquired inputs or omitted its schema')
                if strict_inputs is None:
                    strict_inputs = after
                require(after == strict_inputs and all(value['sha256'] == after[current] for value in writer_exports.values()),
                        'Dependency variant migration independent schema exports differ')
                exports[name] = preserve('schema-exports/' + name + '/' + current.rsplit('/', 1)[-1], schema_input_path(current).read_bytes())
                require(exports[name]['sha256'] == after[current], 'Dependency variant migration STRICT export changed while preserving')
            require(schema_checked_input_hashes() == after and migration_build_hashes(MIGRATION_VARIANT_TOOLS) == frozen and originals_match(),
                    'Dependency variant migration inputs or originals changed while preserving a checkpoint')
            expected = after
            phase['status'] = 'completed'
        completed = True
    except BaseException as error:
        primary_failure = error
        raise
    finally:
        try:
            schema_report_path()
            files, errors = {}, []
            try:
                observed = schema_checked_input_hashes()
            except (ValueError, OSError):
                observed = {}
                errors.append('unsafe-or-unreadable-variant-migration-inputs')
            for key in observed:
                try:
                    entry = preserve(key, schema_input_path(key).read_bytes())
                    files[key] = entry['sha256']
                except (ValueError, OSError):
                    errors.append('variant-migration-input-report-copy-failed')
            matches = files == strict_inputs if strict_inputs is not None else None
            originals_ok, exports_ok, final_checkpoint_ok = False, False, False
            try:
                originals_ok = (originals_match() and set(preimages) == set(reviewed) and
                    set(acquisitions) == set(checkpoints) == set(DEPENDENCY_VARIANT_ACQUIRE_PHASES) and merged is not None and
                    sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/dependency-migration-request.json')) == request_digest)
                exports_ok = (strict_inputs is not None and set(exports) == set(ROOM_SCHEMA_COLLECTION_PHASES) and
                    all(value['sha256'] == strict_inputs[current] for value in (*writer_exports.values(), *exports.values())))
                final_checkpoint_ok = (migration_build_hashes(MIGRATION_VARIANT_TOOLS) == frozen and
                                       schema_checked_input_hashes() == strict_inputs)
            except (ValueError, OSError):
                errors.append('variant-migration-final-original-or-input-check-failed')
            completed = completed and matches and originals_ok and exports_ok and final_checkpoint_ok and not errors
            write_json(schema_report_path('bootstrap-manifest.json'), {
                'schema_version': 5, 'collection_kind': DEPENDENCY_VARIANT_MIGRATION_KIND, **binding,
                'base_source_sha': request['base_source_sha'], 'request_sha256': request_digest,
                'frozen_input_hashes': request['frozen_input_hashes'], 'migration_tool_hashes': request['migration_tool_hashes'],
                'tasks': tasks, 'writer_tasks': writer_tasks, 'phases': phases, 'current_schema': current,
                'reviewed_input_hashes': reviewed, 'reviewed_preimages': preimages, 'variant_acquisitions': acquisitions,
                'writer_checkpoints': checkpoints, 'merged_acquisition': merged, 'acquired_input_hashes': acquired,
                'writer_schema_exports': writer_exports, 'schema_exports': exports, 'schema_exports_match': exports_ok,
                'strict_input_hashes': strict_inputs, 'report_matches_strict_inputs': matches, 'originals_match': originals_ok,
                'report_errors': errors, 'status': 'generated-unreviewed' if completed else 'failed-partial',
                'missing_inputs_at_start': missing, 'missing_outputs': [key for key in required_build_inputs() if key not in files],
                'files': files, 'accepted': False, 'apk_exported': False,
            })
        except Exception:
            if primary_failure is None:
                raise
            primary_failure.add_note('Dependency variant migration report preservation failed')
    require(completed, 'Dependency variant migration did not preserve complete STRICT inputs and originals')
    require(False, 'Dependency variant migration is not an accepted build. Independently review and commit the original '
            'dependency inputs and Room schema, remove the explicit request, then rebuild strictly.')


def bootstrap_dependency_migration(build_sha, mode, missing, total_deadline=None, *, total_started=None):
    """One explicit writer acquires files; three strict calls and external review still gate every input."""
    if total_deadline is None and total_started is None:
        total_started = time.monotonic()
    request, request_digest, reviewed, binding = dependency_migration_request(build_sha, mode, missing)
    # Select the exception only after the complete owner request passes, using the original build start.
    total_seconds = (DEPENDENCY_VARIANT_MIGRATION_SECONDS if request['kind'] == DEPENDENCY_VARIANT_MIGRATION_KIND
                     else TOTAL_BUILD_SECONDS)
    total_deadline = total_started + total_seconds if total_deadline is None else total_deadline
    if request['kind'] == DEPENDENCY_VARIANT_MIGRATION_KIND:
        return bootstrap_dependency_variant_migration(
            build_sha, missing, request, request_digest, reviewed, binding, total_deadline)
    frozen = migration_build_hashes()
    current = request['current_schema']
    android = ROOT / 'android'
    writer_tasks = dependency_acquire_tasks(request['diagnostic_android_test'])
    tasks = [task for variant in ('debug', 'release', 'diagnostic') for task in gradle_tasks(variant)]
    phases = [{'name': 'migration-acquire', 'command': gradle_command(
        [*writer_tasks, DEPENDENCY_ACQUISITION_TASK, LOCK_COLLECTION_TASK]) + [
            '--init-script', '../scripts/ci/dependency-acquire.gradle', '--write-verification-metadata', 'sha256',
            '--write-locks', '--rerun-tasks', '--no-build-cache'], 'status': 'pending'},
        *({'name': name, 'command': gradle_command(gradle_tasks(variant)) + ['--info'], 'status': 'pending'}
          for variant, name in zip(('debug', 'release', 'diagnostic'), ROOM_SCHEMA_COLLECTION_PHASES))]
    report = schema_report_path()
    require(not report.exists() or not any(report.iterdir()), 'Dependency migration report directory must be empty')
    report.mkdir(parents=True, exist_ok=True)
    schema_report_path()
    completed, primary_failure = False, None
    acquired, strict_inputs, acquisition, writer_export = None, None, None, None
    exports, preimages = {}, {}
    before_bytes = {name: schema_input_path(name).read_bytes() for name in reviewed}
    try:
        preserve_migration_bytes('dependency-migration-request.json', migration_repo_file(DEPENDENCY_MIGRATION_REQUEST).read_bytes())
        for name, data in before_bytes.items():
            require(hashlib.sha256(data).hexdigest() == reviewed[name], 'Dependency migration preimage changed')
            preimages[name] = preserve_migration_bytes('reviewed-inputs/' + name, data)
        for phase in phases:
            schema_report_path()
            require(migration_build_hashes() == frozen, 'Dependency migration build/request inputs changed between calls')
            if acquired is None:
                require(schema_checked_input_hashes() == reviewed, 'Dependency migration reviewed input set changed before the writer')
            else:
                expected = strict_inputs if strict_inputs is not None else acquired
                if writer_export is not None and strict_inputs is None:
                    expected = {**acquired, current: writer_export['sha256']}
                require(schema_checked_input_hashes() == expected, 'Dependency migration inputs changed between strict variants')
                if current in expected:
                    # Only an export created by this successful run is removed, after the complete checkpoint.
                    schema_input_path(current).unlink()
                require(schema_checked_input_hashes() == acquired, 'Dependency migration acquired inputs changed')
                require(sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + DEPENDENCY_ACQUISITION_FILE)) ==
                        acquisition['sha256'], 'Dependency migration acquisition original changed')
            phase['status'] = 'started'
            run_gradle(phase['command'], cwd=android, phase=phase['name'], build_sha=build_sha,
                       total_deadline=total_deadline, env=gradle_environment(unsigned=True,
                       diagnostic_phase=phase['name'] if phase['name'] != 'migration-acquire' else None))
            require(migration_build_hashes() == frozen, 'Dependency migration build/request inputs changed during a call')
            if acquisition is not None:
                require(sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + DEPENDENCY_ACQUISITION_FILE)) ==
                        acquisition['sha256'], 'Dependency migration acquisition original changed during a strict call')
            after = schema_checked_input_hashes()
            require(all(name in after for name in required_build_inputs() if name != current) and
                    set(after) <= set(reviewed) | set(DEPENDENCY_REPORT_FILES) | {current} and
                    all(after.get(name) == digest for name, digest in reviewed.items() if name not in DEPENDENCY_REPORT_FILES),
                    'Dependency migration changed old schemas or added unrequested inputs')
            if phase['name'] == 'migration-acquire':
                witness = schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + DEPENDENCY_ACQUISITION_FILE)
                witness_bytes = witness.read_bytes()
                record = migration_json(witness_bytes)
                locks = {project: schema_input_path(name).read_bytes() for project, name in
                         ((':app', 'app/gradle.lockfile'), (':', 'gradle.lockfile')) if name in after}
                validate_dependency_acquisition(record, binding, request, locks)
                old_configuration, old_pins = verification_snapshot(before_bytes[DEPENDENCY_INPUTS[0]])
                new_configuration, new_pins = verification_snapshot(schema_input_path(DEPENDENCY_INPUTS[0]).read_bytes())
                require(old_configuration == new_configuration and all(new_pins.get(key) == value for key, value in old_pins.items()),
                        'Dependency migration removed or changed an existing verification pin')
                for name in ('app/gradle.lockfile', 'gradle.lockfile'):
                    if name in before_bytes:
                        previous = dependency_lock_snapshot(before_bytes[name])
                        updated = dependency_lock_snapshot(schema_input_path(name).read_bytes())
                        require(all(updated.get(key) == value for key, value in previous.items()),
                                'Dependency migration changed an existing configuration lock binding')
                acquired = {name: digest for name, digest in after.items() if name != current}
                require(sha256(witness) == hashlib.sha256(witness_bytes).hexdigest(),
                        'Dependency migration acquisition original changed while validating its bytes')
                acquisition = {'file': DEPENDENCY_ACQUISITION_FILE, 'sha256': hashlib.sha256(witness_bytes).hexdigest()}
                if current in after:
                    writer_export = preserve_migration_bytes('schema-exports/migration-acquire/' + current.rsplit('/', 1)[-1],
                                                            schema_input_path(current).read_bytes())
                    require(writer_export['sha256'] == after[current], 'Dependency migration writer export changed while preserving its bytes')
            else:
                require(current in after and {name: digest for name, digest in after.items() if name != current} == acquired,
                        'Dependency migration strict validation changed dependency inputs or omitted the current schema')
                if strict_inputs is None:
                    strict_inputs = after
                require(after == strict_inputs and (writer_export is None or writer_export['sha256'] == after[current]),
                        'Dependency migration variants exported different current schema bytes')
                exports[phase['name']] = preserve_migration_bytes('schema-exports/' + phase['name'] + '/' + current.rsplit('/', 1)[-1],
                                                                schema_input_path(current).read_bytes())
                require(exports[phase['name']]['sha256'] == after[current],
                        'Dependency migration strict export changed while preserving its bytes')
            require(schema_checked_input_hashes() == after and migration_build_hashes() == frozen,
                    'Dependency migration inputs changed while preserving a phase original')
            phase['status'] = 'completed'
        completed = True
    except BaseException as error:
        primary_failure = error
        raise
    finally:
        try:
            schema_report_path()
            files, errors = {}, []
            try:
                observed = schema_checked_input_hashes()
            except (ValueError, OSError):
                observed = {}
                errors.append('unsafe-or-unreadable-migration-inputs')
            for name in observed:
                try:
                    copy = preserve_migration_bytes(name, schema_input_path(name).read_bytes())
                    files[name] = copy['sha256']
                except (ValueError, OSError):
                    errors.append('migration-input-report-copy-failed')
            matches = files == strict_inputs if strict_inputs is not None else None
            originals_match = False
            try:
                originals_match = (sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/dependency-migration-request.json')) ==
                    request_digest and set(preimages) == set(reviewed) and
                    all(sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + entry['file'])) == entry['sha256'] == reviewed[name]
                        for name, entry in preimages.items()) and acquisition is not None and
                    sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + acquisition['file'])) == acquisition['sha256'])
                exports_match = (strict_inputs is not None and set(exports) == set(ROOM_SCHEMA_COLLECTION_PHASES) and
                    all(sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + entry['file'])) ==
                        entry['sha256'] == strict_inputs[current] for entry in exports.values()) and
                    (writer_export is None or sha256(schema_input_path(ROOM_SCHEMA_REPORT_DIRECTORY + '/' + writer_export['file'])) ==
                        writer_export['sha256'] == strict_inputs[current]))
            except (ValueError, OSError):
                exports_match = False
                errors.append('migration-original-or-export-check-failed')
            checkpoint_matches = False
            try:
                checkpoint_matches = migration_build_hashes() == frozen and schema_checked_input_hashes() == strict_inputs
            except (ValueError, OSError):
                errors.append('migration-final-input-checkpoint-failed')
            if completed and (not matches or not originals_match or not exports_match or errors or not checkpoint_matches):
                completed = False
            write_json(schema_report_path('bootstrap-manifest.json'), {
                'schema_version': 4, 'collection_kind': DEPENDENCY_MIGRATION_KIND, **binding,
                'base_source_sha': request['base_source_sha'], 'request_sha256': request_digest,
                'frozen_input_hashes': request['frozen_input_hashes'], 'migration_tool_hashes': request['migration_tool_hashes'],
                'tasks': tasks, 'writer_tasks': writer_tasks, 'phases': phases, 'current_schema': current,
                'reviewed_input_hashes': reviewed, 'reviewed_preimages': preimages, 'acquisition': acquisition,
                'acquired_input_hashes': acquired, 'writer_schema_export': writer_export, 'schema_exports': exports,
                'schema_exports_match': exports_match, 'strict_input_hashes': strict_inputs,
                'report_matches_strict_inputs': matches, 'originals_match': originals_match, 'report_errors': errors,
                'status': 'generated-unreviewed' if completed else 'failed-partial', 'missing_inputs_at_start': missing,
                'missing_outputs': [name for name in required_build_inputs() if name not in files],
                'files': files, 'accepted': False, 'apk_exported': False,
            })
        except Exception:
            if primary_failure is None:
                raise
            primary_failure.add_note('Dependency migration report preservation failed')
    require(completed, 'Dependency migration did not preserve complete strict inputs and originals')
    require(False, 'Dependency migration is not an accepted build. Independently review and commit the original '
            'dependency inputs and Room schema, remove the explicit request, then rebuild strictly.')


def bootstrap_dependencies(build_sha, missing, total_deadline=None):
    """PR-only资料采集；无论生成成功与否，都不能导出可验收APK。"""
    android = ROOT / 'android'
    total_deadline = time.monotonic() + TOTAL_BUILD_SECONDS if total_deadline is None else total_deadline
    tasks = [task for mode in ('debug', 'release', 'diagnostic') for task in gradle_tasks(mode)]
    phases = [
        # Each cold variant has its own phase budget while all calls share the overall deadline.
        *({'name': 'verification-' + mode, 'command': gradle_command(gradle_tasks(mode)) +
           ['--write-verification-metadata', 'sha256', '--write-locks']}
          for mode in ('debug', 'release', 'diagnostic')),
        # 真实任务先消费正常图；末尾补齐配置，避免采集任务提前固定消费者的图。
        {'name': 'normal-lock-collection', 'command': gradle_command([*tasks, LOCK_COLLECTION_TASK]) + ['--write-locks']},
        # Task cache reasons are limited to unsigned strict bootstrap calls and bounded capture.
        *({'name': 'strict-' + mode, 'command': gradle_command(gradle_tasks(mode)) + ['--info']}
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
            'schema_version': 2, 'collection_kind': 'variant-verification-normal-lock-strict',
            'source_sha': os.environ.get('SOURCE_SHA', build_sha), 'build_sha': build_sha,
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
    from package_contract import has_phone
    if has_phone(ROOT):
        from package_build import build_packages
        return build_packages(mode, sys.modules[__name__])
    require(mode != 'candidate', 'A full candidate requires both Android applications')
    request_path = ROOT / DEPENDENCY_MIGRATION_REQUEST
    has_migration_request = request_path.exists() or request_path.is_symlink()
    if has_migration_request:
        require(mode == 'debug' and os.environ.get('GITHUB_EVENT_NAME') == 'pull_request' and
                not os.environ.get('CANDIDATE_OPERATION'), 'Dependency migration requires an unsigned owner PR')
    check(require_android=True, release_snapshot=mode != 'debug')
    lock = properties(ROOT / '.ci/toolchain.properties')
    version = properties(ROOT / 'version.properties')
    build_sha = git('rev-parse', 'HEAD')
    is_pr = os.environ['GITHUB_EVENT_NAME'] == 'pull_request'
    require(not is_pr or mode == 'debug', 'PR builds only temporary Debug APKs')
    if mode != 'debug':
        require(os.environ.get('CANDIDATE_OPERATION') == 'prepare-candidate', 'Use candidate preparation for optimized builds')
    tasks = gradle_tasks(mode)
    total_started = time.monotonic()
    total_deadline = total_started + TOTAL_BUILD_SECONDS
    has_instrumentation = mode == 'diagnostic' and (ROOT / 'android/app/src/androidTest').is_dir()
    missing = missing_dependency_inputs()
    if has_migration_request:
        bootstrap_dependency_migration(build_sha, mode, missing, total_started=total_started)
    if missing:
        require(is_pr and mode == 'debug',
                'Committed strict dependency inputs are missing: ' + ', '.join(missing) + '. '
                'Only a temporary Debug PR may bootstrap dependency reports; trusted builds cannot.')
        if missing == [current_room_schema()]:
            bootstrap_room_schema(build_sha, missing, total_deadline)
        else:
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
    parser.add_argument('mode', choices=['debug', 'release', 'diagnostic', 'candidate'])
    build(parser.parse_args().mode)
