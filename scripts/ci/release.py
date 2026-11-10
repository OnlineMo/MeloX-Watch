"""Owner releases use a tag-only snapshot; development branch versions never change."""
import argparse
import json
import os
import re
import subprocess

from check import ROOT, check, properties
from version_policy import authorize_release, next_release


def git(*args):
    return subprocess.run(['git', *args], cwd=ROOT, check=True, text=True, capture_output=True).stdout.strip()


def prepare():
    authorize_release()
    check(require_android=True)
    readiness = json.loads((ROOT / '.ci/release-readiness.json').read_text(encoding='utf-8'))
    required = {'P0_device_and_login', 'P1_playback_foundation', 'P2_online_features', 'P3_round_watch_mvp',
                'watch_x2_background_audio', 'privacy_and_source_licenses', 'install_and_known_limitations_docs',
                'branch_and_tag_protections', 'signing_backup_verified'}
    if readiness.get('owner_approved') is not True or set(readiness.get('gates', {})) != required or not all(value is True for value in readiness['gates'].values()):
        raise SystemExit('First-release gates are incomplete; no release is created')
    report = (ROOT / readiness['device_report']).resolve()
    if not report.is_relative_to(ROOT / 'docs') or not report.is_file():
        raise SystemExit('A committed local device validation report is required')
    requested = os.environ['RELEASE_VERSION']
    if requested == '0.1.0' and os.environ.get('PRERELEASE') != 'true':
        raise SystemExit('The first 0.1.0 release must be a prerelease')
    git('fetch', 'origin', 'main', 'contribution', '--tags')
    if git('rev-parse', 'HEAD') != git('rev-parse', 'origin/main'):
        raise SystemExit('main advanced after dispatch; rerun against its current head')
    baseline = (ROOT / 'version.properties').read_bytes()
    if git('show', 'origin/contribution:version.properties') != baseline.decode().strip():
        raise SystemExit('contribution version differs from the immutable development version')
    history = []
    for tag in git('tag', '--list', 'v*').splitlines():
        if not re.fullmatch(r'v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', tag):
            continue
        text = git('show', tag + ':version.properties')
        values = dict(line.split('=', 1) for line in text.splitlines() if line and not line.startswith('#'))
        if values.get('versionName') != tag[1:]:
            raise SystemExit('Release tag metadata mismatch: ' + tag)
        history.append((values['versionName'], int(values['versionCode'])))
    code = next_release(requested, history)
    if git('tag', '--list', 'v' + requested):
        raise SystemExit('Release tag already exists; recover its existing artifacts, never overwrite it')
    git('config', 'user.name', 'OnlineMo')
    git('config', 'user.email', '143324986+OnlineMo@users.noreply.github.com')
    git('switch', '--detach')
    (ROOT / 'version.properties').write_text('# Release snapshot only; never merge this commit into development branches.\n'
                                            f'versionName={requested}\nversionCode={code}\n', encoding='utf-8')
    git('add', 'version.properties')
    git('commit', '-m', f'chore(release): v{requested}')
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as stream:
        stream.write(f'version={requested}\nrelease-sha={git("rev-parse", "HEAD")}\n')


def publish():
    authorize_release()
    version = properties(ROOT / 'version.properties')['versionName']
    release_sha = git('rev-parse', 'HEAD')
    source_sha = git('rev-parse', 'HEAD^')
    if source_sha != os.environ['GITHUB_SHA']:
        raise SystemExit('Release must be a single snapshot commit on the dispatched main commit')
    if git('diff', '--name-only', source_sha, release_sha) != 'version.properties':
        raise SystemExit('The release snapshot may change only version.properties')
    manifest = json.loads((ROOT / 'artifacts/build-manifest.json').read_text(encoding='utf-8'))
    if manifest['build_sha'] != release_sha or manifest['source_sha'] != source_sha or manifest['mode'] != 'release':
        raise SystemExit('Release artifact does not match its source/snapshot commits')
    if git('ls-remote', '--tags', 'origin', 'refs/tags/v' + version):
        raise SystemExit('Tag already exists; do not overwrite it')
    git('tag', '-a', 'v' + version, release_sha, '-m', 'MeloX Watch v' + version)
    # Explicit single refspec: never push HEAD, main, contribution, --all, or --mirror.
    git('push', 'origin', 'refs/tags/v' + version + ':refs/tags/v' + version)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=['prepare', 'publish'])
    args = parser.parse_args()
    prepare() if args.operation == 'prepare' else publish()
