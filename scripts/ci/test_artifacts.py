"""CI-01 反例：不可信来源、产物替换、路径穿越、包名及秘密泄漏边界。"""
import copy
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import tempfile
import traceback
import unittest
from unittest.mock import MagicMock, patch
import urllib.error
import zipfile

from apk_verify import parse_badging, require_no_signature_blocks
from artifact_io import GitHub, MAX_ARCHIVE_BYTES, NoRedirect, fetch_artifact, unpack_data, validate_run
import build
from ci_common import expected_identity, no_signing_secrets, trusted_context, write_checksums
import sign
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


class ArtifactDownloadTests(unittest.TestCase):
    def test_api_media_type_and_redirect_do_not_forward_authorization(self):
        path = '/repos/OnlineMo/MeloX-Watch/actions/artifacts/456/zip'
        signed_url = 'https://artifact.example.invalid/archive.zip?signature=fixture'
        response = MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = b'artifact fixture'
        redirect = urllib.error.HTTPError('https://api.github.com' + path, 302, 'Found',
                                          {'Location': signed_url}, None)
        with patch('artifact_io.urllib.request.build_opener') as make_opener, \
                patch('artifact_io.urllib.request.urlopen', return_value=response) as signed_open:
            make_opener.return_value.open.side_effect = redirect
            self.assertEqual(GitHub(token='fixture-token').download(path), b'artifact fixture')
            make_opener.assert_called_once_with(NoRedirect)
            request = make_opener.return_value.open.call_args.args[0]
            self.assertEqual(request.full_url, 'https://api.github.com' + path)
            self.assertEqual(request.get_header('Accept'), 'application/vnd.github+json')
            self.assertEqual(request.get_header('X-github-api-version'), '2022-11-28')
            self.assertEqual(request.get_header('Authorization'), 'Bearer fixture-token')
            # URL 字符串发起独立请求，不复制已认证 API 的请求头。
            signed_open.assert_called_once_with(signed_url, timeout=60)
            response.read.assert_called_once_with(MAX_ARCHIVE_BYTES + 1)

    def test_http_or_missing_redirect_is_rejected_before_external_request(self):
        path = '/repos/OnlineMo/MeloX-Watch/actions/artifacts/456/zip'
        for location in ('http://artifact.example.invalid/archive.zip', 'ftp://artifact.example.invalid/archive.zip', ''):
            error = urllib.error.HTTPError('https://api.github.com' + path, 302, 'Found',
                                           {'Location': location}, None)
            with self.subTest(location=location), patch('artifact_io.urllib.request.build_opener') as make_opener, \
                    patch('artifact_io.urllib.request.urlopen') as signed_open:
                make_opener.return_value.open.side_effect = error
                with self.assertRaisesRegex(ValueError, 'redirect must use HTTPS'):
                    GitHub(token='fixture-token').download(path)
                signed_open.assert_not_called()

    def test_non302_api_error_does_not_expose_error_reason_or_url_in_traceback(self):
        path = '/repos/OnlineMo/MeloX-Watch/actions/artifacts/456/zip'
        secret_marker = 'fixture-sensitive-marker'
        for code in (301, 307, 403, 415, 500):
            error = urllib.error.HTTPError('https://api.github.com' + path + '?token=' + secret_marker,
                                           code, secret_marker, {'Location': 'https://artifact.example.invalid/' + secret_marker}, None)
            with self.subTest(code=code), patch('artifact_io.urllib.request.build_opener') as make_opener, \
                    patch('artifact_io.urllib.request.urlopen') as signed_open:
                make_opener.return_value.open.side_effect = error
                try:
                    GitHub(token='fixture-token').download(path)
                except ValueError as caught:
                    self.assertEqual(str(caught), f'Artifact download failed: HTTP {code}')
                    self.assertNotIn(secret_marker, ''.join(traceback.format_exception(caught)))
                else:
                    self.fail('Non302 response was accepted')
                signed_open.assert_not_called()


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


