"""Monitor Linux cloud build deadlines and retain bounded diagnostics before stopping children."""
from collections import deque
from contextlib import contextmanager
from dataclasses import dataclass
import ctypes
import errno
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import selectors
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time


TEST_EVENT_PREFIX = 'MELOX_CI_TEST '
MAX_LINE_BYTES = 8192
MAX_LOG_BYTES = 8 * 1024 * 1024
MAX_DUMP_BYTES = 2 * 1024 * 1024
MAX_ACTIVE_TESTS = 64
MAX_PENDING_OUTPUT_BYTES = 64 * 1024
MAX_NATIVE_CRASH_DIRECTORY_ENTRIES = 256
MAX_NATIVE_CRASH_FILES = 4
MAX_NATIVE_CRASH_FILE_BYTES = 2 * 1024 * 1024
MAX_NATIVE_CRASH_TOTAL_BYTES = 4 * 1024 * 1024
MAX_NATIVE_CRASH_LINES = 8192
MAX_NATIVE_CRASH_LINE_BYTES = 4096
MAX_NATIVE_CRASH_FRAMES = 64
MAX_NATIVE_CRASH_SUMMARY_BYTES = 64 * 1024
NATIVE_CRASH_CAPTURE_SECONDS = 2
TOTAL_BUILD_SECONDS = 45 * 60
_OWNERSHIP_LOCK = threading.Lock()
_NATIVE_CRASH_NAME = re.compile(r'hs_err_pid[1-9][0-9]{0,9}\.log\Z')
_NATIVE_CRASH_MODULES = frozenset(('libc.so.6', 'libjvm.so', 'libjava.so', 'libjli.so', 'libnet.so',
    'libnio.so', 'libzip.so', 'libverify.so', 'libinstrument.so', 'libjsig.so', 'libpthread.so.0',
    'libdl.so.2', 'librt.so.1', 'libm.so.6', 'libstdc++.so.6', 'libgcc_s.so.1',
    'ld-linux-x86-64.so.2', 'ld-linux-aarch64.so.1', 'libsqliteJni.so'))
_NATIVE_CRASH_JAVA_PREFIXES = ('java.', 'javax.', 'jdk.', 'sun.', 'com.sun.', 'android.', 'androidx.',
    'com.android.', 'com.google.', 'org.gradle.', 'org.robolectric.', 'org.junit.', 'junit.',
    'org.jetbrains.', 'kotlin.', 'kotlinx.', 'io.github.onlinemo.meloxwatch.')


@dataclass(frozen=True)
class BuildLimits:
    phase_seconds: float = 20 * 60
    silent_seconds: float = 5 * 60
    test_seconds: float = 4 * 60
    termination_seconds: float = 2
    heartbeat_seconds: float = 30

    def __post_init__(self):
        if any(isinstance(value, bool) or not math.isfinite(value) or value <= 0
               for value in self.__dict__.values()):
            raise ValueError('Build deadlines must be positive finite numbers')


class BuildTimeout(subprocess.TimeoutExpired):
    def __init__(self, command, phase, reason, seconds):
        super().__init__(command, seconds)
        self.phase, self.reason = phase, reason

    def __str__(self):
        return f'Build phase {self.phase} stopped: {self.reason} ({self.timeout:g}s limit)'


class BuildInterrupted(KeyboardInterrupt):
    pass


class BuildCleanupError(RuntimeError):
    pass


class BuildReportError(RuntimeError):
    pass


def _note_failure(primary, operation, error):
    primary.add_note(f'{operation} also failed: {type(error).__name__}')


class TestProgress:
    """A start keeps its original deadline; unrelated output cannot renew it."""
    def __init__(self):
        self.active = {}
        self.recent = deque(maxlen=16)
        self.completed = 0
        self.failed = 0
        self.invalid_events = 0
        self.longest_completed_seconds = 0

    def consume(self, line, observed_at):
        if not line.startswith(TEST_EVENT_PREFIX):
            return False
        try:
            event = json.loads(line[len(TEST_EVENT_PREFIX):])
            if not isinstance(event, dict) or event.get('event') not in ('start', 'end'):
                raise ValueError('Invalid test event')
            identity = tuple(event.get(field) for field in ('task', 'className', 'name'))
            if any(not isinstance(value, str) or not value or len(value) > 2048 or
                   any(ord(character) < 32 for character in value) for value in identity):
                raise ValueError('Invalid test identity')
            if event['event'] == 'start':
                if identity in self.active or len(self.active) >= MAX_ACTIVE_TESTS:
                    raise ValueError('Test admission mismatch')
                self.active[identity] = observed_at
            else:
                if identity not in self.active or event.get('result') not in ('SUCCESS', 'FAILURE', 'SKIPPED'):
                    raise ValueError('Test completion mismatch')
                started = self.active.pop(identity)
                self.longest_completed_seconds = max(self.longest_completed_seconds, observed_at - started)
                self.completed += 1
                self.failed += event['result'] == 'FAILURE'
                self.recent.append({'task': identity[0], 'className': identity[1], 'name': identity[2],
                                    'result': event['result'], 'observed_seconds': observed_at - started})
            return True
        except (ValueError, TypeError):
            self.invalid_events += 1
            return False

    def overdue(self, now, seconds):
        return self.longest_completed_seconds >= seconds or any(now - started >= seconds for started in self.active.values())

    def snapshot(self, now):
        return {'completed_events': self.completed, 'failed_events': self.failed,
                'invalid_events': self.invalid_events,
                'longest_completed_seconds': self.longest_completed_seconds,
                'active': [{'task': key[0], 'className': key[1], 'name': key[2],
                            'observed_seconds': now - started} for key, started in self.active.items()],
                'recent': list(self.recent),
                'junit_totals': 'UNKNOWN; use completed original JUnit XML'}


class _EventLines:
    def __init__(self, progress):
        self.progress, self.partial, self.dropping = progress, bytearray(), False

    def consume(self, chunk, now):
        for index, fragment in enumerate(chunk.split(b'\n')):
            if index:
                if not self.dropping:
                    self.progress.consume(self.partial.decode('utf-8', errors='replace').rstrip('\r'), now)
                self.partial.clear()
                self.dropping = False
            if not self.dropping:
                self.partial.extend(fragment)
                if len(self.partial) > MAX_LINE_BYTES:
                    if self.partial.startswith(TEST_EVENT_PREFIX.encode()):
                        self.progress.invalid_events += 1
                    self.partial.clear()
                    self.dropping = True

    def finish(self, now):
        if self.partial and not self.dropping:
            self.progress.consume(self.partial.decode('utf-8', errors='replace').rstrip('\r'), now)
        self.partial.clear()


