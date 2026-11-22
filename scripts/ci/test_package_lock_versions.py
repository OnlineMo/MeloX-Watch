"""Data-only schema 6 policy and staged mock-boundary tests; real Gradle graphs remain untested."""
import copy
import json
import os
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import build
import package_build as packages
import package_contract as contract
import package_lock_graph as graph
import package_lock_versions as versions
import package_migration as migration
import test_package_multischema as multischema
from test_package_multischema import BASE, BINDING, OWNER, PLAN, digest

DEBUG = 'watchAndroidDebugRuntimeClasspath'
COMPILE = 'watchAndroidDebugCompileClasspath'
METADATA = 'implementationDependenciesMetadata'
TOOLING = 'kspWatchAndroidDebugKotlinProcessorClasspath'
TARGETS = {'okio': '3.17.0', 'annotation': '1.10.0', 'startup': '1.2.0'}
RUNTIME = {'bundledSqliteHost': ['x', 'host', '1'], 'robolectricSdk': ['x', 'sdk', '1'], 'robolectricSdk30': ['x', 'sdk30', '1']}


def lock_bytes(configurations):
    rows = {}
    empty = []
    for configuration, modules in configurations.items():
        if not modules:
            empty.append(configuration)
        for module, version in modules.items():
            rows.setdefault(module + ':' + version, []).append(configuration)
    lines = [coordinate + '=' + ','.join(sorted(names)) for coordinate, names in sorted(rows.items())]
    if empty:
        lines.append('empty=' + ','.join(sorted(empty)))
    return ('\n'.join(lines) + '\n').encode()


def xml_bytes(pins):
    namespace = 'https://schema.gradle.org/dependency-verification'
    ET.register_namespace('', namespace)
    root = ET.Element('{' + namespace + '}verification-metadata')
    configuration = ET.SubElement(root, '{' + namespace + '}configuration')
    for name, value in [('verify-metadata', 'true'), ('verify-signatures', 'false')]:
        ET.SubElement(configuration, '{' + namespace + '}' + name).text = value
    components = ET.SubElement(root, '{' + namespace + '}components')
    grouped = {}
    for (group, module, version, name), value in sorted(pins.items()):
        key = (group, module, version)
        if key not in grouped:
            grouped[key] = ET.SubElement(components, '{' + namespace + '}component', group=group, name=module, version=version)
        artifact = ET.SubElement(grouped[key], '{' + namespace + '}artifact', name=name)
        ET.SubElement(artifact, '{' + namespace + '}sha256', value=value)
    return ET.tostring(root, encoding='utf-8') + b'\n'


