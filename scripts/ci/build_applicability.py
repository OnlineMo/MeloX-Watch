"""Read-only change classification. Unknown histories require checks rather than implying no change."""
import json
import os
from pathlib import Path
import re
import subprocess

from ci_common import ROOT, require
from package_contract import has_phone


def changed_paths(root=ROOT, environment=None):
    environment = os.environ if environment is None else environment
    event = environment.get('GITHUB_EVENT_NAME')
    event_path = environment.get('GITHUB_EVENT_PATH')
    if event not in ('pull_request', 'push') or not event_path:
        return None
    path = Path(event_path)
    require(path.is_file() and not path.is_symlink() and path.stat().st_size <= 4 * 1024 * 1024,
            'Workflow event data is unavailable or too large')
    data = json.loads(path.read_text(encoding='utf-8'))
    base = data.get('pull_request', {}).get('base', {}).get('sha') if event == 'pull_request' else data.get('before')
    if type(base) is not str or not re.fullmatch(r'[0-9a-f]{40}', base) or base == '0' * 40:
        return None
    result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', 'diff', '--name-only', '--no-renames', '-z', base, 'HEAD'],
                            cwd=root, capture_output=True)
    if result.returncode != 0:
        return None
    require(len(result.stdout) <= 4 * 1024 * 1024, 'Changed path inventory exceeds its bound')
    return set(filter(None, result.stdout.decode('utf-8').split('\0')))


def application_changed(paths):
    return paths is None or any(name.startswith('android/') or
                                name.startswith('scripts/ci/') and not Path(name).name.startswith('test_') or
                                name in ('version.properties', '.ci/toolchain.properties', '.ci/signing-certificates.json',
                                         '.ci/upstream-lock.json', '.ci/dependency-migration-request.json')
                                for name in paths)


def policy_changed(paths):
    return paths is None or any(name.startswith(('scripts/ci/', '.github/')) or
                                name.startswith('.ci/') and name != '.ci/release-readiness.json' for name in paths)


def outputs(root=ROOT, environment=None):
    paths = changed_paths(root, environment)
    phone = has_phone(root) if (Path(root) / 'android').is_dir() else False
    request = Path(root) / '.ci/dependency-migration-request.json'
    requested = request.exists() or request.is_symlink()
    return {'android-applicable': str(application_changed(paths)).lower(), 'package-build': str(phone).lower(),
            'package-input-request': str(requested).lower(),
            'package-input-collection': str(phone and requested).lower(),
            'policy-tests-applicable': str(policy_changed(paths)).lower()}
