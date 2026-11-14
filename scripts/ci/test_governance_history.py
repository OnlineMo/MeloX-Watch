"""两轮提升/回同步及单独 Actions 维护必须保留提交祖先与精确版本字节。"""
import os
from pathlib import Path
import tempfile
import unittest

from ci_common import OWNER, OWNER_EMAIL, git


class GovernanceHistoryTests(unittest.TestCase):
    def test_two_merge_cycles_and_workflow_maintenance_do_not_allocate_sync_versions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            identity = dict(os.environ, GIT_AUTHOR_NAME=OWNER, GIT_AUTHOR_EMAIL=OWNER_EMAIL,
                            GIT_COMMITTER_NAME=OWNER, GIT_COMMITTER_EMAIL=OWNER_EMAIL)
            def command(*args):
                return git('-c', 'commit.gpgsign=false', *args, cwd=root, env=identity)
            def commit(name, value):
                (root / name).parent.mkdir(parents=True, exist_ok=True)
                (root / name).write_text(value, encoding='utf-8')
                command('add', '--', name)
                command('commit', '-m', 'chore: policy fixture')
            command('init', '-b', 'main')
            commit('version.properties', 'versionName=0.0.0\nversionCode=0\n')
            command('branch', 'contribution')
            previous_count = 1
            for round_number in (1, 2):
                command('switch', 'contribution')
                command('switch', '-c', 'topic-' + str(round_number))
                commit('version.properties', f'versionName=0.{round_number}.0\nversionCode={previous_count + 1}\n')
                proposal = command('rev-parse', 'HEAD')
                command('switch', 'contribution')
                command('merge', '--no-ff', 'topic-' + str(round_number), '-m', 'chore: integrate fixture')
                command('switch', 'main')
                command('merge', '--no-ff', 'contribution', '-m', 'chore: promote fixture')
                promoted = command('rev-parse', 'HEAD')
                version_blob = command('rev-parse', 'HEAD:version.properties')
                self.assertEqual(len(command('show', '-s', '--format=%P', promoted).split()), 2)
                self.assertEqual(command('merge-base', proposal, promoted), proposal)
                command('switch', 'contribution')
                command('merge', '--no-ff', 'main', '-m', 'chore: sync fixture')
                self.assertEqual(command('rev-parse', 'HEAD:version.properties'), version_blob)
                self.assertEqual(command('merge-base', promoted, 'HEAD'), promoted)
                previous_count = int(command('rev-list', '--count', 'HEAD'))
            # 模拟各长期分支单独落实同一受控维护；后续提升不携带受限路径差异。
            for branch in ('main', 'contribution'):
                command('switch', branch)
                commit('.github/workflows/fixture.yml', 'name: fixture\n')
            self.assertEqual(command('diff', 'main', 'contribution', '--', '.github/workflows'), '')
            command('switch', 'main')
            command('merge', '--no-ff', 'contribution', '-m', 'chore: promotion after maintenance fixture')
            command('switch', 'contribution')
            command('merge', '--no-ff', 'main', '-m', 'chore: synchronization after maintenance fixture')
            self.assertEqual(command('diff', 'main', 'contribution', '--', '.github/workflows', 'version.properties'), '')


if __name__ == '__main__':
    unittest.main()
