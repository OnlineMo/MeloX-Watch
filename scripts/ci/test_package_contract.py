"""Pure Python contract tests; application/Gradle/Android tools are never executed."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from build_applicability import application_changed, policy_changed
from ci_common import OWNER_EMAIL, expected_identity
from package_build import (CANDIDATE_SECONDS, COLLECTION_SECONDS, NORMAL_SECONDS,
                           source_hashes, stage_order, tasks_debug_tests, tasks_outputs)
from package_contract import (PROTOCOL_FILES, artifact_files, artifact_layout, current_schema, native_abis,
                              package_identity, project_plan, validate_artifacts)
from package_migration import consumers, growth, writer_tasks
from package_test_evidence import (ARCHIVE_FILE, digest, encoded, pack, task_id,
                                   validate_archive_bytes)

VERSION = {'versionName': '1.2.3', 'versionCode': '71'}
PLAN = {'application_roles': ['watch', 'phone'], 'instrumentation_roles': ['watch'],
        'protocol': {'schema': 2, 'source_hashes': {name: 'a' * 64 for name in PROTOCOL_FILES}}}
BINDING = {'repository': 'OnlineMo/MeloX-Watch', 'source_sha': 'a' * 40,
           'build_sha': 'a' * 40, 'run_id': 123, 'run_attempt': 1,
           'event': 'push', 'ref': 'refs/heads/contribution'}


def debug_test_fixture(binding=None):
    """Synthetic successful task/JUnit bytes, never an application execution result."""
    binding = copy.deepcopy(BINDING if binding is None else binding)
    members = {}
    task_records = []
    for role in PLAN['application_roles']:
        result = {'schema_version': 1, 'kind': 'package-debug-task-result', **binding,
                  'role': role, 'task': task_id(role), 'outcome': 'executed', 'result': 'SUCCESS',
                  'test_count': 1, 'successful_test_count': 1, 'failed_test_count': 0, 'skipped_test_count': 0,
                  'started_at_epoch_ms': 1000, 'completed_at_epoch_ms': 1001,
                  'scope': {'includes': [], 'excludes': [], 'include_patterns': [], 'exclude_patterns': [],
                            'command_line_include_patterns': [], 'include_categories': [], 'exclude_categories': [],
                            'test_framework': 'junit4', 'scan_for_test_classes': True}}
        result_name = role + '/task-result.json'
        xml_name = role + '/junit/TEST-Fixture.xml'
        result_raw = encoded(result)
        xml_raw = ('<testsuite tests="1" failures="0" errors="0" skipped="0">'
                   '<testcase classname="fixture.' + role + '" name="synthetic_contract_only"/>'
                   '</testsuite>').encode()
        members[result_name] = result_raw
        members[xml_name] = xml_raw
        task_records.append({'role': role, 'task': task_id(role), 'record_file': result_name,
                             'record_sha256': digest(result_raw),
                             'junit_files': [{'file': xml_name, 'sha256': digest(xml_raw)}]})
    index = {'schema_version': 1, 'kind': 'package-debug-test-evidence', **binding,
             'task_records': task_records}
    raw = pack(members, index)
    return raw, validate_archive_bytes(raw, binding)


def manifest(mode='release', signing='stable', binding=None):
    binding = copy.deepcopy(BINDING if binding is None else binding)
    entries = []
    for layout in artifact_layout(mode, PLAN):
        identity = expected_identity(mode, VERSION, layout['role'])
        if layout['kind'] == 'instrumentation':
            identity = {**identity, 'package': identity['package'] + '.test', 'version_code': '0',
                        'version_name': '', 'debuggable': True, 'profileable': False,
                        'target_package': identity['package']}
        entries.append({**layout, **identity, 'abis': [], 'sha256': 'b' * 64, 'signing': signing,
                        **({'unsigned_sha256': 'c' * 64, 'certificate_sha256': 'd' * 64} if signing == 'stable' else {})})
    _, evidence = debug_test_fixture(binding)
    return {'schema_version': 2, **binding, **copy.deepcopy(PLAN), 'mode': mode,
            'version': VERSION, 'artifacts': entries, 'test_scope': 'full-debug-once',
            'debug_test_evidence': evidence}


def xml(extra=False, digest='a'):
    second = '<component group="x" name="new" version="1"><artifact name="new.jar"><sha256 value="' + 'b' * 64 + '"/></artifact></component>' if extra else ''
    return ('<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
            '<configuration><verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures></configuration>'
            '<components><component group="x" name="old" version="1"><artifact name="old.jar"><sha256 value="' +
            digest * 64 + '"/></artifact></component>' + second + '</components></verification-metadata>').encode()


class PackageContractTests(unittest.TestCase):
    def test_debug_runs_each_whole_application_suite_once(self):
        self.assertEqual(tasks_debug_tests(PLAN), [':app:testWatchAndroidDebugUnitTest', ':companion:testPhoneAndroidDebugUnitTest'])
        tasks = tasks_outputs({'mode': 'debug', 'plan': PLAN, 'collection': None})
        self.assertEqual(tasks, [':app:lintWatchAndroidDebug', ':app:assembleWatchAndroidDebug',
                                 ':companion:lintPhoneAndroidDebug', ':companion:assemblePhoneAndroidDebug'])
        self.assertFalse(any('UnitTest' in task for task in tasks))

    def test_candidate_keeps_lint_and_optimized_outputs_without_full_suite_repetition(self):
        tasks = tasks_outputs({'mode': 'candidate', 'plan': PLAN, 'collection': None})
        self.assertEqual(len(tasks), 9)
        self.assertIn(':app:assembleWatchAndroidDiagnosticAndroidTest', tasks)
        for role, flavor in [('app', 'WatchAndroid'), ('companion', 'PhoneAndroid')]:
            for mode in ('Release', 'Diagnostic'):
                self.assertIn(':' + role + ':lint' + flavor + mode, tasks)
                self.assertIn(':' + role + ':assemble' + flavor + mode, tasks)
        self.assertFalse(any('UnitTest' in task for task in tasks))

    def test_visible_stages_share_lower_hard_cost_admission(self):
        self.assertEqual((NORMAL_SECONDS, COLLECTION_SECONDS, CANDIDATE_SECONDS), (1200, 1800, 1800))
        self.assertEqual(stage_order({'collection': None, 'mode': 'debug'}), ['test-debug', 'lint-build', 'seal'])
        self.assertEqual(stage_order({'collection': {}, 'mode': 'debug'}), ['acquire', 'strict-inputs', 'seal'])
        for mode in ('release', 'diagnostic'):
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                stage_order({'collection': None, 'mode': mode})

    def test_acquisition_selects_real_debug_consumers_for_both_projects_without_test_execution(self):
        request = {'variants': ['debug'], 'plan': PLAN}
        tasks = writer_tasks(request)
        self.assertEqual(len(tasks), 12)
        self.assertFalse(any(':test' in task for task in tasks))
        selected = consumers(request)
        self.assertIn(':app:testWatchAndroidDebugUnitTest', selected)
        self.assertIn(':companion:testPhoneAndroidDebugUnitTest', selected)
        self.assertIn(':companion:buildscript:classpath', selected)
        self.assertFalse(any('Release' in name or 'Diagnostic' in name for name in selected))

    def test_both_role_versions_flags_and_signed_hashes_are_checked(self):
        for mode in ('debug', 'release', 'diagnostic'):
            record = manifest(mode)
            validate_artifacts(record, mode, VERSION, PLAN, signing='stable')
            self.assertEqual(record['artifacts'][1]['package'], expected_identity(mode, VERSION, 'phone')['package'])
        self.assertFalse(expected_identity('diagnostic', VERSION, 'phone')['profileable'])
        self.assertTrue(expected_identity('diagnostic', VERSION, 'watch')['profileable'])

    def test_missing_duplicate_or_rebound_phone_apk_cannot_form_a_candidate(self):
        original = manifest()
        changed = []
        missing = copy.deepcopy(original)
        missing['artifacts'].pop()
        changed.append(missing)
        duplicate = copy.deepcopy(original)
        duplicate['artifacts'][1] = copy.deepcopy(duplicate['artifacts'][0])
        changed.append(duplicate)
        for key, value in [('package', expected_identity('release', VERSION)['package']), ('version_code', '70'),
                           ('version_name', '1.2.2'), ('sha256', 'bad'), ('certificate_sha256', 'bad'),
                           ('debuggable', True), ('file', 'another.apk')]:
            candidate = copy.deepcopy(original)
            candidate['artifacts'][1][key] = value
            changed.append(candidate)
        for record in changed:
            with self.subTest(record=changed.index(record)), self.assertRaises(ValueError):
                validate_artifacts(record, 'release', VERSION, PLAN, signing='stable')

    def test_protocol_integer_types_and_each_source_hash_are_bound(self):
        for value in (True, 2.0, 3):
            record = manifest()
            record['protocol']['schema'] = value
            with self.assertRaises(ValueError):
                validate_artifacts(record, 'release', VERSION, PLAN, signing='stable')
        record = manifest()
        record['protocol']['source_hashes'][PROTOCOL_FILES[1]] = 'f' * 64
        with self.assertRaises(ValueError):
            validate_artifacts(record, 'release', VERSION, PLAN, signing='stable')

    def test_instrumentation_inventory_is_per_role_and_targets_the_exact_package(self):
        record = manifest('diagnostic')
        validate_artifacts(record, 'diagnostic', VERSION, PLAN, signing='stable')
        self.assertEqual(artifact_files('diagnostic', PLAN),
                         {'watch-diagnostic.apk', 'phone-diagnostic.apk', 'watch-diagnostic-test.apk',
                          'build-manifest.json', 'SHA256SUMS', ARCHIVE_FILE})
        record['artifacts'][-1]['target_package'] = expected_identity('diagnostic', VERSION, 'phone')['package']
        with self.assertRaises(ValueError):
            validate_artifacts(record, 'diagnostic', VERSION, PLAN, signing='stable')

    def test_owner_confirmation_hash_changes_with_either_apk_certificate_abi_or_protocol(self):
        entries = manifest()['artifacts']
        original = package_identity(entries, PLAN['protocol'])
        for index, key, value in [(0, 'sha256', 'e' * 64), (1, 'sha256', 'e' * 64),
                                  (1, 'certificate_sha256', 'e' * 64), (1, 'abis', ['x86_64'])]:
            changed = copy.deepcopy(entries)
            changed[index][key] = value
            self.assertNotEqual(original['package_set_sha256'], package_identity(changed, PLAN['protocol'])['package_set_sha256'])
        protocol = copy.deepcopy(PLAN['protocol'])
        protocol['schema'] = 3
        self.assertNotEqual(original['package_set_sha256'], package_identity(entries, protocol)['package_set_sha256'])

    def test_success_string_cannot_replace_both_current_debug_task_scopes(self):
        original = manifest()
        changed = []
        missing = copy.deepcopy(original)
        missing.pop('debug_test_evidence')
        changed.append(missing)
        rebound = copy.deepcopy(original)
        rebound['build_sha'] = 'f' * 40
        changed.append(rebound)
        phone_missing = copy.deepcopy(original)
        phone_missing['debug_test_evidence']['tasks'].pop()
        changed.append(phone_missing)
        for record in changed:
            with self.subTest(record=changed.index(record)), self.assertRaises(ValueError):
                validate_artifacts(record, 'release', VERSION, PLAN, signing='stable')

    def test_actual_abi_inventory_is_data_only_and_handles_a_managed_phone_apk(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'data.zip'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('AndroidManifest.xml', b'fixture data')
            self.assertEqual(native_abis(path), [])
            with zipfile.ZipFile(path, 'a') as archive:
                archive.writestr('lib/x86_64/libfixture.so', b'fixture data')
                archive.writestr('lib/arm64-v8a/libfixture.so', b'fixture data')
            self.assertEqual(native_abis(path), ['arm64-v8a', 'x86_64'])

    def test_schema_and_dependency_growth_preserves_every_old_binding(self):
        before = {'gradle/verification-metadata.xml': xml(), 'app/gradle.lockfile': b'x:old:1=debug\nempty=release\n',
                  'companion/gradle.lockfile': b'x:old:1=debug\n', 'schema/1.json': b'old schema'}
        after = {**before, 'gradle/verification-metadata.xml': xml(True),
                 'app/gradle.lockfile': b'x:old:1=debug\nx:new:1=debug\nempty=release\n',
                 'companion/gradle.lockfile': b'x:old:1=debug\nx:new:1=debug\n'}
        growth(before, after)
        for name, data in [('companion/gradle.lockfile', b'x:old:2=debug\n'),
                           ('app/gradle.lockfile', b'x:old:1=debug\n'),
                           ('gradle/verification-metadata.xml', xml(digest='c')), ('schema/1.json', b'changed')]:
            changed = {**after, name: data}
            with self.subTest(name=name), self.assertRaises(ValueError):
                growth(before, changed)

    def test_document_reports_do_not_trigger_android_or_policy_suite_repetition(self):
        self.assertFalse(application_changed({'docs/13-status.md', 'docs/releases/1.2.3/report.json', '.ci/release-readiness.json'}))
        self.assertFalse(policy_changed({'docs/13-status.md', '.ci/release-readiness.json'}))
        for paths in (None, {'android/companion/src/main/Code.kt'}, {'version.properties'}, {'scripts/ci/package_build.py'}):
            self.assertTrue(application_changed(paths))
        self.assertTrue(policy_changed({'scripts/ci/test_package_contract.py'}))

    def test_complete_shared_source_is_required_and_legacy_copies_are_refused(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for module in ('app', 'companion'):
                script = root / 'android' / module / 'build.gradle.kts'
                script.parent.mkdir(parents=True)
                script.write_text('getByName("main").java.srcDir("../shared-transfer/src/main/kotlin")\n', encoding='utf-8')
                metadata = script.parent / 'src/main/AndroidManifest.xml'
                metadata.parent.mkdir(parents=True)
                metadata.write_text('<manifest/>', encoding='utf-8')
            for index, name in enumerate(PROTOCOL_FILES):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('const val SCHEMA = 2' if index == 0 else 'fixture source', encoding='utf-8')
            self.assertEqual(project_plan(root)['application_roles'], ['watch', 'phone'])
            database = root / 'android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/data/database/MeloXDatabase.kt'
            database.parent.mkdir(parents=True)
            database.write_text('@Database(entities = [], version = 6)', encoding='utf-8')
            self.assertTrue(current_schema(root).endswith('/6.json'))
            old = root / 'android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/data/transfer/TransferContracts.kt'
            old.parent.mkdir(parents=True, exist_ok=True)
            old.write_text('legacy fixture', encoding='utf-8')
            with self.assertRaises(ValueError):
                project_plan(root)

    def test_source_inventory_uses_exact_git_bytes_and_detects_drift(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            def run(*args):
                return subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', *args], cwd=root, check=True, capture_output=True)
            run('init')
            run('config', 'user.name', 'OnlineMo')
            run('config', 'user.email', OWNER_EMAIL)
            path = root / 'android/companion/src/main/Code.kt'
            path.parent.mkdir(parents=True)
            path.write_text('fixture source\n', encoding='utf-8', newline='\n')
            run('add', '.')
            run('commit', '-m', 'fixture')
            self.assertEqual(source_hashes(root), {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()})
            path.write_text('different source\n', encoding='utf-8')
            with self.assertRaises(ValueError):
                source_hashes(root)


if __name__ == '__main__':
    unittest.main()
