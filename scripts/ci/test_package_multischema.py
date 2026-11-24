"""Data-only multi-current input policy tests; no Git writes, Android or Gradle execution."""
import copy
import hashlib
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import package_build as packages
import package_contract as contract
import package_migration as migration

BASE = 'a' * 40
BINDING = {'repository': 'OnlineMo/MeloX-Watch', 'source_sha': 'b' * 40, 'build_sha': 'c' * 40,
           'run_id': 123, 'run_attempt': 1, 'event': 'pull_request', 'ref': 'refs/pull/999/merge'}
PLAN = {'application_roles': ['watch', 'phone'], 'instrumentation_roles': ['watch'],
        'protocol': {'schema': 2, 'source_hashes': {name: 'd' * 64 for name in contract.PROTOCOL_FILES}}}
OWNER = {'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch', 'GITHUB_ACTOR': 'OnlineMo',
         'GITHUB_ACTOR_ID': '143324986', 'GITHUB_TRIGGERING_ACTOR': 'OnlineMo'}
HISTORY_SCHEMA_SIX = contract.SCHEMA_DIRECTORY + '/6.json'
MAIN_SCHEMA = contract.SCHEMA_DIRECTORY + '/7.json'
CACHE_SCHEMA = 'app/schemas/io.github.onlinemo.meloxwatch.data.online.cache.OnlineCatalogDatabase/1.json'
ADMITTED_SIX = b'synthetic admitted schema six bytes'
ADMITTED_SEVEN = b'synthetic admitted schema seven bytes'
VERIFICATION = (b'<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
                b'<configuration><verify-metadata>true</verify-metadata><verify-signatures>false</verify-signatures>'
                b'</configuration><components><component group="x" name="old" version="1">'
                b'<artifact name="old.jar"><sha256 value="' + b'a' * 64 +
                b'"/></artifact></component></components></verification-metadata>')


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def declaration(qualified, version, exported, modifier=''):
    package, name = qualified.rsplit('.', 1)
    return (f'package {package}\n@Database(entities = [], version = {version}, '
            f'exportSchema = {str(exported).lower()})\n{modifier}abstract class {name} : RoomDatabase()\n').encode()