@contextmanager
def _interrupts():
    # Signal handlers only mark cancellation: cleanup and report writes must remain bounded and uninterrupted.
    previous, received = {}, []
    if threading.current_thread() is threading.main_thread():
        def interrupted(number, _frame):
            if not received:
                received.append(number)
        for number in (signal.SIGINT, signal.SIGTERM):
            previous[number] = signal.signal(number, interrupted)
    try:
        yield lambda: received[0] if received else None
    finally:
        for number, handler in previous.items():
            signal.signal(number, handler)


@dataclass(frozen=True)
class _ProcessIdentity:
    pid: int
    start_time: int
    parent_pid: int
    state: str
    name: str

    @property
    def key(self):
        return self.pid, self.start_time


def _read_process(pid):
    try:
        raw = (Path('/proc') / str(pid) / 'stat').read_bytes()
    except OSError as error:
        if error.errno in (errno.ENOENT, errno.ESRCH):
            return None
        raise BuildCleanupError('Cannot read a Linux process identity') from error
    try:
        closing = raw.rindex(b')')
        opening = raw.index(b'(')
        fields = raw[closing + 2:].split()
        started, parent = int(fields[19]), int(fields[1])
        if int(raw[:opening].strip()) != pid or len(fields[0]) != 1 or started < 0 or parent < 0:
            raise ValueError('Ambiguous process identity')
        return _ProcessIdentity(pid, started, parent, fields[0].decode('ascii'),
                                raw[opening + 1:closing].decode('utf-8', errors='replace')[:128])
    except (ValueError, IndexError) as error:
        raise BuildCleanupError('Cannot validate a Linux process identity') from error


def _snapshot_processes():
    try:
        directories = list(Path('/proc').iterdir())
    except OSError as error:
        raise BuildCleanupError('Cannot enumerate the Linux process table') from error
    found = {}
    for directory in directories:
        if directory.name.isdecimal():
            identity = _read_process(int(directory.name))
            if identity is not None:
                found[identity.pid] = identity
    if os.getpid() not in found:
        raise BuildCleanupError('Linux process table does not identify this build monitor')
    return found


def _get_subreaper():
    value = ctypes.c_int()
    library = ctypes.CDLL(None, use_errno=True)
    if library.prctl(37, ctypes.byref(value), 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), 'Cannot read Linux child subreaper state')
    return value.value


def _set_subreaper(value):
    library = ctypes.CDLL(None, use_errno=True)
    if library.prctl(36, value, 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), 'Cannot set Linux child subreaper state')


def _has_children():
    # WNOWAIT preserves zombies; __WALL includes clone children and children of all monitor threads.
    try:
        os.waitid(os.P_ALL, 0, os.WEXITED | os.WNOHANG | os.WNOWAIT | 0x40000000)
    except OSError as error:
        if error.errno == errno.ECHILD:
            return False
        raise BuildCleanupError('Cannot verify Linux child process closure') from error
    # None means eligible live children still exist, rather than an empty child set.
    return True


def _signal_owned(identity, number):
    # A pidfd pins the target even if the PID is recycled between inspection and signaling.
    try:
        descriptor = os.pidfd_open(identity.pid, 0)
    except ProcessLookupError:
        return
    try:
        current = _read_process(identity.pid)
        if current is not None and current.key == identity.key:
            signal.pidfd_send_signal(descriptor, number)
    except ProcessLookupError:
        pass
    finally:
        os.close(descriptor)


