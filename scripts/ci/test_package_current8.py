"""current8纯数据政策定义；临时synthetic字节不是Room导出或Android执行证据。"""
import copy
import json
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import package_build as packages
import package_contract as contract
import package_migration as migration
import test_package_multischema as legacy

MAIN_EIGHT = contract.SCHEMA_DIRECTORY + '/8.json'
MAIN_SEVEN = contract.SCHEMA_DIRECTORY + '/7.json'
SCHEMA_EIGHT_BYTES = b'synthetic current8 policy fixture; not a Room schema export'
CACHE_BYTES = b'synthetic admitted cache policy fixture; not a Room schema export'


class CurrentEightSchemaTests(unittest.TestCase):
    def setUp(self):
        self.fixture = legacy.MultiCurrentSchemaTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.root = self.fixture.root
        self.fixture.admit_input('android/' + legacy.HISTORY_SCHEMA_SIX, legacy.ADMITTED_SIX)
        self.fixture.admit_input('android/' + MAIN_SEVEN, legacy.ADMITTED_SEVEN)
        self.fixture.write(contract.DATABASE_SOURCE, legacy.declaration(
            'io.github.onlinemo.meloxwatch.data.database.MeloXDatabase', 8, True))

    def add_cache(self):
        self.fixture.write(contract.ONLINE_DATABASE_SOURCE, legacy.declaration(
            'io.github.onlinemo.meloxwatch.data.online.cache.OnlineCatalogDatabase', 1, True, 'internal '))
        self.fixture.admit_input('android/' + legacy.CACHE_SCHEMA, CACHE_BYTES)

    def acquire(self, state, *, change_seven=False, remove_seven=False):
        outputs = {name: SCHEMA_EIGHT_BYTES for name in migration.request_collected_schemas(state['collection']['request'])}

        def writer(command, **kwargs):
            self.assertFalse(any(':test' in name or ':assemble' in name or ':package' in name for name in command))
            for name, raw in outputs.items():
                self.fixture.write('android/' + name, raw)
            if change_seven:
                self.fixture.write('android/' + MAIN_SEVEN, b'synthetic changed admitted seven')
            if remove_seven:
                (self.root / 'android' / MAIN_SEVEN).unlink()
            self.fixture.write(migration.REPORT + '/acquisition.json', b'{}\n')

        calls = []
        legacy_process = SimpleNamespace(
            gradle_environment=lambda **kwargs: {}, gradle_command=lambda tasks: tasks,
            run_gradle=lambda command, **kwargs: (calls.append((command, kwargs)), writer(command, **kwargs))[1])
        with patch.object(migration, 'source_hashes', return_value=state['source_input_hashes']), \
                patch.object(migration, 'committed_hash', return_value=state['collection']['request_sha256']), \
                patch.object(migration, 'validate_acquisition'):
            migration.acquire(self.root, state, legacy_process)
        self.assertEqual(len(calls), 1)
        return outputs

    def normal_start(self):
        binding = {**legacy.BINDING, 'event': 'push', 'ref': 'refs/heads/contribution'}

        def committed(root, relative, revision):
            self.assertEqual((root, revision), (self.root, binding['build_sha']))
            raw = self.fixture.base_inputs.get(relative)
            if raw is None:
                raise ValueError('Synthetic current input was not admitted in the frozen commit')
            return legacy.digest(raw)

        with patch.object(packages, 'context', return_value=binding), \
                patch.object(packages, 'project_plan', return_value=copy.deepcopy(legacy.PLAN)), \
                patch.object(packages, 'room_declarations', return_value=contract.room_declarations(self.root)), \
                patch.object(packages, 'committed_hash', side_effect=committed), \
                patch.object(packages, 'source_hashes', return_value=self.fixture.frozen()), \
                patch.object(packages, 'properties', return_value={}), \
                patch.object(packages.time, 'monotonic', return_value=0), \
                patch.object(packages, 'write_state', return_value='a' * 64):
            return packages.start('debug', self.root)

    def test_current8_requires_and_allows_real_seven_predecessor_with_one_or_two_databases(self):
        history = {contract.SCHEMA_DIRECTORY + '/' + str(version) + '.json' for version in (1, 2, 3, 6, 7)}
        dependencies = {'gradle/verification-metadata.xml', 'app/gradle.lockfile', 'companion/gradle.lockfile'}
        for with_cache in (False, True):
            if with_cache:
                self.add_cache()
            with self.subTest(current_count=2 if with_cache else 1):
                current = {MAIN_EIGHT} | ({legacy.CACHE_SCHEMA} if with_cache else set())
                self.assertEqual(contract.allowed_schema_paths(self.root), history | current)
                self.assertEqual(set(contract.required_inputs(self.root)), history | current | dependencies)
                self.assertEqual(contract.input_hashes(self.root)[MAIN_SEVEN], legacy.digest(legacy.ADMITTED_SEVEN))
                self.assertEqual((self.root / 'android' / MAIN_SEVEN).read_bytes(), legacy.ADMITTED_SEVEN)
                self.assertFalse((self.root / 'android' / MAIN_EIGHT).exists())

    def test_single_current8_request_keeps_v3_scalar_contract_and_collects_only_eight(self):
        request = self.fixture.make_request()
        self.assertEqual(request['schema_version'], 3)
        self.assertEqual((request['current_schema'], request['schema_to_collect']), (MAIN_EIGHT, MAIN_EIGHT))
        self.assertFalse({'current_schemas', 'schemas_to_collect'} & set(request))
        self.assertEqual(request['reviewed_input_hashes'][MAIN_SEVEN], legacy.digest(legacy.ADMITTED_SEVEN))
        self.assertEqual(migration.schema_export_name(request, 'acquire', MAIN_EIGHT), 'schema-exports/acquire/8.json')
        self.assertFalse((self.root / 'android' / MAIN_EIGHT).exists())

    def test_multi_current8_request_binds_actual_declarations_and_keeps_admitted_cache(self):
        self.add_cache()
        request = self.fixture.make_request()
        self.assertEqual(request['schema_version'], 4)
        self.assertEqual(request['current_schemas'], sorted([MAIN_EIGHT, legacy.CACHE_SCHEMA]))
        self.assertEqual(request['schemas_to_collect'], [MAIN_EIGHT])
        self.assertEqual(request['room_declarations'][contract.DATABASE_SOURCE]['version'], 8)
        self.assertEqual(request['room_declarations'][contract.ONLINE_DATABASE_SOURCE]['version'], 1)
        self.assertEqual(request['reviewed_input_hashes'][legacy.CACHE_SCHEMA], legacy.digest(CACHE_BYTES))
        with self.assertRaisesRegex(ValueError, 'only one actual current database'):
            self.fixture.make_request(schema_version=3)

    def test_current8_without_any_admitted_seven_rejects_before_request_or_writer(self):
        name = 'android/' + MAIN_SEVEN
        self.fixture.base_inputs.pop(name)
        (self.root / name).unlink()
        for with_cache in (False, True):
            if with_cache:
                self.add_cache()
            with self.subTest(current_count=2 if with_cache else 1), self.assertRaisesRegex(
                    ValueError, 'retain admitted Watch history1/2/3/6 and each registered predecessor'):
                self.fixture.make_request()
        self.assertFalse((self.root / packages.REQUEST_FILE).exists())
        self.assertFalse((self.root / migration.REPORT).exists())
        self.assertFalse((self.root / 'android' / MAIN_EIGHT).exists())

    def test_removed_admitted_seven_rejects_with_the_frozen_original_retained(self):
        path = self.root / 'android' / MAIN_SEVEN
        before = copy.deepcopy(self.fixture.base_inputs)
        path.unlink()
        for with_cache in (False, True):
            if with_cache:
                self.add_cache()
            with self.subTest(current_count=2 if with_cache else 1), self.assertRaisesRegex(
                    ValueError, 'Admitted Watch history cannot be removed or recollected'):
                self.fixture.make_request()
        self.assertEqual(self.fixture.base_inputs['android/' + MAIN_SEVEN], before['android/' + MAIN_SEVEN])
        self.assertFalse(path.exists())
        self.assertFalse((self.root / packages.REQUEST_FILE).exists())
        self.assertFalse((self.root / migration.REPORT).exists())

    def test_rebound_admitted_seven_rejects_before_writer_and_does_not_replace_base_bytes(self):
        self.fixture.write('android/' + MAIN_SEVEN, b'synthetic rebound seven')
        with self.assertRaisesRegex(ValueError, 'Reviewed package preimage differs from the base'):
            self.fixture.make_request()
        self.assertEqual(self.fixture.base_inputs['android/' + MAIN_SEVEN], legacy.ADMITTED_SEVEN)
        self.assertFalse((self.root / migration.REPORT).exists())

    def test_unreviewed_eight_cannot_be_reused_as_an_admitted_current_export(self):
        self.fixture.write('android/' + MAIN_EIGHT, SCHEMA_EIGHT_BYTES)
        with self.assertRaises(ValueError):
            self.fixture.make_request()
        self.assertNotIn('android/' + MAIN_EIGHT, self.fixture.base_inputs)
        self.assertEqual((self.root / 'android' / MAIN_EIGHT).read_bytes(), SCHEMA_EIGHT_BYTES)
        self.assertFalse((self.root / packages.REQUEST_FILE).exists())
        self.assertFalse((self.root / migration.REPORT).exists())

    def test_removed_admitted_eight_is_not_recollected_under_an_existing_current_version(self):
        self.fixture.admit_input('android/' + MAIN_EIGHT, SCHEMA_EIGHT_BYTES)
        (self.root / 'android' / MAIN_EIGHT).unlink()
        with self.assertRaisesRegex(ValueError, 'Admitted current Watch schema cannot be removed or recollected'):
            self.fixture.make_request()
        self.assertEqual(self.fixture.base_inputs['android/' + MAIN_EIGHT], SCHEMA_EIGHT_BYTES)
        self.assertFalse((self.root / 'android' / MAIN_EIGHT).exists())
        self.assertFalse((self.root / migration.REPORT).exists())

    def test_current8_strict_regenerates_only_new_eight_and_preserves_every_old_input(self):
        self.add_cache()
        before = copy.deepcopy(self.fixture.base_inputs)
        request = self.fixture.make_request()
        state = self.fixture.state(request)
        outputs = self.acquire(state)
        self.assertEqual(outputs, {MAIN_EIGHT: SCHEMA_EIGHT_BYTES})
        admission = state['collection']
        with patch.object(migration, 'committed_hash', return_value=admission['request_sha256']):
            migration.before_strict(self.root, state, 'strict-inputs')
        self.assertFalse((self.root / 'android' / MAIN_EIGHT).exists())
        for name, raw in before.items():
            self.assertEqual((self.root / name).read_bytes(), raw)
        self.fixture.write('android/' + MAIN_EIGHT, SCHEMA_EIGHT_BYTES)
        migration.after_strict(self.root, state, 'strict-inputs')
        with patch.object(migration.time, 'monotonic', return_value=10):
            migration.seal(self.root, state)
        report = json.loads((self.root / migration.REPORT / 'package-manifest.json').read_bytes())
        self.assertEqual(report['writer_schema_hashes'], {MAIN_EIGHT: legacy.digest(SCHEMA_EIGHT_BYTES)})
        self.assertEqual(report['writer_schema_hashes'], report['strict_schema_hashes'])
        self.assertEqual(report['schemas_to_collect'], [MAIN_EIGHT])
        self.assertEqual(report['application_tests'], 'NOT_RUN')
        self.assertEqual(report['apk_assembly'], 'NOT_RUN')
        self.assertFalse(report['accepted'])
        self.assertFalse(report['apk_exported'])
        for name, raw in before.items():
            self.assertEqual((self.root / name).read_bytes(), raw)

    def test_current8_writer_cannot_remove_admitted_seven(self):
        state = self.fixture.state(self.fixture.make_request())
        with self.assertRaisesRegex(ValueError, 'omitted required outputs'):
            self.acquire(state, remove_seven=True)
        self.assertIsNone(state['collection']['acquired_input_hashes'])
        self.assertFalse((self.root / migration.REPORT / 'package-manifest.json').exists())
        self.assertEqual((self.root / migration.REPORT / 'reviewed-inputs' / MAIN_SEVEN).read_bytes(), legacy.ADMITTED_SEVEN)

    def test_current8_writer_seven_drift_keeps_complete_originals_after_source_declaration_failure(self):
        state = self.fixture.state(self.fixture.make_request())
        with self.assertRaisesRegex(ValueError, 'An old Room schema changed') as caught:
            self.acquire(state, change_seven=True)
        self.fixture.write(contract.DATABASE_SOURCE, b'synthetic invalid declaration after rejected writer')
        foreign = contract.SCHEMA_DIRECTORY + '/9.json'
        foreign_bytes = b'synthetic rejected future schema; not a Room export'
        self.fixture.write('android/' + foreign, foreign_bytes)
        with patch.object(migration.time, 'monotonic', return_value=10):
            migration.record_failure(self.root, state, 'acquire', caught.exception)
        report = json.loads((self.root / migration.REPORT / 'failure-manifest.json').read_bytes())
        self.assertEqual(report['unexpected_schema_outputs'], [foreign])
        self.assertEqual(report['partial_input_hashes'][MAIN_SEVEN], legacy.digest(b'synthetic changed admitted seven'))
        self.assertEqual(report['partial_input_hashes'][foreign], legacy.digest(foreign_bytes))
        self.assertFalse(report['accepted'])
        self.assertFalse(report['apk_exported'])
        self.assertEqual((self.root / migration.REPORT / 'reviewed-inputs' / MAIN_SEVEN).read_bytes(), legacy.ADMITTED_SEVEN)
        self.assertEqual((self.root / migration.REPORT / 'failed-partial-inputs' / MAIN_SEVEN).read_bytes(),
                         b'synthetic changed admitted seven')

    def test_current8_strict_export_drift_does_not_qualify_or_replace_old_seven(self):
        self.add_cache()
        state = self.fixture.state(self.fixture.make_request())
        self.acquire(state)
        with patch.object(migration, 'committed_hash', return_value=state['collection']['request_sha256']):
            migration.before_strict(self.root, state, 'strict-inputs')
        rejected = b'synthetic different strict current8 bytes'
        self.fixture.write('android/' + MAIN_EIGHT, rejected)
        with self.assertRaisesRegex(ValueError, 'Strict build changed dependency, lock or schema bytes'):
            migration.after_strict(self.root, state, 'strict-inputs')
        self.assertNotIn('strict_inputs_completed', state['collection'])
        self.assertEqual((self.root / 'android' / MAIN_SEVEN).read_bytes(), legacy.ADMITTED_SEVEN)
        self.assertEqual((self.root / 'android' / MAIN_EIGHT).read_bytes(), rejected)
        self.assertFalse((self.root / migration.REPORT / 'package-manifest.json').exists())

    def test_normal_current8_start_rejects_missing_or_uncommitted_eight_and_seven_drift(self):
        with self.assertRaisesRegex(ValueError, 'Strict package inputs are missing'):
            self.normal_start()
        self.fixture.write('android/' + MAIN_EIGHT, SCHEMA_EIGHT_BYTES)
        with self.assertRaises(ValueError):
            self.normal_start()
        self.fixture.admit_input('android/' + MAIN_EIGHT, SCHEMA_EIGHT_BYTES)
        self.fixture.write('android/' + MAIN_SEVEN, b'synthetic uncommitted history drift')
        with self.assertRaisesRegex(ValueError, 'Normal dependency/schema inputs must match their frozen commit'):
            self.normal_start()
        self.assertFalse((self.root / packages.SESSION_FILE).exists())
        self.assertFalse((self.root / migration.REPORT).exists())

    def test_normal_current8_start_accepts_only_the_complete_frozen_input_inventory(self):
        self.add_cache()
        self.fixture.admit_input('android/' + MAIN_EIGHT, SCHEMA_EIGHT_BYTES)
        state, _ = self.normal_start()
        self.assertIsNone(state['collection'])
        self.assertEqual(state['input_hashes'][MAIN_SEVEN], legacy.digest(legacy.ADMITTED_SEVEN))
        self.assertEqual(state['input_hashes'][MAIN_EIGHT], legacy.digest(SCHEMA_EIGHT_BYTES))
        self.assertEqual(state['input_hashes'][legacy.CACHE_SCHEMA], legacy.digest(CACHE_BYTES))
        self.assertIsNone(state['debug_test_evidence'])
        self.assertFalse((self.root / packages.SESSION_FILE).exists())
        self.assertFalse((self.root / 'artifacts').exists())

    def test_current8_history_registration_rejects_foreign_and_unfrozen_historical_outputs(self):
        names = [contract.SCHEMA_DIRECTORY + '/' + str(version) + '.json' for version in (4, 5)] + [
            'app/schemas/unregistered.Database/8.json']
        for name in names:
            path = self.fixture.write('android/' + name, b'synthetic rejected output; not a Room schema')
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'Unregistered or noncurrent Room schema output'):
                contract.input_hashes(self.root)
            self.assertEqual(path.read_bytes(), b'synthetic rejected output; not a Room schema')
            path.unlink()
        for value in (True, 8.0, 0, -1):
            with self.subTest(version=value), self.assertRaises(ValueError):
                contract.historical_schema_paths(value)


if __name__ == '__main__':
    unittest.main()