class MultiCurrentSchemaTests(unittest.TestCase):
    def test_new_current_retains_admitted_schema_six_without_inventing_four_or_five(self):
        admitted = ADMITTED_SIX
        legacy = self.write('android/' + HISTORY_SCHEMA_SIX, admitted)
        self.write(contract.DATABASE_SOURCE, declaration(
            'io.github.onlinemo.meloxwatch.data.database.MeloXDatabase', 7, True))
        current = contract.SCHEMA_DIRECTORY + '/7.json'
        allowed = contract.allowed_schema_paths(self.root)
        self.assertIn(HISTORY_SCHEMA_SIX, allowed)
        self.assertIn(current, allowed)
        self.assertEqual(contract.input_hashes(self.root)[HISTORY_SCHEMA_SIX], digest(admitted))
        self.assertEqual(legacy.read_bytes(), admitted)
        for version in (4, 5):
            self.assertNotIn(contract.SCHEMA_DIRECTORY + '/' + str(version) + '.json', allowed)
        foreign = self.write('android/' + contract.SCHEMA_DIRECTORY + '/5.json', b'unregistered bytes')
        with self.assertRaisesRegex(ValueError, 'Unregistered or noncurrent Room schema output'):
            contract.input_hashes(self.root)
        self.assertEqual(legacy.read_bytes(), admitted)
        self.assertEqual(foreign.read_bytes(), b'unregistered bytes')

    def setUp(self):
        parent = os.environ.get('CI_POLICY_FIXTURE_ROOT')
        self.temporary = tempfile.TemporaryDirectory(prefix='package-multischema-', dir=parent)
        self.root = Path(self.temporary.name).resolve()
        if parent:
            self.assertTrue(self.root.is_relative_to(Path(parent).resolve()))
        self.write(contract.DATABASE_SOURCE, declaration(
            'io.github.onlinemo.meloxwatch.data.database.MeloXDatabase', 6, True))
        for relative, qualified, version, exported in contract.ROOM_DECLARATIONS:
            if not exported:
                self.write(relative, declaration(qualified, version, exported))
        for version in (1, 2, 3):
            self.write('android/' + contract.SCHEMA_DIRECTORY + '/' + str(version) + '.json',
                       ('synthetic historical schema ' + str(version)).encode())
        self.write('android/gradle/verification-metadata.xml', VERIFICATION)
        for relative in ('app/gradle.lockfile', 'companion/gradle.lockfile'):
            self.write('android/' + relative, b'x:old:1=debug\n')
        self.base_inputs = {'android/' + name: (self.root / 'android' / name).read_bytes()
                            for name in contract.input_hashes(self.root)}

    def tearDown(self):
        # The temporary target was resolved within its explicit parent before recursive cleanup.
        self.temporary.cleanup()

    def write(self, relative, raw):
        path = contract.safe_path(self.root, relative)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
        return path

    def admit_input(self, relative, raw):
        self.write(relative, raw)
        self.base_inputs[relative] = raw

    def add_cache(self, *, main_version=7, admitted_six=True):
        if admitted_six:
            self.admit_input('android/' + HISTORY_SCHEMA_SIX, ADMITTED_SIX)
        self.write(contract.DATABASE_SOURCE, declaration(
            'io.github.onlinemo.meloxwatch.data.database.MeloXDatabase', main_version, True))
        self.write(contract.ONLINE_DATABASE_SOURCE, declaration(
            'io.github.onlinemo.meloxwatch.data.online.cache.OnlineCatalogDatabase', 1, True, 'internal '))

    def base_bytes(self, root, name, revision=None, *, optional=False):
        self.assertEqual((root, revision), (self.root, BASE))
        if name not in self.base_inputs and not optional:
            raise ValueError('Synthetic frozen base input is absent')
        return self.base_inputs.get(name)

    def base_hash(self, root, name, base=BASE):
        return digest(self.base_bytes(root, name, base))

    def frozen(self):
        result = {name: digest(name.encode()) for name in migration.TOOLS + migration.GRAPH_FILES}
        for name in contract.room_declarations(self.root):
            result[name] = digest((self.root / name).read_bytes())
        return result

    def make_request(self, **kwargs):
        with patch.object(migration, 'project_plan', return_value=copy.deepcopy(PLAN)), \
                patch.object(migration, 'committed_hash', side_effect=self.base_hash), \
                patch.object(migration, 'data_bytes', side_effect=self.base_bytes), \
                patch.object(migration, 'source_hashes', return_value=self.frozen()), \
                patch.object(migration, 'runtime_components', return_value={'fixture': ['x', 'y', '1']}):
            return migration.make_request(self.root, BASE, **kwargs)

    def state(self, request):
        admission = {'request': request, 'request_sha256': digest(b'synthetic request'),
                     'acquired_input_hashes': None, 'acquisition_sha256': None,
                     'writer_schema_hashes': {}, 'strict_schema_hashes': {}}
        self.write(packages.REQUEST_FILE, (json.dumps(request, sort_keys=True) + '\n').encode())
        return {'collection': admission, 'binding': copy.deepcopy(BINDING), 'plan': copy.deepcopy(PLAN),
                'source_input_hashes': self.frozen(), 'debug_test_evidence': None, 'completed': [],
                'overall_limit_seconds': 1800, 'deadline_monotonic_seconds': 1800,
                'started_monotonic_seconds': 0}

    def acquire(self, state, *, change_history_six=False):
        outputs = {name: ('synthetic current schema ' + name).encode()
                   for name in migration.request_collected_schemas(state['collection']['request'])}

        def writer(command, **kwargs):
            self.assertFalse(any(':test' in name or ':assemble' in name or ':package' in name for name in command))
            for name, raw in outputs.items():
                self.write('android/' + name, raw)
            if change_history_six:
                self.write('android/' + HISTORY_SCHEMA_SIX, b'synthetic changed historical six')
            self.write(migration.REPORT + '/acquisition.json', b'{}\n')

        legacy = SimpleNamespace(gradle_environment=lambda **kwargs: {}, gradle_command=lambda tasks: tasks,
                                 run_gradle=writer)
        with patch.object(migration, 'source_hashes', return_value=state['source_input_hashes']), \
                patch.object(migration, 'committed_hash', return_value=state['collection']['request_sha256']), \
                patch.object(migration, 'validate_acquisition'):
            migration.acquire(self.root, state, legacy)
        return outputs

    def test_single_current_preserves_v3_names_and_required_tuple(self):
        request = self.make_request()
        self.assertEqual(request['schema_version'], 3)
        self.assertEqual((request['current_schema'], request['schema_to_collect']),
                         (HISTORY_SCHEMA_SIX, HISTORY_SCHEMA_SIX))
        self.assertFalse({'current_schemas', 'schemas_to_collect'} & set(request))
        self.assertEqual(contract.current_schemas(self.root), [HISTORY_SCHEMA_SIX])
        self.assertNotIn('android/' + HISTORY_SCHEMA_SIX, self.base_inputs)
        self.assertEqual(contract.required_inputs(self.root),
                         ('gradle/verification-metadata.xml', 'app/gradle.lockfile', 'companion/gradle.lockfile',
                          contract.SCHEMA_DIRECTORY + '/1.json', HISTORY_SCHEMA_SIX))
        self.assertEqual(migration.schema_export_name(request, 'acquire', HISTORY_SCHEMA_SIX),
                         'schema-exports/acquire/6.json')

    def test_v4_first_generation_six_requires_a_base_without_six(self):
        self.add_cache(main_version=6, admitted_six=False)
        before = copy.deepcopy(self.base_inputs)
        request = self.make_request()
        self.assertEqual(request['schema_version'], 4)
        self.assertEqual(request['current_schemas'], sorted([HISTORY_SCHEMA_SIX, CACHE_SCHEMA]))
        self.assertEqual(request['schemas_to_collect'], request['current_schemas'])
        self.assertNotIn(HISTORY_SCHEMA_SIX, request['reviewed_input_hashes'])
        self.assertNotIn('android/' + HISTORY_SCHEMA_SIX, before)
        outputs = self.acquire(self.state(request))
        self.assertEqual(set(outputs), {HISTORY_SCHEMA_SIX, CACHE_SCHEMA})
        for name, raw in before.items():
            self.assertEqual((self.root / name).read_bytes(), raw)
        self.assertEqual(self.base_inputs, before)

    def test_admitted_six_cannot_be_removed_and_recollected_by_v3_or_v4(self):
        self.admit_input('android/' + HISTORY_SCHEMA_SIX, ADMITTED_SIX)
        path = self.root / 'android' / HISTORY_SCHEMA_SIX
        for version in (3, 4):
            if version == 4:
                self.add_cache(main_version=6)
            before = copy.deepcopy(self.base_inputs)
            path.unlink()
            try:
                with self.subTest(request_version=version), self.assertRaisesRegex(
                        ValueError, 'Admitted Watch history cannot be removed or recollected'):
                    self.make_request(schema_version=version)
                self.assertFalse(path.exists())
                self.assertFalse((self.root / packages.REQUEST_FILE).exists())
                self.assertEqual(self.base_inputs, before)
                for name, raw in before.items():
                    if name != 'android/' + HISTORY_SCHEMA_SIX:
                        self.assertEqual((self.root / name).read_bytes(), raw)
            finally:
                path.write_bytes(ADMITTED_SIX)

    def test_seven_requires_full_history_with_one_or_two_current_databases(self):
        history = {contract.SCHEMA_DIRECTORY + '/' + str(value) + '.json' for value in (1, 2, 3, 6)}
        for with_cache in (False, True):
            if with_cache:
                self.add_cache(admitted_six=False)
            else:
                self.write(contract.DATABASE_SOURCE, declaration(
                    'io.github.onlinemo.meloxwatch.data.database.MeloXDatabase', 7, True))
            expected = history | {MAIN_SCHEMA, 'gradle/verification-metadata.xml',
                                  'app/gradle.lockfile', 'companion/gradle.lockfile'}
            if with_cache:
                expected.add(CACHE_SCHEMA)
            with self.subTest(current_count=2 if with_cache else 1):
                self.assertEqual(set(contract.required_inputs(self.root)), expected)
                self.assertNotIn('android/' + HISTORY_SCHEMA_SIX, self.base_inputs)
                with self.assertRaisesRegex(ValueError, 'retain admitted Watch history1/2/3/6'):
                    self.make_request()

    def test_admitted_six_rebinding_and_writer_changes_reject_with_original_bytes_retained(self):
        self.add_cache()
        path = self.root / 'android' / HISTORY_SCHEMA_SIX
        path.write_bytes(b'synthetic rebound historical six')
        with self.assertRaisesRegex(ValueError, 'Reviewed package preimage differs from the base'):
            self.make_request()
        self.assertEqual(self.base_inputs['android/' + HISTORY_SCHEMA_SIX], ADMITTED_SIX)
        path.write_bytes(ADMITTED_SIX)
        state = self.state(self.make_request())
        with self.assertRaisesRegex(ValueError, 'An old Room schema changed') as caught:
            self.acquire(state, change_history_six=True)
        with patch.object(migration.time, 'monotonic', return_value=10):
            migration.record_failure(self.root, state, 'acquire', caught.exception)
        report = json.loads((self.root / migration.REPORT / 'failure-manifest.json').read_bytes())
        changed = b'synthetic changed historical six'
        self.assertEqual(report['partial_input_hashes'][HISTORY_SCHEMA_SIX], digest(changed))
        self.assertFalse(report['accepted'])
        self.assertFalse(report['apk_exported'])
        self.assertEqual((self.root / migration.REPORT / 'reviewed-inputs' / HISTORY_SCHEMA_SIX).read_bytes(),
                         ADMITTED_SIX)
        self.assertEqual((self.root / migration.REPORT / 'failed-partial-inputs' / HISTORY_SCHEMA_SIX).read_bytes(),
                         changed)
        self.assertEqual(path.read_bytes(), changed)

    def test_internal_cache_requires_both_current_outputs_and_all_history(self):
        self.add_cache()
        request = self.make_request()
        self.assertEqual(request['schema_version'], 4)
        self.assertEqual(request['current_schemas'], sorted([MAIN_SCHEMA, CACHE_SCHEMA]))
        self.assertEqual(request['schemas_to_collect'], request['current_schemas'])
        self.assertFalse({'current_schema', 'schema_to_collect'} & set(request))
        self.assertEqual(request['room_declarations'][contract.ONLINE_DATABASE_SOURCE]['version'], 1)
        self.assertTrue(request['room_declarations'][contract.ONLINE_DATABASE_SOURCE]['export_schema'])
        history = {contract.SCHEMA_DIRECTORY + '/' + str(value) + '.json' for value in (1, 2, 3, 6)}
        self.assertEqual(set(contract.required_inputs(self.root)), history | {MAIN_SCHEMA, CACHE_SCHEMA,
                         'gradle/verification-metadata.xml', 'app/gradle.lockfile', 'companion/gradle.lockfile'})
        with self.assertRaises(ValueError):
            self.make_request(schema_version=3)
        (self.root / 'android' / contract.SCHEMA_DIRECTORY / '2.json').unlink()
        with self.assertRaises(ValueError):
            self.make_request()

    def test_cache_and_nonexporting_declarations_keep_exact_literals_and_namespace(self):
        self.add_cache()
        original = (self.root / contract.ONLINE_DATABASE_SOURCE).read_bytes()
        for before, after in ((b'data.online.cache\n', b'data.other\n'),
                              (b'class OnlineCatalogDatabase', b'class OtherDatabase'),
                              (b'version = 1', b'version = 2'),
                              (b'exportSchema = true', b'exportSchema = false'),
                              (b'internal abstract', b'private abstract')):
            with self.subTest(change=after):
                self.write(contract.ONLINE_DATABASE_SOURCE, original.replace(before, after))
                with self.assertRaises(ValueError):
                    contract.room_declarations(self.root)
        self.write(contract.ONLINE_DATABASE_SOURCE, original)
        for relative, _, _, exported in contract.ROOM_DECLARATIONS:
            if not exported:
                old = (self.root / relative).read_bytes()
                self.write(relative, old.replace(b'exportSchema = false', b'exportSchema = true'))
                with self.subTest(source=relative), self.assertRaises(ValueError):
                    contract.room_declarations(self.root)
                self.write(relative, old)

    def test_unknown_namespace_and_noncurrent_versions_never_enter_inputs(self):
        self.add_cache()
        names = [contract.SCHEMA_DIRECTORY + '/' + str(value) + '.json' for value in (4, 5)] + [
            CACHE_SCHEMA.replace('/1.json', '/2.json'), 'app/schemas/unregistered.Database/1.json']
        for name in names:
            path = self.write('android/' + name, b'synthetic rejected bytes')
            with self.subTest(path=name), self.assertRaises(ValueError):
                contract.input_hashes(self.root)
            self.assertIn(name, contract.schema_output_paths(self.root, allow_unexpected=True))
            path.unlink()

    def test_source_inventory_excludes_only_registered_inputs_and_still_hashes_sources(self):
        self.add_cache()
        self.write('android/' + MAIN_SCHEMA, ADMITTED_SEVEN)
        self.write('android/' + CACHE_SCHEMA, b'synthetic admitted cache')
        names = sorted(contract.room_declarations(self.root)) + [
            'android/' + name for name in contract.schema_output_paths(self.root)]

        def inventory(paths):
            rows = []
            for name in paths:
                raw = (self.root / name).read_bytes()
                blob = hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()
                rows.append('100644 blob ' + blob + '\t' + name + '\0')
            return SimpleNamespace(returncode=0, stdout=''.join(rows).encode())

        with patch.object(packages.subprocess, 'run', return_value=inventory(names)):
            observed = packages.source_hashes(self.root)
        self.assertEqual(observed, {name: digest((self.root / name).read_bytes())
                                    for name in contract.room_declarations(self.root)})
        unknown = 'android/' + contract.SCHEMA_DIRECTORY + '/4.json'
        self.write(unknown, b'synthetic rejected historical export')
        with patch.object(packages.subprocess, 'run', return_value=inventory(names + [unknown])), self.assertRaises(ValueError):
            packages.source_hashes(self.root)

    def test_v4_requires_each_actual_declaration_to_be_a_frozen_source(self):
        self.add_cache()
        frozen = self.frozen()
        frozen.pop(contract.ONLINE_DATABASE_SOURCE)
        with patch.object(migration, 'project_plan', return_value=copy.deepcopy(PLAN)), \
                patch.object(migration, 'committed_hash', side_effect=self.base_hash), \
                patch.object(migration, 'data_bytes', side_effect=self.base_bytes), \
                patch.object(migration, 'source_hashes', return_value=frozen), \
                self.assertRaisesRegex(ValueError, 'exact committed source inputs'):
            migration.make_request(self.root, BASE)

    def test_admission_rejects_rebound_fields_and_hashes_before_any_writer(self):
        self.add_cache()
        original = self.make_request()

        def admit(request):
            path = self.write(packages.REQUEST_FILE, (json.dumps(request, sort_keys=True) + '\n').encode())
            with patch.dict(os.environ, {**OWNER, 'CANDIDATE_OPERATION': ''}), \
                    patch.object(migration, 'committed_hash', return_value=digest(path.read_bytes())), \
                    patch.object(migration, 'git'), patch.object(migration, 'make_request', return_value=original):
                return migration.admit(self.root, BINDING, PLAN)

        admission = admit(original)
        self.assertEqual((admission['writer_schema_hashes'], admission['strict_schema_hashes']), ({}, {}))
        changes = [('schema_version', True), ('schema_version', 4.0), ('owner_id', True),
                   ('owner_login', 'another'), ('current_schema', MAIN_SCHEMA),
                   ('current_schemas', [MAIN_SCHEMA]), ('schemas_to_collect', [CACHE_SCHEMA, CACHE_SCHEMA])]
        for name, value in changes:
            request = {**copy.deepcopy(original), name: value}
            with self.subTest(field=name), self.assertRaises(ValueError):
                admit(request)
        for name in ('frozen_input_hashes', 'migration_tool_hashes', 'graph_input_hashes', 'reviewed_input_hashes'):
            request = copy.deepcopy(original)
            first = next(iter(request[name]))
            request[name][first] = 'f' * 64
            with self.subTest(hash_map=name), self.assertRaises(ValueError):
                admit(request)
        request = copy.deepcopy(original)
        request['room_declarations'][contract.ONLINE_DATABASE_SOURCE]['source_sha256'] = 'f' * 64
        with self.assertRaises(ValueError):
            admit(request)

    def complete_collection(self, existing_main):
        self.add_cache()
        if existing_main:
            self.admit_input('android/' + MAIN_SCHEMA, ADMITTED_SEVEN)
        request = self.make_request()
        state = self.state(request)
        outputs = self.acquire(state)
        admission = state['collection']
        self.assertEqual(admission['writer_schema_hashes'], {name: digest(raw) for name, raw in outputs.items()})
        with patch.object(migration, 'committed_hash', return_value=admission['request_sha256']):
            migration.before_strict(self.root, state, 'strict-inputs')
        self.assertTrue(all(not (self.root / 'android' / name).exists() for name in outputs))
        if existing_main:
            self.assertEqual((self.root / 'android' / MAIN_SCHEMA).read_bytes(), ADMITTED_SEVEN)
        self.assertEqual((self.root / 'android' / HISTORY_SCHEMA_SIX).read_bytes(), ADMITTED_SIX)
        for name, raw in outputs.items():
            self.write('android/' + name, raw)
        migration.after_strict(self.root, state, 'strict-inputs')
        with patch.object(migration.time, 'monotonic', return_value=10):
            migration.seal(self.root, state)
        report = json.loads((self.root / migration.REPORT / 'package-manifest.json').read_bytes())
        self.assertEqual(report['schema_version'], 7)
        self.assertEqual(report['writer_schema_hashes'], report['strict_schema_hashes'])
        self.assertEqual(report['current_schemas'], sorted([MAIN_SCHEMA, CACHE_SCHEMA]))
        self.assertEqual(report['schemas_to_collect'], sorted(outputs))
        self.assertEqual(report['application_tests'], 'NOT_RUN')
        self.assertEqual(report['apk_assembly'], 'NOT_RUN')
        self.assertFalse(report['accepted'])
        self.assertFalse(report['apk_exported'])
        self.assertEqual((self.root / 'android' / HISTORY_SCHEMA_SIX).read_bytes(), ADMITTED_SIX)
        for name, raw in outputs.items():
            for phase in ('acquire', 'strict-inputs'):
                self.assertEqual((self.root / migration.REPORT /
                                  migration.schema_export_name(request, phase, name)).read_bytes(), raw)

    def test_two_new_current_schemas_have_independent_namespace_exports_and_hashes(self):
        self.complete_collection(existing_main=False)

    def test_only_new_cache_is_regenerated_when_current_seven_is_already_reviewed(self):
        self.complete_collection(existing_main=True)

    def test_missing_or_different_second_export_rejects_and_preserves_rejected_bytes(self):
        self.add_cache()
        state = self.state(self.make_request())
        outputs = self.acquire(state)
        with patch.object(migration, 'committed_hash', return_value=state['collection']['request_sha256']):
            migration.before_strict(self.root, state, 'strict-inputs')
        self.write('android/' + MAIN_SCHEMA, outputs[MAIN_SCHEMA])
        with self.assertRaises(ValueError):
            migration.after_strict(self.root, state, 'strict-inputs')
        self.write('android/' + CACHE_SCHEMA, b'synthetic different second export')
        with self.assertRaises(ValueError):
            migration.after_strict(self.root, state, 'strict-inputs')
        self.assertNotIn('strict_inputs_completed', state['collection'])
        with self.assertRaises(ValueError):
            migration.seal(self.root, state)
        unknown = CACHE_SCHEMA.replace('/1.json', '/2.json')
        rejected = b'synthetic unexpected schema bytes'
        self.write('android/' + unknown, rejected)
        self.write(contract.ONLINE_DATABASE_SOURCE, b'invalid declaration after rejected writer')
        with patch.object(migration.time, 'monotonic', return_value=10):
            migration.record_failure(self.root, state, 'strict-inputs', ValueError('synthetic refusal'))
        report = json.loads((self.root / migration.REPORT / 'failure-manifest.json').read_bytes())
        self.assertEqual(report['schema_version'], 7)
        self.assertEqual(report['unexpected_schema_outputs'], [unknown])
        self.assertEqual(report['partial_input_hashes'][unknown], digest(rejected))
        self.assertFalse(report['accepted'])
        self.assertFalse(report['apk_exported'])
        self.assertFalse((self.root / migration.REPORT / 'package-manifest.json').exists())
        self.assertEqual((self.root / migration.REPORT / 'failed-partial-inputs' / unknown).read_bytes(), rejected)
        for name, raw in outputs.items():
            self.assertEqual((self.root / migration.REPORT /
                              migration.schema_export_name(state['collection']['request'], 'acquire', name)).read_bytes(), raw)


if __name__ == '__main__':
    unittest.main()
