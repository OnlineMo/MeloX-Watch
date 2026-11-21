"""Run staged Python control flow with a mock Gradle boundary and small data fixtures."""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import zipfile

import build
import package_build
import package_migration
import package_test_evidence
from ci_common import OWNER_EMAIL, write_json
from package_contract import DATABASE_SOURCE, PROTOCOL_FILES, SCHEMA_DIRECTORY
from test_package_contract import debug_test_fixture, xml


class PackageStageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.command('init')
        self.command('config', 'user.name', 'OnlineMo')
        self.command('config', 'user.email', OWNER_EMAIL)
        for name in package_migration.TOOLS:
            self.write(name, 'fixture tool data\n')
        for module in ('app', 'companion'):
            self.write('android/' + module + '/build.gradle.kts',
                       'getByName("main").java.srcDir("../shared-transfer/src/main/kotlin")\n')
            self.write('android/' + module + '/src/main/AndroidManifest.xml', '<manifest/>\n')
        for index, name in enumerate(PROTOCOL_FILES):
            self.write(name, 'const val SCHEMA = 2\n' if index == 0 else 'fixture source\n')
        self.write('android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/data/database/MeloXDatabase.kt',
                   'package io.github.onlinemo.meloxwatch.data.database\n'
                   '@Database(entities = [], version = 6, exportSchema = true)\n'
                   'abstract class MeloXDatabase : RoomDatabase()\n')
        self.write('android/settings.gradle.kts', 'include(":app", ":companion")\n')
        self.write('android/build.gradle.kts', 'fixture project data\n')
        self.write('android/gradle.properties', 'fixture=value\n')
        self.write('android/gradle/libs.versions.toml',
                   '[libraries]\nsqlite-bundled-host={module="x:host", version="1"}\n'
                   'robolectric-sdk={module="x:sdk", version="1"}\nrobolectric-sdk30={module="x:sdk30", version="1"}\n')
        self.write('version.properties', 'versionName=0.0.0\nversionCode=0\n')
        self.write('.ci/toolchain.properties', 'android.buildTools=35.0.0\n')
        self.write('.ci/signing-certificates.json', '{}\n')
        self.write('.ci/upstream-lock.json', '{}\n')
        self.write('android/gradle/verification-metadata.xml', xml())
        self.write('android/app/gradle.lockfile', 'x:old:1=debug\n')
        self.write('android/' + SCHEMA_DIRECTORY + '/1.json', 'schema one\n')
        self.write('android/' + SCHEMA_DIRECTORY + '/3.json', 'schema three\n')
        self.commit('fixture base')
        self.base = self.command('rev-parse', 'HEAD')
        self.legacy = MagicMock()
        self.legacy.ROOT = self.root
        self.legacy.gradle_command.side_effect = build.gradle_command
        self.legacy.gradle_environment.side_effect = build.gradle_environment
        self.legacy.run_gradle.side_effect = lambda command, **kwargs: self.debug_fixture(kwargs['env']) if kwargs['phase'] == 'package-test-debug' else None

    def tearDown(self):
        self.temporary.cleanup()

    def command(self, *args):
        result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false', *args],
                                cwd=self.root, check=True, capture_output=True, text=True)
        return result.stdout.strip()

    def write(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(data, bytes):
            path.write_bytes(data)
        else:
            path.write_text(data, encoding='utf-8', newline='\n')
        return path

    def commit(self, message):
        self.command('add', '.')
        self.command('commit', '-m', message)

    def environment(self):
        head = self.command('rev-parse', 'HEAD')
        return {'PATH': os.environ.get('PATH', ''), 'SYSTEMROOT': os.environ.get('SYSTEMROOT', ''),
                'GITHUB_ACTIONS': 'true', 'CI': 'true', 'GITHUB_EVENT_NAME': 'pull_request',
                'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch', 'GITHUB_ACTOR': 'OnlineMo',
                'GITHUB_ACTOR_ID': '143324986', 'GITHUB_TRIGGERING_ACTOR': 'OnlineMo',
                'GITHUB_SHA': head, 'SOURCE_SHA': head, 'GITHUB_REF': 'refs/pull/123/merge',
                'GITHUB_RUN_ID': '7', 'GITHUB_RUN_ATTEMPT': '1', 'GH_TOKEN': 'fixture-only'}

    def add_request(self):
        request = package_migration.make_request(self.root, self.base)
        self.write(package_build.REQUEST_FILE, json.dumps(request, indent=2) + '\n')
        self.commit('fixture explicit package request')
        return request

    def debug_fixture(self, environment):
        binding = json.loads(environment['CI_PACKAGE_DEBUG_BINDING'])
        raw, _ = debug_test_fixture(binding)
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            for role in ('watch', 'phone'):
                self.write(package_test_evidence.REPORT + '/' + role + '-task-result.json', archive.read(role + '/task-result.json'))
                self.write(package_test_evidence.junit_directory(role) + '/TEST-Fixture.xml', archive.read(role + '/junit/TEST-Fixture.xml'))

    def acquire_fixture(self, state, bad_lock=False):
        request = state['collection']['request']
        self.write('android/gradle/verification-metadata.xml', xml(True))
        self.write('android/app/gradle.lockfile', 'x:old:2=debug\n' if bad_lock else 'x:old:1=debug\n')
        self.write('android/companion/gradle.lockfile', 'x:old:1=debug\n')
        self.write('android/' + request['current_schema'], 'schema six\n')
        selected = package_migration.consumers(request)
        records = []
        for name, kind in sorted(selected.items()):
            entry = {'id': name, 'kind': kind, 'files': [{'name': 'fixture.jar', 'size': 1, 'sha256': 'b' * 64}]}
            if kind in ('task-input-archives', 'test-classpath-archives'):
                entry.update(file_count=1, directory_count=0, excluded_file_count=0, missing_non_archive_count=0)
            if kind == 'task-input-archives':
                entry['outcome'] = 'executed'
            if kind in ('configuration-artifacts', 'buildscript-artifacts'):
                entry['files'][0]['component'] = request['runtime_components'][name.rsplit(':', 1)[1]] if kind == 'configuration-artifacts' else ['x', 'plugin', '1']
            records.append(entry)
        runtimes = []
        for name, kind in sorted(selected.items()):
            if kind == 'test-classpath-archives':
                project, task = name.rsplit(':', 1)
                runtimes.append({'consumer_id': name, 'producer_tasks': [{'id': project + ':' + task.replace('test', 'compile', 1) + 'Kotlin',
                                                                        'outcome': 'executed'}]})
        record = {'schema_version': 3, 'kind': 'dependency-package-file-acquisition', **state['binding'],
                  'request_sha256': state['collection']['request_sha256'], 'variants': request['variants'],
                  'writer_tasks': package_migration.writer_tasks(request), 'test_task_count': 0, 'graph_task_count': 200,
                  'consumers': records, 'runtime_classpaths': runtimes,
                  'archive_budget': {'unique_archive_count': 1, 'unique_archive_bytes': 1},
                  'lock_capture': {'task': package_migration.COLLECTOR, 'preceding_file_capture_task': package_migration.ACQUIRE_TASK,
                                   'status': 'completed', 'unresolved_dependency_count': 0, 'configuration_count': 2,
                                   'project_configurations': {':': [], ':app': ['debug'], ':companion': ['debug']}}}
        self.write(package_migration.REPORT + '/acquisition.json', json.dumps(record) + '\n')

    def test_explicit_acquire_then_strict_compile_preserves_v3_without_tests_or_apks(self):
        self.add_request()
        with patch.dict(os.environ, self.environment(), clear=True):
            state, digest = package_build.start('debug', self.root, self.legacy)
            def process(command, **kwargs):
                if kwargs['phase'] == 'package-acquire':
                    self.acquire_fixture(state)
                elif kwargs['phase'] == 'package-strict-inputs':
                    self.write('android/' + state['collection']['request']['current_schema'], 'schema six\n')
            self.legacy.run_gradle.side_effect = process
            digest = package_build.run_stage('acquire', digest, self.root, self.legacy)
            digest = package_build.run_stage('strict-inputs', digest, self.root, self.legacy)
            with self.assertRaisesRegex(ValueError, 'PACKAGE_INPUT_REPORT_ONLY_REJECTION'):
                package_build.run_stage('seal', digest, self.root, self.legacy)
        commands = [call.args[0] for call in self.legacy.run_gradle.call_args_list]
        self.assertEqual(len(commands), 2)
        self.assertEqual(commands[0].count('--continue'), 1)
        self.assertNotIn('--continue', commands[1])
        self.assertTrue(all(':companion:prepareRobolectricSdk' in command for command in commands))
        self.assertFalse(any(':test' in task or ':assemble' in task for command in commands for task in command))
        self.assertEqual((self.root / 'android/' / SCHEMA_DIRECTORY / '3.json').read_text(), 'schema three\n')
        report = json.loads((self.root / package_migration.REPORT / 'package-manifest.json').read_text())
        self.assertFalse(report['accepted'])
        self.assertFalse(report['apk_exported'])
        self.assertTrue(report['current_schema'].endswith('/6.json'))
        self.assertEqual(report['writer_schema_sha256'], report['strict_schema_sha256'])
        self.assertEqual(report['application_tests'], 'NOT_RUN')
        self.assertEqual(report['apk_assembly'], 'NOT_RUN')
        self.assertNotIn('debug_test_evidence', report)
        self.assertFalse((self.root / package_test_evidence.REPORT).exists())
        self.assertFalse((self.root / 'artifacts').exists())
        for call in self.legacy.run_gradle.call_args_list:
            self.assertNotIn('GH_TOKEN', call.kwargs['env'])
            self.assertEqual(call.kwargs['overall_limit_seconds'], 1800)
            self.assertEqual(call.kwargs['env'].get('CI_PACKAGE_FRESH_ROOM_SCHEMA'),
                             'true' if call.kwargs['phase'] == 'package-strict-inputs' else None)

    def test_acquire_failure_keeps_partial_diagnostics_without_qualifying_or_starting_strict(self):
        self.add_request()
        original = subprocess.CalledProcessError(1, ['fixture-independent-writer'])
        partial_schema = b'fixture partial schema, never admitted\n'
        watch_report = b'fixture watch compiler failure\n'
        phone_report = b'fixture phone lint failure\n'
        with patch.dict(os.environ, self.environment(), clear=True):
            state, digest = package_build.start('debug', self.root, self.legacy)
            def process(command, **kwargs):
                self.assertIn('--continue', command)
                self.assertEqual(kwargs['phase'], 'package-acquire')
                self.write('android/' + state['collection']['request']['current_schema'], partial_schema)
                self.write('android/app/build/reports/fixture-compiler.txt', watch_report)
                self.write('android/companion/build/reports/fixture-lint.txt', phone_report)
                raise original
            self.legacy.run_gradle.side_effect = process
            with self.assertRaises(subprocess.CalledProcessError) as caught:
                package_build.run_stage('acquire', digest, self.root, self.legacy)
            self.assertIs(caught.exception, original)
            session = self.root / package_build.SESSION_FILE
            failed = json.loads(session.read_bytes())
            self.assertEqual(failed['status'], 'failed')
            self.assertEqual(failed['completed'], [])
            self.assertIsNone(failed['collection']['acquired_input_hashes'])
            report = json.loads((self.root / package_migration.REPORT / 'failure-manifest.json').read_bytes())
            self.assertEqual(report['failed_stage'], 'acquire')
            self.assertFalse(report['accepted'])
            self.assertFalse(report['apk_exported'])
            current = state['collection']['request']['current_schema']
            self.assertEqual(report['partial_input_hashes'][current], hashlib.sha256(partial_schema).hexdigest())
            with self.assertRaises(ValueError):
                package_build.run_stage('strict-inputs', hashlib.sha256(session.read_bytes()).hexdigest(), self.root, self.legacy)
        self.assertEqual((self.root / 'android/app/build/reports/fixture-compiler.txt').read_bytes(), watch_report)
        self.assertEqual((self.root / 'android/companion/build/reports/fixture-lint.txt').read_bytes(), phone_report)
        self.assertEqual(self.legacy.run_gradle.call_count, 1)
        self.assertFalse((self.root / package_migration.REPORT / 'package-manifest.json').exists())
        self.assertFalse((self.root / package_test_evidence.REPORT).exists())
        self.assertFalse((self.root / 'artifacts').exists())

    def test_old_request_contract_is_rejected_before_any_gradle(self):
        request = self.add_request()
        request['schema_version'] = 2
        self.write(package_build.REQUEST_FILE, json.dumps(request) + '\n')
        self.commit('fixture invalid request contract')
        with patch.dict(os.environ, self.environment(), clear=True), self.assertRaisesRegex(ValueError, 'v3'):
            package_build.start('debug', self.root, self.legacy)
        self.legacy.run_gradle.assert_not_called()

    def test_historical_room_source_is_bound_without_granting_a_json_export(self):
        name = 'android/app/src/test/kotlin/io/github/onlinemo/meloxwatch/data/database/HistoricalMeloXV4Database.kt'
        self.write(name, 'package io.github.onlinemo.meloxwatch.data.database\n'
                   '@Database(entities = [], version = 4, exportSchema = false)\n'
                   'abstract class HistoricalMeloXV4Database : RoomDatabase()\n')
        self.commit('fixture real historical declaration')
        request = self.add_request()
        declaration = request['room_declarations'][name]
        self.assertEqual(declaration['version'], 4)
        self.assertIs(declaration['export_schema'], False)
        self.assertIsNone(declaration['schema_output'])
        self.assertEqual(declaration['source_sha256'], hashlib.sha256((self.root / name).read_bytes()).hexdigest())
        with patch.dict(os.environ, self.environment(), clear=True):
            state, digest = package_build.start('debug', self.root, self.legacy)
            def process(command, **kwargs):
                self.acquire_fixture(state)
                self.write('android/app/schemas/io.github.onlinemo.meloxwatch.data.database.HistoricalMeloXV4Database/4.json',
                           'unexpected export under its original namespace\n')
            self.legacy.run_gradle.side_effect = process
            with self.assertRaisesRegex(ValueError, 'Unexpected Room schema namespace'):
                package_build.run_stage('acquire', digest, self.root, self.legacy)
        report = json.loads((self.root / package_migration.REPORT / 'failure-manifest.json').read_text())
        foreign = 'app/schemas/io.github.onlinemo.meloxwatch.data.database.HistoricalMeloXV4Database/4.json'
        self.assertEqual(report['unexpected_schema_outputs'], [foreign])
        self.assertEqual((self.root / package_migration.REPORT / 'failed-partial-inputs' / foreign).read_text(),
                         'unexpected export under its original namespace\n')
        self.assertFalse(report['accepted'])
        self.assertEqual(self.legacy.run_gradle.call_count, 1)

    def test_request_json_numeric_types_cannot_rebind_protocol_or_room_declarations(self):
        original = self.add_request()
        for kind in ('protocol-float', 'room-version-float', 'room-export-int'):
            broken = copy.deepcopy(original)
            if kind == 'protocol-float':
                broken['plan']['protocol']['schema'] = 2.0
            elif kind == 'room-version-float':
                broken['room_declarations'][DATABASE_SOURCE]['version'] = 6.0
            else:
                broken['room_declarations'][DATABASE_SOURCE]['export_schema'] = 1
            self.write(package_build.REQUEST_FILE, json.dumps(broken) + '\n')
            self.commit('fixture invalid nested type ' + kind)
            with self.subTest(kind=kind), patch.dict(os.environ, self.environment(), clear=True), self.assertRaisesRegex(ValueError, 'current committed source'):
                package_build.start('debug', self.root, self.legacy)
        self.legacy.run_gradle.assert_not_called()

    def test_rebound_old_lock_stops_before_the_debug_suite_and_retains_preimages(self):
        self.add_request()
        with patch.dict(os.environ, self.environment(), clear=True):
            state, digest = package_build.start('debug', self.root, self.legacy)
            self.legacy.run_gradle.side_effect = lambda command, **kwargs: self.acquire_fixture(state, bad_lock=True)
            with self.assertRaisesRegex(ValueError, 'lock bindings changed'):
                package_build.run_stage('acquire', digest, self.root, self.legacy)
        self.assertEqual(self.legacy.run_gradle.call_count, 1)
        self.assertEqual((self.root / package_migration.REPORT / 'reviewed-inputs/app/gradle.lockfile').read_bytes(), b'x:old:1=debug\n')
        failed = json.loads((self.root / package_migration.REPORT / 'failure-manifest.json').read_text())
        self.assertEqual(failed['status'], 'failed-partial')
        self.assertEqual(failed['failed_stage'], 'acquire')
        self.assertFalse(failed['accepted'])
        self.assertEqual((self.root / package_migration.REPORT / 'failed-partial-inputs/app/gradle.lockfile').read_bytes(), b'x:old:2=debug\n')
        self.assertFalse((self.root / 'artifacts').exists())

    def test_normal_build_rejects_uncommitted_xml_locks_and_all_schema_exports_before_gradle(self):
        self.write('android/companion/gradle.lockfile', 'x:old:1=debug\n')
        self.write('android/' + SCHEMA_DIRECTORY + '/6.json', 'schema six\n')
        self.commit('fixture admitted package inputs')
        names = ['android/gradle/verification-metadata.xml', 'android/app/gradle.lockfile',
                 'android/companion/gradle.lockfile', 'android/gradle.lockfile',
                 'android/' + SCHEMA_DIRECTORY + '/3.json', 'android/' + SCHEMA_DIRECTORY + '/6.json',
                 'android/' + SCHEMA_DIRECTORY + '/7.json']
        for name in names:
            path = self.root / name
            original = path.read_bytes() if path.exists() else None
            with self.subTest(name=name):
                try:
                    self.write(name, b'unreviewed prior output\n')
                    with patch.dict(os.environ, self.environment(), clear=True), self.assertRaisesRegex(ValueError, 'commit'):
                        package_build.start('debug', self.root, self.legacy)
                    self.assertFalse((self.root / package_build.SESSION_FILE).exists())
                finally:
                    if original is None:
                        path.unlink()
                    else:
                        path.write_bytes(original)
        self.legacy.run_gradle.assert_not_called()

    def test_untracked_current_schema_cannot_satisfy_normal_build_admission(self):
        self.write('android/companion/gradle.lockfile', 'x:old:1=debug\n')
        self.commit('fixture admitted companion lock only')
        self.write('android/' + SCHEMA_DIRECTORY + '/6.json', 'prior untracked schema six\n')
        with patch.dict(os.environ, self.environment(), clear=True), self.assertRaisesRegex(ValueError, 'frozen commit'):
            package_build.start('debug', self.root, self.legacy)
        self.legacy.run_gradle.assert_not_called()

    def test_original_timeout_survives_a_failure_receipt_write_error(self):
        self.add_request()
        original = TimeoutError('fixture original timeout')
        with patch.dict(os.environ, self.environment(), clear=True):
            state, digest = package_build.start('debug', self.root, self.legacy)
            self.legacy.run_gradle.side_effect = original
            with patch.object(package_migration, 'record_failure', side_effect=OSError('fixture receipt error')):
                with self.assertRaises(TimeoutError) as caught:
                    package_build.run_stage('acquire', digest, self.root, self.legacy)
            self.assertIs(caught.exception, original)
            self.assertEqual(json.loads((self.root / package_build.SESSION_FILE).read_text())['status'], 'failed')
        self.assertEqual(self.legacy.run_gradle.call_count, 1)

    def test_acquisition_record_rejects_malformed_coordinates_and_foreign_test_producers(self):
        self.add_request()
        with patch.dict(os.environ, self.environment(), clear=True):
            state, _ = package_build.start('debug', self.root, self.legacy)
        self.acquire_fixture(state)
        witness = json.loads((self.root / package_migration.REPORT / 'acquisition.json').read_text())
        validation_state = {**state, '_root': str(self.root)}
        package_migration.validate_acquisition(witness, validation_state)
        changed = []
        malformed = copy.deepcopy(witness)
        malformed['runtime_classpaths'][0] = True
        changed.append(malformed)
        for producer in (':companion:compilePhoneAndroidDebugUnitTestKotlin', ':app:testForeignUnitTest',
                         ':app:compileWatchAndroidReleaseUnitTestKotlin'):
            malformed = copy.deepcopy(witness)
            malformed['runtime_classpaths'][0]['producer_tasks'][0]['id'] = producer
            changed.append(malformed)
        malformed = copy.deepcopy(witness)
        malformed['consumers'][0]['files'][0]['component'] = ['x', True, '1']
        changed.append(malformed)
        malformed = copy.deepcopy(witness)
        malformed['archive_budget']['unique_archive_count'] = True
        changed.append(malformed)
        for index, record in enumerate(changed):
            with self.subTest(index=index), self.assertRaises(ValueError):
                package_migration.validate_acquisition(record, validation_state)
        self.legacy.run_gradle.assert_not_called()

    def test_both_sdk_archives_and_phone_preparation_are_mandatory_and_scoped(self):
        self.add_request()
        with patch.dict(os.environ, self.environment(), clear=True):
            state, _ = package_build.start('debug', self.root, self.legacy)
        self.acquire_fixture(state)
        witness = json.loads((self.root / package_migration.REPORT / 'acquisition.json').read_text())
        validation_state = {**state, '_root': str(self.root)}
        package_migration.validate_acquisition(witness, validation_state)
        changed = []
        for sdk in (':app:robolectricSdk', ':companion:robolectricSdk'):
            malformed = copy.deepcopy(witness)
            next(item for item in malformed['consumers'] if item['id'] == sdk)['files'] = []
            changed.append(malformed)
            for key, value in [('component', ['x', 'foreign-sdk', '1']), ('sha256', 'unhashed')]:
                malformed = copy.deepcopy(witness)
                next(item for item in malformed['consumers'] if item['id'] == sdk)['files'][0][key] = value
                changed.append(malformed)
        for missing in (':companion:robolectricSdk', ':companion:prepareRobolectricSdk'):
            malformed = copy.deepcopy(witness)
            malformed['consumers'] = [item for item in malformed['consumers'] if item['id'] != missing]
            changed.append(malformed)
        for foreign in (':companion:robolectricSdk30', ':companion:bundledSqliteHost'):
            malformed = copy.deepcopy(witness)
            extra = copy.deepcopy(next(item for item in malformed['consumers'] if item['id'] == ':companion:robolectricSdk'))
            extra['id'] = foreign
            malformed['consumers'].append(extra)
            malformed['consumers'].sort(key=lambda item: item['id'])
            changed.append(malformed)
        malformed = copy.deepcopy(witness)
        malformed['writer_tasks'].remove(':companion:prepareRobolectricSdk')
        changed.append(malformed)
        for index, record in enumerate(changed):
            with self.subTest(index=index), self.assertRaises(ValueError):
                package_migration.validate_acquisition(record, validation_state)
        self.legacy.run_gradle.assert_not_called()

    def test_successful_process_cannot_complete_a_stage_after_the_overall_deadline(self):
        self.write('android/companion/gradle.lockfile', 'x:old:1=debug\n')
        self.write('android/' + SCHEMA_DIRECTORY + '/6.json', 'schema six\n')
        self.commit('fixture admitted package inputs')
        with patch.dict(os.environ, self.environment(), clear=True):
            state, digest = package_build.start('debug', self.root, self.legacy)
            with patch.object(package_build.time, 'monotonic', side_effect=[state['started_monotonic_seconds'] + 1,
                                                                        state['deadline_monotonic_seconds'] + 1]):
                with self.assertRaisesRegex(ValueError, 'cost deadline expired'):
                    package_build.run_stage('test-debug', digest, self.root, self.legacy)
            self.assertEqual(json.loads((self.root / package_build.SESSION_FILE).read_text())['status'], 'failed')
        self.assertEqual(self.legacy.run_gradle.call_count, 1)

    def test_stages_cannot_skip_repeat_change_source_or_extend_the_budget(self):
        self.write('android/companion/gradle.lockfile', 'x:old:1=debug\n')
        self.write('android/' + SCHEMA_DIRECTORY + '/6.json', 'schema six\n')
        self.commit('fixture admitted package inputs')
        with patch.dict(os.environ, self.environment(), clear=True):
            state, digest = package_build.start('debug', self.root, self.legacy)
            with self.assertRaisesRegex(ValueError, 'skipped'):
                package_build.run_stage('lint-build', digest, self.root, self.legacy)
            for changes in ({'schema_version': True}, {'overall_limit_seconds': 1800},
                            {'started_monotonic_seconds': float('inf'), 'deadline_monotonic_seconds': float('inf')}):
                bad = {**copy.deepcopy(state), **changes}
                changed = package_build.write_state(self.root, bad)
                with self.assertRaises(ValueError):
                    package_build.load_state(self.root, changed)
            package_build.write_state(self.root, state)
            digest = package_build.run_stage('test-debug', digest, self.root, self.legacy)
            with self.assertRaisesRegex(ValueError, 'repeated'):
                package_build.run_stage('test-debug', digest, self.root, self.legacy)
        self.assertEqual(self.legacy.run_gradle.call_count, 1)

    def test_source_change_after_admission_stops_before_gradle(self):
        self.write('android/companion/gradle.lockfile', 'x:old:1=debug\n')
        self.write('android/' + SCHEMA_DIRECTORY + '/6.json', 'schema six\n')
        self.commit('fixture admitted package inputs')
        with patch.dict(os.environ, self.environment(), clear=True):
            state, digest = package_build.start('debug', self.root, self.legacy)
            self.write(PROTOCOL_FILES[1], 'changed fixture source\n')
            with self.assertRaises(ValueError):
                package_build.run_stage('test-debug', digest, self.root, self.legacy)
        self.legacy.run_gradle.assert_not_called()

    def test_zero_exit_without_both_junit_and_testresult_cannot_complete_debug(self):
        self.write('android/companion/gradle.lockfile', 'x:old:1=debug\n')
        self.write('android/' + SCHEMA_DIRECTORY + '/6.json', 'schema six\n')
        self.commit('fixture admitted package inputs')
        self.legacy.run_gradle.side_effect = None
        with patch.dict(os.environ, self.environment(), clear=True):
            state, digest = package_build.start('debug', self.root, self.legacy)
            with self.assertRaises(ValueError):
                package_build.run_stage('test-debug', digest, self.root, self.legacy)
        saved = json.loads((self.root / package_build.SESSION_FILE).read_text())
        self.assertEqual(saved['status'], 'failed')
        self.assertNotIn('test-debug', saved['completed'])
        self.assertIsNone(saved['debug_test_evidence'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_current_debug_originals_reject_cache_skip_filter_and_partial_junit(self):
        binding = {'repository': 'OnlineMo/MeloX-Watch', 'source_sha': 'a' * 40, 'build_sha': 'b' * 40,
                   'run_id': 7, 'run_attempt': 1, 'event': 'pull_request', 'ref': 'refs/pull/123/merge'}
        raw, _ = debug_test_fixture(binding)
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            original_members = {name: archive.read(name) for name in archive.namelist() if name != 'test-index.json'}
            original_index = json.loads(archive.read('test-index.json'))
        cases = [('outcome', 'up-to-date'), ('outcome', 'no-source'), ('outcome', 'skipped'),
                 ('test_count', 2), ('skipped_test_count', 1), ('test_count', True),
                 ('scope', {'include_patterns': ['OnlyOneTest']})]
        for key, value in cases:
            members = copy.deepcopy(original_members)
            index = copy.deepcopy(original_index)
            record = json.loads(members['phone/task-result.json'])
            record[key] = value
            members['phone/task-result.json'] = package_test_evidence.encoded(record)
            index['task_records'][1]['record_sha256'] = package_test_evidence.digest(members['phone/task-result.json'])
            changed = package_test_evidence.pack(members, index)
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                package_test_evidence.validate_archive_bytes(changed, binding)
        members = copy.deepcopy(original_members)
        index = copy.deepcopy(original_index)
        del members['phone/junit/TEST-Fixture.xml']
        with self.assertRaises(ValueError):
            package_test_evidence.validate_archive_bytes(package_test_evidence.pack(members, index), binding)


class JunitEncodingBoundaryTests(unittest.TestCase):
    XML = '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="Fixture" name="unicode \u97f3\u4e50"/></testsuite>'

    def test_original_gradle_utf8_declaration_and_plain_xml_remain_valid(self):
        for prefix in ('', '<?xml version="1.0" encoding="UTF-8"?>', '\ufeff<?xml version="1.0" encoding="utf-8"?>'):
            with self.subTest(prefix=prefix):
                self.assertEqual(package_test_evidence.junit_count((prefix + self.XML).encode('utf-8')), 1)

    def test_alternate_encodings_nul_and_entity_originals_are_rejected(self):
        payload = '<?xml version="1.0" encoding="UTF-16"?><!DOCTYPE testsuite [<!ENTITY x "Fixture">]>' + self.XML.replace('unicode \u97f3\u4e50', '&x;')
        cases = {encoding: payload.encode(encoding) for encoding in
                 ('utf-16', 'utf-16-le', 'utf-16-be', 'utf-32', 'utf-32-le', 'utf-32-be')}
        cases.update({'non-utf8-declaration': ('<?xml version="1.0" encoding="ISO-8859-1"?>' + self.XML).encode('utf-8'),
                      'utf8-dtd': payload.encode('utf-8'), 'invalid-utf8': b'\xff' + self.XML.encode('utf-8'),
                      'nul': self.XML.encode('utf-8') + b'\x00'})
        for name, raw in cases.items():
            with self.subTest(name=name), self.assertRaises(ValueError):
                package_test_evidence.junit_count(raw)


if __name__ == '__main__':
    unittest.main()