class SigningOutputTests(unittest.TestCase):
    def sign_fixture(self, root, unexpected_sidecar=False):
        input_directory = root / 'signing-input'
        input_directory.mkdir()
        (input_directory / 'watch-debug.apk').write_bytes(b'unsigned fixture bytes')
        manifest = {'mode': 'debug', 'artifacts': [{
            'file': 'watch-debug.apk', 'signing': 'unsigned',
            'sha256': hashlib.sha256(b'unsigned fixture bytes').hexdigest(),
            'identity': expected_identity('debug', VERSION),
        }]}
        (input_directory / 'build-manifest.json').write_text(json.dumps(manifest), encoding='utf-8')
        write_checksums(input_directory)
        (root / 'verified-signing-input.json').write_text(json.dumps({
            'manifest': manifest, 'input_artifact': {'id': 456},
        }), encoding='utf-8')
        (root / '.ci').mkdir()
        (root / '.ci/signing-certificates.json').write_text(json.dumps({'debug': 'a' * 64}), encoding='utf-8')
        (root / 'temporary').mkdir()

        def signer_tool(arguments):
            if Path(arguments[0]).name == 'zipalign':
                Path(arguments[-1]).write_bytes(Path(arguments[-2]).read_bytes())
            else:
                target = Path(arguments[arguments.index('--out') + 1])
                target.write_bytes(b'signed fixture bytes')
                # 模拟真实默认行为及工具意外输出；不执行APK或任何Android工具。
                v4_disabled = '--v4-signing-enabled' in arguments and arguments[arguments.index('--v4-signing-enabled') + 1] == 'false'
                if unexpected_sidecar or not v4_disabled:
                    target.with_name(target.name + '.idsig').write_bytes(b'sidecar fixture')

        fixture_environment = ENV | {
            'SIGNING_KEY_B64': 'Zml4dHVyZS1ub3QtYS1yZWFsLWtleQ==',
            'SIGNING_PASSWORD': 'fixture-not-a-real-password', 'RUNNER_TEMP': str(root / 'temporary'),
        }
        with patch.dict(os.environ, fixture_environment, clear=True), patch.object(sign, 'ROOT', root), \
                patch.object(sign, 'properties', return_value={}), patch.object(sign, 'build_tools', return_value=root / 'tools'), \
                patch.object(sign, 'run_tool', side_effect=signer_tool), patch.object(sign, 'certificate', return_value='a' * 64), \
                patch.object(sign, 'inspect_apk'):
            sign.sign('debug')

    def test_signed_output_roundtrips_through_the_original_archive_contract(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.sign_fixture(root)
            output = root / 'signed-artifacts'
            files = {path.name: path.read_bytes() for path in output.iterdir()}
            expected_files = {'watch-debug.apk', 'build-manifest.json', 'SHA256SUMS'}
            self.assertEqual(set(files), expected_files)
            unpack_data(archive_bytes(files), root / 'downloaded-original', expected_files)
            manifest = json.loads(files['build-manifest.json'])
            entry = manifest['artifacts'][0]
            self.assertEqual(entry['signing'], 'stable')
            self.assertEqual(entry['unsigned_sha256'], hashlib.sha256(b'unsigned fixture bytes').hexdigest())
            self.assertEqual(entry['sha256'], hashlib.sha256(files['watch-debug.apk']).hexdigest())
            self.assertEqual(manifest['input_artifact'], {'id': 456})

    def test_unexpected_signer_file_stops_before_manifest_or_checksum_publication(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(ValueError, 'Signing produced unexpected files'):
                self.sign_fixture(root, unexpected_sidecar=True)
            self.assertFalse((root / 'signed-artifacts/build-manifest.json').exists())
            self.assertFalse((root / 'signed-artifacts/SHA256SUMS').exists())


if __name__ == '__main__':
    unittest.main()
