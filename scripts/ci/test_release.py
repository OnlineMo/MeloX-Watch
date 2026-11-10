"""Exercise publication without network, credentials, Gradle, or actual Git mutations."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import release


class ReleasePublicationTests(unittest.TestCase):
    def run_publish(self, **overrides):
        calls = []
        results = {'rev-parse HEAD': 'release-sha', 'rev-parse HEAD^': 'source-sha',
                   'diff --name-only source-sha release-sha': 'version.properties',
                   'ls-remote --tags origin refs/tags/v0.1.0': ''}
        results.update(overrides)

        def fake_git(*args):
            calls.append(args)
            return results.get(' '.join(args), '')

        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'artifacts').mkdir()
            (root / 'version.properties').write_text('versionName=0.1.0\nversionCode=1\n')
            (root / 'artifacts/build-manifest.json').write_text(json.dumps({
                'build_sha': 'release-sha', 'source_sha': 'source-sha', 'mode': 'release'}))
            with patch.object(release, 'ROOT', root), patch.object(release, 'git', fake_git), \
                    patch.object(release, 'authorize_release'), patch.dict('os.environ', {'GITHUB_SHA': 'source-sha'}):
                release.publish()
        return calls

    def test_publication_pushes_only_new_tag(self):
        calls = self.run_publish()
        self.assertEqual([call for call in calls if call[0] == 'push'], [
            ('push', 'origin', 'refs/tags/v0.1.0:refs/tags/v0.1.0')])

    def test_existing_tag_is_never_overwritten(self):
        with self.assertRaises(SystemExit):
            self.run_publish(**{'ls-remote --tags origin refs/tags/v0.1.0': 'existing-sha'})

    def test_snapshot_cannot_modify_other_files_or_parent(self):
        for changes in [{'diff --name-only source-sha release-sha': 'version.properties\napp.kt'},
                        {'rev-parse HEAD^': 'unrelated-sha'}]:
            with self.assertRaises(SystemExit):
                self.run_publish(**changes)


if __name__ == '__main__':
    unittest.main()