class _LinuxProcessOwner:
    """One dedicated monitor owns new children, including descendants detached with setsid()."""
    def __init__(self):
        self.previous_subreaper = None
        self.baseline = set()
        self.baseline_zombies = 0
        self.baseline_live_descendants = 0
        self.tracked = {}
        self.launchers = {}
        self.monitor_identity = None
        self.locked = False
        self.state_touched = False
        self.restored = False

    def activate(self):
        if (not hasattr(os, 'pidfd_open') or not hasattr(signal, 'pidfd_send_signal') or
                not all(hasattr(os, name) for name in ('waitid', 'P_ALL', 'WEXITED', 'WNOHANG', 'WNOWAIT'))):
            raise BuildCleanupError('Linux cloud ownership requires pidfd and waitid support')
        if threading.current_thread() is not threading.main_thread() or threading.active_count() != 1:
            raise BuildCleanupError('Child ownership requires a dedicated single-threaded monitor')
        if signal.getsignal(signal.SIGCHLD) != signal.SIG_DFL:
            raise BuildCleanupError('Child exit observation requires default SIGCHLD handling')
        if not _OWNERSHIP_LOCK.acquire(blocking=False):
            raise BuildCleanupError('Another child ownership scope is already active')
        self.locked = True
        self.previous_subreaper = _get_subreaper()
        if self.previous_subreaper not in (0, 1):
            raise BuildCleanupError('Invalid Linux subreaper state')
        snapshot = _snapshot_processes()
        self.monitor_identity = snapshot[os.getpid()].key
        descendants = {self.monitor_identity}
        while True:
            added = {item.key for item in snapshot.values()
                     if item.parent_pid in snapshot and snapshot[item.parent_pid].key in descendants}
            if added <= descendants:
                break
            descendants.update(added)
        self.baseline = descendants - {self.monitor_identity}
        self.baseline_zombies = sum(item.key in self.baseline and item.state == 'Z' for item in snapshot.values())
        self.baseline_live_descendants = len(self.baseline) - self.baseline_zombies
        # Existing live work may fork an unobserved child that is later adopted. Refuse to guess its owner.
        if self.baseline_live_descendants:
            raise BuildCleanupError('Dedicated build monitor has pre-existing live descendants; no build launched')
        if self.baseline or _has_children():
            raise BuildCleanupError('Dedicated build monitor has pre-existing unreaped children; no build launched')
        self.state_touched = True
        _set_subreaper(1)
        if _get_subreaper() != 1:
            raise BuildCleanupError('Linux child subreaper was not enabled')

    def register(self, process, tag='build'):
        # Popen has not been polled yet: even an immediate exit must still have a pinned /proc identity.
        process._ci_owner, process._ci_owner_tag = self, tag
        identity = _read_process(process.pid)
        if identity is None or identity.parent_pid != os.getpid():
            raise BuildCleanupError('Cannot establish launched process ownership')
        self.tracked[identity.key] = tag
        self.launchers[identity.key] = process

    def refresh(self):
        for process in self.launchers.values():
            process.poll()
        snapshot = _snapshot_processes()
        if snapshot[os.getpid()].key != self.monitor_identity:
            raise BuildCleanupError('Linux monitor identity changed')
        owned = {item.key: self.tracked[item.key] for item in snapshot.values() if item.key in self.tracked}
        # With no pre-existing live descendants, newly adopted direct children belong to this scope.
        owned.update({item.key: self.tracked.get(item.key, 'build') for item in snapshot.values()
                      if item.parent_pid == os.getpid() and item.key not in self.baseline})
        while True:
            added = {item.key: owned[snapshot[item.parent_pid].key] for item in snapshot.values()
                     if item.key not in self.baseline and item.parent_pid in snapshot and
                     snapshot[item.parent_pid].key in owned}
            if added.keys() <= owned.keys():
                break
            owned.update(added)
        self.tracked = owned
        for item in snapshot.values():
            if item.key not in owned or item.key in self.launchers or item.parent_pid != os.getpid() or item.state != 'Z':
                continue
            try:
                os.waitpid(item.pid, os.WNOHANG)
            except ChildProcessError as error:
                current = _read_process(item.pid)
                if current is not None and current.key == item.key:
                    raise BuildCleanupError('Owned adopted zombie could not be reaped') from error
        # Reaping a parent can adopt further children; the next refresh includes them too.
        remaining = []
        for item in snapshot.values():
            if item.key in owned:
                current = _read_process(item.pid)
                if current is not None and current.key == item.key:
                    remaining.append((current, owned[item.key]))
        return remaining

    def members(self, tag=None):
        return [(item.pid, item.name) for item, category in self.refresh()
                if item.state != 'Z' and (tag is None or category == tag)]

    def stop(self, process, grace, tag=None):
        try:
            return self._stop_tree(process, grace, tag)
        except BaseException as primary:
            # A failed scan is never proof of closure. Still stop individually pinned, previously observed work.
            for key in list(self.tracked):
                try:
                    current = _read_process(key[0])
                    if current is not None and current.key == key:
                        _signal_owned(current, signal.SIGKILL)
                except BaseException as error:
                    _note_failure(primary, 'Stopping a known child after process scan failure', error)
            for launcher in {*self.launchers.values(), process}:
                try:
                    launcher.wait(timeout=grace)
                except BaseException as error:
                    _note_failure(primary, 'Reaping a launcher after process scan failure', error)
            raise

    def _stop_tree(self, process, grace, tag):
        terminated = set()
        for number in (signal.SIGTERM, signal.SIGKILL):
            deadline, signaled = time.monotonic() + grace, set()
            while True:
                targets = [item for item, category in self.refresh() if tag is None or category == tag]
                if not targets and (tag is not None or not _has_children()):
                    break
                for item in targets:
                    if item.state != 'Z' and item.key not in signaled:
                        _signal_owned(item, number)
                        signaled.add(item.key)
                        terminated.add(item.key)
                if time.monotonic() >= deadline:
                    break
                time.sleep(min(0.02, max(0, deadline - time.monotonic())))
        remaining = [item for item, category in self.refresh() if tag is None or category == tag]
        if tag is None and _has_children():
            raise BuildCleanupError('Owned child closure could not be verified before the cleanup deadline')
        process.poll()
        return {'parent_reaped': process.returncode is not None,
                'remaining_live_owned_processes': [item.pid for item in remaining if item.state != 'Z'],
                'remaining_owned_zombies': [item.pid for item in remaining if item.state == 'Z'],
                'ownership_verified': tag is None, 'signaled_owned_process_count': len(terminated)}

    def restore(self):
        try:
            if self.state_touched:
                if self.refresh() or _has_children():
                    raise BuildCleanupError('Cannot restore subreaper with remaining owned children')
                if _get_subreaper() != 1:
                    raise BuildCleanupError('Linux child subreaper changed during build')
                _set_subreaper(self.previous_subreaper)
                if _get_subreaper() != self.previous_subreaper:
                    raise BuildCleanupError('Linux child subreaper state was not restored')
            elif self.previous_subreaper is None or _get_subreaper() != self.previous_subreaper:
                raise BuildCleanupError('Cannot verify unchanged Linux child subreaper state')
            self.restored = True
        finally:
            if self.locked:
                _OWNERSHIP_LOCK.release()
                self.locked = False


def _stop_owned_processes(process, grace):
    owner = process._ci_owner
    registered = any(launcher is process for launcher in owner.launchers.values())
    tag = process._ci_owner_tag if registered and process._ci_owner_tag != 'build' else None
    return owner.stop(process, grace, tag=tag)


class _OutputSink:
    """Forward bounded batches with nonblocking writes; a full Actions pipe cannot stop the watchdog."""
    def __init__(self, output):
        self.output, self.pending = output, bytearray()
        self.dropped_bytes = 0
        self.descriptor, self.original_flags = None, None

    def activate(self):
        if type(self.output) is io.StringIO:
            return
        import fcntl
        self.descriptor = self.output.fileno()
        mode = os.fstat(self.descriptor).st_mode
        if not (stat.S_ISFIFO(mode) or stat.S_ISSOCK(mode) or stat.S_ISCHR(mode)):
            raise ValueError('Cloud output must be a nonblocking pipe, socket or terminal')
        self.original_flags = fcntl.fcntl(self.descriptor, fcntl.F_GETFL)
        fcntl.fcntl(self.descriptor, fcntl.F_SETFL, self.original_flags | os.O_NONBLOCK)

    def enqueue(self, raw):
        admitted = min(len(raw), MAX_PENDING_OUTPUT_BYTES - len(self.pending))
        self.pending.extend(raw[:admitted])
        self.dropped_bytes += len(raw) - admitted

    def pump(self):
        for _ in range(8):
            if not self.pending:
                break
            batch = bytes(self.pending[:4096])
            if self.descriptor is None:
                self.output.write(batch.decode('utf-8', errors='replace'))
                count = len(batch)
            else:
                try:
                    count = os.write(self.descriptor, batch)
                except BlockingIOError:
                    break
            if count <= 0:
                raise OSError('Cloud output accepted no bytes')
            del self.pending[:count]

    def close(self):
        # Do not flush a caller's TextIOWrapper: its blocking buffer is outside this watchdog.
        try:
            self.pump()
        finally:
            self.dropped_bytes += len(self.pending)
            self.pending.clear()
            if self.original_flags is not None:
                import fcntl
                fcntl.fcntl(self.descriptor, fcntl.F_SETFL, self.original_flags)

    def snapshot(self):
        return {'dropped_bytes': self.dropped_bytes, 'pending_bytes': len(self.pending),
                'maximum_pending_bytes': MAX_PENDING_OUTPUT_BYTES}


def _open_pipe(process):
    os.set_blocking(process.stdout.fileno(), False)
    selector = selectors.DefaultSelector()
    try:
        selector.register(process.stdout, selectors.EVENT_READ)
        return selector
    except BaseException as primary:
        try:
            selector.close()
        except BaseException as error:
            _note_failure(primary, 'Closing an unregistered pipe selector', error)
        raise


def _close_resources(resources, primary=None):
    failure = primary
    for label, resource in resources:
        if resource is None:
            continue
        try:
            resource.close()
        except BaseException as error:
            if failure is None:
                failure = error
            else:
                _note_failure(failure, label, error)
    return failure


