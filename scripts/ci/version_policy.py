"""Owner-only release authorization and version sequence, independent of Gradle."""
import os
import re


def parse_version(text):
    values = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        match = re.fullmatch(r'\s*(versionName|versionCode)\s*=\s*(\S+)\s*', line)
        if not match or match[1] in values:
            raise ValueError('Malformed version properties')
        values[match[1]] = match[2]
    if set(values) != {'versionName', 'versionCode'}:
        raise ValueError('Incomplete version properties')
    if not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', values['versionName']):
        raise ValueError('Noncanonical versionName')
    if not re.fullmatch(r'0|[1-9]\d*', values['versionCode']) or int(values['versionCode']) > 2100000000:
        raise ValueError('Invalid versionCode')
    if (values['versionName'] == '0.0.0') != (values['versionCode'] == '0'):
        raise ValueError('Zero is reserved for the unstarted sentinel')
    return values

def authorize_release():
    expected = {'GITHUB_ACTIONS': 'true', 'CI': 'true', 'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch',
                'GITHUB_ACTOR': 'OnlineMo', 'GITHUB_TRIGGERING_ACTOR': 'OnlineMo',
                'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                'GITHUB_WORKFLOW': 'Owner release'}
    if any(os.environ.get(key, '').lower() != value.lower() for key, value in expected.items()):
        raise SystemExit('Only the owner-dispatched main Release workflow may build a release snapshot')


def next_release(requested, history, main_commit_count, development=None):
    pattern = r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)'
    if not re.fullmatch(pattern, requested):
        raise ValueError('Use an exact x.y.z without a v prefix; prerelease is a separate flag')
    if any(not re.fullmatch(pattern, name) for name, _ in history):
        raise ValueError('Release history contains a noncanonical version')
    versions = [tuple(map(int, name.split('.'))) for name, _ in history]
    if len(set(versions)) != len(versions):
        raise ValueError('Release history repeats a version')
    if tuple(map(int, requested.split('.'))) <= max(versions, default=(0, 0, 0)):
        raise ValueError('Release version must exceed every existing release tag')
    codes = [code for _, code in history]
    if any(type(code) is not int or not 1 <= code <= 2100000000 for code in codes) or len(set(codes)) != len(codes):
        raise ValueError('Release history has invalid or reused versionCode values')
    ordered_codes = [code for _, code in sorted(zip(versions, codes))]
    if ordered_codes != sorted(ordered_codes):
        raise ValueError('Historical versionName and versionCode ordering disagree')
    if not isinstance(main_commit_count, int) or isinstance(main_commit_count, bool) or main_commit_count < 1:
        raise ValueError('main must have a positive complete-history commit count')
    # One additional commit is created for the release version metadata.
    code = max(main_commit_count + 1, max(codes, default=0) + 1)
    if development:
        current_name = tuple(map(int, development['versionName'].split('.')))
        requested_name = tuple(map(int, requested.split('.')))
        if requested_name < current_name:
            raise ValueError('Requested release must not be below the version prepared by the owner in main')
        if code < int(development['versionCode']):
            raise ValueError('Generated candidate code is below the owner-prepared development code')
    if code > 2100000000:
        raise ValueError('versionCode exceeds the project Google Play-compatible limit')
    return code


def validate_frozen_version(version, history):
    """发布只校验冻结值是否仍递增，绝不按审批提交数重新分配 code。"""
    current = tuple(map(int, version['versionName'].split('.')))
    code = int(version['versionCode'])
    if not 1 <= code <= 2100000000:
        raise ValueError('Frozen candidate code is out of range')
    for name, previous_code in history:
        if current <= tuple(map(int, name.split('.'))) or code <= previous_code:
            raise ValueError('Candidate is stale relative to the latest release history')
