"""CI-01 反例：不可信来源、产物替换、路径穿越、包名及秘密泄漏边界。"""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from apk_verify import parse_badging, require_no_signature_blocks
from artifact_io import fetch_artifact, unpack_data, validate_run
import build
from ci_common import expected_identity, no_signing_secrets, trusted_context
from sign import validate_manifest

SOURCE = 'a' * 40
VERSION = {'versionName': '0.1.0', 'versionCode': '25'}
ENV = {'GITHUB_ACTIONS': 'true', 'CI': 'true', 'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch',
       'GITHUB_ACTOR': 'OnlineMo', 'GITHUB_TRIGGERING_ACTOR': 'OnlineMo', 'GITHUB_WORKFLOW': 'Cloud build',
       'GITHUB_EVENT_NAME': 'push', 'GITHUB_REF': 'refs/heads/contribution', 'GITHUB_SHA': SOURCE,
       'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2'}
RUN = {'repository': {'full_name': 'OnlineMo/MeloX-Watch'}, 'run_attempt': 2, 'head_sha': SOURCE,
       'head_branch': 'contribution', 'path': '.github/workflows/cloud-build.yml', 'event': 'push',
       'actor': {'login': 'OnlineMo'}, 'triggering_actor': {'login': 'OnlineMo'}, 'conclusion': 'success'}


def archive_bytes(files):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w') as archive:
        for name, value in files.items():
            archive.writestr(name, value)
    return buffer.getvalue()