def _thread_dump(command, target, environment, deadline, interrupted, owner=None):
    """Read into a capped file, without communicate() accumulating an unbounded stdout buffer."""
    process, selector, output, primary = None, None, None, None
    standalone = owner is None
    owner = _LinuxProcessOwner() if standalone else owner
    captured, truncated, status, ended = 0, False, 'completed', False
    try:
        if standalone:
            owner.activate()
        process = subprocess.Popen(command, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        owner.register(process, tag=f'diagnostic-{process.pid}')
        selector = _open_pipe(process)
        output = target.open('wb')
        while True:
            if interrupted() is not None or time.monotonic() >= deadline:
                status = 'interrupted' if interrupted() is not None else 'timed-out'
                break
            members = owner.members(tag=process._ci_owner_tag)
            if ended:
                if process.poll() is not None and not members:
                    status = 'completed' if process.returncode == 0 else 'failed'
                    break
                time.sleep(0.02)
                continue
            if not selector.select(timeout=0.05):
                continue
            try:
                chunk = os.read(process.stdout.fileno(), 4096)
            except BlockingIOError:
                continue
            if not chunk:
                ended = True
                continue
            available = min(len(chunk), MAX_DUMP_BYTES - captured)
            output.write(chunk[:available])
            captured += available
            if available < len(chunk):
                truncated, status = True, 'size-limit'
                break
    except BaseException as error:
        primary = error
    finally:
        cleanup_error = None
        if process is not None:
            try:
                termination = owner.stop(process, 0.25, tag=None if standalone else process._ci_owner_tag)
                if (not termination['parent_reaped'] or termination['remaining_live_owned_processes'] or
                        termination['remaining_owned_zombies']):
                    cleanup_error = BuildCleanupError('Thread dump process could not be stopped')
            except BaseException as error:
                cleanup_error = error
        cleanup_error = _close_resources((('Closing thread dump selector', selector),
                                          ('Closing thread dump stdout', process.stdout if process else None),
                                          ('Closing thread dump file', output)), cleanup_error)
        if standalone:
            try:
                owner.restore()
            except BaseException as error:
                if cleanup_error is None:
                    cleanup_error = error
                else:
                    _note_failure(cleanup_error, 'Restoring thread dump subreaper', error)
        if cleanup_error is not None:
            if primary is not None:
                _note_failure(primary, 'Thread dump cleanup', cleanup_error)
            raise BuildCleanupError('Thread dump cleanup failed') from (primary or cleanup_error)
    if primary is not None:
        raise primary
    return {'file': target.name, 'exit_code': process.returncode, 'bytes': captured,
            'truncated': truncated, 'status': status,
            'owned_children_closure': 'verified' if standalone else 'deferred-to-build-scope'}


def _target_is_owned(owner, identity):
    current = _read_process(identity.pid)
    if current is None or current.key != identity.key or current.state == 'Z' or current.name != 'java':
        return False
    return any(item.key == identity.key and tag == 'build' and item.state != 'Z'
               for item, tag in owner.refresh())


def _capture_owned_jvm(identity, owner, executable, directory, environment, deadline, interrupted):
    descriptor = os.pidfd_open(identity.pid, 0)
    scratch, primary = None, None
    try:
        if not _target_is_owned(owner, identity):
            return {'status': 'UNAVAILABLE', 'reason': 'TARGET_IDENTITY_CHANGED'}
        # An unverified attach stays outside every report/artifact upload path, including on cancellation.
        scratch = tempfile.TemporaryDirectory(prefix='melox-ci-jvm-', dir='/tmp')
        captured = Path(scratch.name) / 'threads.txt'
        dump = _thread_dump([str(executable), str(identity.pid), 'Thread.print', '-l'],
                            captured, environment, deadline, interrupted, owner=owner)
        # The held pidfd must still refer to a live target: PID reuse cannot make this check pass.
        try:
            signal.pidfd_send_signal(descriptor, 0)
        except ProcessLookupError:
            return {'status': 'UNAVAILABLE', 'reason': 'TARGET_EXITED_DURING_CAPTURE'}
        if not _target_is_owned(owner, identity):
            return {'status': 'UNAVAILABLE', 'reason': 'TARGET_IDENTITY_CHANGED'}
        with captured.open('rb') as stream:
            raw = stream.read(MAX_DUMP_BYTES + 1)
        if len(raw) > MAX_DUMP_BYTES or len(raw) != dump['bytes']:
            raise ValueError('Owned JVM diagnostic bytes do not match the bounded capture')
        target = directory / f'jvm-{identity.pid}.threads.txt'
        with target.open('xb') as stream:
            stream.write(raw)
        return {**dump, 'file': target.name, 'target_identity_verified': True}
    except BaseException as error:
        primary = error
        raise
    finally:
        cleanup_error = None
        if scratch is not None:
            try:
                scratch.cleanup()
            except BaseException as error:
                cleanup_error = error
        try:
            os.close(descriptor)
        except BaseException as error:
            if cleanup_error is None:
                cleanup_error = error
            else:
                _note_failure(cleanup_error, 'Closing the JVM target pidfd', error)
        if cleanup_error is not None:
            if primary is not None:
                _note_failure(primary, 'Cleaning JVM capture resources', cleanup_error)
            else:
                raise BuildCleanupError('JVM capture resources could not be closed') from cleanup_error


def collect_jvm_diagnostics(process, directory, environment, interrupted):
    """Only owned JVM thread stacks; never dump arguments, credentials or environment variables."""
    owner = process._ci_owner
    members = [identity for identity, tag in owner.refresh() if tag == 'build' and identity.state != 'Z'][:64]
    result = {'owned_processes': [{'pid': item.pid, 'command_name': item.name,
                                  'start_time_ticks': item.start_time} for item in members],
              'thread_dumps': [], 'platform': 'linux'}
    executable = Path(environment.get('JAVA_HOME', '')) / 'bin/jcmd'
    if not executable.is_file():
        result['jcmd'] = 'UNAVAILABLE'
        return result
    deadline = time.monotonic() + 30
    for identity in members:
        if interrupted() is not None or time.monotonic() >= deadline:
            break
        if identity.name != 'java' or len(result['thread_dumps']) >= 8:
            continue
        try:
            dump = _capture_owned_jvm(identity, owner, executable, directory, environment,
                                      min(deadline, time.monotonic() + 5), interrupted)
            result['thread_dumps'].append({'pid': identity.pid, 'start_time_ticks': identity.start_time, **dump})
        except OSError:
            result['thread_dumps'].append({'pid': identity.pid, 'start_time_ticks': identity.start_time,
                                          'status': 'UNAVAILABLE'})
    return result


@dataclass(frozen=True)
class _NativeCrashRoot:
    label: str
    parts: tuple
    chain: tuple
    status: str
    existing: frozenset


@dataclass(frozen=True)
class _NativeCrashSnapshot:
    phase: str
    started_ns: int
    roots: tuple


class _NativeCrashLimit(ValueError):
    pass


@contextmanager
def _native_crash_directory(cwd, parts, chain):
    """Open known relative directories with no symlink traversal inside the checkout."""
    if any(not isinstance(part, str) or not re.fullmatch(r'[a-z][a-z0-9-]{0,80}', part) for part in parts):
        raise ValueError('Invalid native crash directory component')
    descriptors, primary = [], None
    try:
        flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
        descriptors.append(os.open(cwd, flags))
        chain.append((os.fstat(descriptors[-1]).st_dev, os.fstat(descriptors[-1]).st_ino))
        for part in parts:
            descriptors.append(os.open(part, flags, dir_fd=descriptors[-1]))
            observed = os.fstat(descriptors[-1])
            chain.append((observed.st_dev, observed.st_ino))
        yield descriptors[-1]
    except BaseException as error:
        primary = error
        raise
    finally:
        closing = None
        for descriptor in reversed(descriptors):
            try:
                os.close(descriptor)
            except OSError as error:
                if closing is None:
                    closing = error
                else:
                    _note_failure(closing, 'Closing native crash directory', error)
        if closing is not None:
            if primary is not None:
                _note_failure(primary, 'Closing native crash directories', closing)
            else:
                raise closing


def _native_crash_names(descriptor, deadline):
    names = []
    with os.scandir(descriptor) as entries:
        for count, entry in enumerate(entries, 1):
            if count > MAX_NATIVE_CRASH_DIRECTORY_ENTRIES or time.monotonic() >= deadline:
                raise _NativeCrashLimit('directory_limit')
            if _NATIVE_CRASH_NAME.fullmatch(entry.name):
                names.append(entry.name)
    return frozenset(names)


def snapshot_native_crashes(cwd, phase):
    """A partial baseline cannot establish freshness; do not attribute its files to this phase."""
    if not isinstance(phase, str) or not re.fullmatch(r'[a-z][a-z0-9-]{0,80}', phase):
        raise ValueError('Invalid native crash phase')
    started_ns, deadline, roots = time.time_ns(), time.monotonic() + NATIVE_CRASH_CAPTURE_SECONDS, []
    paths = (('test-jvm', ('app', 'build', 'ci-native-crash', phase)),
             ('android-root', ()), ('app-root', ('app',)))
    for label, parts in paths:
        chain, status, existing = [], 'ok', frozenset()
        try:
            with _native_crash_directory(cwd, parts, chain) as descriptor:
                existing = _native_crash_names(descriptor, deadline)
        except FileNotFoundError:
            status = 'missing' if parts and chain else 'unavailable'
        except _NativeCrashLimit:
            status = 'directory_limit'
        except OSError:
            status = 'unavailable'
        roots.append(_NativeCrashRoot(label, parts, tuple(chain), status, existing))
    return _NativeCrashSnapshot(phase, started_ns, tuple(roots))


def _native_crash_identity(observed):
    return (observed.st_dev, observed.st_ino, observed.st_mode, observed.st_nlink, observed.st_size,
            observed.st_mtime_ns, observed.st_ctime_ns)


def _native_crash_eligibility(observed, started_ns, ended_ns, available_bytes):
    if not stat.S_ISREG(observed.st_mode):
        return 'non_regular'
    if observed.st_nlink != 1:
        return 'linked_file'
    if not started_ns <= observed.st_mtime_ns <= ended_ns or not started_ns <= observed.st_ctime_ns <= ended_ns:
        return 'outside_phase'
    if observed.st_size <= 0 or observed.st_size > min(MAX_NATIVE_CRASH_FILE_BYTES, available_bytes):
        return 'size_limit'
    return None


def _read_native_crash(descriptor, name, initial, deadline):
    handle = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC | os.O_NONBLOCK, dir_fd=descriptor)
    with os.fdopen(handle, 'rb', buffering=0) as stream:
        if _native_crash_identity(os.fstat(stream.fileno())) != _native_crash_identity(initial):
            raise _NativeCrashLimit('file_changed')
        raw = bytearray()
        while len(raw) <= initial.st_size:
            if time.monotonic() >= deadline:
                raise _NativeCrashLimit('capture_deadline')
            chunk = stream.read(min(64 * 1024, initial.st_size + 1 - len(raw)))
            if not chunk:
                break
            raw.extend(chunk)
        final = os.fstat(stream.fileno())
        current = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
        if (len(raw) != initial.st_size or _native_crash_identity(final) != _native_crash_identity(initial) or
                _native_crash_identity(current) != _native_crash_identity(initial)):
            raise _NativeCrashLimit('file_changed')
    return bytes(raw)


