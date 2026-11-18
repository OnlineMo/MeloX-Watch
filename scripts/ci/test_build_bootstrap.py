"""Dependency bootstrap trust/failure boundaries; Gradle is never executed by these tests."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import build
from build_process import BuildTimeout

SOURCE = 'a' * 40
ENV = {
    'GITHUB_ACTIONS': 'true', 'CI': 'true', 'GITHUB_EVENT_NAME': 'pull_request',
    'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch', 'GITHUB_REF': 'refs/pull/27/merge',
    'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2', 'SOURCE_SHA': SOURCE,
}
VERSION = {'versionName': '0.0.0', 'versionCode': '0'}
EXPECTED_PHASES = ('verification-debug', 'verification-release', 'verification-diagnostic',
                   'normal-lock-collection', 'strict-debug', 'strict-release', 'strict-diagnostic')


class BuildDependencyBootstrapTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.android = self.root / 'android'
        self.android.mkdir()
        contexts = [patch.object(build, 'ROOT', self.root), patch.object(build, 'check'),
                    patch.object(build, 'git', return_value=SOURCE), patch.object(build, 'properties', return_value=VERSION)]
        for context in contexts:
            context.start()
            self.addCleanup(context.stop)
        context = patch.dict(os.environ, ENV, clear=True)
        context.start()
        self.addCleanup(context.stop)

    def write_inputs(self, names=build.DEPENDENCY_INPUTS):
        for name in names:
            path = self.android / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('unexecuted test fixture\n', encoding='utf-8')

    def manifest(self):
        return json.loads((self.android / 'app/build/reports/dependency-bootstrap/bootstrap-manifest.json').read_text(encoding='utf-8'))

    def test_trusted_builds_never_generate_missing_dependency_inputs(self):
        for event, mode in (('push', 'debug'), ('pull_request_target', 'debug'), ('workflow_dispatch', 'debug'),
                            ('workflow_dispatch', 'release'), ('workflow_dispatch', 'diagnostic')):
            with self.subTest(event=event, mode=mode), patch.dict(os.environ, {
                'GITHUB_EVENT_NAME': event, 'GITHUB_REF': 'refs/heads/main',
                'CANDIDATE_OPERATION': 'prepare-candidate',
            }), patch.object(build, 'run_gradle') as process, patch.object(build, 'inspect_apk') as inspect:
                with self.assertRaisesRegex(ValueError, 'Committed strict dependency inputs'):
                    build.build(mode)
                process.assert_not_called()
                inspect.assert_not_called()
                self.assertFalse((self.root / 'artifacts').exists())

    def test_optimized_pr_modes_cannot_enter_bootstrap(self):
        for mode in ('release', 'diagnostic'):
            with self.subTest(mode=mode), patch.object(build, 'run_gradle') as process:
                with self.assertRaisesRegex(ValueError, 'PR builds only temporary Debug'):
                    build.build(mode)
                process.assert_not_called()

    def test_bootstrap_resolves_all_variants_unsigned_without_tokens_and_exports_no_apk(self):
        phases = []
        deadlines = []

        def generated(command, **kwargs):
            phase = len(phases)
            phases.append(command)
            deadlines.append(kwargs['total_deadline'])
            self.assertEqual(kwargs['phase'], EXPECTED_PHASES[phase])
            self.assertEqual(kwargs['build_sha'], SOURCE)
            self.assertIn('--dependency-verification=strict', command)
            self.assertEqual('--info' in command, phase >= 4)
            if phase < 3:
                self.assertIn('--write-locks', command)
                self.assertEqual(command[command.index('--write-verification-metadata') + 1], 'sha256')
                self.assertNotIn(build.LOCK_COLLECTION_TASK, command)
                variant = ('Debug', 'Release', 'Diagnostic')[phase]
                self.assertEqual([argument for argument in command if argument.startswith(':')],
                                 [f':app:testWatchAndroid{variant}UnitTest',
                                  f':app:lintWatchAndroid{variant}', f':app:assembleWatchAndroid{variant}'])
            elif phase == 3:
                self.assertIn('--write-locks', command)
                self.assertNotIn('--write-verification-metadata', command)
                expected_consumers = [f':app:{task}WatchAndroid{variant}{suffix}'
                                      for variant in ('Debug', 'Release', 'Diagnostic')
                                      for task, suffix in (('test', 'UnitTest'), ('lint', ''), ('assemble', ''))]
                self.assertEqual([argument for argument in command if argument.startswith(':')],
                                 [*expected_consumers, build.LOCK_COLLECTION_TASK])
            else:
                self.assertNotIn('--write-locks', command)
                self.assertNotIn('--write-verification-metadata', command)
                self.assertNotIn(build.LOCK_COLLECTION_TASK, command)
            variants = ('Debug', 'Release', 'Diagnostic') if phase == 3 else (
                ('Debug', 'Release', 'Diagnostic')[phase if phase < 3 else phase - 4],)
            for variant in variants:
                self.assertIn(f':app:testWatchAndroid{variant}UnitTest', command)
                self.assertIn(f':app:lintWatchAndroid{variant}', command)
                self.assertIn(f':app:assembleWatchAndroid{variant}', command)
            self.assertEqual(kwargs['env']['CI_UNSIGNED_APK'], 'true')
            self.assertEqual(kwargs['env']['CI_DEPENDENCY_DIAGNOSTIC_PHASE'],
                             EXPECTED_PHASES[phase])
            for token in ('GH_TOKEN', 'GITHUB_TOKEN', 'ACTIONS_RUNTIME_TOKEN', 'ACTIONS_ID_TOKEN_REQUEST_TOKEN'):
                self.assertNotIn(token, kwargs['env'])
            self.assertEqual(kwargs['cwd'], self.android)
            self.write_inputs(build.DEPENDENCY_REPORT_FILES)

        with patch.dict(os.environ, {name: 'unlogged-test-marker' for name in
                                   ('GH_TOKEN', 'GITHUB_TOKEN', 'ACTIONS_RUNTIME_TOKEN',
                                    'ACTIONS_ID_TOKEN_REQUEST_TOKEN', 'CI_DEPENDENCY_DIAGNOSTIC_PHASE')}), \
                patch.object(build, 'run_gradle', side_effect=generated) as process, \
                patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'not an accepted build'):
                build.build('debug')
            self.assertEqual(process.call_count, 7)
            self.assertEqual(len(set(deadlines)), 1)
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(manifest['schema_version'], 2)
        self.assertEqual(manifest['collection_kind'], 'variant-verification-normal-lock-strict')
        self.assertEqual(manifest['status'], 'generated-unreviewed')
        self.assertEqual(manifest['files'].keys(), set(build.DEPENDENCY_REPORT_FILES))
        self.assertEqual(manifest['missing_outputs'], [])
        self.assertEqual(manifest['source_sha'], SOURCE)
        self.assertFalse(manifest['accepted'])
        self.assertFalse(manifest['apk_exported'])
        self.assertEqual([phase['name'] for phase in manifest['phases']], list(EXPECTED_PHASES))
        self.assertTrue(all(phase['status'] == 'completed' for phase in manifest['phases']))
        self.assertEqual(manifest['strict_input_hashes'], manifest['files'])
        self.assertTrue(manifest['report_matches_strict_inputs'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_strict_bootstrap_info_only_adds_diagnostics_without_changing_task_or_flag_contracts(self):
        with patch.object(build, 'run_gradle', side_effect=lambda *args, **kwargs: self.write_inputs()) as process:
            with self.assertRaisesRegex(ValueError, 'not an accepted build'):
                build.bootstrap_dependencies(SOURCE, list(build.DEPENDENCY_INPUTS), total_deadline=12345)
        calls = process.call_args_list
        self.assertEqual(len(calls), 7)
        for index, mode in enumerate(('debug', 'release', 'diagnostic')):
            self.assertEqual(calls[index].args[0], build.gradle_command(build.gradle_tasks(mode)) +
                             ['--write-verification-metadata', 'sha256', '--write-locks'])
            self.assertEqual(calls[index + 4].args[0], build.gradle_command(build.gradle_tasks(mode)) + ['--info'])
        all_tasks = [task for mode in ('debug', 'release', 'diagnostic') for task in build.gradle_tasks(mode)]
        self.assertEqual(calls[3].args[0], build.gradle_command([*all_tasks, build.LOCK_COLLECTION_TASK]) + ['--write-locks'])
        for call in calls:
            self.assertEqual(call.kwargs['total_deadline'], 12345)
            self.assertEqual(call.kwargs['env']['CI_UNSIGNED_APK'], 'true')
            self.assertEqual(call.kwargs['env']['GITHUB_EVENT_NAME'], 'pull_request')
        self.assertNotIn('--info', build.gradle_command(build.gradle_tasks('debug')))
        self.assertNotIn('--info', build.gradle_command(build.gradle_tasks('release')))
        self.assertNotIn('--info', build.gradle_command(build.gradle_tasks('diagnostic')))

    def test_each_variant_writer_failure_stops_before_normal_and_strict_even_with_complete_outputs(self):
        for index, failed_phase in enumerate(EXPECTED_PHASES[:3]):
            with self.subTest(phase=failed_phase):
                calls = []

                def failed(command, **kwargs):
                    calls.append(kwargs['phase'])
                    self.write_inputs(build.DEPENDENCY_REPORT_FILES)
                    if kwargs['phase'] == failed_phase:
                        raise subprocess.CalledProcessError(13, command)

                with patch.object(build, 'run_gradle', side_effect=failed), \
                        patch.object(build, 'inspect_apk') as inspect:
                    with self.assertRaises(subprocess.CalledProcessError) as failure:
                        build.bootstrap_dependencies(SOURCE, list(build.DEPENDENCY_INPUTS), total_deadline=12345)
                    self.assertEqual(failure.exception.returncode, 13)
                    inspect.assert_not_called()
                self.assertEqual(calls, list(EXPECTED_PHASES[:index + 1]))
                manifest = self.manifest()
                self.assertEqual(manifest['schema_version'], 2)
                self.assertEqual(manifest['collection_kind'], 'variant-verification-normal-lock-strict')
                self.assertEqual(manifest['missing_outputs'], [])
                self.assertEqual(manifest['status'], 'failed-partial')
                self.assertEqual([phase.get('status') for phase in manifest['phases']],
                                 ['completed'] * index + ['started'] + [None] * (6 - index))
                self.assertIsNone(manifest['strict_input_hashes'])
                self.assertIsNone(manifest['report_matches_strict_inputs'])
                self.assertFalse(manifest['accepted'])
                self.assertFalse(manifest['apk_exported'])
                self.assertFalse((self.root / 'artifacts').exists())

    def test_every_writer_timeout_retains_its_deadline_and_failed_partial_disposition(self):
        for index, failed_phase in enumerate(EXPECTED_PHASES[:3]):
            for reason, seconds in (('phase deadline', 1200), ('no build output', 300),
                                    ('active test deadline including setup/cleanup', 240)):
                with self.subTest(phase=failed_phase, reason=reason):
                    calls = []

                    def timed_out(command, **kwargs):
                        calls.append(kwargs['phase'])
                        self.write_inputs(build.DEPENDENCY_REPORT_FILES)
                        if kwargs['phase'] == failed_phase:
                            raise BuildTimeout(command, kwargs['phase'], reason, seconds)

                    with patch.object(build, 'run_gradle', side_effect=timed_out), \
                            patch.object(build, 'inspect_apk') as inspect:
                        with self.assertRaises(BuildTimeout) as failure:
                            build.bootstrap_dependencies(SOURCE, list(build.DEPENDENCY_INPUTS), total_deadline=12345)
                        self.assertEqual(failure.exception.phase, failed_phase)
                        self.assertEqual(failure.exception.reason, reason)
                        self.assertEqual(failure.exception.timeout, seconds)
                        inspect.assert_not_called()
                    self.assertEqual(calls, list(EXPECTED_PHASES[:index + 1]))
                    manifest = self.manifest()
                    self.assertEqual(manifest['status'], 'failed-partial')
                    self.assertEqual([phase.get('status') for phase in manifest['phases']],
                                     ['completed'] * index + ['started'] + [None] * (6 - index))
                    self.assertIsNone(manifest['strict_input_hashes'])
                    self.assertIsNone(manifest['report_matches_strict_inputs'])
                    self.assertFalse(manifest['accepted'])
                    self.assertFalse(manifest['apk_exported'])
                    self.assertFalse((self.root / 'artifacts').exists())

    def test_all_seven_calls_reuse_the_supplied_overall_deadline_without_resetting_it(self):
        supplied_deadline = 12345.678
        with patch.object(build.time, 'monotonic', side_effect=AssertionError('overall deadline reset')), \
                patch.object(build, 'run_gradle', side_effect=lambda *args, **kwargs: self.write_inputs()) as process:
            with self.assertRaisesRegex(ValueError, 'not an accepted build'):
                build.bootstrap_dependencies(SOURCE, list(build.DEPENDENCY_INPUTS), total_deadline=supplied_deadline)
        self.assertEqual(process.call_count, 7)
        self.assertEqual([call.kwargs['total_deadline'] for call in process.call_args_list], [supplied_deadline] * 7)
        self.assertEqual([call.kwargs['phase'] for call in process.call_args_list], list(EXPECTED_PHASES))
        manifest = self.manifest()
        self.assertEqual(manifest['status'], 'generated-unreviewed')
        self.assertFalse(manifest['accepted'])
        self.assertFalse(manifest['apk_exported'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_inherited_diagnostics_are_removed_from_ordinary_gradle_environments(self):
        for event in ('pull_request', 'push', 'workflow_dispatch'):
            for unsigned in (True, False):
                with self.subTest(event=event, unsigned=unsigned), patch.dict(os.environ, {
                    'GITHUB_EVENT_NAME': event, 'CI_DEPENDENCY_DIAGNOSTIC_PHASE': 'strict-debug',
                    'GH_TOKEN': 'unlogged-test-marker', 'GITHUB_TOKEN': 'unlogged-test-marker',
                    'ACTIONS_RUNTIME_TOKEN': 'unlogged-test-marker',
                    'ACTIONS_ID_TOKEN_REQUEST_TOKEN': 'unlogged-test-marker',
                }):
                    environment = build.gradle_environment(unsigned)
                    self.assertEqual(environment['CI_UNSIGNED_APK'], str(unsigned).lower())
                    for name in ('CI_DEPENDENCY_DIAGNOSTIC_PHASE', 'GH_TOKEN', 'GITHUB_TOKEN',
                                 'ACTIONS_RUNTIME_TOKEN', 'ACTIONS_ID_TOKEN_REQUEST_TOKEN'):
                        self.assertNotIn(name, environment)

    def test_diagnostics_cannot_be_requested_outside_unsigned_pr_bootstrap(self):
        for event, unsigned, phase in (('pull_request', True, 'ordinary-debug'),
                                      ('pull_request', False, 'strict-debug'),
                                      ('push', True, 'normal-lock-collection'),
                                      ('workflow_dispatch', True, 'verification-collection')):
            with self.subTest(event=event, unsigned=unsigned, phase=phase), \
                    patch.dict(os.environ, {'GITHUB_EVENT_NAME': event}):
                with self.assertRaisesRegex(ValueError, 'unsigned PR bootstrap phase'):
                    build.gradle_environment(unsigned, diagnostic_phase=phase)

    def test_new_writer_diagnostics_keep_the_unsigned_pr_boundary(self):
        for phase in EXPECTED_PHASES[:3]:
            for event, unsigned in (('pull_request', False), ('push', True), ('workflow_dispatch', True)):
                with self.subTest(event=event, unsigned=unsigned, phase=phase), \
                        patch.dict(os.environ, {'GITHUB_EVENT_NAME': event}):
                    with self.assertRaisesRegex(ValueError, 'unsigned PR bootstrap phase'):
                        build.gradle_environment(unsigned, diagnostic_phase=phase)

    def test_legacy_writer_diagnostic_tag_remains_available_only_to_unsigned_pr_tooling(self):
        environment = build.gradle_environment(True, diagnostic_phase='verification-collection')
        self.assertEqual(environment['CI_DEPENDENCY_DIAGNOSTIC_PHASE'], 'verification-collection')
        self.assertEqual(environment['CI_UNSIGNED_APK'], 'true')

    def test_normal_resolution_lock_replaces_writer_graph_before_independent_verification(self):
        calls = []
        lock = self.android / 'app/gradle.lockfile'

        def generated(command, **kwargs):
            calls.append(command)
            if '--write-verification-metadata' in command:
                self.write_inputs()
                lock.write_text('writer metadata graph\n', encoding='utf-8')
            elif '--write-locks' in command:
                lock.write_text('normal task graph\n', encoding='utf-8')
            else:
                self.assertEqual(lock.read_text(encoding='utf-8'), 'normal task graph\n')

        with patch.object(build, 'run_gradle', side_effect=generated), patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'not an accepted build'):
                build.build('debug')
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(len(calls), 7)
        copied = self.android / 'app/build/reports/dependency-bootstrap/app/gradle.lockfile'
        self.assertEqual(copied.read_text(encoding='utf-8'), 'normal task graph\n')
        self.assertEqual(manifest['strict_input_hashes']['app/gradle.lockfile'], build.sha256(copied))
        self.assertFalse(manifest['accepted'])

    def test_explicit_graph_failure_never_accepts_complete_writer_inputs(self):
        def generated(command, **kwargs):
            if build.LOCK_COLLECTION_TASK in command:
                raise subprocess.CalledProcessError(1, command)
            self.write_inputs(build.DEPENDENCY_REPORT_FILES)

        with patch.object(build, 'run_gradle', side_effect=generated) as process, patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaises(subprocess.CalledProcessError):
                build.build('debug')
            self.assertEqual(process.call_count, 4)
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(manifest['missing_outputs'], [])
        self.assertEqual(manifest['files'].keys(), set(build.DEPENDENCY_REPORT_FILES))
        self.assertEqual(manifest['status'], 'failed-partial')
        self.assertEqual([phase.get('status') for phase in manifest['phases']],
                         ['completed', 'completed', 'completed', 'started', None, None, None])
        self.assertIsNone(manifest['strict_input_hashes'])
        self.assertIsNone(manifest['report_matches_strict_inputs'])
        self.assertFalse(manifest['accepted'])
        self.assertFalse(manifest['apk_exported'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_independent_strict_failure_does_not_complete_collection_or_export(self):
        def generated(command, **kwargs):
            if '--write-locks' in command:
                self.write_inputs()
            else:
                raise subprocess.CalledProcessError(1, command)

        with patch.object(build, 'run_gradle', side_effect=generated) as process, patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaises(subprocess.CalledProcessError):
                build.build('debug')
            self.assertEqual(process.call_count, 5)
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(manifest['status'], 'failed-partial')
        self.assertEqual([phase.get('status') for phase in manifest['phases']],
                         ['completed', 'completed', 'completed', 'completed', 'started', None, None])
        self.assertFalse(manifest['accepted'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_independent_strict_input_drift_never_becomes_reviewable_complete_material(self):
        def generated(command, **kwargs):
            if '--write-locks' in command:
                self.write_inputs()
            else:
                (self.android / 'app/gradle.lockfile').write_text('unreviewed drift\n', encoding='utf-8')

        with patch.object(build, 'run_gradle', side_effect=generated), patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'changed dependency input bytes'):
                build.build('debug')
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(manifest['status'], 'failed-partial')
        self.assertFalse(manifest['accepted'])
        self.assertFalse(manifest['apk_exported'])

    def test_each_strict_variant_rejects_xml_lock_and_schema_drift_without_running_later_phases(self):
        source = self.android / build.ROOM_DATABASE_SOURCE
        source.parent.mkdir(parents=True)
        source.write_text('@Database(version = 2, exportSchema = true)\n', encoding='utf-8')
        schema_names = tuple(build.ROOM_SCHEMA_DIRECTORY + f'/{version}.json' for version in (1, 2))
        inputs = (*build.DEPENDENCY_REPORT_FILES, *schema_names)
        for phase_index, failed_phase in enumerate(EXPECTED_PHASES[4:], 4):
            for changed_input in inputs:
                with self.subTest(phase=failed_phase, input=changed_input):
                    calls = []

                    def changed(command, **kwargs):
                        calls.append(kwargs['phase'])
                        if '--write-locks' in command:
                            self.write_inputs(inputs)
                        elif kwargs['phase'] == failed_phase:
                            (self.android / changed_input).write_text('unreviewed strict drift\n', encoding='utf-8')

                    with patch.object(build, 'run_gradle', side_effect=changed), \
                            patch.object(build, 'inspect_apk') as inspect:
                        with self.assertRaisesRegex(ValueError, 'changed dependency input bytes'):
                            build.bootstrap_dependencies(SOURCE, list(inputs), total_deadline=12345)
                        inspect.assert_not_called()
                    self.assertEqual(calls, list(EXPECTED_PHASES[:phase_index + 1]))
                    manifest = self.manifest()
                    self.assertEqual(manifest['status'], 'failed-partial')
                    self.assertEqual([phase.get('status') for phase in manifest['phases']],
                                     ['completed'] * phase_index + ['started'] + [None] * (6 - phase_index))
                    self.assertIsNotNone(manifest['strict_input_hashes'])
                    self.assertFalse(manifest['report_matches_strict_inputs'])
                    self.assertFalse(manifest['accepted'])
                    self.assertFalse(manifest['apk_exported'])
                    self.assertFalse((self.root / 'artifacts').exists())

    def test_gradle_failure_preserves_partial_reports_and_remains_failed(self):
        def failed(command, **kwargs):
            self.write_inputs(('gradle/verification-metadata.xml',))
            raise subprocess.CalledProcessError(1, command)

        with patch.object(build, 'run_gradle', side_effect=failed), patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaises(subprocess.CalledProcessError):
                build.build('debug')
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(manifest['status'], 'failed-partial')
        self.assertEqual(manifest['missing_outputs'], ['app/gradle.lockfile'])
        self.assertFalse(manifest['accepted'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_gradle_timeout_preserves_partial_reports_without_accepting_inputs_or_exporting_apk(self):
        def timed_out(command, **kwargs):
            self.write_inputs(('gradle/verification-metadata.xml',))
            raise BuildTimeout(command, kwargs['phase'], 'active test deadline including setup/cleanup', 240)

        with patch.object(build, 'run_gradle', side_effect=timed_out) as process, \
                patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaises(BuildTimeout):
                build.build('debug')
            process.assert_called_once()
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(manifest['status'], 'failed-partial')
        self.assertEqual(manifest['missing_outputs'], ['app/gradle.lockfile'])
        self.assertFalse(manifest['accepted'])
        self.assertFalse(manifest['apk_exported'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_report_copy_drift_after_last_strict_pass_is_rejected_and_marked_partial(self):
        original_copy = build.shutil.copyfile

        def copy_with_drift(source, target):
            if source == self.android / 'app/gradle.lockfile':
                source.write_text('changed after strict validation\n', encoding='utf-8')
            return original_copy(source, target)

        with patch.object(build, 'run_gradle', side_effect=lambda *args, **kwargs: self.write_inputs()), \
                patch.object(build.shutil, 'copyfile', side_effect=copy_with_drift), \
                patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'strict input bytes in reports'):
                build.build('debug')
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertTrue(all(phase['status'] == 'completed' for phase in manifest['phases']))
        self.assertEqual(manifest['status'], 'failed-partial')
        self.assertFalse(manifest['report_matches_strict_inputs'])
        self.assertFalse(manifest['accepted'])
        self.assertFalse(manifest['apk_exported'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_existing_but_uncommitted_inputs_cannot_unlock_trusted_builds(self):
        self.write_inputs()
        def git_command(*args):
            if args[0] == 'cat-file':
                raise ValueError('git failed')
            return SOURCE

        with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/contribution'}), \
                patch.object(build, 'git', side_effect=git_command), patch.object(build, 'run_gradle') as process:
            with self.assertRaisesRegex(ValueError, 'Committed strict dependency inputs'):
                build.build('debug')
            process.assert_not_called()

    def test_complete_committed_inputs_use_strict_build_without_generation(self):
        self.write_inputs()
        with patch.dict(os.environ, {'CI_DEPENDENCY_DIAGNOSTIC_PHASE': 'strict-debug'}), \
                patch.object(build, 'run_gradle', side_effect=RuntimeError('mock stops before APK validation')) as process:
            with self.assertRaisesRegex(RuntimeError, 'mock stops'):
                build.build('debug')
        command = process.call_args.args[0]
        self.assertIn('--dependency-verification=strict', command)
        self.assertNotIn('--info', command)
        self.assertNotIn('--write-verification-metadata', command)
        self.assertNotIn('--write-locks', command)
        self.assertEqual(process.call_args.kwargs['env']['CI_UNSIGNED_APK'], 'false')
        self.assertNotIn('CI_DEPENDENCY_DIAGNOSTIC_PHASE', process.call_args.kwargs['env'])
        self.assertFalse((self.android / 'app/build/reports/dependency-bootstrap').exists())

    def test_diagnostic_android_test_dependencies_are_included_when_present(self):
        (self.android / 'app/src/androidTest').mkdir(parents=True)
        self.assertIn(':app:assembleWatchAndroidDiagnosticAndroidTest', build.gradle_tasks('diagnostic'))
        self.assertNotIn(':app:assembleWatchAndroidDiagnosticAndroidTest', build.gradle_tasks('debug'))
        with patch.object(build, 'run_gradle', side_effect=lambda *args, **kwargs: self.write_inputs()) as process:
            with self.assertRaisesRegex(ValueError, 'not an accepted build'):
                build.build('debug')
        commands = [call.args[0] for call in process.call_args_list]
        normal_tasks = [argument for argument in commands[3] if argument.startswith(':')]
        self.assertEqual(normal_tasks[-2:],
                         [':app:assembleWatchAndroidDiagnosticAndroidTest', build.LOCK_COLLECTION_TASK])
        for phase in (2, 6):
            self.assertIn(':app:assembleWatchAndroidDiagnosticAndroidTest', commands[phase])
        for phase in (0, 1, 4, 5):
            self.assertNotIn(':app:assembleWatchAndroidDiagnosticAndroidTest', commands[phase])

    def test_room_schema_bootstrap_preserves_generated_version_and_still_exports_no_apk(self):
        source = self.android / build.ROOM_DATABASE_SOURCE
        source.parent.mkdir(parents=True)
        source.write_text('@Database(version = 1, exportSchema = true)\n', encoding='utf-8')
        schema = build.ROOM_SCHEMA_DIRECTORY + '/1.json'

        def generated(command, **kwargs):
            self.write_inputs((*build.DEPENDENCY_REPORT_FILES, schema))

        with patch.object(build, 'run_gradle', side_effect=generated), patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'not an accepted build'):
                build.build('debug')
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertIn(schema, manifest['missing_inputs_at_start'])
        self.assertIn(schema, manifest['files'])
        self.assertEqual(manifest['strict_input_hashes'], manifest['files'])
        self.assertTrue(manifest['report_matches_strict_inputs'])
        self.assertEqual(manifest['missing_outputs'], [])
        self.assertFalse(manifest['accepted'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_bootstrap_rejects_room_schema_drift_during_independent_strict_validation(self):
        source = self.android / build.ROOM_DATABASE_SOURCE
        source.parent.mkdir(parents=True)
        source.write_text('@Database(version = 1, exportSchema = true)\n', encoding='utf-8')
        schema = build.ROOM_SCHEMA_DIRECTORY + '/1.json'

        def generated(command, **kwargs):
            if '--write-locks' in command:
                self.write_inputs((*build.DEPENDENCY_REPORT_FILES, schema))
            else:
                (self.android / schema).write_text('different generated schema\n', encoding='utf-8')

        with patch.object(build, 'run_gradle', side_effect=generated), patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'changed dependency input bytes'):
                build.build('debug')
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(manifest['status'], 'failed-partial')
        self.assertFalse(manifest['report_matches_strict_inputs'])
        self.assertFalse(manifest['accepted'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_trusted_room_build_rejects_missing_committed_initial_schema(self):
        source = self.android / build.ROOM_DATABASE_SOURCE
        source.parent.mkdir(parents=True)
        source.write_text('@Database(version = 1, exportSchema = true)\n', encoding='utf-8')
        self.write_inputs()
        with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'push'}), patch.object(build, 'run_gradle') as process:
            with self.assertRaisesRegex(ValueError, 'Committed strict dependency inputs'):
                build.build('debug')
            process.assert_not_called()

    def test_strict_build_cannot_export_an_apk_after_changing_reviewed_schema_bytes(self):
        source = self.android / build.ROOM_DATABASE_SOURCE
        source.parent.mkdir(parents=True)
        source.write_text('@Database(version = 1, exportSchema = true)\n', encoding='utf-8')
        schema = self.android / build.ROOM_SCHEMA_DIRECTORY / '1.json'
        self.write_inputs((*build.DEPENDENCY_INPUTS, schema.relative_to(self.android).as_posix()))

        def changed(command, **kwargs):
            schema.write_text('changed schema fixture\n', encoding='utf-8')

        with patch.object(build, 'run_gradle', side_effect=changed), patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'changed the reviewed Room schema'):
                build.build('debug')
            inspect.assert_not_called()
        self.assertFalse((self.root / 'artifacts').exists())

    def test_strict_build_cannot_export_an_apk_after_generating_an_unreviewed_schema_version(self):
        self.write_inputs()
        schema = self.android / build.ROOM_SCHEMA_DIRECTORY / '2.json'

        def generated(command, **kwargs):
            schema.parent.mkdir(parents=True)
            schema.write_text('new schema fixture\n', encoding='utf-8')

        with patch.object(build, 'run_gradle', side_effect=generated), patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'changed the reviewed Room schema'):
                build.build('debug')
            inspect.assert_not_called()
        self.assertFalse((self.root / 'artifacts').exists())

    def test_schema_upgrade_bootstraps_current_version_even_with_reviewed_initial_inputs(self):
        source = self.android / build.ROOM_DATABASE_SOURCE
        source.parent.mkdir(parents=True)
        source.write_text('@Database(\n entities = [QueueEntity::class],\n version = 2,\n exportSchema = true,\n)', encoding='utf-8')
        initial = build.ROOM_SCHEMA_DIRECTORY + '/1.json'
        current = build.ROOM_SCHEMA_DIRECTORY + '/2.json'
        self.write_inputs((*build.DEPENDENCY_INPUTS, initial))
        with patch.object(build, 'run_gradle', side_effect=lambda *args, **kwargs:
                          self.write_inputs((*build.DEPENDENCY_REPORT_FILES, current))) as process, \
                patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'not an accepted build'):
                build.build('debug')
            self.assertEqual(process.call_count, 7)
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(manifest['missing_inputs_at_start'], [current])
        self.assertIn(initial, manifest['files'])
        self.assertIn(current, manifest['files'])
        self.assertEqual(manifest['strict_input_hashes'], manifest['files'])
        self.assertFalse(manifest['accepted'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_trusted_schema_upgrade_cannot_generate_current_version(self):
        source = self.android / build.ROOM_DATABASE_SOURCE
        source.parent.mkdir(parents=True)
        source.write_text('@Database(version = 2, exportSchema = true)', encoding='utf-8')
        self.write_inputs((*build.DEPENDENCY_INPUTS, build.ROOM_SCHEMA_DIRECTORY + '/1.json'))
        with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'push'}), patch.object(build, 'run_gradle') as process:
            with self.assertRaisesRegex(ValueError, 'Committed strict dependency inputs'):
                build.build('debug')
            process.assert_not_called()


if __name__ == '__main__':
    unittest.main()
