"""冻结候选及恢复原 Git 对象。准备流程不推 ref、不发布、不改变门槛。"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import shutil

from apk_verify import build_tools, certificate, inspect_apk
from artifact_io import GitHub, fetch_artifact
from ci_common import (ROOT, OWNER, OWNER_EMAIL, REPOSITORY, SHA_PATTERN, expected_identity,
                       git, no_signing_secrets, properties, require, sha256, trusted_context,
                       write_checksums, write_json)
from version_policy import next_release, parse_version

SNAPSHOT_FILES = {'snapshot.bundle', 'snapshot-manifest.json', 'SHA256SUMS'}
CANDIDATE_FILES = {'watch-release.apk', 'release-build-manifest.json', 'snapshot.bundle',
                   'snapshot-manifest.json', 'candidate-manifest.json', 'SHA256SUMS'}


def release_history(client):
    history, tags = [], {}
    for tag in client.pages('/tags'):
        name = tag['name']
        if not name.startswith('v'):
            continue
        require(re.fullmatch(r'v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', name) is not None,
                'Noncanonical existing release tag')
        blob = client.get('/contents/version.properties?ref=' + name)
        require(blob['type'] == 'file' and blob['encoding'] == 'base64', 'Invalid tagged version file')
        version = parse_version(base64.b64decode(blob['content'], validate=False).decode('utf-8'))
        require('v' + version['versionName'] == name and int(version['versionCode']) > 0,
                'Tag and version metadata disagree')
        history.append((version['versionName'], int(version['versionCode'])))
        tags[name] = tag['commit']['sha']
    return history, tags


def validate_snapshot(descriptor, cwd=ROOT):
    source, snapshot = descriptor['source_sha'], descriptor['snapshot_sha']
    require(SHA_PATTERN.fullmatch(source) and SHA_PATTERN.fullmatch(snapshot), 'Invalid candidate commit identity')
    require(git('rev-parse', '--is-shallow-repository', cwd=cwd) == 'false', 'Candidate requires complete history')
    require(git('show', '-s', '--format=%P', snapshot, cwd=cwd) == source, 'Candidate must have exactly the frozen source parent')
    require(git('diff', '--name-status', '--no-renames', source, snapshot, cwd=cwd) == 'M\tversion.properties',
            'Candidate snapshot may modify only version.properties')
    require(parse_version(git('show', snapshot + ':version.properties', cwd=cwd)) == descriptor['version'],
            'Candidate version does not match snapshot bytes')
    count = int(git('rev-list', '--count', source, cwd=cwd))
    require(type(descriptor['main_commit_count']) is int and descriptor['main_commit_count'] == count,
            'Frozen source history count mismatch')
    require(int(git('rev-list', '--count', snapshot, cwd=cwd)) == count + 1, 'Snapshot history count mismatch')
    require(int(descriptor['version']['versionCode']) == max(count + 1, descriptor['previous_max_code'] + 1),
            'Frozen candidate code violates the count policy')
    identity = git('show', '-s', '--format=%an%n%ae%n%cn%n%ce', snapshot, cwd=cwd).splitlines()
    require(identity == [OWNER, OWNER_EMAIL, OWNER, OWNER_EMAIL], 'Snapshot identity must use owner noreply')
    message = git('show', '-s', '--format=%B', snapshot, cwd=cwd)
    require(message == 'chore(release): v' + descriptor['version']['versionName'], 'Unexpected snapshot message')


def prepare():
    trusted_context('Owner release')
    no_signing_secrets()
    require(os.environ.get('CANDIDATE_OPERATION') == 'prepare-candidate', 'Candidate preparation operation required')
    # 工程缺失时在产生快照之前停止；规划检查成功不能生成伪候选。
    from check import check
    check(require_android=True)
    client = GitHub()
    source = os.environ['GITHUB_SHA']
    require(git('rev-parse', 'HEAD') == source, 'Preparation checkout differs from dispatched source')
    require(client.get('/git/ref/heads/main')['object']['sha'] == source, 'main advanced after dispatch')
    require(git('rev-parse', '--is-shallow-repository') == 'false', 'Preparation requires complete history')
    requested = os.environ['RELEASE_VERSION']
    prerelease = os.environ.get('PRERELEASE') == 'true'
    require(requested != '0.1.0' or prerelease, 'The first 0.1.0 must be a prerelease')
    history, tags = release_history(client)
    require('v' + requested not in tags, 'Version tag already exists; recover original bytes or use a higher version')
    count = int(git('rev-list', '--count', source))
    code = next_release(requested, history, count, properties(ROOT / 'version.properties'))
    git('switch', '--detach', source)
    (ROOT / 'version.properties').write_text(
        '# Candidate snapshot; never merge into development branches.\n'
        f'versionName={requested}\nversionCode={code}\n', encoding='utf-8')
    git('add', '--', 'version.properties')
    identity = dict(os.environ, GIT_AUTHOR_NAME=OWNER, GIT_AUTHOR_EMAIL=OWNER_EMAIL,
                    GIT_COMMITTER_NAME=OWNER, GIT_COMMITTER_EMAIL=OWNER_EMAIL)
    git('-c', 'commit.gpgsign=false', 'commit', '-m', 'chore(release): v' + requested, env=identity)
    descriptor = {'schema_version': 1, 'repository': REPOSITORY, 'source_sha': source,
                  'snapshot_sha': git('rev-parse', 'HEAD'), 'main_commit_count': count,
                  'previous_max_code': max((code for _, code in history), default=0),
                  'version': {'versionName': requested, 'versionCode': str(code)}, 'prerelease': prerelease,
                  'run_id': int(os.environ['GITHUB_RUN_ID']), 'run_attempt': int(os.environ['GITHUB_RUN_ATTEMPT']),
                  'toolchain_sha256': sha256(ROOT / '.ci/toolchain.properties'),
                  'upstream_lock_sha256': sha256(ROOT / '.ci/upstream-lock.json'),
                  'certificate_pins_sha256': sha256(ROOT / '.ci/signing-certificates.json')}
    validate_snapshot(descriptor)
    output = ROOT / 'snapshot-artifacts'
    output.mkdir()
    git('bundle', 'create', str(output / 'snapshot.bundle'), 'HEAD')
    write_json(output / 'snapshot-manifest.json', descriptor)
    write_checksums(output)
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as stream:
        stream.write('snapshot-sha=' + descriptor['snapshot_sha'] + '\n')


def fetch_snapshot(artifact_id, checkout=False):
    trusted_context('Owner release')
    no_signing_secrets()
    run_id, attempt = int(os.environ['GITHUB_RUN_ID']), int(os.environ['GITHUB_RUN_ATTEMPT'])
    destination = ROOT / 'snapshot-input'
    provenance = fetch_artifact(GitHub(), artifact_id, run_id=run_id, attempt=attempt,
        source_sha=os.environ['GITHUB_SHA'], workflow_path='.github/workflows/release.yml',
        event='workflow_dispatch', branch='main', job='prepare-snapshot',
        name=f'snapshot-{run_id}-{attempt}', destination=destination, allowed=SNAPSHOT_FILES)
    descriptor = json.loads((destination / 'snapshot-manifest.json').read_text(encoding='utf-8'))
    require(descriptor['source_sha'] == os.environ['GITHUB_SHA'] and descriptor['run_id'] == run_id
            and descriptor['run_attempt'] == attempt, 'Prepared snapshot context mismatch')
    import_snapshot(destination, descriptor)
    write_json(ROOT / 'snapshot-provenance.json', provenance)
    if checkout:
        git('switch', '--detach', descriptor['snapshot_sha'])
    return descriptor


def import_snapshot(directory, descriptor):
    bundle = str(Path(directory) / 'snapshot.bundle')
    heads = git('bundle', 'list-heads', bundle).splitlines()
    require(heads == [descriptor['snapshot_sha'] + ' HEAD'], 'Unexpected bundle references')
    git('bundle', 'verify', bundle)
    git('bundle', 'unbundle', bundle)
    validate_snapshot(descriptor)


def fetch_signed(client, mode, artifact_id, descriptor, has_instrumentation=False):
    run_id, attempt = descriptor['run_id'], descriptor['run_attempt']
    allowed = {f'watch-{mode}.apk', 'build-manifest.json', 'SHA256SUMS'}
    if has_instrumentation:
        allowed.add('watch-diagnostic-test.apk')
    destination = ROOT / ('signed-' + mode)
    provenance = fetch_artifact(client, artifact_id, run_id=run_id, attempt=attempt,
        source_sha=descriptor['source_sha'], workflow_path='.github/workflows/release.yml', event='workflow_dispatch',
        branch='main', job='sign-' + mode, name=f'signed-{mode}-{run_id}-{attempt}',
        destination=destination, allowed=allowed)
    manifest = json.loads((destination / 'build-manifest.json').read_text(encoding='utf-8'))
    for key, value in {'source_sha': descriptor['source_sha'], 'build_sha': descriptor['snapshot_sha'],
                       'run_id': run_id, 'run_attempt': attempt, 'mode': mode, 'version': descriptor['version'],
                       'repository': REPOSITORY, 'event': 'workflow_dispatch', 'ref': 'refs/heads/main'}.items():
        require(manifest.get(key) == value, 'Signed manifest mismatch: ' + key)
    require(len(manifest['artifacts']) == (2 if has_instrumentation else 1), 'Signed APK inventory mismatch')
    tools = build_tools(properties(ROOT / '.ci/toolchain.properties'))
    pins = json.loads((ROOT / '.ci/signing-certificates.json').read_text(encoding='utf-8'))
    for entry in manifest['artifacts']:
        require(entry['file'] in allowed and entry['file'].endswith('.apk'), 'Unexpected signed APK filename')
        apk = destination / entry['file']
        require(sha256(apk) == entry['sha256'] and entry['signing'] == 'stable', 'Signed APK hash/signing mismatch')
        expected = expected_identity(mode, descriptor['version'])
        if entry.get('role') == 'instrumentation':
            expected.update(package=expected['package'] + '.test', debuggable=True, profileable=False)
        inspect_apk(apk, tools, expected)
        digest = certificate(apk, tools, pins['release' if mode == 'release' else 'debug'])
        require(entry['certificate_sha256'] == digest, 'Manifest certificate mismatch')
    return provenance, manifest


def seal(release_id, diagnostic_id):
    trusted_context('Owner release')
    no_signing_secrets()
    descriptor = json.loads((ROOT / 'snapshot-input/snapshot-manifest.json').read_text(encoding='utf-8'))
    validate_snapshot(descriptor)
    client = GitHub()
    release_provenance, release_manifest = fetch_signed(client, 'release', release_id, descriptor)
    diagnostic_provenance, diagnostic_manifest = fetch_signed(client, 'diagnostic', diagnostic_id, descriptor,
        os.environ.get('HAS_INSTRUMENTATION') == 'true')
    output = ROOT / 'candidate-artifacts'
    output.mkdir()
    shutil.copyfile(ROOT / 'signed-release/watch-release.apk', output / 'watch-release.apk')
    shutil.copyfile(ROOT / 'signed-release/build-manifest.json', output / 'release-build-manifest.json')
    for name in ('snapshot.bundle', 'snapshot-manifest.json'):
        shutil.copyfile(ROOT / 'snapshot-input' / name, output / name)
    manifest = {
        'schema_version': 1, 'kind': 'release-candidate', **descriptor,
        # artifact ID 由上传完成后的 API/locator 确定，不能写入自身内容形成循环哈希。
        'artifact_name': f'candidate-{descriptor["run_id"]}-{descriptor["run_attempt"]}',
        'apk': release_manifest['artifacts'][0],
        'inputs': {'release': release_provenance, 'diagnostic': diagnostic_provenance,
                   'snapshot': json.loads((ROOT / 'snapshot-provenance.json').read_text(encoding='utf-8'))},
        'diagnostic': diagnostic_manifest['artifacts'],
        'files': {path.name: sha256(path) for path in output.iterdir()},
        'device_validation': 'NOT RUN', 'owner_publication_approval': 'NOT REQUESTED',
    }
    write_json(output / 'candidate-manifest.json', manifest)
    write_checksums(output)


def fetch_candidate(client, run_id, attempt, artifact_id, destination):
    run = client.get(f'/actions/runs/{run_id}/attempts/{attempt}')
    provenance = fetch_artifact(client, artifact_id, run_id=run_id, attempt=attempt, source_sha=run['head_sha'],
        workflow_path='.github/workflows/release.yml', event='workflow_dispatch', branch='main', job='seal-candidate',
        name=f'candidate-{run_id}-{attempt}', destination=destination, allowed=CANDIDATE_FILES, require_success=True)
    manifest = json.loads((Path(destination) / 'candidate-manifest.json').read_text(encoding='utf-8'))
    descriptor = json.loads((Path(destination) / 'snapshot-manifest.json').read_text(encoding='utf-8'))
    require(all(manifest.get(key) == value for key, value in descriptor.items()), 'Candidate snapshot descriptor mismatch')
    require(manifest['kind'] == 'release-candidate' and manifest['source_sha'] == run['head_sha'], 'Invalid candidate manifest')
    require(manifest['run_id'] == run_id and manifest['run_attempt'] == attempt, 'Candidate attempt mismatch')
    require(set(manifest['files']) == CANDIDATE_FILES - {'candidate-manifest.json', 'SHA256SUMS'}, 'Candidate file inventory mismatch')
    for name, digest in manifest['files'].items():
        require(sha256(Path(destination) / name) == digest, 'Candidate file hash mismatch')
    return manifest, provenance


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['prepare', 'fetch-snapshot', 'seal'])
    parser.add_argument('--artifact-id', type=int)
    parser.add_argument('--checkout', action='store_true')
    parser.add_argument('--release-id', type=int)
    parser.add_argument('--diagnostic-id', type=int)
    args = parser.parse_args()
    if args.operation == 'prepare':
        prepare()
    elif args.operation == 'fetch-snapshot':
        fetch_snapshot(args.artifact_id, args.checkout)
    else:
        seal(args.release_id, args.diagnostic_id)
