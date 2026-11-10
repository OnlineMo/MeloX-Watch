"""把候选验收与真实保护绑定；readiness 的 true 不能独立授权发布。"""
import datetime
import fnmatch
import json
from pathlib import Path

from ci_common import ROOT, OWNER, git, require, sha256

GATES = {'P0_device_and_login', 'P1_playback_foundation', 'P2_online_features', 'P3_watch_mvp',
         'reference_device_background_audio', 'privacy_and_source_licenses',
         'install_and_known_limitations_docs', 'branch_and_tag_protections', 'signing_backup_verified'}


def candidate_identity(manifest, provenance):
    return {'run_id': provenance['run_id'], 'run_attempt': provenance['run_attempt'],
            'artifact_id': provenance['id'], 'archive_sha256': provenance['sha256'],
            'source_sha': manifest['source_sha'], 'snapshot_sha': manifest['snapshot_sha'],
            'version': manifest['version'], 'apk_sha256': manifest['apk']['sha256'],
            'certificate_sha256': manifest['apk']['certificate_sha256']}


def approved_paths(version):
    return {'docs/13-仓库状态.md', '.ci/release-readiness.json'}, f'docs/releases/{version}/'


def validate_approval_history(source, approval, version, cwd=ROOT):
    require(git('merge-base', source, approval, cwd=cwd) == source, 'Candidate source is not an ancestor of approval')
    exact, prefix = approved_paths(version)
    for commit in git('rev-list', approval, '^' + source, cwd=cwd).splitlines():
        changes = git('diff-tree', '-m', '-r', '-z', '--no-commit-id', '--no-renames', '--name-only', commit, cwd=cwd)
        # 逐提交检查，拒绝先改代码再还原；仅比较最终 tree 会漏过这种情况。
        for path in filter(None, changes.split('\0')):
            require(path in exact or path.startswith(prefix), 'Build input changed after candidate preparation')


def report_file(root, relative, version, approval):
    prefix = f'docs/releases/{version}/'
    require(isinstance(relative, str) and relative.startswith(prefix), 'Evidence must be in this version report directory')
    path = (root / relative).resolve()
    require(path.is_relative_to((root / prefix).resolve()) and path.is_file() and not path.is_symlink(),
            'Evidence path is invalid or missing')
    tree = git('ls-tree', approval, '--', relative, cwd=root)
    require(tree.startswith('100644 blob '), 'Evidence must be a committed ordinary file')
    return path


def validate_approval(readiness, report, identity, approval, root=ROOT):
    version = identity['version']['versionName']
    require(readiness.get('schema_version') == 2 and readiness.get('candidate') == identity, 'Readiness candidate binding missing')
    require(readiness.get('owner_approved') is True, 'Explicit candidate publication approval is missing')
    require(set(readiness.get('gates', {})) == GATES and all(value is True for value in readiness['gates'].values()),
            'Readiness gates are incomplete')
    require(report.get('schema_version') == 1 and report.get('candidate') == identity, 'Evidence identifies a different candidate')
    decision = report.get('publication_approval', {})
    require(decision.get('owner') == OWNER and decision.get('approved') is True, 'Owner decision missing')
    try:
        approved_at = datetime.datetime.fromisoformat(decision['approved_at'].replace('Z', '+00:00'))
    except (KeyError, ValueError, TypeError):
        raise ValueError('Owner approval needs an explicit timestamp') from None
    require(approved_at.tzinfo is not None and approved_at <= datetime.datetime.now(datetime.timezone.utc), 'Invalid owner approval time')
    evidence = report.get('gate_evidence', {})
    require(set(evidence) == GATES, 'Per-gate evidence is incomplete')
    hashes = {}
    for gate, record in evidence.items():
        require(record.get('status') == 'passed' and isinstance(record.get('reports'), list) and record['reports'],
                'Gate lacks accepted evidence: ' + gate)
        for entry in record['reports']:
            path = report_file(root, entry['path'], version, approval)
            require(sha256(path) == entry['sha256'], 'Evidence file hash mismatch')
            hashes[entry['path']] = entry['sha256']
    background = report.get('background_audio', {})
    require(background.get('validation_kind') == 'physical-device' and background.get('screen_off') is True,
            'Physical-device screen-off evidence is required')
    require(background.get('apk_sha256') == identity['apk_sha256'], 'Background evidence uses a different APK')
    require(isinstance(background.get('reference_device'), str) and bool(background['reference_device'].strip()),
            'Reference device evidence is missing')
    for key, minimum in [('online_minutes', 60), ('local_minutes', 120)]:
        value = background.get(key)
        require(type(value) in (int, float) and minimum <= value < float('inf'), 'Required background duration missing: ' + key)
    return hashes


