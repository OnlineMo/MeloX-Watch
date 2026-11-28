"""Python-only deadline/protocol tests; Linux cloud tests use harmless Python child processes."""
from contextlib import ExitStack
import errno
import io
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import build_process as monitor


def event(kind, name='queued-close', task=':app:testWatchAndroidDebugUnitTest', result=None):
    fields = {'event': kind, 'task': task, 'className': 'DeadlineFixture', 'name': name}
    if result is not None:
        fields['result'] = result
    return monitor.TEST_EVENT_PREFIX + json.dumps(fields, ensure_ascii=False)


def native_crash_fixture(native=None, java=None, after=''):
    native = ['C  [libc.so.6+0x45d20]', 'V  [libjvm.so+0x1234]'] if native is None else native
    java = ['j  androidx.sqlite.driver.bundled.BundledSQLiteConnection.nativeOpen(Ljava/lang/String;I)J+0',
            'J 42 c2 java.lang.Thread.run()V (12 bytes) @ 0x1234'] if java is None else java
    return ('# A fatal error has been detected by the Java Runtime Environment:\n'
            '# SIGBUS (0x7) at pc=0x1234, pid=123, tid=456\n'
            '# Problematic frame:\n'
            '# C  [/fixture-private-path/libc.so.6+0x45d20] private-symbol\n'
            '# VM Arguments: private-arguments\n'
            '---------------  T H R E A D  ---------------\n'
            'Current thread (0x1234): JavaThread "fixture-private-thread" [_thread_in_native, id=456]\n'
            'Stack: [0x1000,0x2000], sp=0x1500\n'
            'Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)\n' +
            '\n'.join(native) + '\n\n'
            'Java frames: (J=compiled Java code, j=interpreted, Vv=VM code)\n' +
            '\n'.join(java) + '\n\n' + after).encode('ascii')


class NativeCrashPolicyTests(unittest.TestCase):
    def observed(self, **changes):
        values = {'st_dev': 1, 'st_ino': 2, 'st_mode': stat.S_IFREG | 0o600, 'st_nlink': 1,
                  'st_size': 100, 'st_mtime_ns': 20, 'st_ctime_ns': 20}
        values.update(changes)
        return SimpleNamespace(**values)

    def test_leaf_names_and_directory_components_cannot_escape_the_fixed_roots(self):
        self.assertIsNotNone(monitor._NATIVE_CRASH_NAME.fullmatch('hs_err_pid123.log'))
        for name in ('../hs_err_pid123.log', '/hs_err_pid123.log', 'hs_err_pid123.log.tmp',
                     'hs_err_pid0.log', 'hs_err_pid123.log\n', 'core.123', 'hs_err_pid123.log/child'):
            with self.subTest(name=name):
                self.assertIsNone(monitor._NATIVE_CRASH_NAME.fullmatch(name))
        for part in ('..', '.', '/tmp', 'app/child', 'app\\child', ''):
            with self.subTest(part=part), self.assertRaises(ValueError):
                with monitor._native_crash_directory('.', (part,), []):
                    self.fail('Invalid path reached an OS open')
        for phase in ('../phase', '', 'phase/private', 'Phase', 'phase\n'):
            with self.subTest(phase=phase), self.assertRaises(ValueError):
                monitor.snapshot_native_crashes('.', phase)

    def test_symlink_directory_fifo_and_hardlink_are_ineligible_before_read(self):
        for mode in (stat.S_IFLNK, stat.S_IFDIR, stat.S_IFIFO, stat.S_IFSOCK):
            with self.subTest(mode=mode):
                self.assertEqual(monitor._native_crash_eligibility(self.observed(st_mode=mode), 10, 30, 1000),
                                 'non_regular')
        self.assertEqual(monitor._native_crash_eligibility(self.observed(st_nlink=2), 10, 30, 1000), 'linked_file')
        self.assertIsNone(monitor._native_crash_eligibility(self.observed(), 10, 30, 1000))

    def test_both_timestamps_and_the_complete_read_budget_are_required(self):
        for field in ('st_mtime_ns', 'st_ctime_ns'):
            for value in (9, 31):
                with self.subTest(field=field, value=value):
                    self.assertEqual(monitor._native_crash_eligibility(self.observed(**{field: value}), 10, 30, 1000),
                                     'outside_phase')
        for size, available in ((0, 1000), (101, 100), (monitor.MAX_NATIVE_CRASH_FILE_BYTES + 1,
                                                       monitor.MAX_NATIVE_CRASH_TOTAL_BYTES)):
            with self.subTest(size=size, available=available):
                self.assertEqual(monitor._native_crash_eligibility(self.observed(st_size=size), 10, 30, available),
                                 'size_limit')

    def test_cross_phase_and_success_capture_are_rejected_before_any_io(self):
        snapshot = monitor._NativeCrashSnapshot('debug-build', 10, ())
        with patch.object(monitor, '_native_crash_directory') as directory:
            for binding, reason in (({'phase': 'release-build'}, 'timeout'),
                                    ({'phase': 'debug-build'}, 'completed')):
                with self.subTest(binding=binding, reason=reason), self.assertRaises(ValueError):
                    monitor.collect_native_crashes('.', Path('.'), snapshot, binding, reason)
            directory.assert_not_called()


class NativeCrashParserTests(unittest.TestCase):
    def test_fatal_kind_and_current_native_java_frames_have_only_whitelisted_fields(self):
        result = monitor.parse_native_crash(native_crash_fixture())
        self.assertEqual(result['fatal_kind'], 'SIGBUS')
        self.assertEqual(result['current_thread_kind'], 'JavaThread')
        self.assertEqual(result['problematic_frame'], {'kind': 'C', 'module': 'libc.so.6', 'offset': '0x45d20'})
        self.assertEqual(result['native_frames'], [{'kind': 'C', 'module': 'libc.so.6', 'offset': '0x45d20'},
                                                 {'kind': 'V', 'module': 'libjvm.so', 'offset': '0x1234'}])
        self.assertEqual(result['java_frames'], [
            {'kind': 'j', 'class': 'androidx.sqlite.driver.bundled.BundledSQLiteConnection', 'method': 'nativeOpen'},
            {'kind': 'J', 'class': 'java.lang.Thread', 'method': 'run'}])
        serialized = json.dumps(result)
        for marker in ('fixture-private', 'private-symbol', 'private-arguments', 'Ljava/lang', 'pid=', 'tid=',
                       'sp=', '@ 0x'):
            self.assertNotIn(marker, serialized)

    def test_arguments_environment_properties_urls_addresses_and_other_thread_stacks_are_not_copied(self):
        address = '.'.join(str(number) for number in (203, 0, 113, 7))
        private = ('---------------  P R O C E S S  ---------------\n'
                   'VM Arguments: password=fixture-secret\n'
                   'Command Line: -Dprivate.property=fixture-secret\n'
                   'Environment Variables: PRIVATE=fixture-secret\n'
                   'URL=https://fixture.invalid/private\n' + 'host=' + address + '\n'
                   'Current thread (0x1234): JavaThread "another-thread"\n'
                   'Java frames: (J=compiled Java code, j=interpreted, Vv=VM code)\n'
                   'j  java.lang.Thread.otherThread()V\n')
        result = monitor.parse_native_crash(native_crash_fixture(after=private))
        self.assertEqual(len(result['java_frames']), 2)
        serialized = json.dumps(result)
        for marker in ('fixture-secret', 'private.property', 'PRIVATE', 'fixture.invalid', address,
                       'another-thread', 'otherThread'):
            self.assertNotIn(marker, serialized)

    def test_unknown_modules_packages_and_bad_frame_shapes_are_filtered(self):
        result = monitor.parse_native_crash(native_crash_fixture(
            native=['C  [private-library.so+0x1234]', 'C  [libc.so.6+0x' + 'a' * 20 + ']'],
            java=['j  private.package.Person.name()V', 'j  java.lang.Thread.bad/name()V',
                  'j  java.lang.Thread.<init>()V']))
        self.assertEqual(result['native_frames'], [])
        self.assertEqual(result['java_frames'], [{'kind': 'j', 'class': 'java.lang.Thread', 'method': '<init>'}])
        self.assertEqual(result['filtered_frame_lines'], 4)
        self.assertNotIn('private-library', json.dumps(result))
        self.assertNotIn('private.package', json.dumps(result))

    def test_free_form_suffixes_descriptors_and_native_symbols_are_never_serialized(self):
        result = monitor.parse_native_crash(native_crash_fixture(
            native=['C  [/fixture-private-path/libc.so.6+0x1234] secret@fixture.invalid + private=fixture-secret'],
            java=['j  java.lang.Thread.run(Lprivate/type;)V+0 private=fixture-secret']))
        self.assertEqual(result['native_frames'], [{'kind': 'C', 'module': 'libc.so.6', 'offset': '0x1234'}])
        self.assertEqual(result['java_frames'], [{'kind': 'j', 'class': 'java.lang.Thread', 'method': 'run'}])
        for marker in ('fixture', 'secret', 'private', '@'):
            self.assertNotIn(marker, json.dumps(result))

    def test_stacks_require_the_first_current_thread_section_and_stop_at_other_content(self):
        raw = native_crash_fixture().replace(b'Current thread (0x1234):', b'Unrecognized thread (0x1234):')
        result = monitor.parse_native_crash(raw)
        self.assertIsNone(result['current_thread_kind'])
        self.assertEqual(result['native_frames'], [])
        self.assertEqual(result['java_frames'], [])
        result = monitor.parse_native_crash(native_crash_fixture(native=['Command Line: private'],
                                                                 java=['j  java.lang.Thread.unrelated()V']))
        self.assertEqual(result['java_frames'], [])

    def test_repeated_stack_heading_cannot_append_a_second_thread_stack(self):
        after = ('Java frames: (J=compiled Java code, j=interpreted, Vv=VM code)\n'
                 'j  java.lang.Thread.unrelated()V\n')
        result = monitor.parse_native_crash(native_crash_fixture(after=after))
        self.assertEqual(len(result['java_frames']), 2)
        self.assertNotIn('unrelated', json.dumps(result))

    def test_line_frame_and_raw_limits_do_not_export_prefixes_of_oversized_lines(self):
        raw = native_crash_fixture(native=['C  [libc.so.6+0x1111] ' + 'x' * monitor.MAX_NATIVE_CRASH_LINE_BYTES,
                                          'C  [libc.so.6+0x2222]'])
        result = monitor.parse_native_crash(raw)
        self.assertEqual(result['native_frames'], [{'kind': 'C', 'module': 'libc.so.6', 'offset': '0x2222'}])
        self.assertEqual(result['filtered_frame_lines'], 1)
        with patch.object(monitor, 'MAX_NATIVE_CRASH_FRAMES', 1):
            result = monitor.parse_native_crash(native_crash_fixture())
        self.assertEqual(len(result['native_frames']), 1)
        self.assertEqual(len(result['java_frames']), 1)
        self.assertTrue(result['frame_limit_reached'])
        with patch.object(monitor, 'MAX_NATIVE_CRASH_LINES', 4):
            result = monitor.parse_native_crash(native_crash_fixture())
        self.assertTrue(result['line_limit_reached'])
        self.assertEqual(result['java_frames'], [])
        with patch.object(monitor, 'MAX_NATIVE_CRASH_FILE_BYTES', 4), self.assertRaises(monitor._NativeCrashLimit):
            monitor.parse_native_crash(b'12345')

    def test_non_ascii_frames_and_files_without_a_valid_fatal_header_are_not_relabelled(self):
        self.assertIsNone(monitor.parse_native_crash(b'Environment Variables: fixture-private\n'))
        self.assertIsNone(monitor.parse_native_crash(native_crash_fixture().replace(b'# SIGBUS', b'# UNKNOWN')))
        raw = native_crash_fixture(native=['C  [libc.so.6+0x1234]']).replace(b'0x1234]\n', b'0x1234] \xff\n')
        result = monitor.parse_native_crash(raw)
        self.assertEqual(result['native_frames'], [])
        self.assertEqual(result['filtered_frame_lines'], 1)

    def test_parser_respects_the_shared_capture_deadline(self):
        with self.assertRaises(monitor._NativeCrashLimit) as failure:
            monitor.parse_native_crash(native_crash_fixture(), deadline=time.monotonic() - 1)
        self.assertEqual(str(failure.exception), 'capture_deadline')


