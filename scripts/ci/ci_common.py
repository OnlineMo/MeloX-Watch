"""构建、签名和发布共用的纯数据约束；导入时不执行应用代码。"""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = 'OnlineMo/MeloX-Watch'
OWNER = 'OnlineMo'
OWNER_EMAIL = '143324986+OnlineMo@users.noreply.github.com'
APPLICATION_ID = 'io.github.onlinemo.meloxwatch'
PHONE_APPLICATION_ID = APPLICATION_ID + '.companion'
PACKAGES = {'debug': APPLICATION_ID + '.dev', 'release': APPLICATION_ID,
            'diagnostic': APPLICATION_ID + '.diagnostic'}
SHA_PATTERN = re.compile(r'[0-9a-f]{40}')
SECRET_VARIABLES = ('SIGNING_KEY_B64', 'SIGNING_PASSWORD', 'CI_DEBUG_KEYSTORE_B64',
                    'CI_DEBUG_STORE_PASSWORD', 'RELEASE_KEYSTORE_B64', 'RELEASE_STORE_PASSWORD')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def properties(path):
    result = {}
    for line in Path(path).read_text(encoding='utf-8').splitlines():
        if line.strip() and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            key, value = key.strip(), value.strip()
            require(key not in result, 'Duplicate property: ' + key)
            result[key] = value
    return result


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def write_checksums(directory):
    files = sorted(path for path in Path(directory).iterdir() if path.is_file() and path.name != 'SHA256SUMS')
    (Path(directory) / 'SHA256SUMS').write_text(
        ''.join(sha256(path) + '  ' + path.name + '\n' for path in files), encoding='utf-8')


def run_tool(command, **kwargs):
    # 工具失败信息可能带 URL/环境内容；对外只报工具与退出码，原始秘密不进入日志。
    result = subprocess.run(command, text=True, encoding='utf-8', capture_output=True, **kwargs)
    require(result.returncode == 0, f'{Path(str(command[0])).name} failed (exit {result.returncode})')
    return result.stdout.strip()


def git(*args, cwd=ROOT, env=None):
    return run_tool(['git', '-c', 'core.hooksPath=/dev/null', *args], cwd=cwd, env=env)


def cloud_only(environment=None):
    environment = os.environ if environment is None else environment
    require(environment.get('GITHUB_ACTIONS') == 'true' and environment.get('CI') == 'true',
            'Android compilation and signing are cloud-only')


def no_signing_secrets(environment=None):
    environment = os.environ if environment is None else environment
    require(not any(environment.get(name) for name in SECRET_VARIABLES),
            'Compilation must not receive signing secrets')


def trusted_context(workflow, environment=None):
    """校验受信任入口；实际 job 权限和 runner 隔离仍由受控工作流保证。"""
    environment = os.environ if environment is None else environment
    cloud_only(environment)
    for key, expected in {'GITHUB_REPOSITORY': REPOSITORY, 'GITHUB_ACTOR': OWNER,
                          'GITHUB_TRIGGERING_ACTOR': OWNER, 'GITHUB_WORKFLOW': workflow}.items():
        require(environment.get(key) == expected, 'Untrusted workflow context: ' + key)
    if workflow == 'Cloud build':
        require(environment.get('GITHUB_EVENT_NAME') in ('push', 'workflow_dispatch'), 'PRs cannot use stable signing')
        require(environment.get('GITHUB_REF') in ('refs/heads/main', 'refs/heads/contribution'), 'Untrusted signing ref')
    else:
        require(workflow == 'Owner release', 'Unknown trusted workflow')
        require(environment.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'Owner dispatch required')
        require(environment.get('GITHUB_REF') == 'refs/heads/main', 'Candidate operations require main')
    require(SHA_PATTERN.fullmatch(environment.get('GITHUB_SHA', '')) is not None, 'Invalid source SHA')


def expected_identity(mode, version, role='watch'):
    require(mode in PACKAGES, 'Unknown APK mode')
    require(role in ('watch', 'phone'), 'Unknown application role')
    package = PACKAGES[mode] if role == 'watch' else PHONE_APPLICATION_ID + {
        'debug': '.dev', 'release': '', 'diagnostic': '.diagnostic'}[mode]
    return {'package': package, 'version_name': version['versionName'],
            'version_code': str(version['versionCode']), 'debuggable': mode == 'debug',
            # The phone editor currently has no shell-profileable diagnostic manifest.
            'profileable': mode == 'diagnostic' and role == 'watch'}
