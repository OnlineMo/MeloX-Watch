"""冻结候选及恢复原 Git 对象。准备流程不推 ref、不发布、不改变门槛。"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import shutil

from apk_verify import build_tools, certificate, inspect_apk, inspect_test_apk
from artifact_io import GitHub, fetch_artifact, validate_run
from ci_common import (ROOT, OWNER, OWNER_EMAIL, REPOSITORY, SHA_PATTERN, expected_identity,
                       git, no_signing_secrets, properties, require, sha256, trusted_context,
                       write_checksums, write_json)
from version_policy import next_release, parse_version
from package_contract import (artifact_files, exact_data, native_abis, package_identity, project_plan, validate_artifacts)
from package_test_evidence import ARCHIVE_FILE, BINDING_KEYS, validate_archive as validate_debug

SNAPSHOT_FILES = {'snapshot.bundle', 'snapshot-manifest.json', 'SHA256SUMS'}
CANDIDATE_FILES = {'watch-release.apk', 'phone-release.apk', ARCHIVE_FILE, 'release-build-manifest.json', 'snapshot.bundle',
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
    if descriptor.get('schema_version') == 2:
        plan = project_plan(cwd, source)
        require(type(descriptor['schema_version']) is int and exact_data({key: descriptor.get(key) for key in plan}, plan),
                'Snapshot package/protocol scope differs')


def prepare():
    trusted_context('Owner release')
    no_signing_secrets()
    require(os.environ.get('CANDIDATE_OPERATION') == 'prepare-candidate', 'Candidate preparation operation required')
    # 工程缺失时在产生快照之前停止；规划检查成功不能生成伪候选。
    from check import check
    check(require_android=True, unit_checks=False)
    plan = project_plan(ROOT)
    client = GitHub()
    source = os.environ['GITHUB_SHA']
    run_id, attempt = int(os.environ['GITHUB_RUN_ID']), int(os.environ['GITHUB_RUN_ATTEMPT'])
    # 重跑仅暴露触发者登录名；其数字 ID 必须从本次尝试的元数据核验。
    run = client.get(f'/actions/runs/{run_id}/attempts/{attempt}')
    require(type(run.get('id')) is int and run['id'] == run_id, 'Preparation run identity mismatch')
    validate_run(run, attempt, source, '.github/workflows/release.yml', 'workflow_dispatch', 'main')
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
    descriptor = {'schema_version': 2, 'repository': REPOSITORY, 'source_sha': source, **plan,
                  'snapshot_sha': git('rev-parse', 'HEAD'), 'main_commit_count': count,
                  'previous_max_code': max((code for _, code in history), default=0),
                  'version': {'versionName': requested, 'versionCode': str(code)}, 'prerelease': prerelease,
                  'run_id': run_id, 'run_attempt': attempt,
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
    plan = None
    if descriptor.get('schema_version') == 2:
        plan = {key: descriptor[key] for key in ('application_roles', 'instrumentation_roles', 'protocol')}
        allowed = artifact_files(mode, plan)
    if has_instrumentation and plan is None:
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
        require(type(manifest.get(key)) is type(value) and manifest[key] == value, 'Signed manifest mismatch: ' + key)
    if plan is None:
        require(len(manifest['artifacts']) == (2 if has_instrumentation else 1), 'Signed APK inventory mismatch')
    else:
        validate_artifacts(manifest, mode, descriptor['version'], plan, signing='stable')
        validate_debug(destination / ARCHIVE_FILE, {key: manifest[key] for key in BINDING_KEYS}, manifest['debug_test_evidence'])
    tools = build_tools(properties(ROOT / '.ci/toolchain.properties'))
    pins = json.loads((ROOT / '.ci/signing-certificates.json').read_text(encoding='utf-8'))
    for entry in manifest['artifacts']:
        require(entry['file'] in allowed and entry['file'].endswith('.apk'), 'Unexpected signed APK filename')
        apk = destination / entry['file']
        require(sha256(apk) == entry['sha256'] and entry['signing'] == 'stable', 'Signed APK hash/signing mismatch')
        expected = expected_identity(mode, descriptor['version'], entry['role'] if plan is not None else 'watch')
        if entry.get('role') == 'instrumentation' or entry.get('kind') == 'instrumentation':
            actual = inspect_test_apk(apk, tools, expected['package'])
            require(all(entry.get(key) == value for key, value in actual.items()), 'Test APK metadata mismatch')
        else:
            inspect_apk(apk, tools, expected)
        digest = certificate(apk, tools, pins['release' if mode == 'release' else 'debug'])
        require(entry['certificate_sha256'] == digest, 'Manifest certificate mismatch')
        if plan is not None:
            require(native_abis(apk) == entry['abis'], 'Signed APK ABI inventory differs')
    return provenance, manifest


def seal(release_id, diagnostic_id):
    trusted_context('Owner release')
    no_signing_secrets()
    descriptor = json.loads((ROOT / 'snapshot-input/snapshot-manifest.json').read_text(encoding='utf-8'))
    require(descriptor.get('schema_version') == 2, 'New candidates require the complete watch and phone snapshot')
    validate_snapshot(descriptor)
    client = GitHub()
    release_provenance, release_manifest = fetch_signed(client, 'release', release_id, descriptor)
    diagnostic_provenance, diagnostic_manifest = fetch_signed(client, 'diagnostic', diagnostic_id, descriptor,
        os.environ.get('HAS_INSTRUMENTATION') == 'true')
    require(release_manifest.get('test_scope') == 'full-debug-once' and
            release_manifest['source_input_hashes'] == diagnostic_manifest['source_input_hashes'] and
            release_manifest['strict_input_hashes'] == diagnostic_manifest['strict_input_hashes'] and
            exact_data(release_manifest['debug_test_evidence'], diagnostic_manifest['debug_test_evidence']),
            'Candidate packages need one full Debug test scope on the same strict source inputs')
    output = ROOT / 'candidate-artifacts'
    output.mkdir()
    for entry in release_manifest['artifacts']:
        shutil.copyfile(ROOT / 'signed-release' / entry['file'], output / entry['file'])
    shutil.copyfile(ROOT / 'signed-release/build-manifest.json', output / 'release-build-manifest.json')
    shutil.copyfile(ROOT / 'signed-release' / ARCHIVE_FILE, output / ARCHIVE_FILE)
    for name in ('snapshot.bundle', 'snapshot-manifest.json'):
        shutil.copyfile(ROOT / 'snapshot-input' / name, output / name)
    manifest = {
        'schema_version': 2, 'kind': 'release-candidate', **descriptor,
        # artifact ID 由上传完成后的 API/locator 确定，不能写入自身内容形成循环哈希。
        'artifact_name': f'candidate-{descriptor["run_id"]}-{descriptor["run_attempt"]}',
        'apks': release_manifest['artifacts'],
        'inputs': {'release': release_provenance, 'diagnostic': diagnostic_provenance,
                   'snapshot': json.loads((ROOT / 'snapshot-provenance.json').read_text(encoding='utf-8'))},
        'diagnostic': diagnostic_manifest['artifacts'],
        'files': {path.name: sha256(path) for path in output.iterdir()},
        'device_validation': 'NOT RUN', 'owner_publication_approval': 'NOT REQUESTED',
    }
    write_json(output / 'candidate-manifest.json', manifest)
    write_checksums(output)
    accepted = package_identity(manifest['apks'], manifest['protocol'])
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
            summary.write('Candidate watch/phone package-set SHA-256: ' + accepted['package_set_sha256'] +
                          '. Device acceptance and publication approval remain NOT RUN.\n')


def fetch_candidate(client, run_id, attempt, artifact_id, destination):
    run = client.get(f'/actions/runs/{run_id}/attempts/{attempt}')
    provenance = fetch_artifact(client, artifact_id, run_id=run_id, attempt=attempt, source_sha=run['head_sha'],
        workflow_path='.github/workflows/release.yml', event='workflow_dispatch', branch='main', job='seal-candidate',
        name=f'candidate-{run_id}-{attempt}', destination=destination, allowed=CANDIDATE_FILES, require_success=True)
    manifest = json.loads((Path(destination) / 'candidate-manifest.json').read_text(encoding='utf-8'))
    descriptor = json.loads((Path(destination) / 'snapshot-manifest.json').read_text(encoding='utf-8'))
    require(type(manifest.get('schema_version')) is int and manifest['schema_version'] == 2,
            'First-release candidate must contain the multi-package schema')
    require(exact_data({key: manifest.get(key) for key in descriptor}, descriptor), 'Candidate snapshot descriptor mismatch')
    require(manifest['kind'] == 'release-candidate' and manifest['source_sha'] == run['head_sha'], 'Invalid candidate manifest')
    require(type(manifest['run_id']) is int and manifest['run_id'] == run_id and
            type(manifest['run_attempt']) is int and manifest['run_attempt'] == attempt, 'Candidate attempt mismatch')
    require(set(manifest['files']) == CANDIDATE_FILES - {'candidate-manifest.json', 'SHA256SUMS'}, 'Candidate file inventory mismatch')
    for name, digest in manifest['files'].items():
        require(sha256(Path(destination) / name) == digest, 'Candidate file hash mismatch')
    build_manifest = json.loads((Path(destination) / 'release-build-manifest.json').read_text(encoding='utf-8'))
    for key, value in {'repository': REPOSITORY, 'source_sha': manifest['source_sha'], 'build_sha': manifest['snapshot_sha'],
                       'run_id': run_id, 'run_attempt': attempt, 'event': 'workflow_dispatch', 'ref': 'refs/heads/main'}.items():
        require(type(build_manifest.get(key)) is type(value) and build_manifest[key] == value,
                'Candidate signed-build provenance differs: ' + key)
    plan = {key: descriptor[key] for key in ('application_roles', 'instrumentation_roles', 'protocol')}
    validate_artifacts(build_manifest, 'release', manifest['version'], plan, signing='stable')
    validate_debug(Path(destination) / ARCHIVE_FILE, {key: build_manifest[key] for key in BINDING_KEYS}, build_manifest['debug_test_evidence'])
    require(manifest.get('apks') == build_manifest['artifacts'] and build_manifest.get('test_scope') == 'full-debug-once',
            'Candidate package inventory or full Debug test scope differs from the producing build')
    package_identity(manifest['apks'], manifest['protocol'])
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
