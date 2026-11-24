"""Data-only current-run Debug TestResult/JUnit contract for the two fixed applications."""
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import xml.etree.ElementTree as ET
import zipfile

from ci_common import require, sha256
from package_contract import ROLES, SPECS, exact_data, safe_path

REPORT = 'android/app/build/reports/package-debug-tests'
ARCHIVE_FILE = 'debug-tests.zip'
BINDING_KEYS = ('repository', 'source_sha', 'build_sha', 'run_id', 'run_attempt', 'event', 'ref')
MAX_JUNIT_FILES = 1024
MAX_MEMBER_BYTES = 2 * 1024 * 1024
MAX_RAW_BYTES = 32 * 1024 * 1024
MAX_ARCHIVE_BYTES = MAX_RAW_BYTES + 2 * 1024 * 1024
HASH = re.compile(r'[0-9a-f]{64}')
XML_NAME = re.compile(r'TEST-[A-Za-z0-9_.$+\-]{1,220}\.xml')


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def bounded_read(path, limit):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), 'Debug original file is unavailable or linked')
    before = path.stat()
    require(0 < before.st_size <= limit, 'Debug original file exceeds its bound')
    with path.open('rb') as stream:
        raw = stream.read(before.st_size + 1)
    after = path.stat()
    require(len(raw) == before.st_size and (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) ==
            (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns), 'Debug original file changed while reading')
    return raw


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode('utf-8')


def read_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'Duplicate Debug evidence JSON key')
            result[key] = value
        return result
    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Nonfinite Debug evidence value')))


def task_id(role):
    spec = SPECS[role]
    return ':' + spec['module'] + ':test' + spec['flavor'][0].upper() + spec['flavor'][1:] + 'DebugUnitTest'


def junit_directory(role):
    return 'android/' + SPECS[role]['module'] + '/build/test-results/' + task_id(role).rsplit(':', 1)[-1]


def validate_binding(record, binding):
    require(type(record) is dict and exact_data({key: record.get(key) for key in BINDING_KEYS}, binding),
            'Debug evidence uses a different source, snapshot or run')
    require(set(binding) == set(BINDING_KEYS) and binding['repository'] == 'OnlineMo/MeloX-Watch' and
            all(type(binding[key]) is str and re.fullmatch(r'[0-9a-f]{40}', binding[key]) for key in ('source_sha', 'build_sha')) and
            all(type(binding[key]) is int and 0 < binding[key] < 10 ** 20 for key in ('run_id', 'run_attempt')) and
            type(binding['event']) is str and type(binding['ref']) is str, 'Debug evidence binding is invalid')


def validate_task(record, role, binding):
    validate_binding(record, binding)
    fields = {'schema_version', 'kind', 'role', 'task', 'outcome', 'result', 'test_count', 'successful_test_count',
              'failed_test_count', 'skipped_test_count', 'started_at_epoch_ms', 'completed_at_epoch_ms', 'scope'} | set(BINDING_KEYS)
    require(set(record) == fields and type(record['schema_version']) is int and record['schema_version'] == 1 and
            record['kind'] == 'package-debug-task-result' and record['role'] == role and record['task'] == task_id(role) and
            record['outcome'] == 'executed' and record['result'] == 'SUCCESS', 'Debug Test task did not execute successfully')
    for key in ('test_count', 'successful_test_count', 'failed_test_count', 'skipped_test_count'):
        require(type(record[key]) is int and 0 <= record[key] <= 20000, 'Debug TestResult counter is invalid')
    require(record['test_count'] > 0 and record['successful_test_count'] == record['test_count'] and
            record['failed_test_count'] == 0 and record['skipped_test_count'] == 0,
            'Debug Test scope is empty, partial, failed or skipped')
    require(all(type(record[key]) is int and 0 < record[key] < 10 ** 16 for key in ('started_at_epoch_ms', 'completed_at_epoch_ms')) and
            record['completed_at_epoch_ms'] >= record['started_at_epoch_ms'], 'Debug TestResult timing is invalid')
    expected = {'includes': [], 'excludes': [], 'include_patterns': [], 'exclude_patterns': [],
                'command_line_include_patterns': [], 'include_categories': [], 'exclude_categories': [],
                'test_framework': 'junit4', 'scan_for_test_classes': True}
    require(exact_data(record['scope'], expected), 'Debug Test scope contains a filter or incomplete class discovery')
    return record