class ArtifactBoundaryTests(unittest.TestCase):
    def test_invalid_existing_signatures_cannot_be_treated_as_unsigned(self):
        with tempfile.TemporaryDirectory() as temporary:
            apk = Path(temporary) / 'fixture.apk'
            apk.write_bytes(archive_bytes({'AndroidManifest.xml': b'fixture'}))
            require_no_signature_blocks(apk)
            apk.write_bytes(archive_bytes({'AndroidManifest.xml': b'fixture', 'META-INF/CERT.RSA': b'broken'}))
            with self.assertRaisesRegex(ValueError, 'v1 signature'):
                require_no_signature_blocks(apk)

    def test_signing_rejects_pr_fork_ref_and_nonowner_rerun(self):
        trusted_context('Cloud build', ENV)
        for changes in [{'GITHUB_EVENT_NAME': 'pull_request'}, {'GITHUB_REF': 'refs/heads/topic'},
                        {'GITHUB_REPOSITORY': 'other/MeloX-Watch'}, {'GITHUB_TRIGGERING_ACTOR': 'other'},
                        {'GITHUB_WORKFLOW': 'Untrusted'}, {'GITHUB_SHA': ''}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                trusted_context('Cloud build', ENV | changes)

    def test_secret_injection_stops_before_preflight_or_gradle(self):
        for name in ('SIGNING_KEY_B64', 'SIGNING_PASSWORD', 'RELEASE_KEYSTORE_B64', 'CI_DEBUG_STORE_PASSWORD'):
            with self.subTest(name=name), patch.dict(os.environ, ENV | {name: 'test-marker'}, clear=True), \
                    patch.object(build, 'check') as preflight, patch.object(build.subprocess, 'run') as process:
                with self.assertRaisesRegex(ValueError, 'must not receive'):
                    build.build('debug')
                preflight.assert_not_called()
                process.assert_not_called()
        no_signing_secrets({})

    def test_completed_attempt_is_checked_independently_of_current_rerun(self):
        validate_run(RUN, 2, SOURCE, RUN['path'], 'push', 'contribution', True)
        for changes in [{'head_sha': 'b' * 40}, {'run_attempt': 3}, {'conclusion': 'failure'},
                        {'triggering_actor': {'login': 'other'}}, {'event': 'pull_request'},
                        {'repository': {'full_name': 'fork/MeloX-Watch'}}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_run(RUN | changes, 2, SOURCE, RUN['path'], 'push', 'contribution', True)

    def test_archive_rejects_traversal_symlink_extra_file_and_wrong_checksum(self):
        data = b'APK bytes are not executed'
        digest = hashlib.sha256(data).hexdigest()
        good = {'watch-debug.apk': data, 'SHA256SUMS': digest + '  watch-debug.apk\n'}
        with tempfile.TemporaryDirectory() as temporary:
            unpack_data(archive_bytes(good), Path(temporary) / 'good', set(good))
            failures = [good | {'../escape': b'x'}, good | {'script.py': b'x'},
                        good | {'watch-debug.apk': b'altered'}, good | {'SHA256SUMS': ''}]
            for index, files in enumerate(failures):
                with self.subTest(index=index), self.assertRaises(ValueError):
                    unpack_data(archive_bytes(files), Path(temporary) / str(index), set(good))
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, 'w') as archive:
                member = zipfile.ZipInfo('watch-debug.apk')
                member.external_attr = (stat.S_IFLNK | 0o777) << 16
                archive.writestr(member, b'/outside')
                archive.writestr('SHA256SUMS', good['SHA256SUMS'])
            with self.assertRaisesRegex(ValueError, 'symlinks'):
                unpack_data(buffer.getvalue(), Path(temporary) / 'symlink', set(good))

    def test_artifact_id_cannot_substitute_another_run_or_expired_archive(self):
        data = archive_bytes({'payload': b'x', 'SHA256SUMS': hashlib.sha256(b'x').hexdigest() + '  payload\n'})

        class Client:
            artifact = {'workflow_run': {'id': 123, 'head_sha': SOURCE}, 'name': 'unsigned-debug-123-2',
                        'expired': False, 'size_in_bytes': len(data), 'digest': 'sha256:' + hashlib.sha256(data).hexdigest()}

            def get(self, path):
                return RUN if '/attempts/' in path else self.artifact

            def pages(self, path, key):
                return [{'name': 'android-compile', 'conclusion': 'success'}]

            def download(self, path):
                return data

        with tempfile.TemporaryDirectory() as temporary:
            for index, changes in enumerate([{}, {'expired': True}, {'name': 'unsigned-debug-123-1'},
                                             {'workflow_run': {'id': 999, 'head_sha': SOURCE}}, {'digest': 'sha256:wrong'}]):
                client = Client()
                client.artifact = copy.deepcopy(Client.artifact) | changes
                def fetch():
                    return fetch_artifact(client, 456, run_id=123, attempt=2, source_sha=SOURCE,
                        workflow_path=RUN['path'], event='push', branch='contribution', job='android-compile',
                        name='unsigned-debug-123-2', destination=Path(temporary) / str(index),
                        allowed={'payload', 'SHA256SUMS'})
                if changes:
                    with self.subTest(changes=changes), self.assertRaises(ValueError):
                        fetch()
                else:
                    self.assertEqual(fetch()['id'], 456)

    def test_manifest_binds_version_mode_attempt_and_each_sha(self):
        manifest = {'schema_version': 1, 'repository': ENV['GITHUB_REPOSITORY'], 'source_sha': SOURCE,
                    'build_sha': SOURCE, 'run_id': 123, 'run_attempt': 2, 'event': 'push',
                    'ref': ENV['GITHUB_REF'], 'mode': 'debug', 'version': VERSION,
                    'artifacts': [{'file': 'watch-debug.apk', 'signing': 'unsigned'}]}
        with patch.dict(os.environ, ENV, clear=True):
            validate_manifest(manifest, 'debug', VERSION, SOURCE)
            for changes in [{'source_sha': 'b' * 40}, {'build_sha': 'b' * 40}, {'run_attempt': 1},
                            {'version': VERSION | {'versionCode': '26'}}, {'mode': 'release'}]:
                with self.subTest(changes=changes), self.assertRaises(ValueError):
                    validate_manifest(manifest | changes, 'debug', VERSION, SOURCE)

    def test_exact_package_and_diagnostic_flags_are_separate(self):
        debug = "package: name='io.github.onlinemo.meloxwatch.dev' versionCode='25' versionName='0.1.0'\napplication-debuggable\n"
        self.assertEqual(parse_badging(debug, ''), expected_identity('debug', VERSION))
        self.assertNotEqual(parse_badging(debug.replace('onlinemo', 'other'), ''), expected_identity('debug', VERSION))
        profile = 'E: manifest\n  E: application\n    E: profileable\n      A: android:shell(0x01010601)=(type 0x12)0xffffffff\n'
        release = debug.replace('.dev', '').replace('application-debuggable\n', '')
        self.assertNotEqual(parse_badging(release, profile), expected_identity('release', VERSION))
        self.assertEqual(parse_badging(release.replace('meloxwatch', 'meloxwatch.diagnostic'), profile),
                         expected_identity('diagnostic', VERSION))


if __name__ == '__main__':
    unittest.main()