@unittest.skipUnless(sys.platform.startswith('linux') and Path('/proc').is_dir(),
                     'Linux no-follow/dirfd native crash checks run in cloud; no local Android execution')
class NativeCrashFilesTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.phase = 'debug-build'
        self.report = self.root / 'app/build/reports/ci-process' / self.phase
        self.report.mkdir(parents=True)
        self.raw_directory = self.root / 'app/build/ci-native-crash' / self.phase
        self.binding = {'phase': self.phase, 'source_sha': 'a' * 40, 'build_sha': 'b' * 40,
                        'run_id': '123', 'run_attempt': '2'}

    def capture(self, snapshot=None, binding=None):
        return monitor.collect_native_crashes(self.root, self.report, snapshot or self.snapshot,
                                             binding or self.binding, 'natural-nonzero-exit')

    def new_crash(self, directory=None, name='hs_err_pid123.log', raw=None):
        directory = self.raw_directory if directory is None else directory
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / name
        path.write_bytes(native_crash_fixture() if raw is None else raw)
        return path

    def test_new_phase_raw_file_stays_outside_reports_and_summary_binds_exact_bytes(self):
        self.snapshot = monitor.snapshot_native_crashes(self.root, self.phase)
        raw = self.new_crash()
        result = self.capture()
        self.assertEqual(result['status'], 'COLLECTED')
        self.assertFalse(result['raw_uploaded'])
        self.assertEqual(len(result['files']), 1)
        item = result['files'][0]
        summary = (self.report / item['summary_file']).read_bytes()
        self.assertEqual(monitor.hashlib.sha256(summary).hexdigest(), item['summary_sha256'])
        self.assertEqual(monitor.hashlib.sha256(raw.read_bytes()).hexdigest(), item['raw_sha256'])
        parsed = json.loads(summary)
        for key, value in self.binding.items():
            self.assertEqual(parsed[key], value)
        self.assertNotIn('fixture-private', summary.decode())
        self.assertEqual(list(self.report.glob('hs_err*')), [])
        self.assertEqual(len(list(self.report.iterdir())), 1)
        self.assertTrue(raw.is_file())

    def test_old_filename_even_if_rewritten_and_old_timestamp_under_new_name_are_excluded(self):
        old = self.new_crash()
        self.snapshot = monitor.snapshot_native_crashes(self.root, self.phase)
        old.write_bytes(native_crash_fixture(java=['j  java.lang.Thread.rewritten()V']))
        stale = self.new_crash(name='hs_err_pid124.log')
        os.utime(stale, ns=(self.snapshot.started_ns - 1000000, self.snapshot.started_ns - 1000000))
        result = self.capture()
        self.assertEqual(result['files'], [])
        self.assertEqual(result['omitted']['old_file'], 1)
        self.assertEqual(result['omitted']['outside_phase'], 1)
        self.assertEqual(list(self.report.iterdir()), [])

    def test_symlink_leaf_hardlink_fifo_and_symlink_parent_are_never_read(self):
        self.raw_directory.mkdir(parents=True)
        self.snapshot = monitor.snapshot_native_crashes(self.root, self.phase)
        original = self.new_crash(name='original.txt')
        (self.raw_directory / 'hs_err_pid123.log').symlink_to(original)
        os.link(original, self.raw_directory / 'hs_err_pid124.log')
        os.mkfifo(self.raw_directory / 'hs_err_pid125.log')
        result = self.capture()
        self.assertEqual(result['files'], [])
        self.assertEqual(result['omitted']['non_regular'], 2)
        self.assertEqual(result['omitted']['linked_file'], 1)
        self.assertEqual(list(self.report.iterdir()), [])
        for path in self.raw_directory.iterdir():
            path.unlink()
        self.raw_directory.rmdir()
        outside = self.root / 'outside'
        outside.mkdir()
        self.raw_directory.symlink_to(outside, target_is_directory=True)
        self.new_crash(directory=outside)
        result = self.capture()
        self.assertEqual(result['files'], [])
        self.assertIn('directory_unavailable', result['omitted'])

    def test_replaced_existing_root_and_changed_file_are_not_exported(self):
        self.raw_directory.mkdir(parents=True)
        self.snapshot = monitor.snapshot_native_crashes(self.root, self.phase)
        self.raw_directory.rename(self.root / 'retired-raw')
        self.new_crash()
        result = self.capture()
        self.assertEqual(result['files'], [])
        self.assertEqual(result['omitted']['directory_changed'], 1)
        self.snapshot = monitor.snapshot_native_crashes(self.root, self.phase)
        candidate = self.new_crash(name='hs_err_pid124.log')
        original = monitor._read_native_crash
        def changing(descriptor, name, initial, deadline):
            candidate.write_bytes(candidate.read_bytes() + b'changed\n')
            return original(descriptor, name, initial, deadline)
        with patch.object(monitor, '_read_native_crash', side_effect=changing):
            result = self.capture()
        self.assertEqual(result['files'], [])
        self.assertEqual(result['omitted']['file_changed'], 1)

    def test_scan_file_total_byte_and_summary_budgets_fail_closed(self):
        self.snapshot = monitor.snapshot_native_crashes(self.root, self.phase)
        for number in range(5):
            self.new_crash(name=f'hs_err_pid{number + 123}.log', raw=native_crash_fixture(
                java=[f'j  java.lang.Thread.method{number}()V']))
        with patch.object(monitor, 'MAX_NATIVE_CRASH_FILES', 2):
            result = self.capture()
        self.assertEqual(len(result['files']), 2)
        self.assertIn('capture_limit', result['omitted'])
        for path in self.report.iterdir():
            path.unlink()
        with patch.object(monitor, 'MAX_NATIVE_CRASH_TOTAL_BYTES', 1):
            result = self.capture()
        self.assertEqual(result['files'], [])
        self.assertIn('size_limit', result['omitted'])
        with patch.object(monitor, 'MAX_NATIVE_CRASH_DIRECTORY_ENTRIES', 1):
            result = self.capture()
        self.assertEqual(result['files'], [])
        self.assertIn('directory_unavailable', result['omitted'])
        with patch.object(monitor, 'MAX_NATIVE_CRASH_SUMMARY_BYTES', 1):
            result = self.capture()
        self.assertEqual(result['files'], [])
        self.assertIn('summary_limit', result['omitted'])

    def test_partial_old_baseline_and_other_phase_files_cannot_gain_fresh_attribution(self):
        self.new_crash()
        with patch.object(monitor, 'MAX_NATIVE_CRASH_DIRECTORY_ENTRIES', 1):
            self.new_crash(name='hs_err_pid124.log')
            self.snapshot = monitor.snapshot_native_crashes(self.root, self.phase)
        self.new_crash(name='hs_err_pid125.log')
        self.new_crash(directory=self.root / 'app/build/ci-native-crash/release-build')
        result = self.capture()
        self.assertEqual(result['files'], [])
        self.assertIn('baseline_unavailable', result['omitted'])

    def test_changed_reads_still_consume_total_byte_budget_including_the_growth_probe(self):
        self.snapshot = monitor.snapshot_native_crashes(self.root, self.phase)
        payload = native_crash_fixture()
        for number in range(3):
            self.new_crash(name=f'hs_err_pid{number + 123}.log', raw=payload)
        with patch.object(monitor, 'MAX_NATIVE_CRASH_TOTAL_BYTES', 2 * (len(payload) + 1)), \
                patch.object(monitor, '_read_native_crash', side_effect=monitor._NativeCrashLimit('file_changed')) as read:
            result = self.capture()
        self.assertEqual(read.call_count, 2)
        self.assertEqual(result['omitted']['file_changed'], 2)
        self.assertEqual(result['omitted']['size_limit'], 1)
        self.assertEqual(result['files'], [])

    def test_capture_deadline_and_unknown_error_messages_do_not_expose_raw_values(self):
        self.snapshot = monitor.snapshot_native_crashes(self.root, self.phase)
        self.new_crash()
        with patch.object(monitor, 'NATIVE_CRASH_CAPTURE_SECONDS', 0):
            result = self.capture()
        self.assertEqual(result['files'], [])
        self.assertIn('directory_unavailable', result['omitted'])
        with patch.object(monitor, '_read_native_crash', side_effect=monitor._NativeCrashLimit('fixture-private')):
            result = self.capture()
        self.assertEqual(result['files'], [])
        self.assertEqual(result['omitted']['diagnostic_unavailable'], 1)
        self.assertNotIn('fixture-private', json.dumps(result))

    def test_unsafe_binding_values_and_summary_destination_symlinks_are_not_exported(self):
        self.snapshot = monitor.snapshot_native_crashes(self.root, self.phase)
        self.new_crash()
        private = dict(self.binding, source_sha='fixture-private', build_sha='fixture-private',
                       run_id='fixture-private', run_attempt='fixture-private')
        result = self.capture(binding=private)
        parsed = json.loads((self.report / result['files'][0]['summary_file']).read_bytes())
        for key in ('source_sha', 'build_sha', 'run_id', 'run_attempt'):
            self.assertIsNone(parsed[key])
        self.assertNotIn('fixture-private', json.dumps(parsed))
        for path in self.report.iterdir():
            path.unlink()
        self.report.rmdir()
        outside = self.root / 'outside-reports'
        outside.mkdir()
        self.report.symlink_to(outside, target_is_directory=True)
        result = self.capture()
        self.assertEqual(result['files'], [])
        self.assertIn('file_unavailable', result['omitted'])
        self.assertEqual(list(outside.iterdir()), [])


