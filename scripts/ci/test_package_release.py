"""Paired signing/evidence/recovery contracts with data and mock Android/API boundaries."""
import copy
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch
import zipfile

import sign
from artifact_io import verify_checksums
from ci_common import expected_identity, write_checksums
from package_contract import package_identity
from package_test_evidence import ARCHIVE_FILE
from release_evidence import GATES, validate_approval
from test_package_contract import PLAN, VERSION, debug_test_fixture, manifest

ENV = {'GITHUB_ACTIONS': 'true', 'CI': 'true', 'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch',
       'GITHUB_ACTOR': 'OnlineMo', 'GITHUB_TRIGGERING_ACTOR': 'OnlineMo', 'GITHUB_WORKFLOW': 'Cloud build',
       'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/contribution', 'GITHUB_SHA': 'a' * 40,
       'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1', 'GH_TOKEN': 'fixture-only'}


def apk_bytes():
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        archive.writestr('AndroidManifest.xml', b'fixture metadata, never executed')
    return buffer.getvalue()


class PairedSigningTests(unittest.TestCase):
    def test_prepare_checks_both_packages_before_any_signer_receives_a_key(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            record = manifest('debug', 'unsigned')
            record.update(repository=ENV['GITHUB_REPOSITORY'], source_sha=ENV['GITHUB_SHA'], build_sha=ENV['GITHUB_SHA'],
                          run_id=123, run_attempt=1, event='push', ref=ENV['GITHUB_REF'])
            data = apk_bytes()
            debug_raw, debug_summary = debug_test_fixture()
            self.assertEqual(record['debug_test_evidence'], debug_summary)
            for entry in record['artifacts']:
                entry['sha256'] = hashlib.sha256(data).hexdigest()
            def fetch(*args, **kwargs):
                self.assertEqual(kwargs['allowed'], {'watch-debug.apk', 'phone-debug.apk',
                                                     'build-manifest.json', 'SHA256SUMS', ARCHIVE_FILE})
                self.assertEqual(kwargs['job'], 'android-compile')
                destination = kwargs['destination']
                destination.mkdir()
                for entry in record['artifacts']:
                    (destination / entry['file']).write_bytes(data)
                (destination / ARCHIVE_FILE).write_bytes(debug_raw)
                (destination / 'build-manifest.json').write_text(json.dumps(record), encoding='utf-8')
                write_checksums(destination)
                return {'id': 456, 'run_id': 123, 'run_attempt': 1}
            with patch.dict(os.environ, ENV, clear=True), patch.object(sign, 'ROOT', root), \
                    patch.object(sign, 'fetch_artifact', side_effect=fetch), patch.object(sign, 'GitHub', return_value=object()), \
                    patch.object(sign, 'properties', return_value={}), patch.object(sign, 'build_tools', return_value=root / 'tools'), \
                    patch.object(sign, 'inspect_apk') as inspect, patch.object(sign, 'require_unsigned') as unsigned, \
                    patch.object(sign, 'run_tool') as signer:
                sign.prepare_packages('debug', 456, VERSION, ENV['GITHUB_SHA'], PLAN)
                self.assertEqual(inspect.call_count, 2)
                self.assertEqual(unsigned.call_count, 2)
                signer.assert_not_called()
            prepared = json.loads((root / 'verified-signing-input.json').read_text())
            self.assertEqual([entry['identity']['package'] for entry in prepared['manifest']['artifacts']],
                             [expected_identity('debug', VERSION, role)['package'] for role in ('watch', 'phone')])

    def test_one_fresh_signer_signs_the_declared_pair_and_keeps_exact_unsigned_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            data = apk_bytes()
            record = manifest('debug', 'unsigned')
            record.update(repository=ENV['GITHUB_REPOSITORY'], source_sha=ENV['GITHUB_SHA'], build_sha=ENV['GITHUB_SHA'],
                          run_id=123, run_attempt=1, event='push', ref=ENV['GITHUB_REF'])
            debug_raw, debug_summary = debug_test_fixture()
            self.assertEqual(record['debug_test_evidence'], debug_summary)
            source = root / 'signing-input'
            source.mkdir()
            for entry in record['artifacts']:
                entry['sha256'] = hashlib.sha256(data).hexdigest()
                entry['identity'] = expected_identity('debug', VERSION, entry['role'])
                (source / entry['file']).write_bytes(data)
            (source / ARCHIVE_FILE).write_bytes(debug_raw)
            (source / 'build-manifest.json').write_text(json.dumps(record), encoding='utf-8')
            write_checksums(source)
            (root / 'verified-signing-input.json').write_text(json.dumps({'manifest': record, 'input_artifact': {'id': 456}}), encoding='utf-8')
            (root / '.ci').mkdir()
            (root / '.ci/signing-certificates.json').write_text(json.dumps({'debug': 'e' * 64}), encoding='utf-8')
            (root / 'temporary').mkdir()
            calls = []
            def tool(arguments):
                calls.append(arguments)
                if Path(arguments[0]).name == 'zipalign':
                    Path(arguments[-1]).write_bytes(Path(arguments[-2]).read_bytes())
                else:
                    Path(arguments[arguments.index('--out') + 1]).write_bytes(data + b'fixture signed data')
            environment = ENV | {'SIGNING_KEY_B64': 'Zml4dHVyZS1ub3QtYS1yZWFsLWtleQ==',
                                 'SIGNING_PASSWORD': 'fixture-only', 'RUNNER_TEMP': str(root / 'temporary')}
            with patch.dict(os.environ, environment, clear=True), patch.object(sign, 'ROOT', root), \
                    patch.object(sign, 'project_plan', return_value=PLAN), patch.object(sign, 'properties', return_value={}), \
                    patch.object(sign, 'build_tools', return_value=root / 'tools'), patch.object(sign, 'run_tool', side_effect=tool), \
                    patch.object(sign, 'certificate', return_value='e' * 64), patch.object(sign, 'inspect_apk'):
                sign.sign('debug')
            output = root / 'signed-artifacts'
            verify_checksums(output)
            signed = json.loads((output / 'build-manifest.json').read_text())
            self.assertEqual(len(calls), 4)
            self.assertEqual({path.name for path in output.iterdir()},
                             {'watch-debug.apk', 'phone-debug.apk', 'build-manifest.json', 'SHA256SUMS', ARCHIVE_FILE})
            self.assertEqual((output / ARCHIVE_FILE).read_bytes(), debug_raw)
            self.assertEqual(signed['debug_test_evidence'], debug_summary)
            for entry in signed['artifacts']:
                self.assertEqual(entry['unsigned_sha256'], hashlib.sha256(data).hexdigest())
                self.assertEqual(entry['certificate_sha256'], 'e' * 64)
                self.assertEqual(entry['sha256'], hashlib.sha256((output / entry['file']).read_bytes()).hexdigest())
                self.assertEqual(entry['signing'], 'stable')
            self.assertFalse(any('gradle' in str(call[0]).lower() for call in calls))

    def test_missing_or_different_debug_originals_reject_before_apk_tools_or_signing(self):
        for corruption in ('missing', 'different'):
            with self.subTest(corruption=corruption), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                record = manifest('debug', 'unsigned')
                data = apk_bytes()
                debug_raw, _ = debug_test_fixture()
                for entry in record['artifacts']:
                    entry['sha256'] = hashlib.sha256(data).hexdigest()
                def fetch(*args, **kwargs):
                    destination = kwargs['destination']
                    destination.mkdir()
                    for entry in record['artifacts']:
                        (destination / entry['file']).write_bytes(data)
                    if corruption == 'different':
                        (destination / ARCHIVE_FILE).write_bytes(debug_raw + b'different original')
                    (destination / 'build-manifest.json').write_text(json.dumps(record), encoding='utf-8')
                    write_checksums(destination)
                    return {'id': 456, 'run_id': 123, 'run_attempt': 1}
                with patch.dict(os.environ, ENV, clear=True), patch.object(sign, 'ROOT', root), \
                        patch.object(sign, 'fetch_artifact', side_effect=fetch), patch.object(sign, 'GitHub', return_value=object()), \
                        patch.object(sign, 'build_tools') as tools, patch.object(sign, 'inspect_apk') as inspect, \
                        patch.object(sign, 'run_tool') as signer:
                    with self.assertRaises(ValueError):
                        sign.prepare_packages('debug', 456, VERSION, ENV['GITHUB_SHA'], PLAN)
                    tools.assert_not_called()
                    inspect.assert_not_called()
                    signer.assert_not_called()


class PairedAcceptanceTests(unittest.TestCase):
    def fixture(self):
        identity = {'run_id': 123, 'run_attempt': 1, 'artifact_id': 456, 'archive_sha256': 'a' * 64,
                    'source_sha': 'a' * 40, 'snapshot_sha': 'b' * 40, 'version': VERSION,
                    **package_identity(manifest()['artifacts'], PLAN['protocol'])}
        readiness = {'schema_version': 2, 'candidate': identity, 'owner_approved': True, 'gates': dict.fromkeys(GATES, True)}
        record = {'path': 'docs/releases/1.2.3/fixture.md', 'sha256': hashlib.sha256(b'fixture, not device evidence').hexdigest()}
        report = {'schema_version': 2, 'candidate': identity,
                  'publication_approval': {'owner': 'OnlineMo', 'approved': True,
                                          'approved_at': (datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=1)).isoformat()},
                  'gate_evidence': {gate: {'status': 'passed', 'reports': [record]} for gate in GATES},
                  'background_audio': {'validation_kind': 'physical-device', 'screen_off': True,
                                       'apk_sha256': identity['packages'][0]['sha256'], 'reference_device': 'anonymous fixture',
                                       'online_minutes': 60, 'local_minutes': 120},
                  'interoperability': {'packages': identity['packages'], 'protocol': identity['protocol'], 'status': 'passed',
                                      'cases': dict.fromkeys(('pairing', 'disconnect', 'revision_conflict', 'cancel', 'recovery', 'independent_playback'), 'passed'),
                                      'reports': [record]}}
        return identity, readiness, report

    def test_interoperability_binds_both_original_apks_protocol_and_hashed_report(self):
        identity, readiness, report = self.fixture()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'fixture'
            path.write_bytes(b'fixture, not device evidence')
            with patch('release_evidence.report_file', return_value=path):
                self.assertTrue(validate_approval(readiness, report, identity, 'c' * 40))
                for key, value in [('packages', identity['packages'][:1]), ('protocol', {'schema': 1}), ('status', 'not-run'),
                                   ('cases', {'pairing': 'passed'}), ('reports', [])]:
                    broken = copy.deepcopy(report)
                    broken['interoperability'][key] = value
                    with self.subTest(key=key), self.assertRaises(ValueError):
                        validate_approval(readiness, broken, identity, 'c' * 40)
                broken = copy.deepcopy(report)
                broken['interoperability']['reports'][0]['sha256'] = 'f' * 64
                with self.assertRaises(ValueError):
                    validate_approval(readiness, broken, identity, 'c' * 40)


if __name__ == '__main__':
    unittest.main()
