"""Schema-only trust/failure policies. All Gradle and APK operations are mocked."""
import hashlib
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
    'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch', 'GITHUB_REF': 'refs/pull/31/merge',
    'GITHUB_RUN_ID': '321', 'GITHUB_RUN_ATTEMPT': '2', 'SOURCE_SHA': SOURCE,
}
CURRENT_BYTES = b'{"database":{"version":2,"identityHash":"policy-fixture"}}\n'


def blob(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()


class SchemaBootstrapPolicies(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.android = self.root / 'android'
        self.android.mkdir()
        self.old = build.ROOM_SCHEMA_DIRECTORY + '/1.json'
        self.current = build.ROOM_SCHEMA_DIRECTORY + '/2.json'
        self.committed = {}
        contexts = (
            patch.object(build, 'ROOT', self.root), patch.object(build, 'check'),
            patch.object(build, 'git', side_effect=self.git),
            patch.object(build, 'properties', return_value={'versionName': '0.0.0', 'versionCode': '0'}),
            patch.dict(os.environ, ENV, clear=True),
            patch.object(build, 'run_gradle', side_effect=self.export),
            patch.object(build, 'inspect_apk'),
            patch.object(build, 'bootstrap_dependencies'),
        )
        for context in contexts:
            mocked = context.start()
            self.addCleanup(context.stop)
            attribute = getattr(context, 'attribute', None)
            if attribute == 'run_gradle':
                self.process = mocked
            if attribute == 'bootstrap_dependencies':
                self.legacy = mocked
        self.committed_write('gradle/verification-metadata.xml', b'<verification-metadata/>\n')
        self.committed_write('app/gradle.lockfile', b'# strict policy fixture; not an Android input\n')
        self.committed_write(self.old, b'{"database":{"version":1}}\n')
        source = self.android / build.ROOM_DATABASE_SOURCE
        source.parent.mkdir(parents=True)
        source.write_text('@Database(version = 2, exportSchema = true)\n', encoding='utf-8')

    def write(self, relative, data):
        path = self.android / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def committed_write(self, relative, data):
        self.write(relative, data)
        self.committed[relative] = blob(data)

    def git(self, *args, **kwargs):
        if args == ('rev-parse', 'HEAD'):
            return SOURCE
        if args[:2] == ('cat-file', '-e'):
            relative = args[2].removeprefix('HEAD:android/')
            if relative not in self.committed:
                raise ValueError('git failed')
            return ''
        if args[:2] == ('rev-parse', '--verify'):
            relative = args[2].removeprefix('HEAD:android/')
            if relative not in self.committed:
                raise ValueError('git failed')
            return self.committed[relative]
        raise AssertionError(args)

    def export(self, command, **kwargs):
        self.assertFalse((self.android / self.current).exists())
        self.write(self.current, CURRENT_BYTES)

    def manifest(self):
        return json.loads((self.android / 'app/build/reports/dependency-bootstrap/bootstrap-manifest.json').read_bytes())

    def run_complete(self):
        with self.assertRaisesRegex(ValueError, 'not an accepted build'):
            build.build('debug')

    def test_three_complete_unsigned_variants_freeze_old_inputs_and_export_their_own_identical_schema(self):
        reviewed = build.generated_input_hashes()
        with patch.dict(os.environ, {key: 'unlogged-policy-marker' for key in (
            'GH_TOKEN', 'GITHUB_TOKEN', 'ACTIONS_RUNTIME_TOKEN', 'ACTIONS_ID_TOKEN_REQUEST_TOKEN')}):
            self.run_complete()
        self.assertEqual(self.process.call_count, 3)
        self.legacy.assert_not_called()
        deadlines = []
        for mode, name, call in zip(('debug', 'release', 'diagnostic'),
                                   build.ROOM_SCHEMA_COLLECTION_PHASES, self.process.call_args_list):
            self.assertEqual(call.args[0], build.gradle_command(build.gradle_tasks(mode)) + ['--info'])
            self.assertEqual(call.kwargs['phase'], name)
            self.assertEqual(call.kwargs['env']['CI_UNSIGNED_APK'], 'true')
            self.assertEqual(call.kwargs['env']['CI_DEPENDENCY_DIAGNOSTIC_PHASE'], name)
            for key in ('GH_TOKEN', 'GITHUB_TOKEN', 'ACTIONS_RUNTIME_TOKEN', 'ACTIONS_ID_TOKEN_REQUEST_TOKEN'):
                self.assertNotIn(key, call.kwargs['env'])
            for argument in ('--write-locks', '--write-verification-metadata', build.LOCK_COLLECTION_TASK):
                self.assertNotIn(argument, call.args[0])
            deadlines.append(call.kwargs['total_deadline'])
        self.assertEqual(len(set(deadlines)), 1)
        manifest = self.manifest()
        self.assertEqual(manifest['schema_version'], 3)
        self.assertEqual(manifest['collection_kind'], 'room-schema-strict-variants')
        self.assertEqual(manifest['missing_inputs_at_start'], [self.current])
        self.assertEqual(manifest['reviewed_input_hashes'], reviewed)
        self.assertEqual(manifest['strict_input_hashes'], manifest['files'])
        self.assertTrue(manifest['report_matches_strict_inputs'])
        self.assertEqual(manifest['report_errors'], [])
        self.assertTrue(manifest['schema_exports_match'])
        self.assertEqual(manifest['status'], 'generated-unreviewed')
        self.assertFalse(manifest['accepted'])
        self.assertFalse(manifest['apk_exported'])
        self.assertEqual(list(manifest['schema_exports']), list(build.ROOM_SCHEMA_COLLECTION_PHASES))
        for export in manifest['schema_exports'].values():
            path = self.android / 'app/build/reports/dependency-bootstrap' / export['file']
            self.assertEqual(path.read_bytes(), CURRENT_BYTES)
            self.assertEqual(export['sha256'], manifest['files'][self.current])
        self.assertFalse((self.root / 'artifacts').exists())

    def test_supplied_overall_deadline_is_shared_without_reset(self):
        with patch.object(build.time, 'monotonic', side_effect=AssertionError('deadline reset')):
            with self.assertRaisesRegex(ValueError, 'not an accepted build'):
                build.bootstrap_room_schema(SOURCE, [self.current], total_deadline=12345.67)
        self.assertEqual([call.kwargs['total_deadline'] for call in self.process.call_args_list],
                         [12345.67] * 3)

    def test_existing_unreviewed_current_schema_is_rejected_before_any_call(self):
        self.write(self.current, CURRENT_BYTES)
        with self.assertRaisesRegex(ValueError, 'existing unreviewed current'):
            build.build('debug')
        self.process.assert_not_called()
        self.legacy.assert_not_called()

    def test_xml_app_lock_root_lock_and_all_old_schemas_must_match_head_bytes(self):
        self.committed_write('gradle.lockfile', b'# reviewed optional root lock\n')
        self.committed_write(build.ROOM_SCHEMA_DIRECTORY + '/7.json', b'{"historical":7}\n')
        for relative in tuple(self.committed):
            with self.subTest(relative=relative):
                path = self.android / relative
                before = path.read_bytes()
                path.write_bytes(before + b'changed\n')
                try:
                    with self.assertRaisesRegex(ValueError, 'committed HEAD bytes'):
                        build.build('debug')
                    self.process.assert_not_called()
                    self.legacy.assert_not_called()
                finally:
                    path.write_bytes(before)

    def test_uncommitted_optional_root_lock_and_historical_schema_cannot_unlock_fastpath(self):
        for relative in ('gradle.lockfile', build.ROOM_SCHEMA_DIRECTORY + '/7.json'):
            with self.subTest(relative=relative):
                path = self.write(relative, b'uncommitted policy bytes\n')
                try:
                    with self.assertRaisesRegex(ValueError, 'git failed'):
                        build.build('debug')
                    self.process.assert_not_called()
                    self.legacy.assert_not_called()
                finally:
                    path.unlink()

    def test_symlink_input_and_symlink_ancestor_are_rejected_without_gradle(self):
        original = Path.is_symlink
        target = self.android / 'app/gradle.lockfile'
        for marked in (target, target.parent):
            with self.subTest(marked=marked), patch.object(
                    Path, 'is_symlink', lambda path: path == marked or original(path)):
                with self.assertRaises(ValueError):
                    build.bootstrap_room_schema(SOURCE, [self.current])
                self.process.assert_not_called()
                self.legacy.assert_not_called()

    def test_report_root_or_parent_link_rejects_before_any_variant_or_manifest_write(self):
        report = self.android / build.ROOM_SCHEMA_REPORT_DIRECTORY
        report.mkdir(parents=True)
        original = Path.is_symlink
        for marked in (report, report.parent):
            with self.subTest(marked=marked), patch.object(
                    Path, 'is_symlink', lambda path: path == marked or original(path)), \
                    patch.object(build, 'write_json') as writes:
                with self.assertRaises(ValueError):
                    build.bootstrap_room_schema(SOURCE, [self.current])
                self.process.assert_not_called()
                writes.assert_not_called()
                self.assertEqual(list(report.iterdir()), [])
                self.assertFalse((self.android / self.current).exists())

    def test_old_input_parent_link_after_debug_stops_before_release(self):
        marked, active, original = self.android / 'gradle', False, Path.is_symlink
        before = {name: (self.android / name).read_bytes() for name in self.committed}
        def export(command, **kwargs):
            nonlocal active
            self.export(command, **kwargs)
            if kwargs['phase'] == 'strict-debug':
                active = True
        self.process.side_effect = export
        with patch.object(Path, 'is_symlink', lambda path: (active and path == marked) or original(path)):
            with self.assertRaises(ValueError):
                build.bootstrap_room_schema(SOURCE, [self.current])
        self.process.assert_called_once()
        self.assertEqual(self.process.call_args.kwargs['phase'], 'strict-debug')
        self.assertEqual(self.manifest()['status'], 'failed-partial')
        self.assertEqual({name: (self.android / name).read_bytes() for name in before}, before)

    def preserve_primary_with_unavailable_manifest(self, primary):
        def failure(command, **kwargs):
            self.export(command, **kwargs)
            raise primary
        self.process.side_effect = failure
        with patch.object(build, 'write_json', side_effect=OSError('unlogged-report-fixture')):
            with self.assertRaises(type(primary)) as observed:
                build.bootstrap_room_schema(SOURCE, [self.current])
        self.assertIs(observed.exception, primary)
        self.assertEqual(primary.__notes__, ['Schema-only bootstrap report preservation failed'])
        self.process.assert_called_once()
        self.legacy.assert_not_called()
        self.assertFalse((self.root / 'artifacts').exists())

    def test_natural_failure_keeps_the_exact_object_when_final_manifest_is_unavailable(self):
        self.preserve_primary_with_unavailable_manifest(
            subprocess.CalledProcessError(134, ['unexecuted-gradle-policy']))

    def test_timeout_keeps_the_exact_object_when_final_manifest_is_unavailable(self):
        self.preserve_primary_with_unavailable_manifest(
            BuildTimeout(['unexecuted-gradle-policy'], 'strict-debug', 'phase deadline', 1200))

    def test_each_strict_failure_and_deadline_stops_and_preserves_failed_partial(self):
        timeout_seconds = {'phase deadline': 1200, 'no build output': 300,
                           'active test deadline including setup/cleanup': 240, 'overall build deadline': 2700}
        for index, phase in enumerate(build.ROOM_SCHEMA_COLLECTION_PHASES):
            for failure_kind in ('unknown dependency hash or lock', 'native executor crash',
                                 'phase deadline', 'no build output',
                                 'active test deadline including setup/cleanup', 'overall build deadline'):
                with self.subTest(phase=phase, failure=failure_kind), tempfile.TemporaryDirectory() as name:
                    # Separate output directory for every failure, preserving each old input set.
                    previous = self.android
                    self.android = Path(name) / 'android'
                    self.android.mkdir()
                    for relative in self.committed:
                        self.write(relative, (previous / relative).read_bytes())
                    self.write(build.ROOM_DATABASE_SOURCE, (previous / build.ROOM_DATABASE_SOURCE).read_bytes())
                    def failing(command, **kwargs):
                        self.export(command, **kwargs)
                        if kwargs['phase'] == phase:
                            if failure_kind in timeout_seconds:
                                raise BuildTimeout(command, phase, failure_kind, timeout_seconds[failure_kind])
                            raise subprocess.CalledProcessError(1, command)
                    try:
                        with patch.object(build, 'ROOT', self.android.parent), \
                                patch.object(build, 'run_gradle', side_effect=failing) as process:
                            with self.assertRaises((subprocess.CalledProcessError, BuildTimeout)):
                                build.bootstrap_room_schema(SOURCE, [self.current], total_deadline=12345)
                            self.assertEqual(process.call_count, index + 1)
                            self.assertEqual([call.kwargs['total_deadline'] for call in process.call_args_list],
                                             [12345] * (index + 1))
                        manifest = self.manifest()
                        self.assertEqual(manifest['status'], 'failed-partial')
                        self.assertEqual([item.get('status') for item in manifest['phases']],
                                         ['completed'] * index + ['started'] + [None] * (2 - index))
                        self.assertEqual(len(manifest['schema_exports']), index)
                        self.assertFalse(manifest['accepted'])
                        self.assertFalse(manifest['apk_exported'])
                        self.legacy.assert_not_called()
                    finally:
                        self.android = previous

    def test_missing_current_export_stops_before_next_variant(self):
        self.process.side_effect = lambda *args, **kwargs: None
        with self.assertRaisesRegex(ValueError, 'did not export'):
            build.build('debug')
        self.process.assert_called_once()
        self.assertEqual(self.manifest()['missing_outputs'], [self.current])
        self.assertEqual(self.manifest()['status'], 'failed-partial')
        self.legacy.assert_not_called()

    def test_every_variant_rejects_reviewed_input_drift_without_following_calls(self):
        for index, phase in enumerate(build.ROOM_SCHEMA_COLLECTION_PHASES):
            for relative in (*build.DEPENDENCY_INPUTS, self.old, 'gradle.lockfile'):
                with self.subTest(phase=phase, input=relative), tempfile.TemporaryDirectory() as name:
                    previous = self.android
                    self.android = Path(name) / 'android'
                    self.android.mkdir()
                    for input_path in self.committed:
                        self.write(input_path, (previous / input_path).read_bytes())
                    self.write(build.ROOM_DATABASE_SOURCE, (previous / build.ROOM_DATABASE_SOURCE).read_bytes())
                    def drifting(command, **kwargs):
                        self.export(command, **kwargs)
                        if kwargs['phase'] == phase:
                            self.write(relative, b'new or drifted unreviewed input\n')
                    try:
                        with patch.object(build, 'ROOT', self.android.parent), \
                                patch.object(build, 'run_gradle', side_effect=drifting) as process:
                            with self.assertRaisesRegex(ValueError, 'changed reviewed dependency or old schema'):
                                build.bootstrap_room_schema(SOURCE, [self.current])
                            self.assertEqual(process.call_count, index + 1)
                        self.assertEqual(self.manifest()['status'], 'failed-partial')
                        self.legacy.assert_not_called()
                    finally:
                        self.android = previous

    def test_independent_current_schema_drift_never_becomes_complete_material(self):
        for failing_phase in build.ROOM_SCHEMA_COLLECTION_PHASES[1:]:
            with self.subTest(phase=failing_phase), tempfile.TemporaryDirectory() as name:
                previous = self.android
                self.android = Path(name) / 'android'
                self.android.mkdir()
                for relative in self.committed:
                    self.write(relative, (previous / relative).read_bytes())
                self.write(build.ROOM_DATABASE_SOURCE, (previous / build.ROOM_DATABASE_SOURCE).read_bytes())
                def differing(command, **kwargs):
                    self.write(self.current, CURRENT_BYTES + (b'different\n' if kwargs['phase'] == failing_phase else b''))
                try:
                    with patch.object(build, 'ROOT', self.android.parent), \
                            patch.object(build, 'run_gradle', side_effect=differing) as process:
                        with self.assertRaisesRegex(ValueError, 'different current Room schema bytes'):
                            build.bootstrap_room_schema(SOURCE, [self.current])
                        self.assertEqual(process.call_count, build.ROOM_SCHEMA_COLLECTION_PHASES.index(failing_phase) + 1)
                    self.assertEqual(self.manifest()['status'], 'failed-partial')
                    self.assertFalse(self.manifest()['report_matches_strict_inputs'])
                finally:
                    self.android = previous

    def test_report_copy_drift_cannot_complete_even_after_three_strict_passes(self):
        original_copy = build.shutil.copyfile
        def drifting(source, target):
            if source == self.android / self.current:
                source.write_bytes(CURRENT_BYTES + b'drift after strict passes\n')
            return original_copy(source, target)
        with patch.object(build.shutil, 'copyfile', side_effect=drifting):
            with self.assertRaisesRegex(ValueError, 'strict input bytes in reports'):
                build.build('debug')
        self.assertEqual(self.process.call_count, 3)
        self.assertEqual(self.manifest()['status'], 'failed-partial')
        self.assertFalse(self.manifest()['report_matches_strict_inputs'])
        self.assertFalse(self.manifest()['apk_exported'])

    def test_diagnostic_instrumentation_task_is_retained(self):
        (self.android / 'app/src/androidTest').mkdir(parents=True)
        self.run_complete()
        for index, call in enumerate(self.process.call_args_list):
            self.assertEqual(':app:assembleWatchAndroidDiagnosticAndroidTest' in call.args[0], index == 2)

    def test_saved_variant_export_drift_is_rejected_after_all_three_calls(self):
        original_copy = build.shutil.copyfile
        def drifting(source, target):
            export = self.android / 'app/build/reports/dependency-bootstrap/schema-exports/strict-debug/2.json'
            if source == self.android / 'app/gradle.lockfile':
                export.write_bytes(b'drifted variant original\n')
            return original_copy(source, target)
        with patch.object(build.shutil, 'copyfile', side_effect=drifting):
            with self.assertRaisesRegex(ValueError, 'strict input bytes in reports'):
                build.build('debug')
        self.assertEqual(self.process.call_count, 3)
        self.assertEqual(self.manifest()['status'], 'failed-partial')
        self.assertFalse(self.manifest()['schema_exports_match'])
        self.assertTrue(self.manifest()['report_matches_strict_inputs'])
        self.assertFalse(self.manifest()['apk_exported'])

    def test_missing_dependency_or_initial_schema_uses_the_existing_full_path(self):
        for relative in (*build.DEPENDENCY_INPUTS, self.old):
            with self.subTest(relative=relative):
                path = self.android / relative
                before = path.read_bytes()
                path.unlink()
                try:
                    self.legacy.side_effect = RuntimeError('full path selected')
                    with self.assertRaisesRegex(RuntimeError, 'full path selected'):
                        build.build('debug')
                    self.process.assert_not_called()
                    self.legacy.assert_called_once()
                    self.legacy.reset_mock()
                finally:
                    path.write_bytes(before)

    def test_trusted_events_and_optimized_pr_modes_cannot_enter_schema_collection(self):
        for event, mode in (('push', 'debug'), ('workflow_dispatch', 'debug'),
                            ('workflow_dispatch', 'release'), ('pull_request', 'release'),
                            ('pull_request', 'diagnostic')):
            with self.subTest(event=event, mode=mode), patch.dict(os.environ, {
                    'GITHUB_EVENT_NAME': event, 'CANDIDATE_OPERATION': 'prepare-candidate'}):
                with self.assertRaises(ValueError):
                    build.build(mode)
                self.process.assert_not_called()
                self.legacy.assert_not_called()
        with patch.dict(os.environ, {'GITHUB_EVENT_NAME': 'push'}):
            with self.assertRaisesRegex(ValueError, 'unsigned PR bootstrap'):
                build.bootstrap_room_schema(SOURCE, [self.current])
            self.process.assert_not_called()


if __name__ == '__main__':
    unittest.main()