class TestProgressTests(unittest.TestCase):
    def test_variant_and_sdk_identities_remain_independent_and_are_not_junit_totals(self):
        progress = monitor.TestProgress()
        for task in ('debug', 'release'):
            for name in ('close', 'close[26]'):
                self.assertTrue(progress.consume(event('start', name, task), 10))
                self.assertTrue(progress.consume(event('end', name, task, 'SUCCESS'), 11))
        snapshot = progress.snapshot(20)
        self.assertEqual(snapshot['completed_events'], 4)
        self.assertEqual(snapshot['failed_events'], 0)
        self.assertEqual(snapshot['active'], [])
        self.assertIn('UNKNOWN', snapshot['junit_totals'])

    def test_other_output_and_other_tests_do_not_renew_an_occupied_deadline(self):
        progress = monitor.TestProgress()
        progress.consume(event('start'), 1)
        self.assertFalse(progress.consume('Gradle continues writing unrelated output', 9))
        progress.consume(event('start', 'another'), 9)
        progress.consume(event('end', 'another', result='SUCCESS'), 9.5)
        self.assertTrue(progress.overdue(11, 10))
        self.assertEqual(progress.snapshot(11)['active'][0]['observed_seconds'], 10)

    def test_two_worker_classes_keep_independent_240_second_deadlines_for_the_same_method_name(self):
        progress = monitor.TestProgress()

        def worker_event(kind, class_name, result=None):
            fields = {'event': kind, 'task': ':app:testWatchAndroidDebugUnitTest',
                      'className': class_name, 'name': 'sharedMethod[26]'}
            if result is not None:
                fields['result'] = result
            return monitor.TEST_EVENT_PREFIX + json.dumps(fields)

        self.assertTrue(progress.consume(worker_event('start', 'FirstWorkerClass'), 5))
        self.assertTrue(progress.consume(worker_event('start', 'SecondWorkerClass'), 10))
        self.assertEqual(len(progress.active), 2)
        self.assertFalse(progress.overdue(244.999, 240))
        self.assertTrue(progress.consume(worker_event('end', 'SecondWorkerClass', 'SUCCESS'), 244.999))
        self.assertEqual(len(progress.active), 1)
        self.assertFalse(progress.consume('Unrelated second-worker and Gradle output', 244.999))
        self.assertTrue(progress.overdue(245, 240))
        active = progress.snapshot(245)['active']
        self.assertEqual(active, [{'task': ':app:testWatchAndroidDebugUnitTest',
                                   'className': 'FirstWorkerClass', 'name': 'sharedMethod[26]',
                                   'observed_seconds': 240}])
        self.assertTrue(progress.consume(worker_event('end', 'FirstWorkerClass', 'SUCCESS'), 245.1))
        self.assertEqual(progress.active, {})
        self.assertEqual(progress.completed, 2)
        self.assertEqual(progress.invalid_events, 0)
        self.assertTrue(progress.overdue(245.1, 240))

    def test_duplicate_start_does_not_replace_the_original_deadline(self):
        progress = monitor.TestProgress()
        progress.consume(event('start'), 1)
        self.assertFalse(progress.consume(event('start'), 9))
        self.assertEqual(progress.invalid_events, 1)
        self.assertTrue(progress.overdue(11, 10))

    def test_a_late_completion_cannot_erase_a_test_deadline_violation(self):
        progress = monitor.TestProgress()
        progress.consume(event('start'), 1)
        progress.consume(event('end', result='SUCCESS'), 242)
        self.assertEqual(progress.active, {})
        self.assertTrue(progress.overdue(242, 240))

    def test_admission_is_bounded_and_invalid_completion_cannot_remove_an_active_test(self):
        progress = monitor.TestProgress()
        for number in range(monitor.MAX_ACTIVE_TESTS):
            self.assertTrue(progress.consume(event('start', str(number)), 1))
        self.assertFalse(progress.consume(event('start', 'overflow'), 2))
        self.assertFalse(progress.consume(event('end', '0', result='invented'), 2))
        self.assertFalse(progress.consume(event('end', 'unknown', result='SUCCESS'), 2))
        self.assertEqual(len(progress.active), monitor.MAX_ACTIVE_TESTS)
        self.assertEqual(progress.invalid_events, 3)

    def test_invalid_json_identity_and_controls_are_rejected(self):
        progress = monitor.TestProgress()
        for line in (monitor.TEST_EVENT_PREFIX + '{', monitor.TEST_EVENT_PREFIX + 'null',
                     event('start', 'line\nbreak'), event('start', ''), event('unknown')):
            self.assertFalse(progress.consume(line, 1))
        self.assertEqual(progress.invalid_events, 5)
        self.assertEqual(progress.active, {})

    def test_chunk_boundaries_preserve_unicode_crlf_and_a_final_line(self):
        progress = monitor.TestProgress()
        lines = monitor._EventLines(progress)
        data = (event('start', '取消') + '\r\n' + event('end', '取消', result='SKIPPED')).encode()
        for byte in data:
            lines.consume(bytes([byte]), 2)
        lines.finish(3)
        self.assertEqual(progress.completed, 1)
        self.assertEqual(progress.invalid_events, 0)
        self.assertEqual(progress.active, {})

    def test_an_oversized_line_cannot_admit_a_prefix_from_its_middle(self):
        progress = monitor.TestProgress()
        lines = monitor._EventLines(progress)
        lines.consume(b'x' * (monitor.MAX_LINE_BYTES + 1), 1)
        lines.consume(event('start', 'spoof').encode() + b'\n' + event('start').encode() + b'\n', 2)
        self.assertEqual(len(progress.active), 1)
        self.assertEqual(progress.snapshot(2)['active'][0]['name'], 'queued-close')
        self.assertEqual(progress.invalid_events, 0)

    def test_oversized_reserved_events_fail_closed_without_unbounded_buffering(self):
        progress = monitor.TestProgress()
        lines = monitor._EventLines(progress)
        lines.consume(monitor.TEST_EVENT_PREFIX.encode() + b'x' * monitor.MAX_LINE_BYTES, 1)
        self.assertEqual(progress.invalid_events, 1)
        self.assertLessEqual(len(lines.partial), monitor.MAX_LINE_BYTES)

    def test_deadlines_reject_nonfinite_zero_negative_and_boolean_values(self):
        for value in (0, -1, float('nan'), float('inf'), True):
            with self.subTest(value=value), self.assertRaises(ValueError):
                monitor.BuildLimits(test_seconds=value)

    def test_unsupported_process_ownership_rejects_before_launch(self):
        with patch.object(monitor.sys, 'platform', 'unsupported'), patch.object(monitor.subprocess, 'Popen') as process:
            with self.assertRaisesRegex(ValueError, 'Linux runner'):
                monitor.run_gradle(['unexecuted'], cwd='.', env={}, phase='debug-build')
            process.assert_not_called()


class OutputSinkTests(unittest.TestCase):
    def test_slow_writes_keep_admission_bounded_and_separate_overflow_from_close(self):
        sink = monitor._OutputSink(io.StringIO())
        sink.descriptor = 17
        emitted, blocked_writes, write_attempts = bytearray(), 0, 0
        def slow_write(descriptor, raw):
            nonlocal blocked_writes, write_attempts
            self.assertEqual(descriptor, 17)
            write_attempts += 1
            if write_attempts % 2 == 0:
                blocked_writes += 1
                raise BlockingIOError(errno.EAGAIN, 'Slow output fixture')
            emitted.extend(raw[:1])
            return 1
        chunk = b'x' * 4096
        with patch.object(monitor.os, 'write', side_effect=slow_write):
            for _ in range(32):
                sink.pump()
                sink.enqueue(chunk)
                self.assertLessEqual(len(sink.pending), monitor.MAX_PENDING_OUTPUT_BYTES)
            sink.pump()
        before_close = sink.snapshot()
        overflow = 32 * len(chunk) - len(emitted) - len(sink.pending)
        self.assertGreater(overflow, 0)
        self.assertEqual(before_close['overflow_dropped_bytes'], overflow)
        self.assertEqual(before_close['close_dropped_bytes'], 0)
        self.assertEqual(before_close['dropped_bytes'], overflow)
        self.assertEqual(before_close['blocked_writes'], blocked_writes)
        with patch.object(monitor.os, 'write', side_effect=BlockingIOError(errno.EAGAIN, 'Full output fixture')) as write:
            sink.close()
        write.assert_called_once()
        final = sink.snapshot()
        self.assertEqual(final['pending_bytes'], 0)
        self.assertEqual(final['overflow_dropped_bytes'], overflow)
        self.assertEqual(final['close_dropped_bytes'], before_close['pending_bytes'])
        self.assertEqual(final['dropped_bytes'], 32 * len(chunk) - len(emitted))
        self.assertEqual(final['dropped_bytes'], final['overflow_dropped_bytes'] + final['close_dropped_bytes'])
        self.assertEqual(final['blocked_writes'], blocked_writes + 1)

    def test_blocked_close_classifies_only_pending_abandonment_with_one_write_attempt(self):
        sink = monitor._OutputSink(io.StringIO())
        sink.descriptor = 17
        sink.enqueue(b'x' * 2819)
        with patch.object(monitor.os, 'write', side_effect=BlockingIOError(errno.EAGAIN, 'Full output fixture')) as write:
            sink.close()
        write.assert_called_once_with(17, b'x' * 2819)
        self.assertEqual(sink.snapshot(), {'dropped_bytes': 2819, 'overflow_dropped_bytes': 0,
            'close_dropped_bytes': 2819, 'blocked_writes': 1, 'pending_bytes': 0,
            'maximum_pending_bytes': 65536})

    def test_writable_close_keeps_its_eight_write_budget_and_classifies_the_remaining_tail(self):
        sink = monitor._OutputSink(io.StringIO())
        sink.descriptor = 17
        sink.enqueue(b'x' * monitor.MAX_PENDING_OUTPUT_BYTES)
        with patch.object(monitor.os, 'write', side_effect=lambda _descriptor, raw: len(raw)) as write:
            sink.close()
        self.assertEqual(write.call_count, 8)
        self.assertTrue(all(len(call.args[1]) == 4096 for call in write.call_args_list))
        self.assertEqual(sink.snapshot(), {'dropped_bytes': 32768, 'overflow_dropped_bytes': 0,
            'close_dropped_bytes': 32768, 'blocked_writes': 0, 'pending_bytes': 0,
            'maximum_pending_bytes': 65536})


