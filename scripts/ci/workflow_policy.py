"""检查受控工作流的隔离边界；这不是完整的 GitHub Actions 语义执行器。"""
import argparse
import json
from pathlib import Path
import re

from ci_common import ROOT, require


def validate_workflow(workflow):
    require(workflow.get('permissions', {}).get('contents') == 'read', 'Workflow default token must be read-only')
    for identifier, job in workflow['jobs'].items():
        name = job.get('name', identifier)
        text = json.dumps(job)
        permissions = job.get('permissions', workflow.get('permissions', {}))
        writes = [scope for scope, value in permissions.items() if value == 'write']
        if name == 'publish-accepted-candidate':
            require(writes == ['contents'], 'Only publication may have contents-write')
            require('publish-candidate' in job.get('if', ''), 'Publication must be explicitly selected')
            require('secrets.' not in text and 'setup-build' not in text and 'build.py' not in text,
                    'Publication cannot compile or access signing secrets')
            require('sign.py' not in text and 'setup-gradle' not in text, 'Publication cannot sign or initialize Gradle')
        else:
            require(not writes, 'Nonpublication jobs must be read-only')
        if 'secrets.' in text:
            require(name in ('sign-debug', 'sign-release', 'sign-diagnostic'), 'Secrets outside signing job')
            require('setup-build' not in text and 'setup-gradle' not in text and 'build.py' not in text,
                    'Signing cannot execute application build setup')
            require(job.get('runs-on') == 'ubuntu-24.04', 'Signing must run on a fresh GitHub-hosted runner')
            require(not job.get('container') and not job.get('services'), 'Signing runner must not add unreviewed execution environments')
            commands = [entry.get('run', '') for entry in job['steps']]
            require(any('sign.py prepare' in command for command in commands), 'Signature input verification missing')
            require(any('sign.py sign' in command for command in commands), 'Isolated signing command missing')
        if name.startswith('compile-') or name == 'android-compile':
            require('secrets.' not in text, 'Compilation must never receive signing secrets')
        for entry in job['steps']:
            if entry.get('uses', '').startswith('actions/checkout@'):
                require(entry.get('with', {}).get('persist-credentials') is False, 'Checkout credentials must not persist')
            if 'secrets.' in json.dumps(entry):
                require(entry.get('run', '').startswith('python3 scripts/ci/sign.py sign '), 'Secret lifetime exceeds signing step')
            action = entry.get('uses')
            if action:
                require(action.startswith('./') or re.fullmatch(r'[^@]+@[0-9a-f]{40}', action), 'Floating action version')
        if name == 'sign-debug':
            condition = job.get('if', '')
            require("github.event_name != 'pull_request'" in condition and 'OnlineMo' in condition
                    and 'refs/heads/contribution' in condition and 'refs/heads/main' in condition,
                    'Stable Debug signing lacks explicit PR/ref/actor guards')


def validate_proposal(root):
    import yaml
    root = Path(root)
    for name in ('cloud-build.yml', 'release.yml'):
        value = yaml.safe_load((root / '.github/workflows' / name).read_text(encoding='utf-8'))
        validate_workflow(value)
    action = yaml.safe_load((root / '.github/actions/setup-verify/action.yml').read_text(encoding='utf-8'))
    text = json.dumps(action)
    require('gradle' not in text.lower().replace('no gradle', '') and 'check.py' not in text,
            'Isolated tool installation must not execute build/preflight code or restore caches')
    return True


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    validate_proposal(args.root)
    print('Workflow isolation policy passed; this does not prove cloud APK or signing execution.')
