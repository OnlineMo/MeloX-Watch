"""Dependency bootstrap trust/failure boundaries; Gradle is never executed by these tests."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import build

SOURCE = 'a' * 40
ENV = {
    'GITHUB_ACTIONS': 'true', 'CI': 'true', 'GITHUB_EVENT_NAME': 'pull_request',
    'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch', 'GITHUB_REF': 'refs/pull/27/merge',
    'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2', 'SOURCE_SHA': SOURCE,
}
VERSION = {'versionName': '0.0.0', 'versionCode': '0'}


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
            }), patch.object(build.subprocess, 'run') as process, patch.object(build, 'inspect_apk') as inspect:
                with self.assertRaisesRegex(ValueError, 'Committed strict dependency inputs'):
                    build.build(mode)
                process.assert_not_called()
                inspect.assert_not_called()
                self.assertFalse((self.root / 'artifacts').exists())

    def test_optimized_pr_modes_cannot_enter_bootstrap(self):
        for mode in ('release', 'diagnostic'):
            with self.subTest(mode=mode), patch.object(build.subprocess, 'run') as process:
                with self.assertRaisesRegex(ValueError, 'PR builds only temporary Debug'):
                    build.build(mode)
                process.assert_not_called()

    def test_bootstrap_resolves_all_variants_unsigned_without_tokens_and_exports_no_apk(self):
        phases = []

        def generated(command, **kwargs):
            phase = len(phases)
            phases.append(command)
            self.assertIn('--dependency-verification=strict', command)
            if phase == 0:
                self.assertIn('--write-locks', command)
                self.assertEqual(command[command.index('--write-verification-metadata') + 1], 'sha256')
            elif phase == 1:
                self.assertIn('--write-locks', command)
                self.assertNotIn('--write-verification-metadata', command)
                self.assertIn(build.LOCK_COLLECTION_TASK, command)
            else:
                self.assertNotIn('--write-locks', command)
                self.assertNotIn('--write-verification-metadata', command)
                self.assertNotIn(build.LOCK_COLLECTION_TASK, command)
            variants = ('Debug', 'Release', 'Diagnostic') if phase < 2 else (('Debug', 'Release', 'Diagnostic')[phase - 2],)
            for variant in variants:
                self.assertIn(f':app:testWatchAndroid{variant}UnitTest', command)
                self.assertIn(f':app:lintWatchAndroid{variant}', command)
                self.assertIn(f':app:assembleWatchAndroid{variant}', command)
            self.assertEqual(kwargs['env']['CI_UNSIGNED_APK'], 'true')
            for token in ('GH_TOKEN', 'GITHUB_TOKEN', 'ACTIONS_RUNTIME_TOKEN', 'ACTIONS_ID_TOKEN_REQUEST_TOKEN'):
                self.assertNotIn(token, kwargs['env'])
            self.assertEqual(kwargs['cwd'], self.android)
            self.write_inputs(build.DEPENDENCY_REPORT_FILES)

        with patch.dict(os.environ, {name: 'unlogged-test-marker' for name in ('GH_TOKEN', 'GITHUB_TOKEN', 'ACTIONS_RUNTIME_TOKEN', 'ACTIONS_ID_TOKEN_REQUEST_TOKEN')}), \
                patch.object(build.subprocess, 'run', side_effect=generated) as process, \
                patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'not an accepted build'):
                build.build('debug')
            self.assertEqual(process.call_count, 5)
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(manifest['status'], 'generated-unreviewed')
        self.assertEqual(manifest['files'].keys(), set(build.DEPENDENCY_REPORT_FILES))
        self.assertEqual(manifest['missing_outputs'], [])
        self.assertEqual(manifest['source_sha'], SOURCE)
        self.assertFalse(manifest['accepted'])
        self.assertFalse(manifest['apk_exported'])
        self.assertTrue(all(phase['status'] == 'completed' for phase in manifest['phases']))
        self.assertEqual(manifest['strict_input_hashes'], manifest['files'])
        self.assertTrue(manifest['report_matches_strict_inputs'])
        self.assertFalse((self.root / 'artifacts').exists())

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

        with patch.object(build.subprocess, 'run', side_effect=generated), patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'not an accepted build'):
                build.build('debug')
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(len(calls), 5)
        copied = self.android / 'app/build/reports/dependency-bootstrap/app/gradle.lockfile'
        self.assertEqual(copied.read_text(encoding='utf-8'), 'normal task graph\n')
        self.assertEqual(manifest['strict_input_hashes']['app/gradle.lockfile'], build.sha256(copied))
        self.assertFalse(manifest['accepted'])

    def test_independent_strict_failure_does_not_complete_collection_or_export(self):
        def generated(command, **kwargs):
            if '--write-locks' in command:
                self.write_inputs()
            else:
                raise subprocess.CalledProcessError(1, command)

        with patch.object(build.subprocess, 'run', side_effect=generated) as process, patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaises(subprocess.CalledProcessError):
                build.build('debug')
            self.assertEqual(process.call_count, 3)
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(manifest['status'], 'failed-partial')
        self.assertEqual([phase.get('status') for phase in manifest['phases']],
                         ['completed', 'completed', 'started', None, None])
        self.assertFalse(manifest['accepted'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_independent_strict_input_drift_never_becomes_reviewable_complete_material(self):
        def generated(command, **kwargs):
            if '--write-locks' in command:
                self.write_inputs()
            else:
                (self.android / 'app/gradle.lockfile').write_text('unreviewed drift\n', encoding='utf-8')

        with patch.object(build.subprocess, 'run', side_effect=generated), patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'changed dependency input bytes'):
                build.build('debug')
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(manifest['status'], 'failed-partial')
        self.assertFalse(manifest['accepted'])
        self.assertFalse(manifest['apk_exported'])

    def test_gradle_failure_preserves_partial_reports_and_remains_failed(self):
        def failed(command, **kwargs):
            self.write_inputs(('gradle/verification-metadata.xml',))
            raise subprocess.CalledProcessError(1, command)

        with patch.object(build.subprocess, 'run', side_effect=failed), patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaises(subprocess.CalledProcessError):
                build.build('debug')
            inspect.assert_not_called()
        manifest = self.manifest()
        self.assertEqual(manifest['status'], 'failed-partial')
        self.assertEqual(manifest['missing_outputs'], ['app/gradle.lockfile'])
        self.assertFalse(manifest['accepted'])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_report_copy_drift_after_last_strict_pass_is_rejected_and_marked_partial(self):
        original_copy = build.shutil.copyfile

        def copy_with_drift(source, target):
            if source == self.android / 'app/gradle.lockfile':
                source.write_text('changed after strict validation\n', encoding='utf-8')
            return original_copy(source, target)

        with patch.object(build.subprocess, 'run', side_effect=lambda *args, **kwargs: self.write_inputs()), \
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
                patch.object(build, 'git', side_effect=git_command), patch.object(build.subprocess, 'run') as process:
            with self.assertRaisesRegex(ValueError, 'Committed strict dependency inputs'):
                build.build('debug')
            process.assert_not_called()

    def test_complete_committed_inputs_use_strict_build_without_generation(self):
        self.write_inputs()
        with patch.object(build.subprocess, 'run', side_effect=RuntimeError('mock stops before APK validation')) as process:
            with self.assertRaisesRegex(RuntimeError, 'mock stops'):
                build.build('debug')
        command = process.call_args.args[0]
        self.assertIn('--dependency-verification=strict', command)
        self.assertNotIn('--write-verification-metadata', command)
        self.assertNotIn('--write-locks', command)
        self.assertEqual(process.call_args.kwargs['env']['CI_UNSIGNED_APK'], 'false')
        self.assertFalse((self.android / 'app/build/reports/dependency-bootstrap').exists())

    def test_diagnostic_android_test_dependencies_are_included_when_present(self):
        (self.android / 'app/src/androidTest').mkdir(parents=True)
        self.assertIn(':app:assembleWatchAndroidDiagnosticAndroidTest', build.gradle_tasks('diagnostic'))
        self.assertNotIn(':app:assembleWatchAndroidDiagnosticAndroidTest', build.gradle_tasks('debug'))

    def test_room_schema_bootstrap_preserves_generated_version_and_still_exports_no_apk(self):
        source = self.android / build.ROOM_DATABASE_SOURCE
        source.parent.mkdir(parents=True)
        source.write_text('unexecuted Room declaration fixture\n', encoding='utf-8')
        schema = build.ROOM_SCHEMA_DIRECTORY + '/1.json'

        def generated(command, **kwargs):
            self.write_inputs((*build.DEPENDENCY_REPORT_FILES, schema))

        with patch.object(build.subprocess, 'run', side_effect=generated), patch.object(build, 'inspect_apk') as inspect:
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
        source.write_text('unexecuted Room declaration fixture\n', encoding='utf-8')
        schema = build.ROOM_SCHEMA_DIRECTORY + '/1.json'

        def generated(command, **kwargs):
            if '--write-locks' in command:
                self.write_inputs((*build.DEPENDENCY_REPORT_FILES, schema))
            else:
                (self.android / schema).write_text('different generated schema\n', encoding='utf-8')

        with patch.object(build.subprocess, 'run', side_effect=generated), patch.object(build, 'inspect_apk') as inspect:
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
        source.write_text('unexecuted Room declaration fixture\n', encoding='utf-8')
        self.write_inputs()
        with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'push'}), patch.object(build.subprocess, 'run') as process:
            with self.assertRaisesRegex(ValueError, 'Committed strict dependency inputs'):
                build.build('debug')
            process.assert_not_called()

    def test_strict_build_cannot_export_an_apk_after_changing_reviewed_schema_bytes(self):
        source = self.android / build.ROOM_DATABASE_SOURCE
        source.parent.mkdir(parents=True)
        source.write_text('unexecuted Room declaration fixture\n', encoding='utf-8')
        schema = self.android / build.ROOM_SCHEMA_DIRECTORY / '1.json'
        self.write_inputs((*build.DEPENDENCY_INPUTS, schema.relative_to(self.android).as_posix()))

        def changed(command, **kwargs):
            schema.write_text('changed schema fixture\n', encoding='utf-8')

        with patch.object(build.subprocess, 'run', side_effect=changed), patch.object(build, 'inspect_apk') as inspect:
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

        with patch.object(build.subprocess, 'run', side_effect=generated), patch.object(build, 'inspect_apk') as inspect:
            with self.assertRaisesRegex(ValueError, 'changed the reviewed Room schema'):
                build.build('debug')
            inspect.assert_not_called()
        self.assertFalse((self.root / 'artifacts').exists())


if __name__ == '__main__':
    unittest.main()
