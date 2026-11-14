"""独立 runner 的受控签名入口；只解析 APK 数据，不执行应用构建脚本。"""
import argparse
import base64
import json
import os
from pathlib import Path
import tempfile

from apk_verify import build_tools, certificate, inspect_apk, inspect_test_apk, require_unsigned
from artifact_io import GitHub, fetch_artifact, verify_checksums
from ci_common import (ROOT, expected_identity, properties, require, run_tool, sha256,
                       trusted_context, write_checksums, write_json)


def context(mode):
    workflow = 'Cloud build' if mode == 'debug' else 'Owner release'
    trusted_context(workflow)
    if mode != 'debug':
        require(os.environ.get('CANDIDATE_OPERATION') == 'prepare-candidate', 'Only candidate preparation may sign')
    return workflow


def validate_manifest(manifest, mode, version, build_sha):
    expected = {'schema_version': 1, 'repository': os.environ['GITHUB_REPOSITORY'],
                'source_sha': os.environ['GITHUB_SHA'], 'build_sha': build_sha,
                'run_id': int(os.environ['GITHUB_RUN_ID']), 'run_attempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
                'event': os.environ['GITHUB_EVENT_NAME'], 'ref': os.environ['GITHUB_REF'],
                'mode': mode, 'version': version}
    for key, value in expected.items():
        require(manifest.get(key) == value, 'Unsigned build provenance mismatch: ' + key)
    entries = manifest.get('artifacts', [])
    require(len(entries) == 1 or (mode == 'diagnostic' and len(entries) == 2), 'Unexpected APK inventory')
    require(entries[0]['file'] == f'watch-{mode}.apk', 'Unexpected application APK filename')
    if len(entries) == 2:
        require(entries[1]['file'] == 'watch-diagnostic-test.apk', 'Unexpected test APK filename')
    require(all(entry.get('signing') == 'unsigned' for entry in entries), 'Stable signing requires unsigned input')


def prepare(mode, artifact_id, snapshot=None):
    """无签名秘密的准备步骤：绑定 producing job 和 artifact ID，再解析 APK。"""
    context(mode)
    source = os.environ['GITHUB_SHA']
    version = properties(ROOT / 'version.properties')
    build_sha = source
    if mode != 'debug':
        require(snapshot is not None, 'Optimized signing requires the independently prepared snapshot')
        descriptor = json.loads(Path(snapshot).read_text(encoding='utf-8'))
        require(descriptor['source_sha'] == source, 'Candidate source mismatch')
        version, build_sha = descriptor['version'], descriptor['snapshot_sha']
    attempt, run_id = int(os.environ['GITHUB_RUN_ATTEMPT']), int(os.environ['GITHUB_RUN_ID'])
    allowed = {f'watch-{mode}.apk', 'build-manifest.json', 'SHA256SUMS'}
    if mode == 'diagnostic' and os.environ.get('HAS_INSTRUMENTATION') == 'true':
        allowed.add('watch-diagnostic-test.apk')
    provenance = fetch_artifact(GitHub(), artifact_id, run_id=run_id, attempt=attempt, source_sha=source,
        workflow_path='.github/workflows/cloud-build.yml' if mode == 'debug' else '.github/workflows/release.yml',
        event=os.environ['GITHUB_EVENT_NAME'], branch=os.environ['GITHUB_REF'].removeprefix('refs/heads/'),
        job='android-compile' if mode == 'debug' else f'compile-{mode}',
        name=f'unsigned-{mode}-{run_id}-{attempt}', destination=ROOT / 'signing-input', allowed=allowed)
    manifest = json.loads((ROOT / 'signing-input/build-manifest.json').read_text(encoding='utf-8'))
    validate_manifest(manifest, mode, version, build_sha)
    tools = build_tools(properties(ROOT / '.ci/toolchain.properties'))
    for entry in manifest['artifacts']:
        apk = ROOT / 'signing-input' / entry['file']
        require(sha256(apk) == entry['sha256'], 'Unsigned APK hash mismatch')
        identity = expected_identity(mode, version)
        if entry.get('role') == 'instrumentation':
            identity = inspect_test_apk(apk, tools, identity['package'])
            entry.update(identity)
        else:
            inspect_apk(apk, tools, identity)
        require_unsigned(apk, tools)
        entry['identity'] = identity
    write_json(ROOT / 'verified-signing-input.json', {'manifest': manifest, 'input_artifact': provenance})


def sign(mode):
    """仅本步骤接收证书；私钥只展开到临时目录，不进入任何上传路径。"""
    context(mode)
    verified = json.loads((ROOT / 'verified-signing-input.json').read_text(encoding='utf-8'))
    manifest = verified['manifest']
    require(manifest['mode'] == mode, 'Prepared signing mode mismatch')
    verify_checksums(ROOT / 'signing-input')
    tools = build_tools(properties(ROOT / '.ci/toolchain.properties'))
    pins = json.loads((ROOT / '.ci/signing-certificates.json').read_text(encoding='utf-8'))
    signing_kind = 'release' if mode == 'release' else 'debug'
    require(os.environ.get('SIGNING_KEY_B64') and os.environ.get('SIGNING_PASSWORD'), 'Stable signing secrets missing')
    output = ROOT / 'signed-artifacts'
    require(not output.exists() or not any(output.iterdir()), 'Signing output must be empty')
    output.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='melox-sign-', dir=os.environ.get('RUNNER_TEMP')) as temporary:
        directory = Path(temporary)
        for entry in manifest['artifacts']:
            apk = ROOT / 'signing-input' / entry['file']
            require(sha256(apk) == entry['sha256'], 'Signing input changed after verification')
            run_tool([str(tools / 'zipalign'), '-P', '16', '-f', '4', str(apk), str(directory / entry['file'])])
        keystore = directory / 'signing.p12'
        keystore.write_bytes(base64.b64decode(os.environ['SIGNING_KEY_B64'], validate=True))
        keystore.chmod(0o600)
        for entry in manifest['artifacts']:
            target = output / entry['file']
            run_tool([str(tools / 'apksigner'), 'sign', '--ks', str(keystore), '--ks-type', 'PKCS12',
                      '--ks-key-alias', 'melox-' + signing_kind, '--ks-pass', 'env:SIGNING_PASSWORD',
                      '--key-pass', 'env:SIGNING_PASSWORD', '--out', str(target), str(directory / entry['file'])])
            digest = certificate(target, tools, pins[signing_kind])
            inspect_apk(target, tools, entry.pop('identity'))
            entry.update(unsigned_sha256=entry['sha256'], sha256=sha256(target),
                         signing='stable', certificate_sha256=digest)
    manifest['input_artifact'] = verified['input_artifact']
    write_json(output / 'build-manifest.json', manifest)
    write_checksums(output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['prepare', 'sign'])
    parser.add_argument('mode', choices=['debug', 'release', 'diagnostic'])
    parser.add_argument('--artifact-id', type=int)
    parser.add_argument('--snapshot')
    args = parser.parse_args()
    if args.operation == 'prepare':
        require(args.artifact_id is not None, 'Unsigned artifact ID is required')
        prepare(args.mode, args.artifact_id, args.snapshot)
    else:
        sign(args.mode)
