import unittest
from unittest.mock import patch
from version_policy import authorize_release, next_release, validate_frozen_version


class VersionPolicyTests(unittest.TestCase):
    def test_versions_increment_from_all_tags_not_development_sentinel(self):
        self.assertEqual(next_release('0.1.0', [], 10), 11)
        self.assertEqual(next_release('0.3.0', [('0.2.0', 40), ('0.1.0', 1)], 10), 41)
        self.assertEqual(next_release('0.3.0', [('0.2.0', 4)], 10, {'versionName':'0.3.0','versionCode':'5'}),11)
        with self.assertRaises(ValueError):
            next_release('0.4.0', [('0.2.0', 4)], 10, {'versionName':'0.3.0','versionCode':'50'})
        with self.assertRaises(ValueError):
            next_release('0.3.0', [('0.2.0', 4)], 10, {'versionName':'0.4.0','versionCode':'5'})
        for version in ['0.2.0', '0.1.0', 'v0.3.0', '0.3.0-beta', '00.3.0']:
            with self.assertRaises(ValueError):
                next_release(version, [('0.2.0', 4)], 10)
        with self.assertRaises(ValueError):
            next_release('1.0.0', [('0.1.0', 2100000000)], 10)
        with self.assertRaises(ValueError):
            next_release('1.0.0', [('0.1.0', 1), ('0.2.0', 1)], 10)
        for count in [0, -1, True, 2100000000]:
            with self.assertRaises(ValueError):
                next_release('0.1.0', [], count)

    def test_frozen_candidate_is_not_recounted_and_rejects_newer_release(self):
        candidate = {'versionName': '0.2.0', 'versionCode': '20'}
        validate_frozen_version(candidate, [('0.1.0', 19)])
        for history in [[('0.1.1', 20)], [('0.2.0', 19)], [('0.3.0', 21)]]:
            with self.subTest(history=history), self.assertRaises(ValueError):
                validate_frozen_version(candidate, history)
        for history in [[('bad', 1)], [('0.1.0', True)], [('0.1.0', 2), ('0.2.0', 1)]]:
            with self.subTest(history=history), self.assertRaises(ValueError):
                next_release('1.0.0', history, 10)

    def test_release_needs_owner_main_dispatch_and_cloud(self):
        valid = {'GITHUB_ACTIONS': 'true', 'CI': 'true', 'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch',
                 'GITHUB_ACTOR': 'OnlineMo', 'GITHUB_TRIGGERING_ACTOR': 'OnlineMo',
                 'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_WORKFLOW': 'Owner release'}
        with patch.dict('os.environ', valid, clear=True):
            authorize_release()
        for key in valid:
            with patch.dict('os.environ', {**valid, key: 'unauthorized'}, clear=True):
                with self.assertRaises(SystemExit):
                    authorize_release()


if __name__ == '__main__':
    unittest.main()