class OverallBuildBudgetTests(unittest.TestCase):
    """Exercise the real deadline/report loop with a clock and no launched process or Linux syscalls."""
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.now, self.finish_at, self.exit_code = 1000.0, 1001.0, 0
        self.process = SimpleNamespace(stdout=Mock(), returncode=None)
        self.selector = Mock()
        self.selector.select.side_effect = self.advance
        self.owner = SimpleNamespace(activate=Mock(), register=Mock(), refresh=Mock(return_value=[]),
            members=Mock(return_value=[]), restore=Mock(), previous_subreaper=0, baseline_zombies=0,
            baseline_live_descendants=0, restored=True)
        resources = ExitStack()
        self.addCleanup(resources.close)
        actual_is_dir = Path.is_dir
        for context in (patch.object(monitor.sys, 'platform', 'linux'),
                patch.object(Path, 'is_dir', lambda path: path == Path('/proc') or actual_is_dir(path)),
                patch.object(monitor.time, 'monotonic', side_effect=lambda: self.now),
                patch.object(monitor, '_LinuxProcessOwner', return_value=self.owner),
                patch.object(monitor, '_open_pipe', return_value=self.selector),
                patch.object(monitor, '_has_children', return_value=False),
                patch.object(monitor.os, 'read', return_value=b''),
                patch.object(monitor, 'snapshot_native_crashes', return_value=None),
                patch.object(monitor, '_stop_owned_processes', return_value={
                    'parent_reaped': True, 'ownership_verified': True, 'signaled_owned_process_count': 0,
                    'remaining_live_owned_processes': [], 'remaining_owned_zombies': []})):
            resources.enter_context(context)
        self.launch = resources.enter_context(patch.object(monitor.subprocess, 'Popen', return_value=self.process))

    def advance(self, **_):
        self.now = self.finish_at
        self.process.returncode = self.exit_code
        return [True] if self.exit_code is not None else []

    def run_phase(self, phase='strict-debug', **kwargs):
        return monitor.run_gradle(['unexecuted-budget-fixture'], cwd=self.root, env={}, phase=phase,
                                  output=io.StringIO(), diagnostics=lambda *_: {'status': 'fixture'}, **kwargs)

    def report(self, phase='strict-debug'):
        return json.loads((self.root / 'app/build/reports/ci-process' / phase / 'process.json').read_text())

    def test_writable_stdout_keeps_large_read_batches_and_the_original_binary_log(self):
        for size in (68355, 131072):
            with self.subTest(size=size):
                self.now, self.finish_at, self.exit_code = 1000.0, 1001.0, 0
                tail = b'\x00\xff-tail\n'
                payload = (b'watch-log-' * (size // 10 + 1))[:size - len(tail)] + tail
                chunks = [payload[index:index + 4096] for index in range(0, len(payload), 4096)]
                forwarded, read_counts = io.StringIO(), []
                phase = 'stdout-burst-' + str(size)
                with patch.object(monitor.os, 'read', side_effect=chunks + [b'']) as read, \
                        patch.object(self.owner, 'refresh', side_effect=lambda: read_counts.append(read.call_count) or []):
                    monitor.run_gradle(['unexecuted-output-fixture'], cwd=self.root, env={}, phase=phase,
                                       output=forwarded, diagnostics=lambda *_: {'status': 'fixture'})
                record = self.report(phase)
                self.assertEqual(record['status'], 'completed')
                self.assertEqual(record['stdout']['dropped_bytes'], 0)
                self.assertEqual(record['stdout']['overflow_dropped_bytes'], 0)
                self.assertEqual(record['stdout']['close_dropped_bytes'], 0)
                self.assertEqual(record['stdout']['blocked_writes'], 0)
                self.assertEqual(record['stdout']['pending_bytes'], 0)
                self.assertEqual(record['stdout']['maximum_pending_bytes'], 65536)
                self.assertEqual(record['captured_log_bytes'], size)
                self.assertFalse(record['log_truncated'])
                self.assertEqual((self.root / 'app/build/reports/ci-process' / phase / 'process-output.log').read_bytes(), payload)
                self.assertEqual(forwarded.getvalue(), payload.decode('utf-8', errors='replace'))
                self.assertEqual(read_counts[0], min(32, len(chunks) + 1))

    def test_default_total_limit_and_independent_limits_stay_unchanged(self):
        self.run_phase()
        record = self.report()
        self.assertEqual(record['status'], 'completed')
        self.assertEqual(record['overall_limit_seconds'], 2700)
        self.assertEqual(record['overall_remaining_seconds_at_start'], 2700)
        self.assertEqual(record['limits_seconds'], {'phase_seconds': 1200, 'silent_seconds': 300,
            'test_seconds': 240, 'termination_seconds': 2, 'heartbeat_seconds': 30})

    def test_selected_total_limit_drives_the_default_deadline_receipt_and_timeout_text(self):
        for budget in (2700, 5400):
            self.now, self.finish_at, self.exit_code = 1000.0, 1000.0 + budget, None
            phase = 'budget-' + str(budget)
            with self.subTest(budget=budget), self.assertRaises(monitor.BuildTimeout) as caught:
                self.run_phase(phase, overall_limit_seconds=budget)
            self.assertEqual(caught.exception.reason, 'overall build deadline')
            self.assertEqual(caught.exception.timeout, budget)
            self.assertIn(f'({budget}s limit)', str(caught.exception))
            record = self.report(phase)
            self.assertEqual(record['overall_limit_seconds'], budget)
            self.assertEqual(record['overall_remaining_seconds_at_start'], budget)
            self.assertEqual(record['timeout_reason'], 'overall build deadline')

    def test_a_shorter_explicit_deadline_is_not_extended_by_the_selected_limit(self):
        self.finish_at, self.exit_code = 1020.0, None
        with self.assertRaises(monitor.BuildTimeout) as caught:
            self.run_phase(total_deadline=1020.0, overall_limit_seconds=5400)
        self.assertEqual(caught.exception.reason, 'overall build deadline')
        self.assertEqual(caught.exception.timeout, 5400)
        record = self.report()
        self.assertEqual(record['overall_limit_seconds'], 5400)
        self.assertEqual(record['overall_remaining_seconds_at_start'], 20)
        self.assertEqual(record['elapsed_seconds'], 20)

    def test_expired_explicit_deadline_rejects_before_launch_with_the_selected_timeout_text(self):
        with self.assertRaises(monitor.BuildTimeout) as caught:
            self.run_phase(total_deadline=999.0, overall_limit_seconds=5400)
        self.assertEqual(caught.exception.timeout, 5400)
        self.assertEqual(caught.exception.reason, 'overall build deadline before phase start')
        self.launch.assert_not_called()
        self.assertFalse((self.root / 'app').exists())

    def test_later_phases_keep_the_original_deadline_and_report_only_the_remaining_budget(self):
        deadline = self.now + 5400
        self.run_phase('first-phase', total_deadline=deadline, overall_limit_seconds=5400)
        self.now, self.finish_at, self.exit_code = deadline - 5, deadline + 1, None
        with self.assertRaises(monitor.BuildTimeout) as caught:
            self.run_phase('last-phase', total_deadline=deadline, overall_limit_seconds=5400)
        self.assertEqual(caught.exception.reason, 'overall build deadline')
        self.assertEqual(self.report('first-phase')['overall_remaining_seconds_at_start'], 5400)
        self.assertEqual(self.report('last-phase')['overall_remaining_seconds_at_start'], 5)
        self.assertEqual(self.report('last-phase')['elapsed_seconds'], 6)

    def test_invalid_total_limits_reject_before_report_or_launch(self):
        for budget in (0, -1, float('nan'), float('inf'), True, False):
            with self.subTest(budget=budget), self.assertRaisesRegex(ValueError, 'positive finite'):
                self.run_phase(overall_limit_seconds=budget)
            self.launch.assert_not_called()
            self.assertFalse((self.root / 'app').exists())


class WaitidProtocolTests(unittest.TestCase):
    def setUp(self):
        resources = ExitStack()
        self.addCleanup(resources.close)
        for name, value in (('P_ALL', 0), ('WEXITED', 4), ('WNOHANG', 1), ('WNOWAIT', 0x01000000)):
            resources.enter_context(patch.object(monitor.os, name, value, create=True))
        self.waitid = resources.enter_context(patch.object(monitor.os, 'waitid', create=True))
        self.arguments = (0, 0, 4 | 1 | 0x01000000 | 0x40000000)

    def test_waitid_only_echild_proves_closure_and_retains_wall_wnowait_flags(self):
        for result in (None, Mock(si_pid=123, si_status=0)):
            with self.subTest(result=result):
                self.waitid.return_value = result
                self.assertTrue(monitor._has_children())
                self.waitid.assert_called_with(*self.arguments)
        for error in (ChildProcessError(errno.ECHILD, 'No fixture children'),
                      OSError(errno.ECHILD, 'No fixture children')):
            with self.subTest(error_type=type(error).__name__):
                self.waitid.side_effect = error
                self.assertFalse(monitor._has_children())
                self.waitid.assert_called_with(*self.arguments)

    def test_waitid_unknown_errors_fail_closed_and_preserve_the_original_cause(self):
        for number in (errno.EPERM, errno.EIO, errno.EINVAL, errno.EINTR):
            with self.subTest(errno=number):
                unavailable = OSError(number, 'Fixture child state is unknown')
                self.waitid.side_effect = unavailable
                with self.assertRaises(monitor.BuildCleanupError) as failure:
                    monitor._has_children()
                self.assertIs(failure.exception.__cause__, unavailable)
                self.waitid.assert_called_with(*self.arguments)


class JvmDiagnosticsTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.directory = self.root / 'app/build/reports/ci-process/diagnostics'
        self.directory.mkdir(parents=True)
        self.scratch_parent = self.root / 'unverified'
        self.scratch_parent.mkdir()
        self.identity = monitor._ProcessIdentity(45678, 100, os.getpid(), 'S', 'java')
        self.reused_identity = monitor._ProcessIdentity(45678, 101, os.getpid(), 'S', 'java')
        self.owner = Mock()
        self.owner.refresh.return_value = [(self.identity, 'build')]
        self.executable = Path('fixture-java-home/bin/jcmd')
        self.environment = {'SOURCE_SHA': 'a' * 40}
        self.payload = b'unverified JVM fixture bytes\n'
        self.capture_failure = None
        self.declared_delta = 0
        self.pidfd = 2_000_000_000
        self.closed_pidfds, self.scratch_paths = [], []
        temporary_directory, close = tempfile.TemporaryDirectory, os.close
        def scratch_directory(*, prefix, dir):
            self.assertEqual(prefix, 'melox-ci-jvm-')
            self.assertEqual(dir, '/tmp')
            scratch = temporary_directory(prefix=prefix, dir=self.scratch_parent)
            self.scratch_paths.append(Path(scratch.name))
            return scratch
        def close_descriptor(descriptor):
            if descriptor == self.pidfd:
                self.closed_pidfds.append(descriptor)
                return
            return close(descriptor)
        resources = ExitStack()
        self.addCleanup(resources.close)
        self.open_pidfd = resources.enter_context(
            patch.object(monitor.os, 'pidfd_open', return_value=self.pidfd, create=True))
        resources.enter_context(patch.object(monitor.os, 'close', side_effect=close_descriptor))
        self.signal_pidfd = resources.enter_context(
            patch.object(monitor.signal, 'pidfd_send_signal', create=True))
        self.scratch = resources.enter_context(
            patch.object(monitor.tempfile, 'TemporaryDirectory', side_effect=scratch_directory))
        self.read_identity = resources.enter_context(
            patch.object(monitor, '_read_process', return_value=self.identity))
        self.dump = resources.enter_context(
            patch.object(monitor, '_thread_dump', side_effect=self.write_unverified_capture))
        self.launch = resources.enter_context(patch.object(monitor.subprocess, 'Popen'))

    def write_unverified_capture(self, command, target, environment, deadline, interrupted, *, owner):
        self.assertEqual(command, [str(self.executable), str(self.identity.pid), 'Thread.print', '-l'])
        self.assertIs(owner, self.owner)
        self.assertIs(environment, self.environment)
        self.assertNotIn(self.directory, target.parents)
        self.assertEqual(target.parent.parent, self.scratch_parent)
        self.assertEqual(list(self.directory.iterdir()), [])
        target.write_bytes(self.payload)
        if self.capture_failure is not None:
            raise self.capture_failure
        return {'file': target.name, 'exit_code': 0, 'bytes': len(self.payload) + self.declared_delta,
                'truncated': False, 'status': 'completed', 'owned_children_closure': 'deferred-to-build-scope'}

    def capture(self):
        return monitor._capture_owned_jvm(self.identity, self.owner, self.executable, self.directory,
                                          self.environment, time.monotonic() + 1, lambda: None)

    def assert_resources_retired(self):
        self.open_pidfd.assert_called_once_with(self.identity.pid, 0)
        self.assertEqual(self.closed_pidfds, [self.pidfd])
        self.assertTrue(all(not path.exists() for path in self.scratch_paths))
        self.launch.assert_not_called()

    def assert_unverified_not_exported(self):
        self.assertEqual(list(self.directory.rglob('*')), [])
        self.assert_resources_retired()

    def test_stale_target_identity_before_attach_refuses_capture_and_creates_no_report(self):
        self.read_identity.return_value = self.reused_identity
        result = self.capture()
        self.assertEqual(result, {'status': 'UNAVAILABLE', 'reason': 'TARGET_IDENTITY_CHANGED'})
        self.dump.assert_not_called()
        self.scratch.assert_not_called()
        self.signal_pidfd.assert_not_called()
        self.owner.refresh.assert_not_called()
        self.assert_unverified_not_exported()

    def test_target_exit_during_capture_discards_unverified_bytes_and_closes_pidfd(self):
        self.signal_pidfd.side_effect = ProcessLookupError(errno.ESRCH, 'Fixture JVM exited')
        result = self.capture()
        self.assertEqual(result, {'status': 'UNAVAILABLE', 'reason': 'TARGET_EXITED_DURING_CAPTURE'})
        self.dump.assert_called_once()
        self.signal_pidfd.assert_called_once_with(self.pidfd, 0)
        self.assertEqual(self.read_identity.call_count, 1)
        self.assertEqual(len(self.scratch_paths), 1)
        self.assert_unverified_not_exported()

    def test_target_pid_reuse_during_capture_discards_unverified_bytes(self):
        self.read_identity.side_effect = [self.identity, self.reused_identity]
        result = self.capture()
        self.assertEqual(result, {'status': 'UNAVAILABLE', 'reason': 'TARGET_IDENTITY_CHANGED'})
        self.dump.assert_called_once()
        self.signal_pidfd.assert_called_once_with(self.pidfd, 0)
        self.assertEqual(self.read_identity.call_count, 2)
        self.assertEqual(self.owner.refresh.call_count, 1)
        self.assert_unverified_not_exported()

    def test_verified_capture_exports_exact_bytes_after_both_ownership_checks(self):
        result = self.capture()
        target = self.directory / f'jvm-{self.identity.pid}.threads.txt'
        self.assertEqual(target.read_bytes(), self.payload)
        self.assertEqual(result['file'], target.name)
        self.assertEqual(result['bytes'], len(self.payload))
        self.assertEqual(result['status'], 'completed')
        self.assertTrue(result['target_identity_verified'])
        self.assertEqual(list(self.directory.iterdir()), [target])
        self.assertEqual(self.read_identity.call_count, 2)
        self.assertEqual(self.owner.refresh.call_count, 2)
        self.signal_pidfd.assert_called_once_with(self.pidfd, 0)
        self.dump.assert_called_once()
        self.assert_resources_retired()

    def test_failed_capture_never_exports_unverified_bytes_or_replaces_its_primary_error(self):
        self.capture_failure = OSError(errno.EIO, 'Fixture diagnostic capture failed')
        with self.assertRaises(OSError) as failure:
            self.capture()
        self.assertIs(failure.exception, self.capture_failure)
        self.dump.assert_called_once()
        self.signal_pidfd.assert_not_called()
        self.assert_unverified_not_exported()

    def test_unverified_capture_length_mismatch_cannot_export_a_report(self):
        self.declared_delta = 1
        with self.assertRaisesRegex(ValueError, 'bounded capture'):
            self.capture()
        self.dump.assert_called_once()
        self.signal_pidfd.assert_called_once_with(self.pidfd, 0)
        self.assert_unverified_not_exported()


@unittest.skipUnless(sys.platform.startswith('linux') and Path('/proc').is_dir(),
                     'Linux process-ownership checks run in cloud; no local Android execution')
class LinuxBuildProcessTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.output = io.StringIO()
        self.environment = dict(os.environ, SOURCE_SHA='a' * 40, GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2',
                                MELOX_TEST_PRIVATE_MARKER='unlogged-fixture-marker')
        self.limits = monitor.BuildLimits(phase_seconds=3, silent_seconds=2, test_seconds=0.3,
                                          termination_seconds=0.2, heartbeat_seconds=0.05)

    def run_script(self, script, phase='debug-build', **kwargs):
        return monitor.run_gradle([sys.executable, '-u', '-c', script], cwd=self.root, env=self.environment,
                                  phase=phase, build_sha='b' * 40, output=kwargs.pop('output', self.output),
                                  limits=kwargs.pop('limits', self.limits),
                                  diagnostics=kwargs.pop('diagnostics', lambda *_: {'fixture': 'retained'}), **kwargs)

    def report(self, phase='debug-build'):
        return json.loads((self.root / 'app/build/reports/ci-process' / phase / 'process.json').read_text())

    def assert_stopped(self, report):
        self.assertTrue(report['termination']['parent_reaped'])
        self.assertEqual(report['termination']['remaining_live_owned_processes'], [])
        self.assertEqual(report['termination']['remaining_owned_zombies'], [])
        self.assertTrue(report['termination']['ownership_verified'])
        self.assertTrue(report['ownership']['subreaper_restored'])
        self.assertFalse(report['apk_exported'])
        self.assertIn('UNKNOWN', report['tests']['junit_totals'])
        self.assertNotIn('unlogged-fixture-marker', json.dumps(report))

    def test_successful_python_process_retains_matching_events_and_original_identity(self):
        self.run_script(f'print({event("start")!r}); print({event("end", result="SUCCESS")!r})')
        report = self.report()
        self.assertEqual(report['status'], 'completed')
        self.assertEqual(report['tests']['completed_events'], 1)
        self.assertEqual(report['source_sha'], 'a' * 40)
        self.assertEqual(report['build_sha'], 'b' * 40)
        self.assertEqual(report['run_attempt'], '2')
        self.assert_stopped(report)

    def test_original_nonzero_exit_remains_failure_and_preserves_output(self):
        with self.assertRaises(subprocess.CalledProcessError) as failure:
            self.run_script('import sys; print("real fixture error"); sys.exit(7)')
        self.assertEqual(failure.exception.returncode, 7)
        report = self.report()
        self.assertEqual(report['status'], 'failed')
        self.assertEqual(report['exit_code'], 7)
        self.assertIn('real fixture error', self.output.getvalue())
        self.assert_stopped(report)

    def test_natural_failure_captures_only_a_new_raw_file_and_keeps_original_exit(self):
        payload = native_crash_fixture()
        script = ('import os,pathlib,sys\n'
                  'phase=os.environ["MELOX_CI_BUILD_PHASE"]\n'
                  'folder=pathlib.Path("app/build/ci-native-crash") / phase\n'
                  'folder.mkdir(parents=True)\n'
                  f'(folder / "hs_err_pid123.log").write_bytes({payload!r})\n'
                  'sys.exit(7)')
        with self.assertRaises(subprocess.CalledProcessError) as failure:
            self.run_script(script)
        self.assertEqual(failure.exception.returncode, 7)
        report = self.report()
        self.assertEqual(report['status'], 'failed')
        self.assertEqual(report['native_crashes']['status'], 'COLLECTED')
        self.assertEqual(report['native_crashes']['reason'], 'natural-nonzero-exit')
        self.assertEqual(len(report['native_crashes']['files']), 1)
        self.assertNotIn('fixture-private', json.dumps(report))
        self.assert_stopped(report)

    def test_optional_native_capture_failure_preserves_the_exact_primary_exit_or_timeout(self):
        original_exit, original_timeout = subprocess.CalledProcessError, monitor.BuildTimeout
        created = []
        class CapturedExit(original_exit):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                created.append(self)
        class CapturedTimeout(original_timeout):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                created.append(self)
        unavailable = PermissionError('fixture-private-path')
        with patch.object(monitor, 'collect_native_crashes', side_effect=unavailable), \
                patch.object(monitor.subprocess, 'CalledProcessError', CapturedExit), \
                self.assertRaises(original_exit) as failure:
            self.run_script('import sys; sys.exit(7)')
        self.assertIs(failure.exception, created[0])
        self.assertEqual(failure.exception.returncode, 7)
        self.assertEqual(self.report()['native_crashes']['reason'], 'CAPTURE_FAILED')
        self.assertNotIn('fixture-private', json.dumps(self.report()))
        limits = monitor.BuildLimits(phase_seconds=3, silent_seconds=0.25, test_seconds=2,
                                      termination_seconds=0.2, heartbeat_seconds=0.05)
        with patch.object(monitor, 'collect_native_crashes', side_effect=unavailable), \
                patch.object(monitor, 'BuildTimeout', CapturedTimeout), self.assertRaises(original_timeout) as failure:
            self.run_script('import time; print("ready"); time.sleep(10)', phase='release-build', limits=limits)
        self.assertIs(failure.exception, created[1])
        self.assertEqual(failure.exception.reason, 'no build output')
        report = self.report('release-build')
        self.assertEqual(report['status'], 'timed-out')
        self.assertEqual(report['native_crashes']['reason'], 'CAPTURE_FAILED')
        self.assert_stopped(report)

    def test_timeout_collects_after_owned_cleanup_and_does_not_scan_successful_phases(self):
        actual = monitor.collect_native_crashes
        seen = []
        def after_cleanup(cwd, report, snapshot, binding, reason):
            self.assertTrue(binding['ownership']['subreaper_restored'])
            self.assert_stopped(binding)
            seen.append(reason)
            return actual(cwd, report, snapshot, binding, reason)
        limits = monitor.BuildLimits(phase_seconds=3, silent_seconds=0.25, test_seconds=2,
                                      termination_seconds=0.2, heartbeat_seconds=0.05)
        with patch.object(monitor, 'collect_native_crashes', side_effect=after_cleanup), \
                self.assertRaises(monitor.BuildTimeout):
            self.run_script('import pathlib,time\n'
                            f'pathlib.Path("hs_err_pid123.log").write_bytes({native_crash_fixture()!r})\n'
                            'print("ready"); time.sleep(10)', limits=limits)
        self.assertEqual(seen, ['timeout'])
        self.assertEqual(self.report()['native_crashes']['status'], 'COLLECTED')
        with patch.object(monitor, 'collect_native_crashes') as capture:
            self.run_script('print("completed")', phase='success-build')
            capture.assert_not_called()
        self.assertNotIn('native_crashes', self.report('success-build'))

    def test_heartbeats_do_not_extend_silent_build_timeout_and_diagnostics_precede_stop(self):
        def diagnostics(process, *_):
            self.assertIsNone(process.poll())
            self.assertTrue(process._ci_owner.members())
            return {'fixture': 'retained-before-stop'}
        limits = monitor.BuildLimits(phase_seconds=3, silent_seconds=0.25, test_seconds=2,
                                      termination_seconds=0.2, heartbeat_seconds=0.05)
        with self.assertRaises(monitor.BuildTimeout) as failure:
            self.run_script('import time; print("ready"); time.sleep(10)', limits=limits, diagnostics=diagnostics)
        self.assertEqual(failure.exception.reason, 'no build output')
        report = self.report()
        self.assertEqual(report['status'], 'timed-out')
        self.assertGreaterEqual(report['seconds_since_output'], 0.25)
        self.assertIn('CI phase debug-build', self.output.getvalue())
        self.assertEqual(report['diagnostics']['fixture'], 'retained-before-stop')
        self.assert_stopped(report)

    def test_noisy_stalled_test_stops_at_its_original_test_deadline_with_bounded_log(self):
        script = f'import time\nprint({event("start")!r})\nwhile True:\n print("unrelated output " * 30)\n time.sleep(0.001)'
        with patch.object(monitor, 'MAX_LOG_BYTES', 2048), self.assertRaises(monitor.BuildTimeout) as failure:
            self.run_script(script)
        self.assertEqual(failure.exception.reason, 'active test deadline including setup/cleanup')
        report = self.report()
        self.assertEqual(report['captured_log_bytes'], 2048)
        self.assertTrue(report['log_truncated'])
        self.assertEqual((self.root / 'app/build/reports/ci-process/debug-build/process-output.log').stat().st_size, 2048)
        self.assertEqual(len(report['tests']['active']), 1)
        self.assert_stopped(report)

    def test_continuously_noisy_process_is_stopped_by_the_phase_deadline(self):
        limits = monitor.BuildLimits(phase_seconds=0.35, silent_seconds=2, test_seconds=2,
                                      termination_seconds=0.2, heartbeat_seconds=0.05)
        with self.assertRaises(monitor.BuildTimeout) as failure:
            self.run_script('import time\nwhile True:\n print("working")\n time.sleep(0.01)', limits=limits)
        self.assertEqual(failure.exception.reason, 'phase deadline')
        self.assert_stopped(self.report())

    def test_diagnostic_exception_does_not_replace_the_original_timeout_or_hide_cleanup(self):
        def unavailable(*_):
            raise RuntimeError('unexecuted diagnostic fixture failure')
        limits = monitor.BuildLimits(phase_seconds=3, silent_seconds=0.25, test_seconds=2,
                                      termination_seconds=0.2, heartbeat_seconds=0.05)
        with self.assertRaises(monitor.BuildTimeout) as failure:
            self.run_script('import time; print("ready"); time.sleep(10)', limits=limits, diagnostics=unavailable)
        self.assertEqual(failure.exception.reason, 'no build output')
        report = self.report()
        self.assertEqual(report['diagnostics']['error_type'], 'RuntimeError')
        self.assertEqual(report['failure_type'], 'BuildTimeout')
        self.assert_stopped(report)

    def test_phases_share_one_overall_deadline_instead_of_renewing_the_budget(self):
        deadline = time.monotonic() + 0.8
        self.run_script('import time; time.sleep(0.1); print("first phase")', 'first-phase', total_deadline=deadline)
        with self.assertRaises(monitor.BuildTimeout) as failure:
            self.run_script('import time\nwhile True:\n print("second phase")\n time.sleep(0.01)',
                            'second-phase', total_deadline=deadline)
        self.assertEqual(failure.exception.reason, 'overall build deadline')
        self.assert_stopped(self.report('second-phase'))

    def test_expired_budget_cannot_launch_a_new_phase(self):
        with patch.object(monitor.subprocess, 'Popen') as process, self.assertRaises(monitor.BuildTimeout):
            self.run_script('unexecuted', total_deadline=time.monotonic() - 1)
        process.assert_not_called()
        self.assertFalse((self.root / 'app').exists())

    def test_unmatched_or_failed_test_event_cannot_complete_a_successful_process(self):
        for name, script in (
            ('unfinished', f'print({event("start")!r})'),
            ('failed', f'print({event("start")!r}); print({event("end", result="FAILURE")!r})'),
            ('invalid', f'print({(monitor.TEST_EVENT_PREFIX + "null")!r})'),
        ):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.run_script(script, name)
            self.assertEqual(self.report(name)['status'], 'failed')
            self.assert_stopped(self.report(name))

    def test_term_resistant_descendant_is_killed_before_return_and_cannot_write_late(self):
        marker = self.root / 'owned-child.txt'
        child = ('import signal,time\nsignal.signal(signal.SIGTERM, signal.SIG_IGN)\n'
                 f'while True:\n with open({str(marker)!r}, "a") as stream: stream.write("x")\n time.sleep(0.01)')
        parent = ('import subprocess,sys,signal,time\nsignal.signal(signal.SIGTERM, signal.SIG_IGN)\n'
                  f'subprocess.Popen([sys.executable,"-u","-c",{child!r}])\nprint("children ready")\ntime.sleep(10)')
        groups = []
        def diagnostics(process, *_):
            groups.extend(process._ci_owner.members())
            return {'owned_count': len(groups)}
        limits = monitor.BuildLimits(phase_seconds=3, silent_seconds=0.35, test_seconds=2,
                                      termination_seconds=0.2, heartbeat_seconds=0.05)
        with self.assertRaises(monitor.BuildTimeout):
            self.run_script(parent, limits=limits, diagnostics=diagnostics)
        self.assertGreaterEqual(len(groups), 2)
        self.assert_stopped(self.report())
        before = marker.read_bytes()
        time.sleep(0.15)
        self.assertEqual(marker.read_bytes(), before)

    def test_parent_exit_does_not_release_a_child_that_keeps_the_output_pipe_open(self):
        parent = 'import subprocess,sys\nsubprocess.Popen([sys.executable,"-c","import time; time.sleep(10)"])\nprint("parent done")'
        limits = monitor.BuildLimits(phase_seconds=3, silent_seconds=0.25, test_seconds=2,
                                      termination_seconds=0.2, heartbeat_seconds=0.05)
        with self.assertRaises(monitor.BuildTimeout):
            self.run_script(parent, limits=limits)
        self.assert_stopped(self.report())

    def test_cancellation_restores_signal_handlers_and_stops_owned_children(self):
        previous = {number: signal.getsignal(number) for number in (signal.SIGINT, signal.SIGTERM)}
        script = 'import os,signal,time\nprint("before cancellation")\nos.kill(os.getppid(),signal.SIGTERM)\ntime.sleep(10)'
        with self.assertRaises(monitor.BuildInterrupted):
            self.run_script(script)
        report = self.report()
        self.assertEqual(report['status'], 'interrupted')
        self.assertEqual(report['interrupted_signal'], signal.SIGTERM)
        self.assert_stopped(report)
        self.assertEqual({number: signal.getsignal(number) for number in previous}, previous)

    def test_cleanup_failure_cannot_return_success_or_hide_the_original_build_error(self):
        stop = monitor._stop_owned_processes
        def incomplete(process, grace):
            result = stop(process, grace)
            return {**result, 'remaining_live_owned_processes': [123456789]}
        with patch.object(monitor, '_stop_owned_processes', side_effect=incomplete), \
                self.assertRaises(monitor.BuildCleanupError) as failure:
            self.run_script('import sys; sys.exit(7)')
        self.assertIsInstance(failure.exception.__cause__, subprocess.CalledProcessError)
        self.assertEqual(failure.exception.__cause__.returncode, 7)
        self.assertEqual(self.report()['status'], 'cleanup-failed')

    def test_thread_dump_capture_is_capped_while_the_python_fixture_is_still_running(self):
        target = self.root / 'fixture.threads.txt'
        with patch.object(monitor, 'MAX_DUMP_BYTES', 1024):
            result = monitor._thread_dump([sys.executable, '-u', '-c',
                'import sys,time; sys.stdout.write("x"*65536); sys.stdout.flush(); time.sleep(10)'],
                target, self.environment, time.monotonic() + 2, lambda: None)
        self.assertEqual(result['bytes'], 1024)
        self.assertEqual(target.stat().st_size, 1024)
        self.assertTrue(result['truncated'])
        self.assertEqual(result['status'], 'size-limit')

    def test_silent_thread_dump_has_its_own_bounded_deadline(self):
        result = monitor._thread_dump([sys.executable, '-c', 'import time; time.sleep(10)'],
            self.root / 'fixture.threads.txt', self.environment, time.monotonic() + 0.2, lambda: None)
        self.assertEqual(result['status'], 'timed-out')
        self.assertEqual(result['bytes'], 0)

    def detached_writer(self, marker, identity):
        self.addCleanup(self.retire_fixture, identity)
        return ('import json,os,signal,time\n'
                'os.setsid()\nos.close(1)\nos.close(2)\n'
                'signal.signal(signal.SIGTERM, signal.SIG_IGN)\n'
                'with open("/proc/self/stat") as stream: fields = stream.read()\n'
                'start_time = int(fields[fields.rindex(")") + 2:].split()[19])\n'
                f'with open({str(identity)!r}, "w") as stream:\n'
                ' json.dump({"pid": os.getpid(), "start_time": start_time}, stream)\n'
                f'while True:\n with open({str(marker)!r}, "a") as stream: stream.write("x")\n'
                ' time.sleep(0.01)')

    def retire_fixture(self, identity_file):
        # A failed ownership assertion must not leak a fixture or signal a reused PID.
        if not identity_file.exists():
            return
        expected = json.loads(identity_file.read_text())
        identity = monitor._read_process(expected['pid'])
        if identity is None or identity.start_time != expected['start_time']:
            return
        monitor._signal_owned(identity, signal.SIGKILL)
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            try:
                os.waitpid(identity.pid, os.WNOHANG)
            except ChildProcessError:
                pass
            current = monitor._read_process(identity.pid)
            if current is None or current.key != identity.key:
                return
            time.sleep(0.01)
        self.fail('The owned Python process fixture was not retired')

    def assert_marker_stopped(self, marker, identity_file):
        self.assertTrue(marker.is_file())
        before = marker.read_bytes()
        self.assertTrue(before)
        time.sleep(0.15)
        self.assertEqual(marker.read_bytes(), before)
        expected = json.loads(identity_file.read_text())
        current = monitor._read_process(expected['pid'])
        self.assertTrue(current is None or current.start_time != expected['start_time'])

    def test_detached_child_with_closed_output_cannot_complete_after_parent_exit(self):
        marker, identity = self.root / 'detached.txt', self.root / 'detached.identity.json'
        child = self.detached_writer(marker, identity)
        parent = ('import pathlib,subprocess,sys,time\n'
                  f'subprocess.Popen([sys.executable, "-u", "-c", {child!r}])\n'
                  f'while not pathlib.Path({str(marker)!r}).exists(): time.sleep(0.005)\n'
                  'print("detached child ready; parent exits")')
        limits = monitor.BuildLimits(phase_seconds=3, silent_seconds=0.8, test_seconds=2,
                                      termination_seconds=0.5, heartbeat_seconds=0.05)
        started = time.monotonic()
        with self.assertRaises(monitor.BuildTimeout) as failure:
            self.run_script(parent, limits=limits)
        self.assertEqual(failure.exception.reason, 'no build output')
        self.assertLess(time.monotonic() - started, 5)
        self.assert_stopped(self.report())
        self.assert_marker_stopped(marker, identity)

    def test_term_handler_detached_grandchild_is_discovered_and_reaped(self):
        marker, identity = self.root / 'term-grandchild.txt', self.root / 'term-grandchild.identity.json'
        child = self.detached_writer(marker, identity)
        parent = ('import pathlib,signal,subprocess,sys,time\n'
                  'def terminate(number, frame):\n'
                  ' signal.signal(signal.SIGTERM, signal.SIG_IGN)\n'
                  f' subprocess.Popen([sys.executable, "-u", "-c", {child!r}])\n'
                  f' while not pathlib.Path({str(marker)!r}).exists(): time.sleep(0.005)\n'
                  ' sys.exit(0)\n'
                  'signal.signal(signal.SIGTERM, terminate)\n'
                  'print("TERM handler installed")\ntime.sleep(20)')
        limits = monitor.BuildLimits(phase_seconds=3, silent_seconds=0.8, test_seconds=2,
                                      termination_seconds=0.6, heartbeat_seconds=0.05)
        started = time.monotonic()
        with self.assertRaises(monitor.BuildTimeout) as failure:
            self.run_script(parent, limits=limits)
        self.assertEqual(failure.exception.reason, 'no build output')
        self.assertLess(time.monotonic() - started, 5)
        self.assert_stopped(self.report())
        self.assert_marker_stopped(marker, identity)

    def test_full_stdout_pipe_preserves_phase_deadline_and_original_descriptor_flags(self):
        import fcntl
        read_fd, write_fd = os.pipe()
        self.addCleanup(os.close, read_fd)
        output = os.fdopen(write_fd, 'wb', buffering=0)
        self.addCleanup(output.close)
        original_flags = fcntl.fcntl(write_fd, fcntl.F_GETFL)
        try:
            fcntl.fcntl(write_fd, fcntl.F_SETFL, original_flags | os.O_NONBLOCK)
            while True:
                try:
                    os.write(write_fd, b'x' * 4096)
                except BlockingIOError:
                    break
        finally:
            fcntl.fcntl(write_fd, fcntl.F_SETFL, original_flags)
        limits = monitor.BuildLimits(phase_seconds=0.5, silent_seconds=2, test_seconds=2,
                                      termination_seconds=0.25, heartbeat_seconds=0.05)
        script = 'import os,time\nwhile True:\n os.write(1, b"fixture output\\n" * 256)\n time.sleep(0.001)'
        started = time.monotonic()
        with patch.object(output, 'write') as blocking_write, patch.object(output, 'flush') as blocking_flush, \
                patch.object(monitor, 'MAX_PENDING_OUTPUT_BYTES', 1024), \
                self.assertRaises(monitor.BuildTimeout) as failure:
            self.run_script(script, limits=limits, output=output)
        blocking_write.assert_not_called()
        blocking_flush.assert_not_called()
        self.assertEqual(failure.exception.reason, 'phase deadline')
        self.assertLess(time.monotonic() - started, 3.5)
        self.assertEqual(fcntl.fcntl(write_fd, fcntl.F_GETFL), original_flags)
        self.assertFalse(output.closed)
        report = self.report()
        self.assertGreater(report['captured_log_bytes'], 0)
        self.assertGreater(report['stdout']['dropped_bytes'], 0)
        self.assertEqual(report['stdout']['pending_bytes'], 0)
        self.assert_stopped(report)

    def test_final_report_write_failure_preserves_original_exit_exception(self):
        write_text, original_type = Path.write_text, subprocess.CalledProcessError
        created, final_records = [], []
        write_failure = OSError('Final report write fixture failure')
        class CapturedExit(original_type):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                created.append(self)
        def failing_final(path, text, *args, **kwargs):
            if path.name == 'process.json.tmp':
                record = json.loads(text)
                if record['status'] == 'failed' and 'termination' in record:
                    final_records.append(record)
                    raise write_failure
            return write_text(path, text, *args, **kwargs)
        with patch.object(Path, 'write_text', failing_final), \
                patch.object(monitor.subprocess, 'CalledProcessError', CapturedExit), \
                self.assertRaises(original_type) as failure:
            self.run_script('import sys; sys.exit(7)')
        self.assertEqual(len(created), 1)
        self.assertIs(failure.exception, created[0])
        self.assertEqual(failure.exception.returncode, 7)
        self.assertEqual(len(final_records), 1)
        self.assertEqual(final_records[0]['exit_code'], 7)
        self.assert_stopped(final_records[0])
        self.assertTrue(any('Writing final process report' in note
                            for note in failure.exception.__notes__))

    def test_final_report_write_failure_preserves_original_timeout_exception(self):
        write_text, original_type = Path.write_text, monitor.BuildTimeout
        created, final_records = [], []
        write_failure = OSError('Final timeout report fixture failure')
        class CapturedTimeout(original_type):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                created.append(self)
        def failing_final(path, text, *args, **kwargs):
            if path.name == 'process.json.tmp':
                record = json.loads(text)
                if record['status'] == 'timed-out' and 'termination' in record:
                    final_records.append(record)
                    raise write_failure
            return write_text(path, text, *args, **kwargs)
        limits = monitor.BuildLimits(phase_seconds=3, silent_seconds=0.25, test_seconds=2,
                                      termination_seconds=0.2, heartbeat_seconds=0.05)
        with patch.object(Path, 'write_text', failing_final), \
                patch.object(monitor, 'BuildTimeout', CapturedTimeout), \
                self.assertRaises(original_type) as failure:
            self.run_script('import time; print("ready"); time.sleep(10)', limits=limits)
        self.assertEqual(len(created), 1)
        self.assertIs(failure.exception, created[0])
        self.assertEqual(failure.exception.reason, 'no build output')
        self.assertEqual(len(final_records), 1)
        self.assert_stopped(final_records[0])
        self.assertTrue(any('Writing final process report' in note
                            for note in failure.exception.__notes__))

    def test_selector_and_stdout_close_failures_attempt_both_and_preserve_exit(self):
        open_pipe, attempted, captured = monitor._open_pipe, [], {}
        with ExitStack() as resources:
            def failing_pipe(process):
                selector = open_pipe(process)
                captured['stdout'] = process.stdout
                selector_close, stdout_close = selector.close, process.stdout.close
                def close_selector():
                    attempted.append('selector')
                    selector_close()
                    raise OSError('Selector close fixture failure')
                def close_stdout():
                    attempted.append('stdout')
                    stdout_close()
                    raise OSError('Stdout close fixture failure')
                resources.enter_context(patch.object(selector, 'close', side_effect=close_selector))
                resources.enter_context(patch.object(process.stdout, 'close', side_effect=close_stdout))
                return selector
            resources.enter_context(patch.object(monitor, '_open_pipe', side_effect=failing_pipe))
            with self.assertRaises(monitor.BuildCleanupError) as failure:
                self.run_script('import sys; sys.exit(7)')
        self.assertEqual(attempted, ['selector', 'stdout'])
        self.assertTrue(captured['stdout'].closed)
        self.assertIsInstance(failure.exception.__cause__, subprocess.CalledProcessError)
        self.assertEqual(failure.exception.__cause__.returncode, 7)
        self.assertEqual(self.report()['status'], 'cleanup-failed')
        self.assert_stopped(self.report())

    def test_preexisting_live_child_refuses_launch_without_signaling_or_reaping_it(self):
        previous = monitor._get_subreaper()
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(20)'],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        def retire():
            if child.poll() is None:
                child.terminate()
            try:
                child.wait(timeout=2)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait(timeout=2)
        self.addCleanup(retire)
        with patch.object(monitor.subprocess, 'Popen') as launch, \
                patch.object(monitor, '_signal_owned') as signal_owned, \
                self.assertRaisesRegex(monitor.BuildCleanupError, 'pre-existing live descendants'):
            self.run_script('unexecuted')
        launch.assert_not_called()
        signal_owned.assert_not_called()
        self.assertIsNone(child.poll())
        self.assertEqual(monitor._get_subreaper(), previous)
        report = self.report()
        self.assertEqual(report['status'], 'cleanup-failed')
        self.assertTrue(report['ownership']['subreaper_restored'])
        self.assertNotIn('termination', report)

    def test_preexisting_zombie_refuses_launch_and_retains_its_original_identity(self):
        previous = monitor._get_subreaper()
        child = os.fork()
        if child == 0:
            os._exit(0)
        def retire():
            try:
                os.waitpid(child, 0)
            except ChildProcessError:
                pass
        self.addCleanup(retire)
        deadline = time.monotonic() + 2
        identity = monitor._read_process(child)
        while identity is not None and identity.state != 'Z' and time.monotonic() < deadline:
            time.sleep(0.01)
            identity = monitor._read_process(child)
        self.assertIsNotNone(identity)
        self.assertEqual(identity.state, 'Z')
        with patch.object(monitor.subprocess, 'Popen') as launch, \
                patch.object(monitor, '_signal_owned') as signal_owned, \
                self.assertRaisesRegex(monitor.BuildCleanupError, 'pre-existing unreaped children'):
            self.run_script('unexecuted')
        launch.assert_not_called()
        signal_owned.assert_not_called()
        current = monitor._read_process(child)
        self.assertIsNotNone(current)
        self.assertEqual(current.key, identity.key)
        self.assertEqual(current.state, 'Z')
        self.assertEqual(monitor._get_subreaper(), previous)
        report = self.report()
        self.assertEqual(report['status'], 'cleanup-failed')
        self.assertEqual(report['ownership']['preexisting_zombies'], 1)
        self.assertTrue(report['ownership']['subreaper_restored'])
        self.assertNotIn('termination', report)

    def test_waitid_prevents_completion_when_proc_refresh_hides_a_real_detached_child(self):
        marker, identity = self.root / 'hidden-child.txt', self.root / 'hidden-child.identity.json'
        child = self.detached_writer(marker, identity)
        parent = ('import pathlib,subprocess,sys,time\n'
                  f'subprocess.Popen([sys.executable, "-u", "-c", {child!r}])\n'
                  f'while not pathlib.Path({str(marker)!r}).exists(): time.sleep(0.005)\n'
                  'print("hidden child ready; parent exits")')
        snapshot, open_pipe, has_children = monitor._snapshot_processes, monitor._open_pipe, monitor._has_children
        captured, hidden_refreshes, child_proofs = {}, [], []
        hiding = True
        def capture_pipe(process):
            captured['parent'] = process
            return open_pipe(process)
        def hidden_snapshot():
            found = snapshot()
            process = captured.get('parent')
            if hiding and process is not None and process.returncode == 0 and identity.is_file():
                expected = json.loads(identity.read_text())
                current = found.get(expected['pid'])
                if current is not None and current.start_time == expected['start_time']:
                    hidden_refreshes.append(current.key)
                    del found[current.pid]
            return found
        def observe_children():
            remaining = has_children()
            if hiding and hidden_refreshes:
                child_proofs.append(remaining)
            return remaining
        def diagnostics(*_):
            nonlocal hiding
            hiding = False
            return {'masked_process_refreshes': len(hidden_refreshes)}
        limits = monitor.BuildLimits(phase_seconds=3, silent_seconds=0.8, test_seconds=2,
                                      termination_seconds=0.5, heartbeat_seconds=0.05)
        started = time.monotonic()
        with patch.object(monitor, '_open_pipe', side_effect=capture_pipe), \
                patch.object(monitor, '_snapshot_processes', side_effect=hidden_snapshot), \
                patch.object(monitor, '_has_children', side_effect=observe_children), \
                self.assertRaises(monitor.BuildTimeout) as failure:
            self.run_script(parent, limits=limits, diagnostics=diagnostics)
        self.assertTrue(hidden_refreshes)
        self.assertTrue(child_proofs)
        self.assertTrue(all(child_proofs))
        self.assertEqual(failure.exception.reason, 'no build output')
        self.assertLess(time.monotonic() - started, 5)
        self.assert_stopped(self.report())
        self.assert_marker_stopped(marker, identity)

    def test_unknown_waitid_state_refuses_launch_and_cannot_claim_child_closure(self):
        previous = monitor._get_subreaper()
        unavailable = OSError(errno.EIO, 'Fixture waitid state is unknown')
        with patch.object(monitor.os, 'waitid', side_effect=unavailable), \
                patch.object(monitor.subprocess, 'Popen') as launch, \
                patch.object(monitor, '_signal_owned') as signal_owned, \
                self.assertRaises(monitor.BuildCleanupError) as failure:
            self.run_script('unexecuted')
        launch.assert_not_called()
        signal_owned.assert_not_called()
        self.assertIs(failure.exception.__cause__, unavailable)
        self.assertEqual(monitor._get_subreaper(), previous)
        report = self.report()
        self.assertEqual(report['status'], 'cleanup-failed')
        self.assertTrue(report['ownership']['subreaper_restored'])
        self.assertNotIn('termination', report)

    def test_proc_read_and_enumeration_failures_refuse_launch_instead_of_claiming_empty(self):
        read_bytes, iterdir = Path.read_bytes, Path.iterdir
        previous = monitor._get_subreaper()
        for kind in ('read', 'enumeration'):
            with self.subTest(kind=kind):
                unavailable = PermissionError(errno.EACCES, 'Process table fixture is unreadable')
                def failing_read(path):
                    if path == Path('/proc') / str(os.getpid()) / 'stat':
                        raise unavailable
                    return read_bytes(path)
                def failing_enumeration(path):
                    if path == Path('/proc'):
                        raise unavailable
                    return iterdir(path)
                with patch.object(Path, 'read_bytes', failing_read if kind == 'read' else read_bytes), \
                        patch.object(Path, 'iterdir', failing_enumeration if kind == 'enumeration' else iterdir), \
                        patch.object(monitor.subprocess, 'Popen') as launch, \
                        self.assertRaises(monitor.BuildCleanupError) as failure:
                    self.run_script('unexecuted', phase=f'proc-{kind}-failure')
                launch.assert_not_called()
                self.assertIs(failure.exception.__cause__, unavailable)
                self.assertEqual(monitor._get_subreaper(), previous)
                report = self.report(f'proc-{kind}-failure')
                self.assertEqual(report['status'], 'cleanup-failed')
                self.assertNotIn('termination', report)
                self.assertTrue(report['ownership']['subreaper_restored'])

    def test_process_table_failure_after_launch_fails_closed_and_still_retires_owned_children(self):
        snapshot, observed = monitor._snapshot_processes, []
        unavailable = monitor.BuildCleanupError('Process refresh fixture is unreadable')
        def failing_refresh():
            observed.append(None)
            if len(observed) == 2:
                raise unavailable
            return snapshot()
        with patch.object(monitor, '_snapshot_processes', side_effect=failing_refresh), \
                self.assertRaises(monitor.BuildCleanupError) as failure:
            self.run_script('import time; print("ready"); time.sleep(20)')
        self.assertIs(failure.exception, unavailable)
        self.assertGreater(len(observed), 2)
        self.assertEqual(self.report()['status'], 'cleanup-failed')
        self.assert_stopped(self.report())

    def test_subreaper_read_failure_refuses_launch_and_cannot_claim_restoration(self):
        previous = monitor._get_subreaper()
        unavailable = OSError(errno.EPERM, 'Subreaper state fixture is unreadable')
        with patch.object(monitor, '_get_subreaper', side_effect=unavailable), \
                patch.object(monitor.subprocess, 'Popen') as launch, \
                patch.object(monitor, '_signal_owned') as signal_owned, \
                self.assertRaises(monitor.BuildCleanupError) as failure:
            self.run_script('unexecuted')
        launch.assert_not_called()
        signal_owned.assert_not_called()
        self.assertIs(failure.exception.__cause__, unavailable)
        self.assertEqual(monitor._get_subreaper(), previous)
        report = self.report()
        self.assertEqual(report['status'], 'cleanup-failed')
        self.assertFalse(report['ownership']['subreaper_restored'])
        self.assertNotIn('termination', report)

    def test_subreaper_restoration_failure_blocks_an_otherwise_successful_process(self):
        previous, set_subreaper = monitor._get_subreaper(), monitor._set_subreaper
        calls = []
        unavailable = OSError(errno.EPERM, 'Subreaper restoration fixture failure')
        def failing_restore(value):
            calls.append(value)
            if len(calls) == 2:
                raise unavailable
            return set_subreaper(value)
        try:
            with patch.object(monitor, '_set_subreaper', side_effect=failing_restore), \
                    self.assertRaises(monitor.BuildCleanupError) as failure:
                self.run_script('print("build completed")')
            self.assertEqual(calls, [1, previous])
            self.assertIs(failure.exception.__cause__, unavailable)
            report = self.report()
            self.assertEqual(report['status'], 'cleanup-failed')
            self.assertFalse(report['ownership']['subreaper_restored'])
            self.assertTrue(report['termination']['ownership_verified'])
            self.assertEqual(report['termination']['remaining_live_owned_processes'], [])
            self.assertEqual(report['termination']['remaining_owned_zombies'], [])
        finally:
            set_subreaper(previous)
        self.assertEqual(monitor._get_subreaper(), previous)

    def test_thread_dump_write_and_close_failures_preserve_its_primary_write_error(self):
        target, open_file = self.root / 'failing.threads.txt', Path.open
        previous, open_pipe = monitor._get_subreaper(), monitor._open_pipe
        write_error, close_error = OSError('Thread dump write fixture failure'), OSError('Thread dump close fixture failure')
        attempted, captured = [], {}
        with ExitStack() as resources:
            def capture_pipe(process):
                captured['owner'] = process._ci_owner
                return open_pipe(process)
            def failing_open(path, *args, **kwargs):
                output = open_file(path, *args, **kwargs)
                if path == target:
                    captured['file'] = output
                    close = output.close
                    def fail_close():
                        attempted.append('file')
                        close()
                        raise close_error
                    resources.enter_context(patch.object(output, 'write', side_effect=write_error))
                    resources.enter_context(patch.object(output, 'close', side_effect=fail_close))
                return output
            resources.enter_context(patch.object(Path, 'open', failing_open))
            resources.enter_context(patch.object(monitor, '_open_pipe', side_effect=capture_pipe))
            with self.assertRaises(monitor.BuildCleanupError) as failure:
                monitor._thread_dump([sys.executable, '-u', '-c', 'import time; print("threads"); time.sleep(10)'],
                    target, self.environment, time.monotonic() + 2, lambda: None)
        self.assertIs(failure.exception.__cause__, write_error)
        self.assertEqual(attempted, ['file'])
        self.assertTrue(captured['file'].closed)
        self.assertEqual(captured['owner'].members(), [])
        self.assertEqual(monitor._get_subreaper(), previous)
        self.assertTrue(any('Thread dump cleanup' in note for note in write_error.__notes__))

    def test_thread_dump_write_and_close_failures_preserve_build_timeout_and_reap_both_roots(self):
        original_type, open_file = monitor.BuildTimeout, Path.open
        created, captured, attempted = [], {}, []
        class CapturedTimeout(original_type):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                created.append(self)
        def diagnostics(process, directory, environment, interrupted):
            captured['owner'] = process._ci_owner
            return monitor._thread_dump([sys.executable, '-u', '-c', 'import time; print("threads"); time.sleep(10)'],
                directory / 'failing.threads.txt', environment, time.monotonic() + 2,
                interrupted, owner=process._ci_owner)
        limits = monitor.BuildLimits(phase_seconds=3, silent_seconds=0.25, test_seconds=2,
                                      termination_seconds=0.2, heartbeat_seconds=0.05)
        with ExitStack() as resources:
            def failing_open(path, *args, **kwargs):
                output = open_file(path, *args, **kwargs)
                if path.name == 'failing.threads.txt':
                    captured['file'] = output
                    close = output.close
                    def fail_close():
                        attempted.append('file')
                        close()
                        raise OSError('Thread dump close fixture failure')
                    resources.enter_context(patch.object(output, 'write',
                                                         side_effect=OSError('Thread dump write fixture failure')))
                    resources.enter_context(patch.object(output, 'close', side_effect=fail_close))
                return output
            resources.enter_context(patch.object(Path, 'open', failing_open))
            resources.enter_context(patch.object(monitor, 'BuildTimeout', CapturedTimeout))
            with self.assertRaises(monitor.BuildCleanupError) as failure:
                self.run_script('import time; print("ready"); time.sleep(10)', limits=limits, diagnostics=diagnostics)
        self.assertEqual(len(created), 1)
        self.assertIs(failure.exception.__cause__, created[0])
        self.assertEqual(failure.exception.__cause__.reason, 'no build output')
        self.assertEqual(attempted, ['file'])
        self.assertTrue(captured['file'].closed)
        self.assertEqual(captured['owner'].members(), [])
        self.assertEqual(self.report()['status'], 'cleanup-failed')
        self.assert_stopped(self.report())


if __name__ == '__main__':
    unittest.main()
