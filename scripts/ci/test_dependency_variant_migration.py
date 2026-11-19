"""v5 policy fixtures. Every Gradle/JVM/APK, Git and cloud-check operation is mocked."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import build
from build_process import BuildTimeout
import test_dependency_migration as history

MODES = ('debug', 'release', 'diagnostic')
ACQUIRE = tuple('migration-acquire-' + mode for mode in MODES)
STRICT = tuple('strict-' + mode for mode in MODES)
PHASES = (*ACQUIRE, *STRICT)
SHARED = {':app:prepareRobolectricSdk', ':app:prepareBundledSqliteHost', ':app:bundledSqliteHost',
          ':app:robolectricSdk', ':app:robolectricSdk30', ':buildscript:classpath', ':app:buildscript:classpath'}


def writer_tasks(mode, instrumentation=False):
    variant = mode.title()
    result = [':app:prepareRobolectricSdk', ':app:prepareBundledSqliteHost']
    result.extend(':app:compileWatchAndroid' + variant + suffix for suffix in
                  ('Kotlin', 'JavaWithJavac', 'UnitTestKotlin', 'UnitTestJavaWithJavac'))
    result.append(':app:lintWatchAndroid' + variant)
    if mode == 'diagnostic' and instrumentation:
        result.extend(':app:compileWatchAndroidDiagnosticAndroidTest' + suffix for suffix in ('Kotlin', 'JavaWithJavac'))
    return result


def consumers(mode, instrumentation=False):
    variant = mode.title()
    result = {name: 'task-input-archives' for name in writer_tasks(mode, instrumentation)}
    for prefix, suffix in (('lintAnalyze', ''), ('lintAnalyze', 'UnitTest'), ('lintReport', '')):
        result[':app:' + prefix + 'WatchAndroid' + variant + suffix] = 'task-input-archives'
    result[':app:testWatchAndroid' + variant + 'UnitTest'] = 'test-classpath-archives'
    result.update({':app:' + name: 'configuration-artifacts' for name in ('bundledSqliteHost', 'robolectricSdk', 'robolectricSdk30')})
    result.update({name: 'buildscript-artifacts' for name in (':buildscript:classpath', ':app:buildscript:classpath')})
    return result


class DependencyVariantMigrationPolicies(unittest.TestCase):
    # Reuse only fixtures, never inherit the historical v4 test cases or reinterpret their expected manifests.
    write = history.DependencyMigrationPolicies.write
    commit = history.DependencyMigrationPolicies.commit
    hash = history.DependencyMigrationPolicies.hash
    save_request = history.DependencyMigrationPolicies.save_request
    git = history.DependencyMigrationPolicies.git
    manifest = history.DependencyMigrationPolicies.manifest

    def setUp(self):
        history.DependencyMigrationPolicies.setUp(self)
        self.commit('scripts/ci/dependency-acquire-variants.gradle', b'nonexecuted variant writer policy fixture\n')
        self.request.update(schema_version=2, kind='owner-pr-dependency-variant-acquire-strict',
            migration_tool_hashes={name: self.hash(name) for name in
                                  ('scripts/ci/build.py', 'scripts/ci/dependency-acquire-variants.gradle')})
        self.save_request()
        self.records, self.pins = {}, {}
        self.writer_schema = {phase: True for phase in ACQUIRE}

    def witness(self, mode, environment):
        phase = 'migration-acquire-' + mode
        records = []
        for name, kind in consumers(mode, self.request['diagnostic_android_test']).items():
            archive = {'name': 'fixture-compiler.jar', 'size': 10, 'sha256': 'd' * 64}
            record = {'id': name, 'kind': kind, 'files': [archive]}
            if kind in ('task-input-archives', 'test-classpath-archives'):
                record.update(file_count=1, directory_count=0, excluded_file_count=0, missing_non_archive_count=0)
            if kind == 'task-input-archives':
                record['outcome'] = 'executed'
            if kind == 'configuration-artifacts':
                parts = self.request['runtime_components'][name.removeprefix(':app:')]
                archive.update(name=parts[1] + '-' + parts[2] + '.jar', component=parts)
            if kind == 'buildscript-artifacts':
                archive.update(name='gradle-8.11.1.jar', component=['com.android.tools.build', 'gradle', '8.11.1'])
            records.append(record)
        signatures = {(item['name'], item['size'], item['sha256']) for record in records for item in record['files']}
        prior_count = sum(record['archive_budget']['unique_archive_count'] for record in self.records.values())
        prior_bytes = sum(record['archive_budget']['unique_archive_bytes'] for record in self.records.values())
        prior_consumers = sum(len(record['consumers']) for record in self.records.values())
        return {'schema_version': 2, 'kind': 'dependency-variant-file-acquisition', 'phase': phase, 'variant': mode,
            'source_sha': history.SOURCE, 'build_sha': history.BUILD, 'run_id': 321, 'run_attempt': 2,
            'event': 'pull_request', 'ref': history.ENV['GITHUB_REF'],
            'request_sha256': self.hash(build.DEPENDENCY_MIGRATION_REQUEST),
            'predecessor_acquisitions': json.loads(environment['CI_DEPENDENCY_VARIANT_PREDECESSORS']),
            'writer_tasks': writer_tasks(mode, self.request['diagnostic_android_test']), 'test_task_count': 0, 'graph_task_count': 42,
            'runtime_classpath': {'consumer_id': ':app:testWatchAndroid' + mode.title() + 'UnitTest', 'producer_tasks': [
                {'id': ':app:bundleWatchAndroid' + mode.title() + 'ClassesToRuntimeJar', 'outcome': 'executed'},
                {'id': ':app:compileWatchAndroid' + mode.title() + 'UnitTestKotlin', 'outcome': 'executed'}]},
            'consumers': sorted(records, key=lambda value: value['id']),
            'archive_budget': {'unique_archive_count': len(signatures), 'unique_archive_bytes': sum(item[1] for item in signatures),
                'cumulative_archive_count': prior_count + len(signatures),
                'cumulative_archive_bytes': prior_bytes + sum(item[1] for item in signatures),
                'cumulative_consumer_count': prior_consumers + len(records)},
            'file_capture': {'task': ':ciAcquireVariantDependencyFiles', 'phase': phase, 'status': 'completed'}}

    def merged(self):
        merged = {}
        for phase in ACQUIRE:
            for consumer in self.records[phase]['consumers']:
                if consumer['id'] in merged:
                    merged[consumer['id']]['capture_phases'].append(phase)
                else:
                    merged[consumer['id']] = {'id': consumer['id'], 'kind': consumer['kind'],
                                             'capture_phases': [phase], 'record': copy.deepcopy(consumer)}
        inventory = sorted(build.dependency_lock_snapshot((self.android / 'app/gradle.lockfile').read_bytes()))
        return {'schema_version': 2, 'kind': 'dependency-variant-acquisition-merge', 'phase': ACQUIRE[-1],
            'source_sha': history.SOURCE, 'build_sha': history.BUILD, 'run_id': 321, 'run_attempt': 2,
            'event': 'pull_request', 'ref': history.ENV['GITHUB_REF'], 'request_sha256': self.hash(build.DEPENDENCY_MIGRATION_REQUEST),
            'variant_acquisitions': copy.deepcopy(self.pins), 'consumers': [merged[name] for name in sorted(merged)],
            'archive_budget': {
                'cumulative_archive_count': sum(record['archive_budget']['unique_archive_count'] for record in self.records.values()),
                'cumulative_archive_bytes': sum(record['archive_budget']['unique_archive_bytes'] for record in self.records.values()),
                'cumulative_consumer_count': sum(len(record['consumers']) for record in self.records.values())},
            'lock_capture': {'kind': 'all-resolvable-configuration-graphs', 'task': ':ciResolveAndLockAll', 'phase': ACQUIRE[-1],
                'preceding_file_capture_task': ':ciAcquireVariantDependencyFiles', 'status': 'completed',
                'configuration_count': len(inventory), 'unresolved_dependency_count': 0,
                'project_configurations': {':': [], ':app': inventory}}}

    def acquire(self, command, **kwargs):
        self.assertFalse((self.android / self.current).exists())
        phase = kwargs['phase']
        if phase in ACQUIRE:
            mode = phase.removeprefix('migration-acquire-')
            xml = (self.android / 'gradle/verification-metadata.xml').read_bytes()
            component = (b'<component group="fixture" name="' + mode.encode() + b'" version="1"><artifact name="' +
                         mode.encode() + b'-1.jar"><sha256 value="' + mode[:1].encode() * 64 +
                         b'" origin="Generated by Gradle"/></artifact></component>')
            # d/r are not all hex characters; deterministic fixture pins remain valid SHA-256 syntax.
            component = component.replace(mode[:1].encode() * 64, b'a' * 64)
            self.write('android/gradle/verification-metadata.xml', xml.replace(b'</components>', component + b'</components>'))
            lock = (self.android / 'app/gradle.lockfile').read_bytes()
            if phase == ACQUIRE[0]:
                for name, parts in self.request['runtime_components'].items():
                    lock += (':'.join(parts) + '=' + name + '\n').encode()
            if phase == ACQUIRE[0]:
                lock = lock.replace(b'empty=lintChecks\n', b'')
            lock += ('fixture:' + mode + ':1=new' + mode.title() + ',oldCompile' +
                     (',lintChecks' if phase == ACQUIRE[0] else '') + '\n').encode()
            self.write('android/app/gradle.lockfile', lock)
            record = self.witness(mode, kwargs['env'])
            raw = (json.dumps(record, sort_keys=True) + '\n').encode()
            relative = 'acquisitions/' + phase + '.json'
            self.write('android/' + build.ROOM_SCHEMA_REPORT_DIRECTORY + '/' + relative, raw)
            self.records[phase] = record
            self.pins[phase] = {'file': relative, 'sha256': hashlib.sha256(raw).hexdigest()}
            if self.writer_schema[phase]:
                self.write('android/' + self.current, history.SCHEMA)
            if phase == ACQUIRE[-1]:
                self.write('android/' + build.ROOM_SCHEMA_REPORT_DIRECTORY + '/dependency-variant-acquisition.json',
                           (json.dumps(self.merged(), sort_keys=True) + '\n').encode())
        else:
            self.write('android/' + self.current, history.SCHEMA)

    def complete(self):
        with self.assertRaisesRegex(ValueError, 'not an accepted build'):
            build.build('debug')

    def test_exact_six_commands_one_deadline_and_only_last_writer_collects_the_full_graph(self):
        with patch.dict(os.environ, {key: 'nonsecret-fixture' for key in
                        ('GH_TOKEN', 'GITHUB_TOKEN', 'ACTIONS_RUNTIME_TOKEN', 'ACTIONS_ID_TOKEN_REQUEST_TOKEN',
                         'CI_DEPENDENCY_VARIANT_REQUEST_SHA256', 'CI_DEPENDENCY_VARIANT_PREDECESSORS')}):
            self.complete()
        calls = self.process.call_args_list
        self.assertEqual([call.kwargs['phase'] for call in calls], list(PHASES))
        self.assertEqual(len({call.kwargs['total_deadline'] for call in calls}), 1)
        for index, mode in enumerate(MODES):
            command = calls[index].args[0]
            expected_tail = [':ciAcquireVariantDependencyFiles'] + ([':ciResolveAndLockAll'] if mode == 'diagnostic' else [])
            self.assertEqual([value for value in command if value.startswith(':')], writer_tasks(mode) + expected_tail)
            self.assertEqual(command[command.index('--write-verification-metadata') + 1], 'sha256')
            for flag in ('--rerun-tasks', '--no-build-cache', '--write-locks'):
                self.assertIn(flag, command)
            self.assertIn('../scripts/ci/dependency-acquire-variants.gradle', command)
            self.assertNotIn('CI_DEPENDENCY_DIAGNOSTIC_PHASE', calls[index].kwargs['env'])
            for other in MODES:
                if other != mode:
                    self.assertFalse(any('WatchAndroid' + other.title() in value for value in command))
        for mode, call in zip(MODES, calls[3:]):
            self.assertEqual(call.args[0], build.gradle_command(build.gradle_tasks(mode)) + ['--info'])
            self.assertNotIn('CI_DEPENDENCY_VARIANT_REQUEST_SHA256', call.kwargs['env'])
            self.assertNotIn('CI_DEPENDENCY_VARIANT_PREDECESSORS', call.kwargs['env'])
        for call in calls:
            self.assertEqual(call.kwargs['env']['CI_UNSIGNED_APK'], 'true')
            for key in ('GH_TOKEN', 'GITHUB_TOKEN', 'ACTIONS_RUNTIME_TOKEN', 'ACTIONS_ID_TOKEN_REQUEST_TOKEN'):
                self.assertNotIn(key, call.kwargs['env'])
        manifest = self.manifest()
        self.assertEqual(manifest['schema_version'], 5)
        self.assertEqual(manifest['collection_kind'], 'owner-pr-dependency-variant-acquire-strict')
        self.assertEqual(manifest['status'], 'generated-unreviewed')
        self.assertEqual(set(manifest['writer_checkpoints']), set(ACQUIRE))
        self.assertEqual(set(manifest['writer_schema_exports']), set(ACQUIRE))
        self.assertEqual(set(manifest['schema_exports']), set(STRICT))
        self.assertTrue(manifest['originals_match'])
        self.assertTrue(manifest['schema_exports_match'])
        self.assertTrue(manifest['report_matches_strict_inputs'])
        self.assertFalse(manifest['accepted'])
        self.assertFalse(manifest['apk_exported'])
        self.inspect_apk.assert_not_called()
        self.bootstrap_dependencies.assert_not_called()
        self.bootstrap_room_schema.assert_not_called()
        self.assertFalse((self.root / 'artifacts').exists())

    def test_supplied_deadline_is_shared_without_a_monotonic_reset(self):
        with patch.object(build.time, 'monotonic', side_effect=AssertionError('deadline reset')):
            with self.assertRaisesRegex(ValueError, 'not an accepted build'):
                build.bootstrap_dependency_migration(history.BUILD, 'debug', [self.current], total_deadline=12345.67)
        self.assertEqual([call.kwargs['total_deadline'] for call in self.process.call_args_list], [12345.67] * 6)

    def test_all_three_writer_exports_are_optional_individually_and_all_present_exports_are_compared(self):
        self.writer_schema[ACQUIRE[1]] = False
        self.complete()
        self.assertEqual(set(self.manifest()['writer_schema_exports']), {ACQUIRE[0], ACQUIRE[2]})
        self.assertIsNone(self.manifest()['writer_checkpoints'][ACQUIRE[1]]['schema_export'])

    def test_diagnostic_instrumentation_is_kept_in_its_writer_and_strict_only(self):
        (self.android / 'app/src/androidTest').mkdir(parents=True)
        self.request['diagnostic_android_test'] = True
        self.save_request()
        self.complete()
        for index in (0, 1):
            self.assertNotIn(':app:compileWatchAndroidDiagnosticAndroidTestKotlin', self.process.call_args_list[index].args[0])
        for suffix in ('Kotlin', 'JavaWithJavac'):
            self.assertIn(':app:compileWatchAndroidDiagnosticAndroidTest' + suffix, self.process.call_args_list[2].args[0])
        self.assertIn(':app:assembleWatchAndroidDiagnosticAndroidTest', self.process.call_args_list[-1].args[0])

    def test_v4_request_is_not_reinterpreted_as_v5(self):
        self.request.update(schema_version=1, kind=build.DEPENDENCY_MIGRATION_KIND,
                            migration_tool_hashes={name: self.hash(name) for name in build.MIGRATION_TOOLS})
        self.save_request()
        with patch.object(build, 'bootstrap_dependency_variant_migration') as new_route, patch.object(
                self, 'acquire', side_effect=AssertionError('unused')):
            original_failure = subprocess.CalledProcessError(37, ['historical-v4-fixture'])
            self.process.side_effect = original_failure
            with self.assertRaises(subprocess.CalledProcessError) as caught:
                build.build('debug')
        self.assertIs(caught.exception, original_failure)
        new_route.assert_not_called()
        self.assertEqual(self.process.call_args.kwargs['phase'], 'migration-acquire')
        self.assertEqual(self.manifest()['schema_version'], 4)

    def test_mixed_or_noninteger_request_versions_reject_before_gradle(self):
        for version, kind in ((True, self.request['kind']), (2.0, self.request['kind']),
                              (2, build.DEPENDENCY_MIGRATION_KIND), (1, self.request['kind'])):
            with self.subTest(version=version, kind=kind):
                self.request.update(schema_version=version, kind=kind)
                self.save_request()
                with self.assertRaises(ValueError):
                    build.build('debug')
                self.process.assert_not_called()
                self.assertFalse((self.android / build.ROOM_SCHEMA_REPORT_DIRECTORY).exists())

    def test_new_pin_from_first_writer_cannot_be_replaced_by_the_second_writer(self):
        original = self.acquire
        def changed(command, **kwargs):
            original(command, **kwargs)
            if kwargs['phase'] == ACQUIRE[1]:
                path = self.android / 'gradle/verification-metadata.xml'
                data = path.read_bytes().replace(b'value="' + b'a' * 64 + b'"', b'value="' + b'f' * 64 + b'"', 1)
                path.write_bytes(data)
        self.process.side_effect = changed
        with self.assertRaisesRegex(ValueError, 'previously captured verification pin'):
            build.build('debug')
        self.assertEqual(self.process.call_count, 2)

    def test_new_lock_from_first_writer_cannot_be_rebound_by_a_later_writer(self):
        original = self.acquire
        def changed(command, **kwargs):
            original(command, **kwargs)
            if kwargs['phase'] == ACQUIRE[1]:
                path = self.android / 'app/gradle.lockfile'
                path.write_bytes(path.read_bytes().replace(b'fixture:debug:1=', b'fixture:debug:2='))
        self.process.side_effect = changed
        with self.assertRaisesRegex(ValueError, 'previously captured configuration lock binding'):
            build.build('debug')
        self.assertEqual(self.process.call_count, 2)

    def test_first_writer_schema_cannot_be_lost_by_comparing_only_the_last_writer(self):
        original = self.acquire
        def changed(command, **kwargs):
            original(command, **kwargs)
            if kwargs['phase'] == ACQUIRE[1]:
                (self.android / self.current).write_bytes(b'{"different":true}\n')
        self.process.side_effect = changed
        with self.assertRaisesRegex(ValueError, 'writer exports differ'):
            build.build('debug')
        self.assertEqual(self.process.call_count, 2)

    def test_missing_last_collector_stops_before_strict(self):
        original = self.acquire
        def changed(command, **kwargs):
            original(command, **kwargs)
            if kwargs['phase'] == ACQUIRE[-1]:
                (self.android / build.ROOM_SCHEMA_REPORT_DIRECTORY / 'dependency-variant-acquisition.json').unlink()
        self.process.side_effect = changed
        with self.assertRaises(ValueError):
            build.build('debug')
        self.assertEqual(self.process.call_count, 3)

    def test_saved_first_witness_drift_during_second_writer_stops_immediately(self):
        original = self.acquire
        def changed(command, **kwargs):
            original(command, **kwargs)
            if kwargs['phase'] == ACQUIRE[1]:
                (self.android / build.ROOM_SCHEMA_REPORT_DIRECTORY / ('acquisitions/' + ACQUIRE[0] + '.json')).write_bytes(b'{}\n')
        self.process.side_effect = changed
        with self.assertRaisesRegex(ValueError, 'saved originals changed during'):
            build.build('debug')
        self.assertEqual(self.process.call_count, 2)

    def test_saved_checkpoint_drift_during_strict_is_rejected(self):
        original = self.acquire
        def changed(command, **kwargs):
            original(command, **kwargs)
            if kwargs['phase'] == STRICT[0]:
                path = self.android / build.ROOM_SCHEMA_REPORT_DIRECTORY / ('writer-checkpoints/' + ACQUIRE[0] + '/app/gradle.lockfile')
                path.write_bytes(b'changed saved original\n')
        self.process.side_effect = changed
        with self.assertRaisesRegex(ValueError, 'saved originals changed during'):
            build.build('debug')
        self.assertEqual(self.process.call_count, 4)

    def test_complete_witness_shapes_reject_test_foreign_producer_duplicate_consumer_and_global_bound(self):
        self.complete()
        record = self.records[ACQUIRE[0]]
        cases = []
        value = copy.deepcopy(record); value['test_task_count'] = 1; cases.append(value)
        value = copy.deepcopy(record); value['runtime_classpath']['producer_tasks'][0]['id'] = ':app:bundleWatchAndroidReleaseClassesToRuntimeJar'; cases.append(value)
        value = copy.deepcopy(record); value['consumers'].append(value['consumers'][0]); cases.append(value)
        value = copy.deepcopy(record); value['archive_budget']['unique_archive_count'] = 4097; cases.append(value)
        value = copy.deepcopy(record); value['predecessor_acquisitions'] = {ACQUIRE[1]: 'e' * 64}; cases.append(value)
        binding = {key: record[key] for key in ('source_sha', 'build_sha', 'run_id', 'run_attempt', 'event', 'ref')}
        for value in cases:
            with self.subTest(value=value), self.assertRaises(ValueError):
                build.validate_dependency_variant_acquisition(value, binding, self.request, self.hash(build.DEPENDENCY_MIGRATION_REQUEST), ACQUIRE[0], {}, {})

    def test_shared_consumer_byte_conflict_and_missing_capture_are_rejected(self):
        self.complete()
        value = copy.deepcopy(self.records)
        value[ACQUIRE[1]]['consumers'][0]['files'][0]['sha256'] = 'f' * 64
        with self.assertRaisesRegex(ValueError, 'shared consumer byte conflict'):
            build.dependency_variant_merged_consumers(value)
        value = copy.deepcopy(self.records)
        value[ACQUIRE[2]]['consumers'] = [record for record in value[ACQUIRE[2]]['consumers'] if record['id'] not in SHARED]
        with self.assertRaisesRegex(ValueError, 'omitted a shared consumer capture'):
            build.dependency_variant_merged_consumers(value)

    def test_gradle_contract_has_a_test_free_graph_gate_real_single_classpath_dependencies_and_last_only_collector(self):
        source = Path(__file__).with_name('dependency-acquire-variants.gradle').read_text(encoding='utf-8')
        self.assertIn('!graph.allTasks.any { it instanceof Test }', source)
        self.assertIn('task.dependsOn(selectedTest.classpath.buildDependencies)', source)
        self.assertIn('getDependencies(selectedTest)', source)
        self.assertIn("if (mode == 'diagnostic')", source)
        self.assertIn('task.mustRunAfter(acquire)', source)
        self.assertNotIn('enabled = false', source)
        self.assertNotIn('onlyIf { false', source)


    def test_all_six_calls_preserve_old_bindings_while_adding_modules_to_old_configurations(self):
        self.complete()
        for phase in ACQUIRE:
            relative = 'writer-checkpoints/' + phase + '/app/gradle.lockfile'
            captured = build.dependency_lock_snapshot((self.android / build.ROOM_SCHEMA_REPORT_DIRECTORY / relative).read_bytes())
            self.assertEqual(captured['oldCompile']['fixture:library'], '1')
            self.assertEqual(captured['oldCompile']['fixture:debug'], '1')
            self.assertEqual(captured['lintChecks']['fixture:debug'], '1')

    def test_removing_a_captured_configuration_module_or_changing_its_version_is_rejected(self):
        xml = (self.android / 'gradle/verification-metadata.xml').read_bytes()
        before = {'gradle/verification-metadata.xml': xml, 'app/gradle.lockfile': b'fixture:library:1=oldCompile\nempty=oldEmpty\n'}
        for lock in (b'fixture:library:1=otherCompile\nempty=oldEmpty\n',
                     b'fixture:replacement:1=oldCompile\nempty=oldEmpty\n',
                     b'fixture:library:2=oldCompile\nempty=oldEmpty\n',
                     b'fixture:library:1=oldCompile\n'):
            with self.subTest(lock=lock), self.assertRaisesRegex(ValueError, 'previously captured configuration lock binding'):
                build.validate_dependency_variant_accumulation(before, {**before, 'app/gradle.lockfile': lock})

    def test_an_empty_captured_configuration_can_gain_modules_but_duplicate_versions_are_rejected(self):
        xml = (self.android / 'gradle/verification-metadata.xml').read_bytes()
        before = {'gradle/verification-metadata.xml': xml, 'app/gradle.lockfile': b'empty=oldEmpty\n'}
        build.validate_dependency_variant_accumulation(before, {**before, 'app/gradle.lockfile': b'fixture:library:1=oldEmpty\n'})
        with self.assertRaisesRegex(ValueError, 'duplicate module bindings'):
            build.validate_dependency_variant_accumulation(before, {**before, 'app/gradle.lockfile': b'fixture:library:1=oldEmpty\nfixture:library:2=oldEmpty\n'})

    def test_archive_byte_limits_remain_two_gib_per_stage_and_four_gib_cumulative(self):
        check_acquisition_byte_limits(self, self, build)



def check_acquisition_byte_limits(case, fixture, validator):
    previous = {}
    for mode in MODES:
        phase = 'migration-acquire-' + mode
        predecessors = {name: 'a' * 64 for name in previous}
        fixture.records = previous
        record = fixture.witness(mode, {'CI_DEPENDENCY_VARIANT_PREDECESSORS': json.dumps(predecessors)})
        index = 0
        for consumer in record['consumers']:
            if consumer['kind'] in ('task-input-archives', 'test-classpath-archives'):
                consumer['files'] = [{'name': 'synthetic-' + str(index) + '.jar', 'size': 128 * 1024 * 1024,
                                      'sha256': format(index, '064x')}]
                consumer['file_count'] = 1
                index += 1
        archives = {(item['name'], item['size'], item['sha256']) for consumer in record['consumers'] for item in consumer['files']}
        record['archive_budget']['unique_archive_count'] = len(archives)
        record['archive_budget']['unique_archive_bytes'] = sum(item[1] for item in archives)
        record['archive_budget'].update(validator.dependency_variant_budget({**previous, phase: record}))
        binding = {key: record[key] for key in ('source_sha', 'build_sha', 'run_id', 'run_attempt', 'event', 'ref')}
        arguments = (record, binding, fixture.request, fixture.hash(build.DEPENDENCY_MIGRATION_REQUEST), phase, predecessors, previous)
        if mode == 'diagnostic':
            case.assertGreater(record['archive_budget']['cumulative_archive_bytes'], 4 * 1024 * 1024 * 1024)
            with case.assertRaisesRegex(ValueError, 'shared archive or consumer reservation'):
                validator.validate_dependency_variant_acquisition(*arguments)
        else:
            validator.validate_dependency_variant_acquisition(*arguments)
            if mode == 'release':
                case.assertGreater(record['archive_budget']['cumulative_archive_bytes'], 2 * 1024 * 1024 * 1024)
            if mode == 'debug':
                too_large = copy.deepcopy(record)
                for consumer in too_large['consumers']:
                    if consumer['kind'] in ('task-input-archives', 'test-classpath-archives'):
                        consumer['files'][0]['size'] = 256 * 1024 * 1024
                values = {(item['name'], item['size'], item['sha256']) for consumer in too_large['consumers'] for item in consumer['files']}
                too_large['archive_budget']['unique_archive_bytes'] = sum(item[1] for item in values)
                too_large['archive_budget'].update(validator.dependency_variant_budget({phase: too_large}))
                case.assertGreater(too_large['archive_budget']['unique_archive_bytes'], 2 * 1024 * 1024 * 1024)
                with case.assertRaisesRegex(ValueError, 'archive reservation is inconsistent'):
                    validator.validate_dependency_variant_acquisition(too_large, *arguments[1:])
        previous[phase] = record


def phase_failure_test(phase, timeout):
    def test(self):
        original = self.acquire
        failure = (BuildTimeout(['mock-gradle'], phase, 'phase deadline', 1200) if timeout else
                   subprocess.CalledProcessError(37, ['mock-gradle']))
        def stopped(command, **kwargs):
            if kwargs['phase'] == phase:
                raise failure
            original(command, **kwargs)
        self.process.side_effect = stopped
        with self.assertRaises(type(failure)) as caught:
            build.build('debug')
        self.assertIs(caught.exception, failure)
        self.assertEqual(self.process.call_count, PHASES.index(phase) + 1)
        self.assertEqual(self.manifest()['status'], 'failed-partial')
        self.assertFalse(self.manifest()['accepted'])
        self.inspect_apk.assert_not_called()
    return test


for _phase in PHASES:
    for _timeout in (False, True):
        setattr(DependencyVariantMigrationPolicies, 'test_' + _phase.replace('-', '_') + ('_timeout' if _timeout else '_failure'),
                phase_failure_test(_phase, _timeout))


if __name__ == '__main__':
    unittest.main()
