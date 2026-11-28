"""Schema9 admission policy with synthetic inputs; no Android or Gradle execution."""
import copy
import json
import unittest
from unittest.mock import patch

import package_build as packages
import package_contract as contract
import package_migration as migration
import test_package_current8 as previous
import test_package_multischema as legacy

MAIN_NINE = contract.SCHEMA_DIRECTORY + '/9.json'


class CurrentNineSchemaTests(unittest.TestCase):
    def setUp(self):
        self.previous = previous.CurrentEightSchemaTests()
        self.previous.setUp()
        self.addCleanup(self.previous.doCleanups)
        self.fixture = self.previous.fixture
        self.root = self.fixture.root
        self.fixture.admit_input('android/' + previous.MAIN_EIGHT, previous.SCHEMA_EIGHT_BYTES)
        self.fixture.write(contract.DATABASE_SOURCE, legacy.declaration(
            'io.github.onlinemo.meloxwatch.data.database.MeloXDatabase', 9, True))

    def test_current9_requires_frozen_eight_and_collects_only_nine_with_one_or_two_databases(self):
        history = {contract.SCHEMA_DIRECTORY + '/' + str(version) + '.json'
                   for version in (1, 2, 3, 6, 7, 8)}
        dependencies = {'gradle/verification-metadata.xml', 'app/gradle.lockfile', 'companion/gradle.lockfile'}
        for with_cache in (False, True):
            if with_cache:
                self.previous.add_cache()
            with self.subTest(current_count=2 if with_cache else 1):
                current = {MAIN_NINE} | ({legacy.CACHE_SCHEMA} if with_cache else set())
                self.assertEqual(contract.allowed_schema_paths(self.root), history | current)
                self.assertEqual(set(contract.required_inputs(self.root)), history | current | dependencies)
                request = self.fixture.make_request()
                self.assertEqual(request['schema_version'], 4 if with_cache else 3)
                self.assertEqual(migration.request_collected_schemas(request), [MAIN_NINE])
                self.assertEqual(request['reviewed_input_hashes'][previous.MAIN_EIGHT],
                                 legacy.digest(previous.SCHEMA_EIGHT_BYTES))
                self.assertEqual(request['room_declarations'][contract.DATABASE_SOURCE]['version'], 9)
                self.assertEqual((self.root / 'android' / previous.MAIN_EIGHT).read_bytes(),
                                 previous.SCHEMA_EIGHT_BYTES)
                self.assertFalse((self.root / 'android' / MAIN_NINE).exists())

    def test_current9_without_a_frozen_eight_rejects_before_request_or_writer(self):
        name = 'android/' + previous.MAIN_EIGHT
        self.fixture.base_inputs.pop(name)
        (self.root / name).unlink()
        for with_cache in (False, True):
            if with_cache:
                self.previous.add_cache()
            with self.subTest(current_count=2 if with_cache else 1), self.assertRaisesRegex(
                    ValueError, 'retain admitted Watch history1/2/3/6 and each registered predecessor'):
                self.fixture.make_request()
        self.assertFalse((self.root / packages.REQUEST_FILE).exists())
        self.assertFalse((self.root / migration.REPORT).exists())
        self.assertFalse((self.root / 'android' / MAIN_NINE).exists())

    def test_removed_or_rebound_eight_cannot_be_recollected_as_schema9_history(self):
        path = self.root / 'android' / previous.MAIN_EIGHT
        path.unlink()
        with self.assertRaisesRegex(ValueError, 'Admitted Watch history cannot be removed or recollected'):
            self.fixture.make_request()
        self.fixture.write('android/' + previous.MAIN_EIGHT, b'synthetic rebound admitted eight')
        with self.assertRaisesRegex(ValueError, 'Reviewed package preimage differs from the base'):
            self.fixture.make_request()
        self.assertEqual(self.fixture.base_inputs['android/' + previous.MAIN_EIGHT], previous.SCHEMA_EIGHT_BYTES)
        self.assertFalse((self.root / migration.REPORT).exists())
        self.assertFalse((self.root / 'android' / MAIN_NINE).exists())

    def test_schema9_strict_regenerates_only_new_nine_and_keeps_all_predecessors(self):
        self.previous.add_cache()
        before = copy.deepcopy(self.fixture.base_inputs)
        request = self.fixture.make_request()
        state = self.fixture.state(request)
        outputs = self.fixture.acquire(state)
        self.assertEqual(set(outputs), {MAIN_NINE})
        admission = state['collection']
        with patch.object(migration, 'committed_hash', return_value=admission['request_sha256']):
            migration.before_strict(self.root, state, 'strict-inputs')
        self.assertFalse((self.root / 'android' / MAIN_NINE).exists())
        for name, raw in before.items():
            self.assertEqual((self.root / name).read_bytes(), raw)
        self.fixture.write('android/' + MAIN_NINE, outputs[MAIN_NINE])
        migration.after_strict(self.root, state, 'strict-inputs')
        with patch.object(migration.time, 'monotonic', return_value=10):
            migration.seal(self.root, state)
        report = json.loads((self.root / migration.REPORT / 'package-manifest.json').read_bytes())
        self.assertEqual(report['schema_version'], 7)
        self.assertEqual(report['schemas_to_collect'], [MAIN_NINE])
        self.assertEqual(report['writer_schema_hashes'], report['strict_schema_hashes'])
        self.assertEqual(report['application_tests'], 'NOT_RUN')
        self.assertEqual(report['apk_assembly'], 'NOT_RUN')
        self.assertFalse(report['accepted'])
        self.assertFalse(report['apk_exported'])
        for name, raw in before.items():
            self.assertEqual((self.root / name).read_bytes(), raw)

    def test_schema9_strict_rejects_changed_eight_without_sealing_a_report(self):
        self.previous.add_cache()
        state = self.fixture.state(self.fixture.make_request())
        outputs = self.fixture.acquire(state)
        with patch.object(migration, 'committed_hash', return_value=state['collection']['request_sha256']):
            migration.before_strict(self.root, state, 'strict-inputs')
        self.fixture.write('android/' + MAIN_NINE, outputs[MAIN_NINE])
        rejected = b'synthetic strict drift in admitted eight'
        self.fixture.write('android/' + previous.MAIN_EIGHT, rejected)
        with self.assertRaisesRegex(ValueError, 'Strict build changed dependency, lock or schema bytes'):
            migration.after_strict(self.root, state, 'strict-inputs')
        self.assertNotIn('strict_inputs_completed', state['collection'])
        self.assertFalse((self.root / migration.REPORT / 'package-manifest.json').exists())
        self.assertEqual((self.root / 'android' / previous.MAIN_EIGHT).read_bytes(), rejected)
        self.assertEqual(self.fixture.base_inputs['android/' + previous.MAIN_EIGHT], previous.SCHEMA_EIGHT_BYTES)

    def test_normal_schema9_start_requires_committed_nine_and_unchanged_eight(self):
        self.previous.add_cache()
        with self.assertRaisesRegex(ValueError, 'Strict package inputs are missing'):
            self.previous.normal_start()
        current_bytes = b'synthetic admitted schema9; not a Room export'
        self.fixture.write('android/' + MAIN_NINE, current_bytes)
        with self.assertRaises(ValueError):
            self.previous.normal_start()
        self.fixture.admit_input('android/' + MAIN_NINE, current_bytes)
        state, _ = self.previous.normal_start()
        self.assertIsNone(state['collection'])
        self.assertEqual(state['input_hashes'][previous.MAIN_EIGHT], legacy.digest(previous.SCHEMA_EIGHT_BYTES))
        self.assertEqual(state['input_hashes'][MAIN_NINE], legacy.digest(current_bytes))
        self.fixture.write('android/' + previous.MAIN_EIGHT, b'synthetic uncommitted eight')
        with self.assertRaisesRegex(ValueError, 'Normal dependency/schema inputs must match their frozen commit'):
            self.previous.normal_start()
        self.assertFalse((self.root / packages.SESSION_FILE).exists())
        self.assertFalse((self.root / 'artifacts').exists())

    def test_schema9_keeps_unregistered_old_and_future_outputs_rejected(self):
        for version in (4, 5, 10):
            name = contract.SCHEMA_DIRECTORY + '/' + str(version) + '.json'
            path = self.fixture.write('android/' + name, b'synthetic unregistered output')
            with self.subTest(version=version), self.assertRaisesRegex(
                    ValueError, 'Unregistered or noncurrent Room schema output'):
                contract.input_hashes(self.root)
            self.assertEqual(path.read_bytes(), b'synthetic unregistered output')
            path.unlink()


if __name__ == '__main__':
    unittest.main()