def junit_count(raw):
    require(type(raw) is bytes and 0 < len(raw) <= MAX_MEMBER_BYTES, 'JUnit original exceeds its bound')
    # NUL rejection prevents the XML parser from sniffing BOM-free UTF-16/32 after UTF-8 decoding.
    text = raw.decode('utf-8', errors='strict')
    require('\x00' not in text and '<!DOCTYPE' not in text and '<!ENTITY' not in text, 'JUnit original is unsafe')
    declaration = re.match(r'\A\ufeff?<\?xml\s[^?]*\?>', text)
    if declaration is not None:
        encoding = re.search(r'''\bencoding\s*=\s*(['"])([^'"]+)\1''', declaration[0])
        require(encoding is None or encoding[2].lower() == 'utf-8', 'JUnit original must declare UTF-8')
    root = ET.fromstring(text)
    require(root.tag == 'testsuite', 'Debug JUnit must be one original Gradle testsuite')
    def number(name):
        value = root.attrib.get(name)
        require(type(value) is str and re.fullmatch(r'0|[1-9][0-9]*', value), 'JUnit counter is invalid')
        result = int(value)
        require(result <= 20000, 'JUnit counter exceeds its bound')
        return result
    tests = number('tests')
    require(tests > 0 and all(number(name) == 0 for name in ('failures', 'errors', 'skipped')),
            'JUnit scope is empty, failed or skipped')
    cases = root.findall('testcase')
    require(len(cases) == tests and not any(element.tag in ('failure', 'error', 'skipped', 'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError')
            for element in root.iter()), 'JUnit original is partial or contains a failed/skipped case')
    for case in cases:
        require(all(type(case.attrib.get(key)) is str and 0 < len(case.attrib[key]) <= 2048 and
                    not any(ord(character) < 32 for character in case.attrib[key]) for key in ('classname', 'name')),
                'JUnit case identity is invalid')
    return tests


def validate_summary(summary, binding):
    require(type(summary) is dict and type(summary.get('binding')) is dict and set(summary['binding']) == set(BINDING_KEYS),
            'Debug evidence summary or binding is missing')
    validate_binding(summary.get('binding', {}), binding)
    require(type(summary) is dict and set(summary) == {'schema_version', 'archive_file', 'archive_sha256', 'binding', 'test_scope', 'tasks'} and
            type(summary['schema_version']) is int and summary['schema_version'] == 1 and
            summary['archive_file'] == ARCHIVE_FILE and type(summary['archive_sha256']) is str and HASH.fullmatch(summary['archive_sha256']) and
            summary['test_scope'] == 'watch-and-phone-debug-once', 'Debug evidence summary is missing or invalid')
    tasks = summary['tasks']
    require(type(tasks) is list and len(tasks) == 2, 'Debug evidence requires both complete application scopes')
    for role, entry in zip(ROLES, tasks):
        require(type(entry) is dict and set(entry) == {'role', 'task', 'test_count', 'successful_test_count',
                'failed_test_count', 'skipped_test_count', 'junit_file_count', 'outcome', 'result'} and
                entry['role'] == role and entry['task'] == task_id(role) and entry['outcome'] == 'executed' and entry['result'] == 'SUCCESS' and
                all(type(entry[key]) is int and 0 <= entry[key] <= 20000 for key in
                    ('test_count', 'successful_test_count', 'failed_test_count', 'skipped_test_count')) and
                entry['test_count'] > 0 and entry['test_count'] == entry['successful_test_count'] and
                entry['failed_test_count'] == 0 and entry['skipped_test_count'] == 0 and
                type(entry['junit_file_count']) is int and 0 < entry['junit_file_count'] <= MAX_JUNIT_FILES,
                'Debug summary is empty, rebound, skipped or partial')
    return summary