def _native_crash_frame(line):
    # Only module offsets and known code identifiers leave the raw file. Symbols, descriptors,
    # thread names, native source paths, program counters and free-form frame suffixes do not.
    native = re.match(r'^([CVv])\s+\[([^\]\r\n]{1,512})\+0x([0-9a-fA-F]{1,16})\]', line)
    if native is not None:
        kind, module, offset = native.groups()
        module = module.rsplit('/', 1)[-1]
        if module not in _NATIVE_CRASH_MODULES:
            return None
        return {'kind': kind, 'module': module, 'offset': '0x' + offset.lower()}
    java = re.match(r'^([jJ])\s+(?:[0-9]{1,8}\s+(?:c1|c2|aot)\s+)?'
        r'((?:[A-Za-z_$][A-Za-z0-9_$]*\.)+[A-Za-z_$][A-Za-z0-9_$]*)\.'
        r'(<init>|<clinit>|[A-Za-z_$][A-Za-z0-9_$]*)\(', line)
    if java is not None:
        kind, owner, member = java.groups()
        if len(owner) <= 240 and len(member) <= 80 and owner.startswith(_NATIVE_CRASH_JAVA_PREFIXES):
            return {'kind': kind, 'class': owner, 'method': member}
    if line in ('v  ~StubRoutines::call_stub', 'v  ~StubRoutines::forward_exception_stub',
                'v  ~StubRoutines::catch_exception_stub'):
        return {'kind': 'v', 'stub': line[3:]}
    return None


