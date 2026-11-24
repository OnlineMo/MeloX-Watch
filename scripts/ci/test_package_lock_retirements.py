"""Synthetic policy fixtures only: no Android, JVM, Gradle or SQL execution/admission."""
import copy
import os
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import build
import package_build as packages
import package_lock_graph as graph
import package_lock_retirements as retirements
import package_lock_versions as versions
import package_migration as migration
import test_package_lock_versions as fixture_module
from test_package_multischema import BASE, BINDING, OWNER, PLAN, digest


class PackageRetirementPolicyTests(unittest.TestCase):
    def setUp(self):
        self.policy = fixture_module.PackageVersionUpgradeTests()
        self.policy.setUp()
        self.root = self.policy.root
        original_updated = copy.deepcopy(self.policy.updated)
        original_new_pins = dict(self.policy.new_pins)
        self.permissions = retirements.ordered([
            {'package_role': 'watch', 'module': module, 'configuration': configuration, 'from_version': version}
            for module, configuration, version in retirements.APPROVED_BINDINGS])
        for item in self.permissions:
            self.policy.watch.setdefault(item['configuration'], {})[item['module']] = item['from_version']
        for configuration in retirements.APPLICATION_CONFIGURATIONS:
            self.policy.watch[configuration]['org.jetbrains.kotlin:kotlin-stdlib'] = '2.2.21'
        self.policy.watch[fixture_module.TOOLING]['org.jetbrains.kotlin:kotlin-stdlib-common'] = '2.2.21'
        for module, version, extension in [('kotlin-stdlib-common', '2.2.21', 'module'), ('kotlin-stdlib-jdk7', '1.8.0', 'jar'),
                                           ('kotlin-stdlib-jdk8', '1.8.0', 'jar'), ('kotlin-stdlib', '2.2.21', 'jar')]:
            self.policy.pins[('org.jetbrains.kotlin', module, version, module + '-' + version + '.' + extension)] = 'c' * 64
        self.policy.fixture.admit_input('android/app/gradle.lockfile', fixture_module.lock_bytes(self.policy.watch))
        self.policy.fixture.admit_input('android/gradle/verification-metadata.xml', fixture_module.xml_bytes(self.policy.pins))
        self.policy.before = {name.removeprefix('android/'): raw for name, raw in self.policy.fixture.base_inputs.items()}
        self.policy.updated = copy.deepcopy(self.policy.watch)
        for configuration, modules in original_updated.items():
            self.policy.updated[configuration].update(modules)
        for item in self.permissions:
            del self.policy.updated[item['configuration']][item['module']]
        self.policy.new_pins = {**self.policy.pins, **original_new_pins}
        self.policy.after = {**self.policy.before, 'app/gradle.lockfile': fixture_module.lock_bytes(self.policy.updated),
                             'gradle/verification-metadata.xml': fixture_module.xml_bytes(self.policy.new_pins)}
        self.changes, self.actual = retirements.growth(self.policy.before, self.policy.after, self.policy.grants, self.permissions)
        self.policy.changes = self.changes

    def tearDown(self):
        self.policy.tearDown()

    def frozen(self):
        return {**self.policy.frozen(), 'scripts/ci/package_lock_retirements.py': digest(b'synthetic policy source')}

    def make_request(self, *, version=7, permissions=None):
        with patch.object(migration, 'project_plan', return_value=copy.deepcopy(PLAN)), \
                patch.object(migration, 'committed_hash', side_effect=self.policy.fixture.base_hash), \
                patch.object(migration, 'data_bytes', side_effect=self.policy.fixture.base_bytes), \
                patch.object(migration, 'source_hashes', return_value=self.frozen()), \
                patch.object(migration, 'runtime_components', return_value=copy.deepcopy(fixture_module.RUNTIME)):
            return migration.make_request(self.root, BASE, schema_version=version, allowed_dependency_upgrades=self.policy.grants,
                allowed_dependency_retirements=self.permissions if permissions is None else permissions)

    def state(self, request):
        state = self.policy.state(request)
        state['source_input_hashes'] = self.frozen()
        state['collection']['actual_dependency_retirements'] = []
        return state

    def pipeline(self, mutate=None, run_strict=True):
        state = self.state(self.make_request())
        calls = []
        def process(command, **kwargs):
            calls.append((command, kwargs))
            if kwargs['phase'] == 'package-acquire':
                self.policy.acquisition(state)
            else:
                self.policy.strict(state, mutate)
        legacy = SimpleNamespace(gradle_environment=build.gradle_environment, gradle_command=build.gradle_command, run_gradle=process)
        with patch.dict(os.environ, OWNER, clear=True), patch.object(migration, 'source_hashes', return_value=state['source_input_hashes']), \
                patch.object(migration, 'committed_hash', return_value=state['collection']['request_sha256']):
            migration.acquire(self.root, state, legacy)
            state['completed'].append('acquire')
            if run_strict:
                migration.strict_inputs(self.root, state, legacy)
                state['completed'].append('strict-inputs')
        return state, calls

    def test_exact_permissions_and_actual_subset_retain_core_and_other_bindings(self):
        self.assertEqual(len(self.permissions), 33)
        self.assertEqual(self.actual, self.permissions)
        self.assertTrue(self.changes)
        self.assertEqual(retirements.validate_retirements(self.permissions, self.policy.before['app/gradle.lockfile']), self.permissions)
        self.assertEqual(self.policy.updated[fixture_module.TOOLING]['org.jetbrains.kotlin:kotlin-stdlib-common'], '2.2.21')
        for configuration in retirements.APPLICATION_CONFIGURATIONS:
            self.assertEqual(self.policy.updated[configuration]['org.jetbrains.kotlin:kotlin-stdlib'], '2.2.21')

    def test_request7_is_explicit_and_old6_still_rejects_retirement(self):
        request = self.make_request()
        self.assertEqual(request['schema_version'], 7)
        self.assertEqual(request['kind'], retirements.KIND)
        self.assertEqual(len(request), 18)
        self.assertEqual(request['allowed_dependency_retirements'], self.permissions)
        self.assertEqual(set(request['migration_tool_hashes']), set(migration.RETIREMENT_TOOLS))
        self.assertEqual(len(migration.RETIREMENT_TOOLS), len(migration.VERSION_TOOLS) + 1)
        for version, expected in [(3, 6), (4, 7), (6, 8), (7, 9)]:
            self.assertEqual(migration.report_version({'schema_version': version}), expected)
        with self.assertRaises(ValueError):
            self.make_request(version=6)
        with self.assertRaisesRegex(ValueError, 'module cannot disappear'):
            versions.growth(self.policy.before, self.policy.after, self.policy.grants)

    def test_permissions_reject_roles_fields_versions_modules_duplicates_and_tooling(self):
        for change in [{'package_role': 'phone'}, {'module': 'org.jetbrains.kotlin:kotlin-stdlib'},
                       {'configuration': fixture_module.TOOLING}, {'configuration': 'watchAndroidDebugAndroidTestRuntimeClasspath'},
                       {'from_version': '1.8.1'}, {'from_version': True}, {'from_version': 1.8}, {'to_version': '2.2.21'}]:
            items = copy.deepcopy(self.permissions)
            items[0].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                retirements.validate_retirements(items, self.policy.before['app/gradle.lockfile'])
        for items in [[], self.permissions[:-1], self.permissions + self.permissions[:1], self.permissions[:1] * 33, True, {}]:
            with self.subTest(shape=type(items).__name__), self.assertRaises(ValueError):
                retirements.permission_shape(items)

    def test_from_must_match_actual_admitted_baseline_and_order_must_be_canonical(self):
        baseline = copy.deepcopy(self.policy.watch)
        item = self.permissions[0]
        baseline[item['configuration']][item['module']] = '0.0.1'
        with self.assertRaisesRegex(ValueError, 'admitted base'):
            retirements.validate_retirements(self.permissions, fixture_module.lock_bytes(baseline))
        with self.assertRaisesRegex(ValueError, 'canonical'):
            retirements.growth(self.policy.before, self.policy.after, self.policy.grants, list(reversed(self.permissions)))

    def test_only_actual_missing_subset_is_reported_and_noop_retirement_rejects(self):
        updated = copy.deepcopy(self.policy.updated)
        item = self.permissions[0]
        updated[item['configuration']][item['module']] = item['from_version']
        after = {**self.policy.after, 'app/gradle.lockfile': fixture_module.lock_bytes(updated)}
        _, actual = retirements.growth(self.policy.before, after, self.policy.grants, self.permissions)
        self.assertEqual(len(actual), 32)
        self.assertNotIn(item, actual)
        for item in self.permissions:
            updated[item['configuration']][item['module']] = item['from_version']
        with self.assertRaisesRegex(ValueError, 'nonempty'):
            retirements.growth(self.policy.before, {**self.policy.after, 'app/gradle.lockfile': fixture_module.lock_bytes(updated)},
                               self.policy.grants, self.permissions)

    def test_growth_rejects_unpermitted_removal_core_version_and_config_loss(self):
        for mutation in ('core-removed', 'core-version', 'tooling-common', 'other-module', 'configuration'):
            updated = copy.deepcopy(self.policy.updated)
            if mutation == 'core-removed':
                del updated[fixture_module.DEBUG]['org.jetbrains.kotlin:kotlin-stdlib']
            elif mutation == 'core-version':
                updated[fixture_module.DEBUG]['org.jetbrains.kotlin:kotlin-stdlib'] = '9.0.0'
            elif mutation == 'tooling-common':
                del updated[fixture_module.TOOLING]['org.jetbrains.kotlin:kotlin-stdlib-common']
            elif mutation == 'other-module':
                del updated[fixture_module.DEBUG]['com.squareup.okio:okio']
            else:
                del updated['emptyConfiguration']
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                retirements.growth(self.policy.before, {**self.policy.after, 'app/gradle.lockfile': fixture_module.lock_bytes(updated)},
                                   self.policy.grants, self.permissions)

    def test_phone_root_history_and_old_XML_pins_cannot_change(self):
        for path in ['companion/gradle.lockfile', 'gradle/verification-metadata.xml',
                     'app/schemas/io.github.onlinemo.meloxwatch.data.database.MeloXDatabase/6.json']:
            after = {**self.policy.after, path: b'synthetic invalid changed bytes'}
            with self.subTest(path=path), self.assertRaises(ValueError):
                retirements.growth(self.policy.before, after, self.policy.grants, self.permissions)
        before = {**self.policy.before, 'gradle.lockfile': b'x:old:1=debug\n'}
        with self.assertRaises(ValueError):
            retirements.growth(before, {**self.policy.after, 'gradle.lockfile': b'x:old:2=debug\n'}, self.policy.grants, self.permissions)

    def test_actual_inventory_cannot_be_forged_extended_duplicated_or_empty(self):
        item = copy.deepcopy(self.permissions[0])
        for actual in [[], [item, item], [dict(item, package_role='phone')], [dict(item, module='org.jetbrains.kotlin:kotlin-stdlib')],
                       [dict(item, from_version=True)], list(reversed(self.permissions))]:
            with self.subTest(actual_count=len(actual)), self.assertRaises(ValueError):
                retirements.validate_actual_retirements(self.permissions, actual)

    def test_mock_two_stage_report9_preserves_tasks_deadlines_bytes_and_no_Test_APK(self):
        state, calls = self.pipeline()
        self.assertEqual(len(calls), 2)
        self.assertEqual(state['collection']['actual_dependency_retirements'], self.actual)
        self.assertEqual(state['collection']['actual_dependency_upgrades'], self.changes)
        self.assertEqual(packages.stage_order(state), ['acquire', 'strict-inputs', 'seal'])
        self.assertIn('--write-locks', calls[0][0])
        self.assertNotIn('--write-locks', calls[1][0])
        self.assertNotIn(migration.COLLECTOR, calls[1][0])
        self.assertIn(migration.STRICT_GRAPH_TASK, calls[1][0])
        self.assertTrue(all(call[1]['overall_limit_seconds'] == 1800 for call in calls))
        migration.seal(self.root, state)
        import json
        manifest = json.loads((self.root / migration.REPORT / 'package-manifest.json').read_text())
        self.assertEqual(manifest['schema_version'], 9)
        self.assertEqual(manifest['allowed_dependency_retirements'], self.permissions)
        self.assertEqual(manifest['actual_dependency_retirements'], self.actual)
        self.assertFalse(manifest['accepted'])
        self.assertFalse(manifest['apk_exported'])
        self.assertEqual(manifest['application_tests'], 'NOT_RUN')
        self.assertEqual(manifest['apk_assembly'], 'NOT_RUN')
        self.assertEqual(manifest['writer_schema_hashes'], manifest['strict_schema_hashes'])

    def test_strict_graph_or_retirement_set_drift_blocks_report_and_seal(self):
        with self.assertRaises(ValueError):
            self.pipeline(mutate=lambda record: record['project_configurations'][':app'][fixture_module.DEBUG].update(
                {'org.jetbrains.kotlin:kotlin-stdlib-jdk8': '1.8.0'}))
        self.assertFalse((self.root / migration.REPORT / 'package-manifest.json').exists())

    def test_actual_retirement_must_be_absent_from_exact_graph_and_old6_has_no_permission_argument(self):
        state = self.state(self.make_request())
        self.policy.install_after()
        record = self.policy.graph_record(state, 'acquire')
        graph.validate(self.root, record, state, 'acquire', self.changes, retirements=self.actual)
        item = self.permissions[0]
        record['project_configurations'][':app'][item['configuration']][item['module']] = item['from_version']
        with self.assertRaises(ValueError):
            graph.validate(self.root, record, state, 'acquire', self.changes, retirements=self.actual)
        state['collection']['request']['schema_version'] = 6
        with self.assertRaisesRegex(ValueError, 'cannot authorize'):
            graph.validate(self.root, record, state, 'acquire', self.changes, retirements=self.actual)
