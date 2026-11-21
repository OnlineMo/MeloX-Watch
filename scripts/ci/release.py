"""仅发布已验原候选。该入口不编译、不重签、不重新创建版本快照。"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import urllib.parse

from apk_verify import build_tools, certificate, inspect_apk
from artifact_io import GitHub
from candidate import CANDIDATE_FILES, fetch_candidate, import_snapshot, release_history
from ci_common import (ROOT, OWNER, OWNER_EMAIL, REPOSITORY, expected_identity, git,
                       no_signing_secrets, properties, require, sha256, trusted_context, write_json)
from release_evidence import (candidate_identity, report_file, validate_approval,
                              validate_approval_history, verify_protections)
from version_policy import validate_frozen_version
from package_contract import native_abis


def validate_release_target(manifest, history, tags):
    version = manifest['version']['versionName']
    tag = 'v' + version
    if tag in tags:
        require(tags[tag] == manifest['snapshot_sha'], 'Existing tag points to different objects; never overwrite it')
        history = [(name, code) for name, code in history if name != version]
    validate_frozen_version(manifest['version'], history)
    return tag not in tags


def verify_existing_assets(client, release_id, files):
    existing = client.pages(f'/releases/{release_id}/assets')
    require(len({asset['name'] for asset in existing}) == len(existing), 'Duplicate release asset names')
    require({asset['name'] for asset in existing} <= set(files), 'Unexpected existing asset; owner recovery review required')
    for asset in existing:
        content = client.download(f'/repos/{REPOSITORY}/releases/assets/{asset["id"]}')
        require(hashlib.sha256(content).hexdigest() == sha256(files[asset['name']]),
                'Existing asset has different bytes; never overwrite it')
    return {asset['name'] for asset in existing}


def publish_assets(client, tag, snapshot, files, prerelease):
    releases = [entry for entry in client.pages('/releases') if entry['tag_name'] == tag]
    require(len(releases) <= 1, 'Multiple releases refer to this tag')
    if releases:
        release = releases[0]
        require(release['prerelease'] is prerelease, 'Existing release prerelease state differs')
    else:
        release = client.request('POST', f'/repos/{REPOSITORY}/releases',
            {'tag_name': tag, 'target_commitish': snapshot, 'name': 'MeloX Watch ' + tag,
             'draft': True, 'prerelease': prerelease,
             'body': 'Accepted candidate bytes. Provenance and acceptance are recorded in publication-record.json.'})
    present = verify_existing_assets(client, release['id'], files)
    for name, path in files.items():
        if name in present:
            continue
        encoded_name = urllib.parse.quote(name, safe='')
        client.request('POST', f'/repos/{REPOSITORY}/releases/{release["id"]}/assets?name={encoded_name}',
                       Path(path).read_bytes(), binary=True, upload=True)
    require(verify_existing_assets(client, release['id'], files) == set(files), 'Release assets are incomplete')
    if release['draft']:
        client.request('PATCH', f'/repos/{REPOSITORY}/releases/{release["id"]}', {'draft': False})
    return release['id']


def push_new_tag(tag, snapshot, client):
    require(git('remote', 'get-url', 'origin') in (f'https://github.com/{REPOSITORY}', f'https://github.com/{REPOSITORY}.git'),
            'Unexpected publication remote')
    environment = dict(os.environ, GIT_COMMITTER_NAME=OWNER, GIT_COMMITTER_EMAIL=OWNER_EMAIL)
    git('-c', 'tag.gpgsign=false', 'tag', '-a', tag, snapshot, '-m', 'MeloX Watch ' + tag, env=environment)
    # GITHUB_TOKEN 只在发布 job 内短期有效，不写入 .git/config 或命令参数。
    authentication = base64.b64encode(('x-access-token:' + client.token).encode()).decode()
    environment.update(GIT_CONFIG_COUNT='1', GIT_CONFIG_KEY_0='http.https://github.com/.extraheader',
                       GIT_CONFIG_VALUE_0='AUTHORIZATION: basic ' + authentication)
    git('push', 'origin', f'refs/tags/{tag}:refs/tags/{tag}', env=environment)


def publish(run_id, attempt, artifact_id, approval):
    trusted_context('Owner release')
    no_signing_secrets()
    require(os.environ.get('CANDIDATE_OPERATION') == 'publish-candidate', 'Explicit publish-candidate operation required')
    require(approval == os.environ['GITHUB_SHA'] == git('rev-parse', 'HEAD'), 'Publication must use the selected approval main SHA')
    client = GitHub()
    require(client.get('/git/ref/heads/main')['object']['sha'] == approval, 'main advanced after dispatch')
    directory = ROOT / 'accepted-candidate'
    manifest, provenance = fetch_candidate(client, run_id, attempt, artifact_id, directory)
    import_snapshot(directory, manifest)
    version = manifest['version']['versionName']
    identity = candidate_identity(manifest, provenance)
    validate_approval_history(manifest['source_sha'], approval, version)
    readiness = json.loads((ROOT / '.ci/release-readiness.json').read_text(encoding='utf-8'))
    report_path = report_file(ROOT, readiness.get('device_report', ''), version, approval)
    report = json.loads(report_path.read_text(encoding='utf-8'))
    evidence = validate_approval(readiness, report, identity, approval)
    confirmation = f'{run_id}/{attempt}/{artifact_id}:{identity["package_set_sha256"]}'
    require(os.environ.get('CANDIDATE_CONFIRMATION') == confirmation, 'Owner must confirm the exact accepted candidate package-set hash')
    tools = build_tools(properties(ROOT / '.ci/toolchain.properties'))
    pin = json.loads((ROOT / '.ci/signing-certificates.json').read_text(encoding='utf-8'))['release']
    for entry in identity['packages']:
        apk = directory / entry['file']
        require(sha256(apk) == entry['sha256'] and native_abis(apk) == entry['abis'], 'Accepted package bytes or ABIs differ')
        inspect_apk(apk, tools, expected_identity('release', manifest['version'], entry['role']))
        require(certificate(apk, tools, pin) == entry['certificate_sha256'], 'Accepted package certificate mismatch')
    require(version != '0.1.0' or manifest['prerelease'] is True, '0.1.0 must remain a prerelease')
    tag = 'v' + version
    protection = verify_protections(client, approval, tag)
    history, tags = release_history(client)
    create_tag = validate_release_target(manifest, history, tags)
    require(client.get('/git/ref/heads/main')['object']['sha'] == approval, 'main changed during publication verification')
    if create_tag:
        push_new_tag(tag, manifest['snapshot_sha'], client)
    # 此记录在重试时保持确定字节；原候选 manifest/校验表完全不改写。
    publication = {'schema_version': 1, 'candidate': identity, 'approval_sha': approval,
                   'device_report': readiness['device_report'], 'device_report_sha256': sha256(report_path),
                   'evidence': evidence, 'approved_at': report['publication_approval']['approved_at'],
                   'protection': protection, 'tag': tag, 'prerelease': manifest['prerelease']}
    publication_path = ROOT / 'publication-record.json'
    write_json(publication_path, publication)
    files = {name: directory / name for name in sorted(CANDIDATE_FILES)}
    files['publication-record.json'] = publication_path
    release_id = publish_assets(client, tag, manifest['snapshot_sha'], files, manifest['prerelease'])
    _, after_tags = release_history(client)
    require(after_tags.get(tag) == manifest['snapshot_sha'], 'Published tag identity changed')
    verify_protections(client, approval, tag)
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as summary:
        summary.write(f'Published original candidate `{run_id}/{attempt}/{artifact_id}` as `{tag}` (Release {release_id}). '
                      f'Watch/phone package-set SHA-256: `{identity["package_set_sha256"]}`. No compilation or signing was performed.\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-id', required=True, type=int)
    parser.add_argument('--attempt', required=True, type=int)
    parser.add_argument('--artifact-id', required=True, type=int)
    parser.add_argument('--approval-sha', required=True)
    args = parser.parse_args()
    publish(args.run_id, args.attempt, args.artifact_id, args.approval_sha)