def parse_native_crash(raw, *, deadline=None):
    """Whitelist the fatal header and first current-thread stack, never copy or redact full raw text."""
    if len(raw) > MAX_NATIVE_CRASH_FILE_BYTES:
        raise _NativeCrashLimit('size_limit')
    result = {'fatal_banner': None, 'fatal_kind': None, 'problematic_frame': None,
              'current_thread_kind': None, 'native_frames': [], 'java_frames': [],
              'filtered_frame_lines': 0, 'line_limit_reached': False, 'frame_limit_reached': False}
    thread_seen, in_thread, section, problematic, seen_stacks = False, False, None, False, set()
    raw_lines = raw.split(b'\n')
    result['line_limit_reached'] = len(raw_lines) > MAX_NATIVE_CRASH_LINES
    for index, original in enumerate(raw_lines[:MAX_NATIVE_CRASH_LINES]):
        if deadline is not None and time.monotonic() >= deadline:
            raise _NativeCrashLimit('capture_deadline')
        if len(original) > MAX_NATIVE_CRASH_LINE_BYTES:
            if section is not None:
                result['filtered_frame_lines'] += 1
            problematic = False
            continue
        try:
            line = original.rstrip(b'\r').decode('ascii').strip()
        except UnicodeDecodeError:
            if section is not None:
                result['filtered_frame_lines'] += 1
            problematic = False
            continue
        if index < 128 and not thread_seen:
            if line == '# A fatal error has been detected by the Java Runtime Environment:':
                result['fatal_banner'] = 'A fatal error has been detected by the Java Runtime Environment'
            signal_match = re.match(r'^#\s+(SIGBUS|SIGSEGV|SIGILL|SIGABRT|SIGFPE|SIGTRAP|'
                                    r'EXCEPTION_ACCESS_VIOLATION|EXCEPTION_ILLEGAL_INSTRUCTION)\s+\(0x[0-9a-fA-F]{1,8}\)', line)
            if signal_match is not None:
                result['fatal_kind'] = signal_match[1]
            elif re.match(r'^#\s+Internal Error\s+\(', line):
                result['fatal_kind'] = 'INTERNAL_ERROR'
            if problematic:
                result['problematic_frame'] = _native_crash_frame(line.lstrip('#').strip())
                problematic = False
            elif line == '# Problematic frame:':
                problematic = True
        if re.fullmatch(r'-{10,}\s+T H R E A D\s+-{10,}', line) and not thread_seen:
            thread_seen, in_thread, section = True, True, None
        elif line.startswith('----------'):
            in_thread, section = False, None
        elif in_thread and result['current_thread_kind'] is None:
            current = re.match(r'^Current thread \(0x[0-9a-fA-F]{1,16}\):\s+'
                               r'(JavaThread|VMThread|WatcherThread|CompilerThread)\b', line)
            if current is not None:
                result['current_thread_kind'] = current[1]
        elif in_thread:
            if line == 'Native frames: (J=compiled Java code, j=interpreted, Vv=VM code, C=native code)':
                if 'native_frames' in seen_stacks:
                    in_thread, section = False, None
                else:
                    section = 'native_frames'
                    seen_stacks.add(section)
            elif line == 'Java frames: (J=compiled Java code, j=interpreted, Vv=VM code)':
                if 'java_frames' in seen_stacks:
                    in_thread, section = False, None
                else:
                    section = 'java_frames'
                    seen_stacks.add(section)
            elif section is not None:
                if not line:
                    section = None
                    continue
                if not re.match(r'^[CVvjJ]\s', line):
                    in_thread, section = False, None
                    continue
                frame = _native_crash_frame(line)
                if frame is None:
                    result['filtered_frame_lines'] += 1
                elif len(result[section]) >= MAX_NATIVE_CRASH_FRAMES:
                    result['frame_limit_reached'] = True
                else:
                    result[section].append(frame)
            elif seen_stacks and line:
                in_thread = False
    if result['fatal_banner'] is None or result['fatal_kind'] is None:
        return None
    return result


def _write_native_crash_summary(cwd, report, phase, number, encoded):
    parts = ('app', 'build', 'reports', 'ci-process', phase)
    if Path(report) != Path(cwd).joinpath(*parts):
        raise ValueError('Native crash summary destination must be the current phase report')
    name, chain = f'native-crash-{number:02d}.json', []
    with _native_crash_directory(cwd, parts, chain) as descriptor:
        handle = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC,
                         0o600, dir_fd=descriptor)
        with os.fdopen(handle, 'wb') as output:
            output.write(encoded)
    return name


