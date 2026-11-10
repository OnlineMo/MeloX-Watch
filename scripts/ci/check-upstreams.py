"""Validate exact upstream commits. Never fetch, pull, or upgrade a checkout."""
import json
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[2]
lock = json.loads((root / '.ci/upstream-lock.json').read_text(encoding='utf-8'))
for item in lock['repositories']:
    assert re.fullmatch(r'[0-9a-f]{40}', item['commit']), 'Upstream must use a full commit SHA'
    path = (root / item['localPath']).resolve()
    assert path.is_relative_to(root / 'research'), 'Upstream path must stay under research'
    if not path.exists():
        print(item['name'] + ': lock checked; optional local snapshot absent')
        continue
    def git(*args):
        return subprocess.run(['git', '-C', str(path), *args], text=True, capture_output=True, check=True).stdout.strip()
    assert git('rev-parse', 'HEAD') == item['commit'], item['name'] + ': upstream version drift'
    assert git('rev-parse', '--abbrev-ref', 'HEAD') == 'HEAD', item['name'] + ': use detached HEAD'
    assert not git('status', '--porcelain'), item['name'] + ': reference snapshot modified'
    print(item['name'] + ': exact commit and clean detached checkout verified')
