"""Local-safe repository checks and cloud build preflight (stdlib only)."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
from version_policy import authorize_release

ROOT = Path(__file__).resolve().parents[2]


def properties(path):
    result = {}
    for line in path.read_text(encoding='utf-8').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            if key.strip() in result:
                raise ValueError('Duplicate property: ' + key)
            result[key.strip()] = value.strip()
    return result


def check(require_android=False, release_snapshot=False):
    env = properties(ROOT / '.ci/toolchain.properties')
    version = properties(ROOT / 'version.properties')
    if release_snapshot:
        authorize_release()
    assert re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', version['versionName']), 'Invalid versionName'
    assert version['versionCode'].isdigit() and int(version['versionCode']) <= 2100000000, 'Invalid versionCode'
    assert re.fullmatch(r'[0-9a-f]{64}', env['gradle.sha256']), 'Missing Gradle checksum'
    android = ROOT / 'android'
    ready = android.is_dir()
    if ready:
        required = ['settings.gradle.kts', 'build.gradle.kts', 'gradlew', 'gradlew.bat',
                    'gradle/wrapper/gradle-wrapper.properties', 'gradle/wrapper/gradle-wrapper.jar',
                    'app/build.gradle.kts']
        for relative in required:
            assert (android / relative).is_file(), 'Incomplete Android project: ' + relative
        wrapper = properties(android / 'gradle/wrapper/gradle-wrapper.properties')
        expected = 'https://services.gradle.org/distributions/gradle-' + env['gradle.version'] + '-bin.zip'
        assert wrapper.get('distributionUrl', '').replace('\\:', ':') == expected, 'Gradle wrapper version drift'
        assert wrapper.get('distributionSha256Sum') == env['gradle.sha256'], 'Gradle wrapper checksum drift'
    if require_android:
        assert ready, 'No Android application exists yet; release/build cannot proceed'
    # Avoid scanning ignored upstream clones, generated files and credentials.
    paths = [ROOT / name for name in ['README.md', 'AGENTS.md', 'CONTRIBUTING.md']]
    paths += list((ROOT / 'docs').rglob('*.md'))
    paths += list((ROOT / '.github').rglob('*.yml'))
    for path in paths:
        if not path.exists():
            continue
        text = path.read_text(encoding='utf-8')
        assert '\ufffd' not in text, 'Invalid Unicode in ' + path.name
        if path.suffix == '.md':
            assert text.count('```') % 2 == 0, 'Unbalanced code fences: ' + path.name
            for target in re.findall(r'\[[^\]]+\]\(([^)]+)\)', text):
                if '://' in target or target.startswith('#'):
                    continue
                # Source snapshots intentionally remain local, not in the remote repository.
                if '/research/MeloX' in target or target.startswith('../research/MeloX'):
                    continue
                assert (path.parent / target.split('#')[0]).resolve().exists(), 'Broken link: ' + target
    for workflow in (ROOT / '.github').rglob('*.yml'):
        text = workflow.read_text(encoding='utf-8')
        for action in re.findall(r'uses:\s*([^\s#]+)', text):
            assert action.startswith('./') or re.fullmatch(r'[^@]+@[0-9a-f]{40}', action), 'Unpinned action: ' + action
    subprocess.run(['node', str(ROOT / 'scripts/ci/test-policy.cjs')], check=True)
    subprocess.run([sys.executable, '-m', 'unittest', 'discover', '-s', str(ROOT / 'scripts/ci'), '-p', 'test_*.py'], check=True)
    subprocess.run([sys.executable, str(ROOT / 'scripts/ci/check-upstreams.py')], check=True)
    outputs = {
        'android-ready': str(ready).lower(), 'runner': env['runner'],
        'java-version': env['java.version'], 'java-distribution': env['java.distribution'],
        'gradle-version': env['gradle.version'],
        'cmdline-tools': env['android.commandLineTools'],
        'sdk-packages': 'platform-tools platforms;android-' + env['android.compileSdk'] + ' build-tools;' + env['android.buildTools'],
    }
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as stream:
            stream.writelines(key + '=' + value + '\n' for key, value in outputs.items())
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
            stream.write('## Project state\n' + ('Android project detected; compilation runs in the next job.\n' if ready else 'Planning/configuration checks only. Android build, APK and device tests have NOT run.\n'))
    print('Repository checks passed. Android project ready:', ready)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--require-android', action='store_true')
    args = parser.parse_args()
    check(args.require_android)