def collect_native_crashes(cwd, report, snapshot, binding, reason):
    if (not isinstance(snapshot.phase, str) or not re.fullmatch(r'[a-z][a-z0-9-]{0,80}', snapshot.phase) or
            binding.get('phase') != snapshot.phase):
        raise ValueError('Native crash baseline must belong to the same phase')
    if reason not in ('natural-nonzero-exit', 'timeout'):
        raise ValueError('Native crash capture requires a failed phase')
    ended_ns, deadline = time.time_ns(), time.monotonic() + NATIVE_CRASH_CAPTURE_SECONDS
    result = {'schema_version': 1, 'status': 'NOT_FOUND', 'reason': reason, 'files': [], 'omitted': {},
              'raw_uploaded': False, 'bounds': {'directory_entries': MAX_NATIVE_CRASH_DIRECTORY_ENTRIES,
              'files': MAX_NATIVE_CRASH_FILES, 'file_bytes': MAX_NATIVE_CRASH_FILE_BYTES,
              'total_bytes': MAX_NATIVE_CRASH_TOTAL_BYTES, 'read_probe_bytes_per_file': 1, 'lines': MAX_NATIVE_CRASH_LINES,
              'line_bytes': MAX_NATIVE_CRASH_LINE_BYTES, 'frames_per_stack': MAX_NATIVE_CRASH_FRAMES,
              'summary_bytes': MAX_NATIVE_CRASH_SUMMARY_BYTES, 'capture_seconds': NATIVE_CRASH_CAPTURE_SECONDS}}
    used_bytes, attempted_files, hashes = 0, 0, set()
    safe_binding = {'phase': binding['phase'], 'source_sha': None, 'build_sha': None,
                    'run_id': None, 'run_attempt': None}
    for key in ('source_sha', 'build_sha'):
        value = binding.get(key)
        if isinstance(value, str) and re.fullmatch(r'[0-9a-f]{40}', value):
            safe_binding[key] = value
    for key in ('run_id', 'run_attempt'):
        value = binding.get(key)
        if isinstance(value, str) and re.fullmatch(r'[1-9][0-9]{0,19}', value):
            safe_binding[key] = value

    def omit(classification):
        if classification not in ('baseline_unavailable', 'directory_changed', 'old_file', 'capture_limit',
                'non_regular', 'linked_file', 'outside_phase', 'size_limit', 'duplicate_bytes',
                'no_allowed_fatal_content', 'summary_limit', 'file_changed', 'capture_deadline',
                'directory_limit', 'file_unavailable', 'directory_unavailable'):
            classification = 'diagnostic_unavailable'
        result['omitted'][classification] = result['omitted'].get(classification, 0) + 1

    for root in snapshot.roots:
        if root.status not in ('ok', 'missing'):
            omit('baseline_unavailable')
            continue
        chain = []
        try:
            with _native_crash_directory(cwd, root.parts, chain) as descriptor:
                if tuple(chain[:len(root.chain)]) != root.chain:
                    omit('directory_changed')
                    continue
                for name in sorted(_native_crash_names(descriptor, deadline)):
                    if name in root.existing:
                        omit('old_file')
                        continue
                    if attempted_files >= MAX_NATIVE_CRASH_FILES or time.monotonic() >= deadline:
                        omit('capture_limit')
                        break
                    try:
                        initial = os.stat(name, dir_fd=descriptor, follow_symlinks=False)
                        rejection = _native_crash_eligibility(initial, snapshot.started_ns, ended_ns,
                                                             MAX_NATIVE_CRASH_TOTAL_BYTES - used_bytes - 1)
                        if rejection is not None:
                            omit(rejection)
                            continue
                        attempted_files += 1
                        # Failed or changing reads still consume their reservation, including the growth probe.
                        used_bytes += initial.st_size + 1
                        raw = _read_native_crash(descriptor, name, initial, deadline)
                        raw_hash = hashlib.sha256(raw).hexdigest()
                        if raw_hash in hashes:
                            omit('duplicate_bytes')
                            continue
                        hashes.add(raw_hash)
                        parsed = parse_native_crash(raw, deadline=deadline)
                        if parsed is None:
                            omit('no_allowed_fatal_content')
                            continue
                        summary = {'schema_version': 1, **safe_binding, 'root': root.label,
                                   'raw_bytes': len(raw), 'raw_sha256': raw_hash, 'raw_uploaded': False,
                                   'content': parsed}
                        encoded = (json.dumps(summary, ensure_ascii=True, indent=2) + '\n').encode('ascii')
                        if len(encoded) > MAX_NATIVE_CRASH_SUMMARY_BYTES:
                            omit('summary_limit')
                            continue
                        name = _write_native_crash_summary(cwd, report, snapshot.phase, len(result['files']) + 1, encoded)
                        result['files'].append({'summary_file': name, 'summary_bytes': len(encoded),
                                                'summary_sha256': hashlib.sha256(encoded).hexdigest(),
                                                'raw_bytes': len(raw), 'raw_sha256': raw_hash, 'root': root.label})
                    except _NativeCrashLimit as error:
                        omit(str(error))
                    except OSError:
                        omit('file_unavailable')
        except FileNotFoundError:
            pass
        except (_NativeCrashLimit, OSError):
            omit('directory_unavailable')
    if result['files']:
        result['status'] = 'COLLECTED'
    incomplete = set(result['omitted']) - {'old_file', 'duplicate_bytes'}
    if incomplete:
        result['status'] = 'PARTIAL' if result['files'] else 'UNAVAILABLE'
    return result