class PackageVersionUpgradeTests(unittest.TestCase):
    def setUp(self):
        self.fixture = multischema.MultiCurrentSchemaTests()
        self.fixture.setUp()
        self.root = self.fixture.root
        self.fixture.add_cache()
        old = {module: '3.4.0' if versions.MODULE_GROUP[module] == 'okio' else
               '1.9.1' if versions.MODULE_GROUP[module] == 'annotation' else '1.1.1' for module in versions.MODULE_GROUP}
        self.watch = {DEBUG: dict(old), COMPILE: {module: value for module, value in old.items() if versions.MODULE_GROUP[module] != 'startup'},
                      METADATA: {module: old[module] for module in ('com.squareup.okio:okio', 'androidx.annotation:annotation')},
                      TOOLING: {module: old[module] for module in versions.GROUPS['annotation']}, 'emptyConfiguration': {}}
        for configuration, parts in RUNTIME.items():
            self.watch[configuration] = {':'.join(parts[:2]): parts[2]}
        self.phone = {'phoneAndroidDebugRuntimeClasspath': {'x:old': '1'}, 'robolectricSdk': {'x:sdk': '1'}}
        self.pins = {('x', 'old', '1', 'old.jar'): 'a' * 64, ('x', 'plugin', '1', 'plugin-1.jar'): 'b' * 64}
        for parts in RUNTIME.values():
            self.pins[(*parts, parts[1] + '-' + parts[2] + '.jar')] = 'b' * 64
        for module, value in old.items():
            group, name = module.split(':')
            extension = dict(versions.BINARY_MODULES.values()).get(module, 'module')
            self.pins[(group, name, value, name + '-' + value + '.' + extension)] = 'c' * 64
        for relative, raw in [('app/gradle.lockfile', lock_bytes(self.watch)), ('companion/gradle.lockfile', lock_bytes(self.phone)),
                              ('gradle/verification-metadata.xml', xml_bytes(self.pins))]:
            self.fixture.admit_input('android/' + relative, raw)
        self.before = {name.removeprefix('android/'): raw for name, raw in self.fixture.base_inputs.items()}
        self.grants = versions.ordered([
            {'package_role': 'watch', 'module': module, 'configuration': configuration, 'from_version': value,
             'to_version': TARGETS[versions.MODULE_GROUP[module]]}
            for configuration, modules in self.watch.items() if versions.CONFIGURATION.fullmatch(configuration)
            for module, value in modules.items() if module in versions.MODULE_GROUP])
        self.updated = copy.deepcopy(self.watch)
        # The real JVM modules can upgrade while the permitted aggregate aliases retain their base binding.
        for configuration in (COMPILE, DEBUG):
            self.updated[configuration]['com.squareup.okio:okio-jvm'] = '3.17.0'
            self.updated[configuration]['androidx.annotation:annotation-jvm'] = '1.10.0'
            self.updated[configuration]['com.squareup.okhttp3:okhttp-android'] = '5.4.0'
        self.updated[DEBUG]['androidx.startup:startup-runtime'] = '1.2.0'
        self.new_pins = dict(self.pins)
        for module, extension in versions.BINARY_MODULES.values():
            group, name = module.split(':')
            value = TARGETS[versions.MODULE_GROUP[module]]
            self.new_pins[(group, name, value, name + '-' + value + '.' + extension)] = 'd' * 64
        self.new_pins[('com.squareup.okhttp3', 'okhttp-android', '5.4.0', 'okhttp-android-5.4.0.aar')] = 'e' * 64
        self.after = {**self.before, 'app/gradle.lockfile': lock_bytes(self.updated),
                      'gradle/verification-metadata.xml': xml_bytes(self.new_pins)}
        self.changes = versions.growth(self.before, self.after, self.grants)

    def tearDown(self):
        self.fixture.tearDown()

    def write(self, relative, raw):
        return self.fixture.write(relative, raw)

    def frozen(self):
        return {**self.fixture.frozen(), **{name: digest(name.encode()) for name in migration.VERSION_TOOLS}}

    def make_request(self, **kwargs):
        with patch.object(migration, 'project_plan', return_value=copy.deepcopy(PLAN)), \
                patch.object(migration, 'committed_hash', side_effect=self.fixture.base_hash), \
                patch.object(migration, 'data_bytes', side_effect=self.fixture.base_bytes), \
                patch.object(migration, 'source_hashes', return_value=self.frozen()), \
                patch.object(migration, 'runtime_components', return_value=copy.deepcopy(RUNTIME)):
            return migration.make_request(self.root, BASE, schema_version=6, allowed_dependency_upgrades=self.grants, **kwargs)

    def state(self, request):
        state = self.fixture.state(request)
        state['source_input_hashes'] = self.frozen()
        state['mode'] = 'debug'
        state['collection'].update(actual_dependency_upgrades=[], acquired_graph_sha256=None, strict_graph_sha256=None)
        state['collection']['request_sha256'] = digest((self.root / packages.REQUEST_FILE).read_bytes())
        return state

    def consumers(self, request, strict=False):
        records = []
        runtime = []
        for name, kind in sorted(migration.consumers(request).items()):
            item = {'id': name, 'kind': kind, 'files': [{'name': 'tool-input.jar', 'size': 1, 'sha256': 'b' * 64}]}
            if kind in ('task-input-archives', 'test-classpath-archives'):
                item.update(file_count=1, directory_count=0, excluded_file_count=0, missing_non_archive_count=0)
            if kind == 'task-input-archives':
                item['outcome'] = 'up-to-date' if strict else 'executed'
            if kind in ('configuration-artifacts', 'buildscript-artifacts'):
                parts = request['runtime_components'][name.rsplit(':', 1)[1]] if kind == 'configuration-artifacts' else ['x', 'plugin', '1']
                item['files'][0].update(name=parts[1] + '-' + parts[2] + '.jar', component=parts)
            if kind == 'test-classpath-archives':
                project = name.rsplit(':', 1)[0]
                runtime.append({'consumer_id': name, 'producer_tasks': [{'id': project + ':packageResources',
                                                                        'outcome': 'up-to-date' if strict else 'executed'}]})
            records.append(item)
        return records, runtime

    def budget(self, records, archives=()):
        files = {(item['name'], item['size'], item['sha256']) for consumer in records for item in consumer['files']}
        files |= {(item['name'], item['size'], item['sha256']) for item in archives}
        return {'unique_archive_count': len(files), 'unique_archive_bytes': sum(item[1] for item in files)}

    def graph_record(self, state, phase):
        request = state['collection']['request']
        records, runtime = self.consumers(request, strict=phase == 'strict-inputs')
        archives = [{'component': list(parts), 'name': parts[1] + '-' + parts[2] + '.' + extension,
                     'size': 1, 'sha256': 'd' * 64, 'evidence_kind': 'detached-artifact-only'}
                    for parts, extension in graph.binary_upgrades(self.changes).items()]
        archives.sort(key=lambda item: json.dumps(item, sort_keys=True, separators=(',', ':')))
        projects = {':': {}, ':app': copy.deepcopy(self.updated), ':companion': copy.deepcopy(self.phone)}
        return {'schema_version': 1, 'kind': graph.KIND, 'phase': phase, **copy.deepcopy(BINDING),
                'request_sha256': state['collection']['request_sha256'], 'variants': request['variants'],
                'writer_tasks': migration.writer_tasks(request),
                'graph_task': migration.COLLECTOR if phase == 'acquire' else migration.STRICT_GRAPH_TASK,
                'graph_task_count': 200, 'test_task_count': 0, 'configuration_count': sum(len(value) for value in projects.values()),
                'unresolved_dependency_count': 0, 'project_configurations': projects, 'runtime_classpaths': runtime,
                'consumers': records, 'upgrade_archives': archives, 'archive_budget': self.budget(records, archives)}

    def install_after(self):
        for name, raw in self.after.items():
            self.write('android/' + name, raw)

    def acquisition(self, state):
        self.install_after()
        request = state['collection']['request']
        for name in migration.request_collected_schemas(request):
            self.write('android/' + name, ('synthetic new export ' + name).encode())
        resolved = self.graph_record(state, 'acquire')
        raw = (json.dumps(resolved, sort_keys=True) + '\n').encode()
        self.write(migration.REPORT + '/' + graph.FILES['acquire'], raw)
        records, runtime = self.consumers(request)
        record = {'schema_version': 4, 'kind': 'dependency-package-file-acquisition', **copy.deepcopy(BINDING),
                  'request_sha256': state['collection']['request_sha256'], 'variants': request['variants'],
                  'writer_tasks': migration.writer_tasks(request), 'test_task_count': 0, 'graph_task_count': 200,
                  'consumers': records, 'runtime_classpaths': runtime, 'archive_budget': self.budget(records),
                  'acquired_graph_sha256': digest(raw),
                  'lock_capture': {'task': migration.COLLECTOR, 'preceding_file_capture_task': migration.ACQUIRE_TASK,
                                   'status': 'completed', 'unresolved_dependency_count': 0,
                                   'configuration_count': resolved['configuration_count'],
                                   'project_configurations': {project: sorted(configurations) for project, configurations in
                                                              resolved['project_configurations'].items()}}}
        self.write(migration.REPORT + '/acquisition.json', (json.dumps(record) + '\n').encode())

    def strict(self, state, mutate=None):
        for name in migration.request_collected_schemas(state['collection']['request']):
            self.write('android/' + name, ('synthetic new export ' + name).encode())
        record = self.graph_record(state, 'strict-inputs')
        if mutate:
            mutate(record)
        self.write(migration.REPORT + '/' + graph.FILES['strict-inputs'], (json.dumps(record) + '\n').encode())

    def pipeline(self, *, mutate=None, run_strict=True):
        state = self.state(self.make_request())
        self.last_state = state
        calls = []
        def process(command, **kwargs):
            calls.append((command, kwargs))
            if kwargs['phase'] == 'package-acquire':
                self.acquisition(state)
            else:
                self.strict(state, mutate)
        legacy = SimpleNamespace(gradle_environment=build.gradle_environment, gradle_command=build.gradle_command, run_gradle=process)
        with patch.dict(os.environ, {**OWNER, 'GH_TOKEN': 'synthetic-no-secret'}, clear=True), \
                patch.object(migration, 'source_hashes', return_value=state['source_input_hashes']), \
                patch.object(migration, 'committed_hash', return_value=state['collection']['request_sha256']):
            migration.acquire(self.root, state, legacy)
            state['completed'].append('acquire')
            if run_strict:
                migration.strict_inputs(self.root, state, legacy)
                state['completed'].append('strict-inputs')
        return state, calls, legacy

    def test_schema6_is_separate_and_retains_complete_baseline_and_exact_grants(self):
        request = self.make_request()
        self.assertEqual(request['schema_version'], 6)
        self.assertEqual(request['kind'], versions.KIND)
        self.assertEqual(len(request), 17)
        self.assertEqual(request['allowed_dependency_upgrades'], self.grants)
        self.assertEqual(request['migration_tool_hashes'], {name: self.frozen()[name] for name in migration.VERSION_TOOLS})
        for name, raw in self.before.items():
            self.assertEqual(request['reviewed_input_hashes'][name], digest(raw))
        self.assertEqual(migration.report_version(request), 8)
        self.assertEqual(migration.report_version({'schema_version': 3}), 6)
        self.assertEqual(migration.report_version({'schema_version': 4}), 7)

    def test_binding_grants_reject_unknown_modules_configs_versions_duplicates_and_wrong_roles(self):
        changes = [{'package_role': 'phone'}, {'module': 'org.jetbrains.kotlin:kotlin-stdlib'},
                   {'configuration': TOOLING}, {'configuration': 'phoneAndroidDebugRuntimeClasspath'},
                   {'configuration': 'watchAndroidFutureRuntimeClasspath'}, {'configuration': '*'}, {'configuration': ''},
                   {'module': 'androidx.startup:startup-runtime', 'configuration': COMPILE, 'from_version': '1.1.1'},
                   {'from_version': '3.3.0'}, {'to_version': '3.4.0'}, {'to_version': '3.3.0'},
                   {'to_version': '3.+'}, {'to_version': '[3,4)'}, {'to_version': 'latest.release'}, {'to_version': True},
                   {'to_version': '03.17.0'}, {'extra': 'field'}]
        base = next(item for item in self.grants if item['module'] == 'com.squareup.okio:okio-jvm' and item['configuration'] == DEBUG)
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                versions.validate_upgrades([{**base, **change}], self.before['app/gradle.lockfile'])
        with self.assertRaisesRegex(ValueError, 'duplicated'):
            versions.validate_upgrades([base, base], self.before['app/gradle.lockfile'])
        with self.assertRaises(ValueError):
            versions.validate_upgrades([], self.before['app/gradle.lockfile'])

    def test_alias_targets_must_agree_but_unused_allowed_alias_bindings_can_stay_from(self):
        self.assertTrue(self.changes)
        self.assertTrue(all(item['module'] not in ('com.squareup.okio:okio', 'androidx.annotation:annotation') for item in self.changes))
        self.assertEqual(self.updated[METADATA], self.watch[METADATA])
        grants = copy.deepcopy(self.grants)
        grants[0]['to_version'] = '9.0.0'
        with self.assertRaisesRegex(ValueError, 'conflicting target'):
            versions.validate_upgrades(grants, self.before['app/gradle.lockfile'])

    def test_old_growth_still_rejects_versions_and_new_growth_rejects_complete_noop(self):
        with self.assertRaisesRegex(ValueError, 'lock bindings changed'):
            migration.growth(self.before, self.after)
        with self.assertRaisesRegex(ValueError, 'no-op'):
            versions.growth(self.before, self.before, self.grants)

    def test_growth_rejects_unlisted_change_removal_and_companion_or_history_edits(self):
        for mutation in ('unlisted', 'module-removed', 'config-removed', 'phone', 'root', 'history6', 'removed-pin', 'changed-pin'):
            after = dict(self.after)
            watch = copy.deepcopy(self.updated)
            pins = dict(self.new_pins)
            if mutation == 'unlisted':
                watch[TOOLING]['androidx.annotation:annotation-jvm'] = '1.10.0'
            elif mutation == 'module-removed':
                del watch[DEBUG]['com.squareup.okio:okio']
            elif mutation == 'config-removed':
                del watch['emptyConfiguration']
            elif mutation == 'phone':
                after['companion/gradle.lockfile'] += b'# changed\n'
            elif mutation == 'root':
                before = {**self.before, 'gradle.lockfile': b'x:old:1=rootConfig\n'}
                after['gradle.lockfile'] = b'x:old:2=rootConfig\n'
            elif mutation == 'history6':
                after[contract.SCHEMA_DIRECTORY + '/6.json'] = b'rewritten history six'
            elif mutation == 'removed-pin':
                del pins[('x', 'old', '1', 'old.jar')]
            elif mutation == 'changed-pin':
                pins[('x', 'old', '1', 'old.jar')] = 'f' * 64
            after['app/gradle.lockfile'], after['gradle/verification-metadata.xml'] = lock_bytes(watch), xml_bytes(pins)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                versions.growth(before if mutation == 'root' else self.before, after, self.grants)

    def test_version_request_cannot_remove_complete_baseline_or_enter_old_schema(self):
        old = self.fixture.base_inputs['android/companion/gradle.lockfile']
        (self.root / 'android/companion/gradle.lockfile').unlink()
        with self.assertRaisesRegex(ValueError, 'complete admitted'):
            self.make_request()
        self.write('android/companion/gradle.lockfile', old)
        with patch.object(migration, 'project_plan', return_value=copy.deepcopy(PLAN)), \
                patch.object(migration, 'committed_hash', side_effect=self.fixture.base_hash), \
                patch.object(migration, 'data_bytes', side_effect=self.fixture.base_bytes):
            with self.assertRaisesRegex(ValueError, 'Earlier requests'):
                migration.make_request(self.root, BASE, schema_version=4, allowed_dependency_upgrades=self.grants)

    def test_owner_admission_rejects_non_owner_wrong_kind_base_and_changed_source(self):
        request = self.make_request()
        def admit(value, environment):
            raw = (json.dumps(value, sort_keys=True) + '\n').encode()
            self.write(packages.REQUEST_FILE, raw)
            with patch.dict(os.environ, environment, clear=True), patch.object(migration, 'committed_hash', return_value=digest(raw)), \
                    patch.object(migration, 'make_request', return_value=request), patch.object(migration, 'git'), \
                    patch.object(migration, 'version_source_bound'), \
                    patch.object(migration, 'graph_changed', return_value=True):
                return migration.admit(self.root, copy.deepcopy(BINDING), copy.deepcopy(PLAN))
        admission = admit(request, OWNER)
        self.assertEqual(admission['actual_dependency_upgrades'], [])
        for value, environment in [({**request, 'kind': migration.KIND}, OWNER), ({**request, 'schema_version': True}, OWNER),
                                   ({**request, 'base_source_sha': 'invalid-base'}, OWNER),
                                   ({**request, 'base_source_sha': 'd' * 40}, OWNER),
                                   ({**request, 'frozen_input_hashes': {'changed': 'a' * 64}}, OWNER),
                                   (request, {**OWNER, 'GITHUB_TRIGGERING_ACTOR': 'Other'}),
                                   (request, {**OWNER, 'GITHUB_ACTOR_ID': '1'})]:
            with self.subTest(value=value.get('kind'), environment=environment), self.assertRaises(ValueError):
                admit(value, environment)

    def test_version_source_requires_exact_request_blob_and_same_frozen_inputs(self):
        request = self.make_request()
        with patch.object(migration, 'git', side_effect=['a' * 40, 'b' * 40]):
            with self.assertRaisesRegex(ValueError, 'exact upgrade request'):
                migration.version_source_bound(self.root, BINDING, request)
        frozen_name = next(iter(request['frozen_input_hashes']))
        for changed, accepted in [(frozen_name, False), ('docs/13-仓库状态.md', True)]:
            result = SimpleNamespace(returncode=0, stdout=changed.encode() + b'\0')
            with patch.object(migration, 'git', return_value='a' * 40), patch.object(migration.subprocess, 'run', return_value=result):
                if accepted:
                    migration.version_source_bound(self.root, BINDING, request)
                else:
                    with self.assertRaisesRegex(ValueError, 'identical frozen build inputs'):
                        migration.version_source_bound(self.root, BINDING, request)

    def test_graph_accepts_full_exact_locks_real_producer_shape_and_distinct_pin_evidence(self):
        self.install_after()
        state = self.state(self.make_request_after_reset())
        acquired, strict = self.graph_record(state, 'acquire'), self.graph_record(state, 'strict-inputs')
        graph.validate(self.root, acquired, state, 'acquire', self.changes)
        graph.validate(self.root, strict, state, 'strict-inputs', self.changes)
        graph.compare(acquired, strict)
        self.assertNotEqual(acquired['runtime_classpaths'], strict['runtime_classpaths'])
        self.assertTrue(all(item['evidence_kind'] == 'detached-artifact-only' for item in strict['upgrade_archives']))
        self.assertIn('emptyConfiguration', strict['project_configurations'][':app'])

    def make_request_after_reset(self):
        self.write('android/app/gradle.lockfile', self.before['app/gradle.lockfile'])
        self.write('android/gradle/verification-metadata.xml', self.before['gradle/verification-metadata.xml'])
        request = self.make_request()
        self.install_after()
        return request

    def test_graph_rejects_missing_or_inconsistent_config_modules_pins_and_producer_outcomes(self):
        state = self.state(self.make_request())
        self.install_after()
        for mutation in ('missing-empty', 'wrong-version', 'unknown-module', 'unresolved', 'bool-count', 'bad-producer', 'failed-producer', 'empty-archives'):
            record = self.graph_record(state, 'strict-inputs')
            if mutation == 'missing-empty':
                del record['project_configurations'][':app']['emptyConfiguration']
                record['configuration_count'] -= 1
            elif mutation == 'wrong-version':
                record['project_configurations'][':app'][DEBUG]['com.squareup.okio:okio-jvm'] = '3.4.0'
            elif mutation == 'unknown-module':
                record['project_configurations'][':app'][DEBUG]['x:unlocked'] = '1'
            elif mutation == 'unresolved':
                record['unresolved_dependency_count'] = 1
            elif mutation == 'bool-count':
                record['test_task_count'] = False
            elif mutation == 'bad-producer':
                record['runtime_classpaths'][0]['producer_tasks'][0]['id'] = record['runtime_classpaths'][0]['consumer_id']
            elif mutation == 'failed-producer':
                record['runtime_classpaths'][0]['producer_tasks'][0]['outcome'] = 'failed'
            elif mutation == 'empty-archives':
                record['consumers'][0]['files'] = []
                record['consumers'][0]['file_count'] = 0
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                graph.validate(self.root, record, state, 'strict-inputs', self.changes)

    def test_detached_evidence_rejects_non_graph_non_upgrade_wrong_pin_suffix_and_budget(self):
        state = self.state(self.make_request())
        self.install_after()
        for mutation in ('wrong-version', 'wrong-module', 'wrong-pin', 'wrong-suffix', 'fake-consumer-kind', 'bool-size', 'budget'):
            record = self.graph_record(state, 'strict-inputs')
            item = record['upgrade_archives'][0]
            if mutation == 'wrong-version':
                item['component'][2] = '99.0.0'
            elif mutation == 'wrong-module':
                item['component'][1] = 'unknown'
            elif mutation == 'wrong-pin':
                item['sha256'] = 'f' * 64
            elif mutation == 'wrong-suffix':
                item['name'] = 'classes.jar'
            elif mutation == 'fake-consumer-kind':
                item['evidence_kind'] = 'configuration-artifacts'
            elif mutation == 'bool-size':
                item['size'] = True
            elif mutation == 'budget':
                record['archive_budget']['unique_archive_bytes'] = 0
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                graph.validate(self.root, record, state, 'strict-inputs', self.changes)

    def test_two_graphs_reject_selection_or_producer_change_but_allow_phase_outcome_metadata(self):
        state = self.state(self.make_request())
        acquired, strict = self.graph_record(state, 'acquire'), self.graph_record(state, 'strict-inputs')
        graph.compare(acquired, strict)
        strict['runtime_classpaths'][0]['producer_tasks'][0]['id'] += 'Changed'
        with self.assertRaisesRegex(ValueError, 'producer graph changed'):
            graph.compare(acquired, strict)
        strict = self.graph_record(state, 'strict-inputs')
        strict['project_configurations'][':app'][DEBUG]['androidx.startup:startup-runtime'] = '1.1.1'
        with self.assertRaisesRegex(ValueError, 'configuration/module/version'):
            graph.compare(acquired, strict)

    def test_mock_acquire_strict_and_seal_bind_raw_baseline_two_graphs_and_report8_without_apks(self):
        state, calls, _ = self.pipeline()
        self.assertEqual(packages.stage_order(state), ['acquire', 'strict-inputs', 'seal'])
        self.assertEqual(len(calls), 2)
        acquire, strict = [item[0] for item in calls]
        self.assertIn('--write-locks', acquire)
        self.assertIn('--write-verification-metadata', acquire)
        self.assertIn(migration.STRICT_GRAPH_TASK, strict)
        self.assertNotIn(migration.COLLECTOR, strict)
        self.assertNotIn('--write-locks', strict)
        self.assertNotIn('--write-verification-metadata', strict)
        self.assertTrue(all('GH_TOKEN' not in item[1]['env'] for item in calls))
        self.assertTrue(all(item[1]['env']['CI_DEPENDENCY_PACKAGE_REQUEST_SHA256'] == state['collection']['request_sha256'] for item in calls))
        migration.seal(self.root, state)
        report = json.loads((self.root / migration.REPORT / 'package-manifest.json').read_text())
        self.assertEqual(report['schema_version'], 8)
        self.assertEqual(report['collection_kind'], versions.KIND)
        self.assertEqual(report['actual_dependency_upgrades'], self.changes)
        self.assertFalse(report['accepted'])
        self.assertFalse(report['apk_exported'])
        self.assertEqual(report['application_tests'], 'NOT_RUN')
        self.assertEqual(report['apk_assembly'], 'NOT_RUN')
        self.assertFalse((self.root / 'artifacts').exists())
        for name, raw in self.before.items():
            self.assertEqual((self.root / migration.REPORT / 'reviewed-inputs' / name).read_bytes(), raw)
        self.assertEqual(report['writer_schema_hashes'], report['strict_schema_hashes'])
        self.assertIsNotNone(report['acquired_graph_sha256'])
        self.assertIsNotNone(report['strict_graph_sha256'])

    def test_strict_producer_change_rejects_then_failure_report8_preserves_originals(self):
        with self.assertRaisesRegex(ValueError, 'producer graph changed'):
            self.pipeline(mutate=lambda record: record['runtime_classpaths'][0]['producer_tasks'][0].update(id=':app:changedProducer'))
        self.assertFalse((self.root / migration.REPORT / 'package-manifest.json').exists())
        self.assertTrue((self.root / migration.REPORT / graph.FILES['strict-inputs']).exists())
        self.assertFalse(self.last_state['collection'].get('strict_inputs_completed', False))
        migration.record_failure(self.root, self.last_state, 'strict-inputs', ValueError('synthetic rejected graph'))
        report = json.loads((self.root / migration.REPORT / 'failure-manifest.json').read_text())
        self.assertEqual(report['schema_version'], 8)
        self.assertEqual(report['collection_kind'], versions.KIND)
        self.assertEqual(report['failed_stage'], 'strict-inputs')
        self.assertEqual(report['completed_stages'], ['acquire'])
        self.assertIn(graph.FILES['acquire'], report['retained_originals'])
        self.assertIn(graph.FILES['strict-inputs'], report['retained_originals'])
        self.assertFalse(report['accepted'])
        self.assertFalse(report['apk_exported'])

    def test_preserved_base_or_graph_drift_and_missing_strict_graph_block_sealing(self):
        state, _, _ = self.pipeline(run_strict=False)
        with self.assertRaisesRegex(ValueError, 'Strict compiler'):
            migration.seal(self.root, state)
        original = self.root / migration.REPORT / 'reviewed-inputs/app/gradle.lockfile'
        original.write_bytes(b'rewritten original')
        with patch.object(migration, 'committed_hash', return_value=state['collection']['request_sha256']):
            with self.assertRaisesRegex(ValueError, 'original changed'):
                migration.before_strict(self.root, state, 'strict-inputs')
        original.write_bytes(self.before['app/gradle.lockfile'])
        graph_original = self.root / migration.REPORT / graph.FILES['acquire']
        graph_original.write_bytes(b'{}\n')
        with patch.object(migration, 'committed_hash', return_value=state['collection']['request_sha256']):
            with self.assertRaisesRegex(ValueError, 'graph original changed'):
                migration.before_strict(self.root, state, 'strict-inputs')


if __name__ == '__main__':
    unittest.main()