def validate_branch_protection(value, actions_app_id):
    for flag, required in [('enforce_admins', True), ('required_conversation_resolution', True),
                           ('allow_force_pushes', False), ('allow_deletions', False)]:
        require(value.get(flag, {}).get('enabled') is required, 'Branch protection mismatch: ' + flag)
    checks = value.get('required_status_checks') or {}
    require(checks.get('strict') is True, 'Strict required checks are missing')
    bound = {(item['context'], item.get('app_id')) for item in checks.get('checks', [])}
    require({('cloud-gate', actions_app_id), ('pr-policy', actions_app_id)} <= bound, 'Checks are not bound to GitHub Actions')
    reviews = value.get('required_pull_request_reviews')
    require(isinstance(reviews, dict) and reviews.get('required_approving_review_count') == 0, 'Required PR policy is missing')
    require(reviews.get('require_code_owner_reviews') is False and reviews.get('require_last_push_approval') is False,
            'Sole-owner PR review would be blocked')
    require(not any(reviews.get('bypass_pull_request_allowances', {}).values()), 'Permanent PR bypass is forbidden')


def verify_protections(client, approval, tag):
    repository = client.request('GET', '/repos/OnlineMo/MeloX-Watch')
    require(repository['private'] is False, 'Public visibility requires separate owner approval before publication')
    check_runs = client.pages('/commits/' + approval + '/check-runs', 'check_runs')
    genuine = [entry for entry in check_runs if entry['name'] == 'cloud-gate' and entry['app']['slug'] == 'github-actions']
    require(bool(genuine), 'Approval commit has no GitHub Actions cloud-gate')
    latest = max(genuine, key=lambda entry: entry['id'])
    require(latest['conclusion'] == 'success', 'Latest approval cloud-gate did not pass')
    actions_app_id = latest['app']['id']
    promotions = client.pages('/commits/' + approval + '/pulls')
    accepted = [entry for entry in promotions if entry.get('merged_at') and entry.get('merge_commit_sha') == approval
                and entry['base']['ref'] == 'main' and entry['head']['ref'] == 'contribution'
                and entry['user']['login'] == OWNER and entry['head']['repo']['full_name'] == 'OnlineMo/MeloX-Watch']
    require(bool(accepted), 'Approval SHA must be an owner contribution-to-main promotion')
    head_checks = client.pages('/commits/' + accepted[0]['head']['sha'] + '/check-runs', 'check_runs')
    policies = [entry for entry in head_checks if entry['name'] == 'pr-policy' and entry['app']['id'] == actions_app_id]
    require(bool(policies) and max(policies, key=lambda entry: entry['id'])['conclusion'] == 'success',
            'Approval promotion lacks a successful trusted PR policy')
    for branch in ('main', 'contribution'):
        validate_branch_protection(client.get('/branches/' + branch + '/protection'), actions_app_id)
    protected = False
    target = 'refs/tags/' + tag
    for item in client.pages('/rulesets?includes_parents=true'):
        value = client.get('/rulesets/' + str(item['id']))
        if value.get('target') != 'tag' or value.get('enforcement') != 'active':
            continue
        condition = value.get('conditions', {}).get('ref_name', {})
        includes = condition.get('include', [])
        excludes = condition.get('exclude', [])
        applies = any(pattern == '~ALL' or fnmatch.fnmatchcase(target, pattern) for pattern in includes)
        applies = applies and not any(fnmatch.fnmatchcase(target, pattern) for pattern in excludes)
        if not applies:
            continue
        kinds = {rule['type'] for rule in value['rules']}
        require('creation' not in kinds, 'Tag creation is blocked by an active ruleset')
        if {'update', 'deletion'} <= kinds and not value.get('bypass_actors'):
            protected = True
    require(protected, 'Immutable tag protection with no bypass is missing')
    return {'github_actions_app_id': actions_app_id, 'branches': ['main', 'contribution'], 'tag': tag}