def run_gradle(command, *, cwd, env, phase, build_sha=None, total_deadline=None,
               overall_limit_seconds=TOTAL_BUILD_SECONDS, limits=BuildLimits(), output=None,
               diagnostics=collect_jvm_diagnostics):
    """The caller validates cloud authorization and strips tokens; Linux ownership is mandatory."""
    if not sys.platform.startswith('linux') or not Path('/proc').is_dir():
        raise ValueError('Cloud build process ownership requires the Linux runner')
    if not re.fullmatch(r'[a-z][a-z0-9-]{0,80}', phase):
        raise ValueError('Invalid build phase identity')
    if (isinstance(overall_limit_seconds, bool) or not math.isfinite(overall_limit_seconds) or
            overall_limit_seconds <= 0):
        raise ValueError('Overall build limit must be a positive finite number')
    started = time.monotonic()
    total_deadline = started + overall_limit_seconds if total_deadline is None else total_deadline
    if not math.isfinite(total_deadline):
        raise ValueError('Invalid total build deadline')
    if started >= total_deadline:
        raise BuildTimeout(command, phase, 'overall build deadline before phase start', overall_limit_seconds)
    output = sys.stdout if output is None else output
    report = Path(cwd) / 'app/build/reports/ci-process' / phase
    report.mkdir(parents=True, exist_ok=False)
    last_output, last_heartbeat = started, started
    progress = TestProgress()
    lines = _EventLines(progress)
    record = {'schema_version': 1, 'phase': phase, 'status': 'started', 'limits_seconds': limits.__dict__,
              'overall_limit_seconds': overall_limit_seconds,
              'overall_remaining_seconds_at_start': total_deadline - started,
              'source_sha': env.get('SOURCE_SHA'), 'build_sha': build_sha, 'run_id': env.get('GITHUB_RUN_ID'),
              'run_attempt': env.get('GITHUB_RUN_ATTEMPT'), 'apk_exported': False}
    process, selector, log, failure, cleanup_failure = None, None, None, None, None
    crash_snapshot, crash_reason = None, None
    owner, sink = _LinuxProcessOwner(), _OutputSink(output)
    log_bytes, output_bytes = 0, 0

    def save():
        now = time.monotonic()
        record.update(elapsed_seconds=now - started, seconds_since_output=now - last_output,
                      tests=progress.snapshot(now), captured_log_bytes=log_bytes,
                      log_truncated=output_bytes > log_bytes, stdout=sink.snapshot())
        temporary = report / 'process.json.tmp'
        temporary.write_text(json.dumps(record, ensure_ascii=True, indent=2) + '\n', encoding='utf-8')
        temporary.replace(report / 'process.json')

    def preserve_report(primary, operation):
        try:
            save()
        except Exception as error:
            record.setdefault('report_errors', []).append({'operation': operation, 'error_type': type(error).__name__})
            if primary is not None:
                _note_failure(primary, operation, error)
                return primary
            report_failure = BuildReportError('Could not preserve the cloud build process report')
            report_failure.__cause__ = error
            record['status'] = 'report-failed'
            return report_failure
        return primary

    save()
    with _interrupts() as interrupted:
        try:
            owner.activate()
            try:
                crash_snapshot = snapshot_native_crashes(cwd, phase)
            except Exception:
                # Diagnostics are optional; losing a freshness baseline must not alter build execution.
                crash_snapshot = None
            sink.activate()
            log = (report / 'process-output.log').open('wb')
            process_environment = dict(env, MELOX_CI_BUILD_PHASE=phase)
            process = subprocess.Popen(command, cwd=cwd, env=process_environment, stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, start_new_session=True)
            owner.register(process)
            selector = _open_pipe(process)
            ended = False
            while True:
                if not ended and selector.select(timeout=0.05):
                    # Noisy tests get bounded batches; output cannot starve deadline checks.
                    for _ in range(32):
                        try:
                            chunk = os.read(process.stdout.fileno(), 4096)
                        except BlockingIOError:
                            break
                        if not chunk:
                            ended = True
                            lines.finish(time.monotonic())
                            break
                        last_output = time.monotonic()
                        output_bytes += len(chunk)
                        available = min(len(chunk), MAX_LOG_BYTES - log_bytes)
                        if available:
                            log.write(chunk[:available])
                            sink.enqueue(chunk[:available])
                            log_bytes += available
                        lines.consume(chunk, last_output)
                elif ended:
                    time.sleep(0.02)
                sink.pump()
                owned = owner.refresh()
                now = time.monotonic()
                if interrupted() is not None:
                    raise BuildInterrupted(f'Build interrupted by signal {interrupted()}')
                if process.returncode is not None:
                    record['exit_code'] = process.returncode
                    if process.returncode:
                        crash_reason = 'natural-nonzero-exit'
                        raise subprocess.CalledProcessError(process.returncode, command)
                if progress.invalid_events:
                    raise ValueError('Invalid or unmatched test progress event')
                completed = ended and process.returncode is not None and not owned and not _has_children()
                reason = None
                if now >= total_deadline:
                    reason = ('overall build deadline', overall_limit_seconds)
                elif now - started >= limits.phase_seconds:
                    reason = ('phase deadline', limits.phase_seconds)
                elif progress.overdue(now, limits.test_seconds):
                    reason = ('active test deadline including setup/cleanup', limits.test_seconds)
                elif not completed and now - last_output >= limits.silent_seconds:
                    reason = ('no build output', limits.silent_seconds)
                if reason is not None:
                    crash_reason = 'timeout'
                    timeout_failure = BuildTimeout(command, phase, *reason)
                    record.update(status='timed-out', timeout_reason=reason[0])
                    preserve_report(timeout_failure, 'Writing pre-termination report')
                    try:
                        record['diagnostics'] = diagnostics(process, report, env, interrupted)
                    except BuildCleanupError as error:
                        record['diagnostics'] = {'status': 'CLEANUP_FAILED', 'error_type': type(error).__name__,
                                                 'cause_type': type(error.__cause__).__name__}
                        timeout_failure.diagnostic_failure = error
                        _note_failure(timeout_failure, 'JVM diagnostic cleanup', error)
                        raise BuildCleanupError('JVM diagnostic cleanup failed') from timeout_failure
                    except Exception as error:
                        record['diagnostics'] = {'status': 'UNAVAILABLE', 'error_type': type(error).__name__}
                    preserve_report(timeout_failure, 'Writing thread dump report')
                    raise timeout_failure
                if completed:
                    break
                if now - last_heartbeat >= limits.heartbeat_seconds:
                    sink.enqueue((f'\nCI phase {phase}: {now - started:.0f}s elapsed; '
                                  f'{now - last_output:.0f}s since output; {len(progress.active)} active test(s).\n').encode())
                    last_heartbeat = now
                    report_failure = preserve_report(None, 'Writing heartbeat report')
                    if report_failure is not None:
                        raise report_failure
            if progress.active or progress.failed:
                raise ValueError('Build ended without successful, matching test completion events')
            record['status'] = 'completed'
        except BaseException as error:
            failure = error
            record['failure_type'] = type(error).__name__
            if isinstance(error, BuildInterrupted):
                record['status'] = 'interrupted'
            elif isinstance(error, BuildCleanupError):
                record['status'] = 'cleanup-failed'
            elif record['status'] == 'started':
                record['status'] = 'failed'
        finally:
            if process is not None:
                try:
                    if record['status'] == 'completed' and owner.members():
                        failure = BuildCleanupError('Build launcher exited with unfinished owned work')
                        record.update(status='cleanup-failed', failure_type=type(failure).__name__)
                    record['termination'] = _stop_owned_processes(process, limits.termination_seconds)
                    if record['status'] == 'completed' and record['termination']['signaled_owned_process_count']:
                        failure = BuildCleanupError('Build completion required stopping unfinished owned work')
                        record.update(status='cleanup-failed', failure_type=type(failure).__name__)
                    if (not record['termination']['parent_reaped'] or record['termination']['remaining_live_owned_processes'] or
                            record['termination']['remaining_owned_zombies']):
                        cleanup_failure = BuildCleanupError('Build still has live owned processes after termination')
                except BaseException as error:
                    cleanup_failure = error
            cleanup_failure = _close_resources((('Closing build selector', selector),
                                                ('Closing build stdout', process.stdout if process else None),
                                                ('Closing build log', log), ('Restoring cloud output', sink)), cleanup_failure)
            try:
                owner.restore()
            except BaseException as error:
                if cleanup_failure is None:
                    cleanup_failure = error
                else:
                    _note_failure(cleanup_failure, 'Restoring build subreaper', error)
            record['ownership'] = {'method': 'linux-subreaper-and-pidfd',
                                   'previous_subreaper': owner.previous_subreaper,
                                   'preexisting_zombies': owner.baseline_zombies,
                                   'preexisting_live_descendants': owner.baseline_live_descendants,
                                   'subreaper_restored': owner.restored}
            if crash_reason is not None:
                termination = record.get('termination', {})
                if crash_snapshot is None:
                    record['native_crashes'] = {'status': 'UNAVAILABLE', 'reason': 'BASELINE_UNAVAILABLE',
                                               'raw_uploaded': False}
                elif (not termination.get('ownership_verified') or not termination.get('parent_reaped') or
                      termination.get('remaining_live_owned_processes') or termination.get('remaining_owned_zombies')):
                    record['native_crashes'] = {'status': 'UNAVAILABLE', 'reason': 'PROCESS_CLOSURE_UNVERIFIED',
                                               'raw_uploaded': False}
                else:
                    try:
                        record['native_crashes'] = collect_native_crashes(cwd, report, crash_snapshot, record, crash_reason)
                    except Exception as error:
                        record['native_crashes'] = {'status': 'UNAVAILABLE', 'reason': 'CAPTURE_FAILED',
                                                   'error_type': type(error).__name__, 'raw_uploaded': False}
                        if failure is not None:
                            _note_failure(failure, 'Collecting native crash summary', error)
            if interrupted() is not None:
                record['interrupted_signal'] = interrupted()
                record['status'] = 'interrupted'
                if failure is None:
                    failure = BuildInterrupted(f'Build interrupted by signal {interrupted()}')
            if cleanup_failure is not None:
                record.update(status='cleanup-failed', cleanup_failure_type=type(cleanup_failure).__name__)
            final_failure = preserve_report(failure or cleanup_failure, 'Writing final process report')
            if failure is None and cleanup_failure is None:
                failure = final_failure
    if cleanup_failure is not None:
        raise BuildCleanupError('Cloud build cleanup failed; see its process report') from (failure or cleanup_failure)
    if failure is not None:
        raise failure
