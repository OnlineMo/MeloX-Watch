"""Offline notice integrity tests use bounded ZIP data, without an Android build."""
import copy
import hashlib
import io
import json
import unittest
import zipfile

from apk_licenses import INDEX, NOTICE_BYTES, require_license_evidence, verify_license_assets

FIXTURE_NOTICE = b'Synthetic notice used only by policy fixtures.\n'
FIXTURE_INDEX = {'schema_version': 1, 'entries': [{
    'id': 'fixture', 'name': 'Policy fixture', 'version': '1', 'license': 'Fixture notice',
    'notice_asset': 'licenses/fixture.txt', 'notice_sha256': hashlib.sha256(FIXTURE_NOTICE).hexdigest(),
}]}


def license_fixture_bytes(index=None, notice=FIXTURE_NOTICE, extra=None, raw_index=None):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        archive.writestr('AndroidManifest.xml', b'fixture metadata, never executed')
        archive.writestr(INDEX, raw_index if raw_index is not None else
                         json.dumps(FIXTURE_INDEX if index is None else index, sort_keys=True).encode('utf-8'))
        archive.writestr('assets/licenses/fixture.txt', notice)
        for name, data in (extra or {}).items():
            archive.writestr(name, data)
    return buffer.getvalue()


def fixture_license_report():
    return verify_license_assets(io.BytesIO(license_fixture_bytes()))


class ApkLicenseTests(unittest.TestCase):
    def test_complete_catalog_matches_the_exact_notice_bytes(self):
        report = fixture_license_report()
        self.assertEqual(report['entry_count'], 1)
        self.assertEqual(report['notice_asset_count'], 1)
        self.assertEqual(report['notice_bytes'], len(FIXTURE_NOTICE))
        self.assertEqual(report['file_sha256']['assets/licenses/fixture.txt'],
                         hashlib.sha256(FIXTURE_NOTICE).hexdigest())

    def test_modified_missing_and_unindexed_notices_are_rejected(self):
        cases = [license_fixture_bytes(notice=b'different bytes'),
                 license_fixture_bytes(extra={'assets/licenses/unused.txt': b'not indexed'})]
        missing = copy.deepcopy(FIXTURE_INDEX)
        missing['entries'][0]['notice_asset'] = 'licenses/missing.txt'
        cases.append(license_fixture_bytes(index=missing))
        for data in cases:
            with self.subTest(bytes=len(data)), self.assertRaises(ValueError):
                verify_license_assets(io.BytesIO(data))

    def test_duplicate_json_fields_ids_and_archive_members_are_rejected(self):
        duplicate = copy.deepcopy(FIXTURE_INDEX)
        duplicate['entries'].append(copy.deepcopy(duplicate['entries'][0]))
        for data in [license_fixture_bytes(index=duplicate),
                     license_fixture_bytes(raw_index=b'{"schema_version":1,"schema_version":1,"entries":[]}')]:
            with self.assertRaises(ValueError):
                verify_license_assets(io.BytesIO(data))
        buffer = io.BytesIO(license_fixture_bytes())
        with zipfile.ZipFile(buffer, 'a') as archive:
            with self.assertWarns(UserWarning):
                archive.writestr(INDEX, b'{}')
        with self.assertRaises(ValueError):
            verify_license_assets(io.BytesIO(buffer.getvalue()))

    def test_schema_types_paths_and_unknown_nested_fields_are_rejected(self):
        for change in ('schema-bool', 'schema-float', 'path', 'nested', 'surrogate'):
            index = copy.deepcopy(FIXTURE_INDEX)
            if change == 'schema-bool': index['schema_version'] = True
            elif change == 'schema-float': index['schema_version'] = 1.0
            elif change == 'path': index['entries'][0]['notice_asset'] = 'licenses/../fixture.txt'
            elif change == 'nested': index['entries'][0]['extra'] = {'nested': []}
            else: index['entries'][0]['name'] = '\ud800'
            with self.subTest(change=change), self.assertRaises((ValueError, UnicodeError)):
                verify_license_assets(io.BytesIO(license_fixture_bytes(index=index)))

    def test_invalid_utf8_and_oversized_notices_cannot_be_accepted_by_hash(self):
        for notice in (b'\xff', b'x' * (NOTICE_BYTES + 1)):
            index = copy.deepcopy(FIXTURE_INDEX)
            index['entries'][0]['notice_sha256'] = hashlib.sha256(notice).hexdigest()
            with self.assertRaises((ValueError, UnicodeError)):
                verify_license_assets(io.BytesIO(license_fixture_bytes(index=index, notice=notice)))

    def test_manifest_requires_both_roles_and_exact_numeric_types(self):
        report = fixture_license_report()
        manifest = {'license_assets': {'watch': report, 'phone': copy.deepcopy(report)}}
        require_license_evidence(io.BytesIO(license_fixture_bytes()), 'phone', manifest)
        for change in ('missing-phone', 'bool-count', 'digest'):
            changed = copy.deepcopy(manifest)
            if change == 'missing-phone': del changed['license_assets']['phone']
            elif change == 'bool-count': changed['license_assets']['phone']['entry_count'] = True
            else: changed['license_assets']['phone']['index_sha256'] = '0' * 64
            with self.subTest(change=change), self.assertRaises(ValueError):
                require_license_evidence(io.BytesIO(license_fixture_bytes()), 'phone', changed)


if __name__ == '__main__':
    unittest.main()
