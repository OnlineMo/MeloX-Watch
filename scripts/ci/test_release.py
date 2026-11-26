"""CI-02/03 的 Git 对象、证据、原字节恢复测试；不编译、不签名、不访问远端。"""
import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import call, patch

import candidate
import release
from ci_common import OWNER, OWNER_EMAIL, git
from release import publish_assets, validate_release_target
from release_evidence import (GATES, validate_approval, validate_approval_history,
                              validate_branch_protection, validate_graphql_protection)


class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.identity = dict(os.environ, GIT_AUTHOR_NAME=OWNER, GIT_AUTHOR_EMAIL=OWNER_EMAIL,
                             GIT_COMMITTER_NAME=OWNER, GIT_COMMITTER_EMAIL=OWNER_EMAIL)
        self.command('init', '-b', 'main')
        self.write('version.properties', 'versionName=0.0.0\nversionCode=0\n')
        self.write('build-input.txt', 'source\n')
        self.commit('chore: source fixture')
        self.source = self.command('rev-parse', 'HEAD')
        self.write('version.properties', 'versionName=0.1.0\nversionCode=2\n')
        self.commit('chore(release): v0.1.0')
        self.snapshot = self.command('rev-parse', 'HEAD')
        self.descriptor = {'source_sha': self.source, 'snapshot_sha': self.snapshot,
                           'main_commit_count': 1, 'previous_max_code': 0,
                           'version': {'versionName': '0.1.0', 'versionCode': '2'}}

    def tearDown(self):
        self.temporary.cleanup()

    def command(self, *args):
        return git(*args, cwd=self.root, env=self.identity)

    def write(self, name, text):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding='utf-8')

    def commit(self, message):
        self.command('add', '.')
        self.command('-c', 'commit.gpgsign=false', 'commit', '-m', message)

    def test_original_snapshot_and_complete_counts_are_checked(self):
        candidate.validate_snapshot(self.descriptor, self.root)
        for changes in [{'main_commit_count': 2}, {'previous_max_code': 7}, {'source_sha': self.snapshot},
                        {'version': {'versionName': '0.1.0', 'versionCode': '3'}}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                candidate.validate_snapshot(self.descriptor | changes, self.root)

    def test_bundle_restores_exact_original_commit(self):
        bundle = self.root / 'snapshot.bundle'
        self.command('bundle', 'create', str(bundle), 'HEAD')
        restored = self.root / 'restored'
        git('init', str(restored), cwd=self.root)
        git('bundle', 'unbundle', str(bundle), cwd=restored)
        candidate.validate_snapshot(self.descriptor, restored)
        self.assertEqual(git('rev-list', '--count', self.snapshot, cwd=restored), '2')

    def test_reports_do_not_change_frozen_code_and_reverted_input_invalidates(self):
        self.command('switch', '--detach', self.source)
        self.write('docs/releases/0.1.0/report.md', 'Synthetic policy fixture, not device evidence.\n')
        self.commit('docs: report fixture')
        approval = self.command('rev-parse', 'HEAD')
        validate_approval_history(self.source, approval, '0.1.0', self.root)
        candidate.validate_snapshot(self.descriptor, self.root)
        self.write('build-input.txt', 'changed\n')
        self.commit('chore: change input fixture')
        self.write('build-input.txt', 'source\n')
        self.commit('chore: restore input fixture')
        with self.assertRaisesRegex(ValueError, 'Build input changed'):
            validate_approval_history(self.source, self.command('rev-parse', 'HEAD'), '0.1.0', self.root)

    def test_wrong_version_reports_and_unrelated_history_are_rejected(self):
        self.command('switch', '--detach', self.source)
        self.write('docs/releases/0.2.0/report.md', 'fixture\n')
        self.commit('docs: other version fixture')
        with self.assertRaises(ValueError):
            validate_approval_history(self.source, self.command('rev-parse', 'HEAD'), '0.1.0', self.root)


class AcceptanceTests(unittest.TestCase):
    def test_graphql_rule_requires_exact_branch_all_controls_and_app_identity(self):
        rule = {'pattern': 'main', 'isAdminEnforced': True, 'allowsForcePushes': False, 'allowsDeletions': False,
                'requiresConversationResolution': True, 'requiresApprovingReviews': True,
                'requiredApprovingReviewCount': 0, 'requiresCodeOwnerReviews': False,
                'requireLastPushApproval': False, 'requiresStatusChecks': True, 'requiresStrictStatusChecks': True,
                'requiredStatusChecks': [{'context': name, 'app': {'databaseId': 15368, 'slug': 'github-actions'}}
                                         for name in ('cloud-gate', 'pr-policy')],
                'bypassPullRequestAllowances': {'totalCount': 0}, 'bypassForcePushAllowances': {'totalCount': 0},
                'branchProtectionRuleConflicts': {'totalCount': 0}}
        validate_graphql_protection(rule, 'main', 15368)
        for changes in [{'pattern': '*'}, {'requiresApprovingReviews': False}, {'requiredApprovingReviewCount': False},
                        {'bypassForcePushAllowances': {'totalCount': 1}}, {'requiredStatusChecks': []},
                        {'branchProtectionRuleConflicts': {'totalCount': 1}}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_graphql_protection(rule | changes, 'main', 15368)
        with self.assertRaises(ValueError):
            validate_graphql_protection(None, 'main', 15368)

    def test_all_true_without_candidate_or_real_report_is_rejected(self):
        identity = {'version': {'versionName': '0.1.0'}, 'apk_sha256': 'a' * 64}
        readiness = {'schema_version': 2, 'candidate': None, 'owner_approved': True,
                     'gates': dict.fromkeys(GATES, True)}
        with self.assertRaisesRegex(ValueError, 'binding'):
            validate_approval(readiness, {}, identity, 'a' * 40)
        readiness['candidate'] = identity
        with self.assertRaisesRegex(ValueError, 'different candidate'):
            validate_approval(readiness, {'candidate': {}}, identity, 'a' * 40)

    def test_historical_gate_names_cannot_bypass_schema_migration(self):
        identity = {'version': {'versionName': '0.1.0'}, 'apk_sha256': 'a' * 64}
        gates = dict.fromkeys(GATES, True)
        gates['watch_x2_background_audio'] = gates.pop('reference_device_background_audio')
        with self.assertRaisesRegex(ValueError, 'gates'):
            validate_approval({'schema_version': 2, 'candidate': identity, 'owner_approved': True, 'gates': gates},
                              {}, identity, 'a' * 40)

    def test_protection_requires_real_app_binding_strict_and_no_bypass(self):
        valid = {'enforce_admins': {'enabled': True}, 'required_conversation_resolution': {'enabled': True},
                 'allow_force_pushes': {'enabled': False}, 'allow_deletions': {'enabled': False},
                 'required_status_checks': {'strict': True, 'checks': [{'context': name, 'app_id': 15368}
                                           for name in ('cloud-gate', 'pr-policy')]},
                 'required_pull_request_reviews': {'required_approving_review_count': 0,
                     'require_code_owner_reviews': False, 'require_last_push_approval': False,
                     'bypass_pull_request_allowances': {'users': [], 'teams': [], 'apps': []}}}
        validate_branch_protection(valid, 15368)
        broken = []
        for key in ('enforce_admins', 'required_conversation_resolution', 'allow_force_pushes', 'allow_deletions'):
            value = copy.deepcopy(valid)
            value[key]['enabled'] = not value[key]['enabled']
            broken.append(value)
        value = copy.deepcopy(valid)
        value['required_status_checks']['strict'] = False
        broken.append(value)
        value = copy.deepcopy(valid)
        value['required_status_checks']['checks'][0]['app_id'] = -1
        broken.append(value)
        value = copy.deepcopy(valid)
        value['required_pull_request_reviews']['bypass_pull_request_allowances']['users'] = ['owner']
        broken.append(value)
        for value in broken:
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_branch_protection(value, 15368)


class CandidatePreparationIdentityTests(unittest.TestCase):
    SOURCE = 'a' * 40
    ENV = {'GITHUB_ACTIONS': 'true', 'CI': 'true', 'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch',
           'GITHUB_REPOSITORY_OWNER': 'OnlineMo', 'GITHUB_REPOSITORY_OWNER_ID': '143324986',
           'GITHUB_ACTOR': 'OnlineMo', 'GITHUB_ACTOR_ID': '143324986',
           'GITHUB_TRIGGERING_ACTOR': 'OnlineMo', 'GITHUB_WORKFLOW': 'Owner release',
           'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/main',
           'GITHUB_SHA': SOURCE, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2',
           'CANDIDATE_OPERATION': 'prepare-candidate'}

    def run_metadata(self):
        return {'id': 123, 'repository': {'full_name': 'OnlineMo/MeloX-Watch',
                'owner': {'login': 'OnlineMo', 'id': 143324986}},
                'run_attempt': 2, 'head_sha': self.SOURCE, 'head_branch': 'main',
                'path': '.github/workflows/release.yml', 'event': 'workflow_dispatch',
                'actor': {'login': 'OnlineMo', 'id': 143324986},
                'triggering_actor': {'login': 'OnlineMo', 'id': 143324986}, 'conclusion': None}

    def assert_rejected_before_snapshot(self, metadata):
        with patch.dict(os.environ, self.ENV, clear=True), patch('check.check'), \
                patch.object(candidate, 'project_plan', return_value={}), \
                patch.object(candidate, 'GitHub') as client_type, patch.object(candidate, 'git') as git_boundary:
            client_type.return_value.get.return_value = metadata
            with self.assertRaises(ValueError):
                candidate.prepare()
            client_type.return_value.get.assert_called_once_with('/actions/runs/123/attempts/2')
            git_boundary.assert_not_called()

    def test_valid_owner_attempt_is_read_before_any_snapshot_git_mutation(self):
        with patch.dict(os.environ, self.ENV, clear=True), patch('check.check'), \
                patch.object(candidate, 'project_plan', return_value={}), \
                patch.object(candidate, 'GitHub') as client_type, \
                patch.object(candidate, 'git', return_value='b' * 40) as git_boundary:
            client_type.return_value.get.return_value = self.run_metadata()
            with self.assertRaisesRegex(ValueError, 'checkout differs'):
                candidate.prepare()
            client_type.return_value.get.assert_called_once_with('/actions/runs/123/attempts/2')
            git_boundary.assert_called_once_with('rev-parse', 'HEAD')

    def test_matching_triggering_login_with_foreign_id_never_creates_a_snapshot(self):
        metadata = self.run_metadata()
        metadata['triggering_actor']['id'] = 1
        self.assert_rejected_before_snapshot(metadata)

    def test_other_workflow_event_branch_or_attempt_cannot_prepare_a_snapshot(self):
        for field, value in (('id', 124), ('id', 123.0), ('run_attempt', 3),
                             ('head_sha', 'b' * 40), ('head_branch', 'contribution'),
                             ('path', '.github/workflows/cloud-build.yml'), ('event', 'push')):
            with self.subTest(field=field):
                self.assert_rejected_before_snapshot(self.run_metadata() | {field: value})

    def test_same_repository_and_logins_with_replaced_owner_or_actor_are_rejected(self):
        for field in ('actor', 'repository-owner'):
            metadata = self.run_metadata()
            record = metadata['repository']['owner'] if field == 'repository-owner' else metadata[field]
            record['id'] = 1
            with self.subTest(field=field):
                self.assert_rejected_before_snapshot(metadata)


class PublicationAttemptIdentityTests(unittest.TestCase):
    APPROVAL = 'a' * 40
    ENV = {'GITHUB_ACTIONS': 'true', 'CI': 'true', 'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch',
           'GITHUB_REPOSITORY_OWNER': 'OnlineMo', 'GITHUB_REPOSITORY_OWNER_ID': '143324986',
           'GITHUB_ACTOR': 'OnlineMo', 'GITHUB_ACTOR_ID': '143324986',
           'GITHUB_TRIGGERING_ACTOR': 'OnlineMo', 'GITHUB_WORKFLOW': 'Owner release',
           'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/main',
           'GITHUB_SHA': APPROVAL, 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2',
           'CANDIDATE_OPERATION': 'publish-candidate'}

    def run_metadata(self):
        return {'id': 123, 'repository': {'full_name': 'OnlineMo/MeloX-Watch',
                'owner': {'login': 'OnlineMo', 'id': 143324986}},
                'run_attempt': 2, 'head_sha': self.APPROVAL, 'head_branch': 'main',
                'path': '.github/workflows/release.yml', 'event': 'workflow_dispatch',
                'actor': {'login': 'OnlineMo', 'id': 143324986},
                'triggering_actor': {'login': 'OnlineMo', 'id': 143324986}, 'conclusion': None}

    def assert_rejected_before_candidate_or_publication(self, metadata):
        with patch.dict(os.environ, self.ENV, clear=True), patch.object(release, 'GitHub') as client_type, \
                patch.object(release, 'git', return_value=self.APPROVAL) as git_boundary, \
                patch.object(release, 'fetch_candidate') as fetch, \
                patch.object(release, 'push_new_tag') as tag, patch.object(release, 'publish_assets') as assets:
            client = client_type.return_value
            client.get.return_value = metadata
            with self.assertRaises(ValueError):
                release.publish(987, 1, 654, self.APPROVAL)
            client.get.assert_called_once_with('/actions/runs/123/attempts/2')
            git_boundary.assert_called_once_with('rev-parse', 'HEAD')
            fetch.assert_not_called()
            tag.assert_not_called()
            assets.assert_not_called()
            client.request.assert_not_called()

    def test_valid_in_progress_publication_checks_its_own_attempt_before_fetching_old_candidate(self):
        with patch.dict(os.environ, self.ENV, clear=True), patch.object(release, 'GitHub') as client_type, \
                patch.object(release, 'git', return_value=self.APPROVAL) as git_boundary, \
                patch.object(release, 'fetch_candidate', side_effect=ValueError('fixture old candidate boundary')) as fetch, \
                patch.object(release, 'push_new_tag') as tag, patch.object(release, 'publish_assets') as assets:
            client = client_type.return_value
            client.get.side_effect = [self.run_metadata(), {'object': {'sha': self.APPROVAL}}]
            with self.assertRaisesRegex(ValueError, 'fixture old candidate boundary'):
                release.publish(987, 1, 654, self.APPROVAL)
            self.assertEqual(client.get.call_args_list, [call('/actions/runs/123/attempts/2'), call('/git/ref/heads/main')])
            git_boundary.assert_called_once_with('rev-parse', 'HEAD')
            fetch.assert_called_once_with(client, 987, 1, 654, release.ROOT / 'accepted-candidate')
            tag.assert_not_called()
            assets.assert_not_called()
            client.request.assert_not_called()

    def test_matching_current_triggering_login_with_foreign_or_noninteger_id_never_writes_a_release(self):
        for value in (1, 143324986.0, '143324986', True, None):
            metadata = self.run_metadata()
            metadata['triggering_actor']['id'] = value
            with self.subTest(value=value):
                self.assert_rejected_before_candidate_or_publication(metadata)

    def test_current_repository_owner_and_original_actor_ids_are_checked_separately(self):
        for field in ('actor', 'repository-owner'):
            metadata = self.run_metadata()
            record = metadata['repository']['owner'] if field == 'repository-owner' else metadata[field]
            record['id'] = 1
            with self.subTest(field=field):
                self.assert_rejected_before_candidate_or_publication(metadata)

    def test_current_run_id_attempt_approval_workflow_event_and_branch_cannot_be_substituted(self):
        for field, value in (('id', 124), ('id', 123.0), ('run_attempt', 3),
                             ('head_sha', 'b' * 40), ('head_branch', 'contribution'),
                             ('path', '.github/workflows/cloud-build.yml'), ('event', 'push')):
            with self.subTest(field=field):
                self.assert_rejected_before_candidate_or_publication(self.run_metadata() | {field: value})


class RecoveryTests(unittest.TestCase):
    def test_same_snapshot_recovery_and_stale_candidate_rules(self):
        manifest = {'version': {'versionName': '0.2.0', 'versionCode': '20'}, 'snapshot_sha': 'a' * 40}
        self.assertTrue(validate_release_target(manifest, [('0.1.0', 10)], {'v0.1.0': 'b' * 40}))
        self.assertFalse(validate_release_target(manifest, [('0.1.0', 10), ('0.2.0', 20)], {'v0.2.0': 'a' * 40}))
        with self.assertRaisesRegex(ValueError, 'never overwrite'):
            validate_release_target(manifest, [('0.2.0', 20)], {'v0.2.0': 'b' * 40})
        with self.assertRaisesRegex(ValueError, 'stale'):
            validate_release_target(manifest, [('0.2.0', 20), ('0.3.0', 21)], {'v0.2.0': 'a' * 40})

    def test_recovery_only_uploads_missing_identical_assets(self):
        with tempfile.TemporaryDirectory() as temporary:
            paths = {name: Path(temporary) / name for name in ('watch-release.apk', 'phone-release.apk', 'snapshot.bundle')}
            for name, path in paths.items():
                path.write_bytes(('original fixture ' + name).encode())

            class Client:
                def __init__(self):
                    self.assets = {'watch-release.apk': paths['watch-release.apk'].read_bytes()}
                    self.calls = []

                def pages(self, path):
                    if path == '/releases':
                        return [{'id': 12, 'tag_name': 'v0.1.0', 'prerelease': True, 'draft': True}]
                    return [{'id': index, 'name': name} for index, name in enumerate(self.assets, start=1)]

                def download_release_asset(self, asset_id):
                    return list(self.assets.values())[asset_id - 1]

                def request(self, method, path, data, **kwargs):
                    self.calls.append((method, path))
                    if method == 'POST':
                        self.assets[path.split('?name=')[1]] = data

            client = Client()
            publish_assets(client, 'v0.1.0', 'a' * 40, paths, True)
            posts = [path for method, path in client.calls if method == 'POST']
            self.assertEqual(posts, ['/repos/OnlineMo/MeloX-Watch/releases/12/assets?name=phone-release.apk',
                                     '/repos/OnlineMo/MeloX-Watch/releases/12/assets?name=snapshot.bundle'])
            client.calls.clear()
            publish_assets(client, 'v0.1.0', 'a' * 40, paths, True)
            self.assertFalse(any(method == 'POST' for method, _ in client.calls))
            client.assets['watch-release.apk'] = b'different bytes'
            client.calls.clear()
            with self.assertRaisesRegex(ValueError, 'never overwrite'):
                publish_assets(client, 'v0.1.0', 'a' * 40, paths, True)
            self.assertEqual(client.calls, [])


if __name__ == '__main__':
    unittest.main()
