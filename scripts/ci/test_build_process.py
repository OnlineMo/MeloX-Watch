"""Python-only deadline/protocol tests; Linux cloud tests use harmless Python child processes."""
from contextlib import ExitStack
import errno
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

import build_process as monitor


def event(kind, name='queued-close', task=':app:testWatchAndroidDebugUnitTest', result=None):
    fields = {'event': kind, 'task': task, 'className': 'DeadlineFixture', 'name': name}
    if result is not None:
        fields['result'] = result
    return monitor.TEST_EVENT_PREFIX + json.dumps(fields, ensure_ascii=False)


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