def validate_archive_bytes(raw, binding, expected=None):
    require(type(raw) is bytes and 0 < len(raw) <= MAX_ARCHIVE_BYTES, 'Debug archive exceeds its bound')
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        infos = archive.infolist()
        names = [info.filename for info in infos]
        require(3 <= len(infos) <= 2 * MAX_JUNIT_FILES + 3 and len(names) == len(set(names)) and
                {'test-index.json', 'watch/task-result.json', 'phone/task-result.json'} <= set(names) and
                sum(info.file_size for info in infos) <= MAX_RAW_BYTES and
                all(not info.is_dir() and not info.flag_bits & 1 and 0 <= info.file_size <= MAX_MEMBER_BYTES and
                    stat.S_IFMT(info.external_attr >> 16) in (0, stat.S_IFREG) for info in infos),
                'Debug archive has duplicate, linked, encrypted or oversized members')
        index = read_json(archive.read('test-index.json'))
        validate_binding(index, binding)
        require(set(index) == {'schema_version', 'kind', 'task_records'} | set(BINDING_KEYS) and
                type(index['schema_version']) is int and index['schema_version'] == 1 and
                index['kind'] == 'package-debug-test-evidence' and type(index['task_records']) is list and len(index['task_records']) == 2,
                'Debug archive index differs')
        allowed = {'test-index.json'}
        tasks = []
        for role, entry in zip(ROLES, index['task_records']):
            result_name = role + '/task-result.json'
            require(type(entry) is dict and set(entry) == {'role', 'task', 'record_file', 'record_sha256', 'junit_files'} and
                    entry['role'] == role and entry['task'] == task_id(role) and entry['record_file'] == result_name and
                    type(entry['record_sha256']) is str and HASH.fullmatch(entry['record_sha256']),
                    'Debug archive task binding differs')
            allowed.add(result_name)
            result_raw = archive.read(result_name)
            require(digest(result_raw) == entry['record_sha256'], 'Debug TestResult original hash differs')
            result = validate_task(read_json(result_raw), role, binding)
            files = entry['junit_files']
            require(type(files) is list and 0 < len(files) <= MAX_JUNIT_FILES and
                    all(type(item) is dict and set(item) == {'file', 'sha256'} and type(item['file']) is str and
                        item['file'].startswith(role + '/junit/') and XML_NAME.fullmatch(item['file'].removeprefix(role + '/junit/')) and
                        type(item['sha256']) is str and HASH.fullmatch(item['sha256']) for item in files),
                    'Debug JUnit inventory is missing or unsafe')
            require([item['file'] for item in files] == sorted({item['file'] for item in files}), 'Debug JUnit inventory is duplicate')
            count = 0
            for item in files:
                allowed.add(item['file'])
                require(item['file'] in names, 'Debug JUnit original is missing')
                original = archive.read(item['file'])
                require(digest(original) == item['sha256'], 'Debug JUnit original hash differs')
                count += junit_count(original)
            require(count == result['test_count'], 'JUnit originals do not cover the complete successful TestResult')
            tasks.append({key: result[key] for key in ('role', 'task', 'test_count', 'successful_test_count',
                          'failed_test_count', 'skipped_test_count', 'outcome', 'result')} | {'junit_file_count': len(files)})
        require(set(names) == allowed, 'Debug archive contains missing or foreign evidence')
    summary = {'schema_version': 1, 'archive_file': ARCHIVE_FILE, 'archive_sha256': digest(raw),
               'binding': binding, 'test_scope': 'watch-and-phone-debug-once', 'tasks': tasks}
    validate_summary(summary, binding)
    require(expected is None or exact_data(summary, expected), 'Debug archive differs from the frozen build evidence')
    return summary


def validate_archive(path, binding, expected=None):
    return validate_archive_bytes(bounded_read(path, MAX_ARCHIVE_BYTES), binding, expected)


def pack(members, index):
    data = {'test-index.json': encoded(index), **members}
    require(len(data) <= 2 * MAX_JUNIT_FILES + 3 and sum(map(len, data.values())) <= MAX_RAW_BYTES, 'Debug evidence packaging exceeds its bound')
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', allowZip64=False) as archive:
        for name, raw in sorted(data.items()):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, raw)
    raw = buffer.getvalue()
    require(len(raw) <= MAX_ARCHIVE_BYTES, 'Debug evidence archive exceeds its bound')
    return raw


def begin(root):
    report = safe_path(root, REPORT)
    require(not report.exists() or not any(report.iterdir()), 'Current-run Debug evidence destination must be empty')
    for role in ROLES:
        directory = safe_path(root, junit_directory(role))
        require(not directory.exists() or directory.is_dir() and not any(directory.rglob('*.xml')),
                'Prior JUnit originals cannot grant current-run Test evidence')
    report.mkdir(parents=True, exist_ok=True)


def collect(root, binding):
    members = {}
    records = []
    reserved = 0
    for role in ROLES:
        result_raw = bounded_read(safe_path(root, REPORT + '/' + role + '-task-result.json', file=True), 8192)
        validate_task(read_json(result_raw), role, binding)
        result_name = role + '/task-result.json'
        members[result_name] = result_raw
        directory = safe_path(root, junit_directory(role))
        files = sorted(directory.rglob('*.xml'))
        require(0 < len(files) <= MAX_JUNIT_FILES, 'Current-run JUnit originals are missing or exceed their bound')
        originals = []
        for path in files:
            require(XML_NAME.fullmatch(path.name), 'JUnit original filename is invalid')
            require(path.parent == directory, 'JUnit originals must remain in the declared task directory')
            original = safe_path(root, path.relative_to(root).as_posix(), file=True)
            size = original.stat().st_size
            reserved += size
            require(0 < size <= MAX_MEMBER_BYTES and reserved <= MAX_RAW_BYTES, 'JUnit original bytes exceed their bound')
            raw = bounded_read(original, MAX_MEMBER_BYTES)
            name = role + '/junit/' + path.name
            members[name] = raw
            originals.append({'file': name, 'sha256': digest(raw)})
        records.append({'role': role, 'task': task_id(role), 'record_file': result_name,
                        'record_sha256': digest(result_raw), 'junit_files': originals})
    index = {'schema_version': 1, 'kind': 'package-debug-test-evidence', **binding, 'task_records': records}
    raw = pack(members, index)
    summary = validate_archive_bytes(raw, binding)
    target = safe_path(root, REPORT + '/' + ARCHIVE_FILE)
    require(not target.exists(), 'Debug original archive cannot be overwritten')
    with target.open('xb') as stream:
        stream.write(raw)
    require(sha256(target) == summary['archive_sha256'], 'Debug original archive changed during preservation')
    return summary
