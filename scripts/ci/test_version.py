import unittest
from unittest.mock import patch
from version_policy import authorize_release, next_release


class VersionPolicyTests(unittest.TestCase):
    def test_versions_increment_from_all_tags_not_development_sentinel(self):
        self.assertEqual(next_release('0.1.0', []), 1)
        self.assertEqual(next_release('0.3.0', [('0.2.0', 4), ('0.1.0', 1)]), 5)
        self.assertEqual(next_release('0.3.0', [('0.2.0', 4)], {'versionName':'0.3.0','versionCode':'5'}),5)
        self.assertEqual(next_release('0.4.0', [('0.2.0', 4)], {'versionName':'0.3.0','versionCode':'5'}),6)
        with self.assertRaises(ValueError):
            next_release('0.3.0', [('0.2.0', 4)], {'versionName':'0.4.0','versionCode':'5'})
        for version in ['0.2.0', '0.1.0', 'v0.3.0', '0.3.0-beta', '00.3.0']:
            with self.assertRaises(ValueError):
                next_release(version, [('0.2.0', 4)])
        with self.assertRaises(ValueError):
            next_release('1.0.0', [('0.1.0', 2100000000)])
        with self.assertRaises(ValueError):
            next_release('1.0.0', [('0.1.0', 1), ('0.2.0', 1)])

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
