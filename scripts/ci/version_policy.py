"""Immutable branch version and release sequence rules, independent of Gradle."""
import os
import re

DEVELOPMENT_VERSION = b'# Development branches keep this exact file; versions exist only in release tags.\nversionName=0.0.0\nversionCode=0\n'


def assert_development_version(data):
    if data != DEVELOPMENT_VERSION:
        raise ValueError('main/contribution/PR version.properties must remain byte-for-byte at 0.0.0 / 0')


def authorize_release():
    expected = {'GITHUB_ACTIONS': 'true', 'CI': 'true', 'GITHUB_REPOSITORY': 'OnlineMo/MeloX-Watch',
                'GITHUB_ACTOR': 'OnlineMo', 'GITHUB_TRIGGERING_ACTOR': 'OnlineMo',
                'GITHUB_REF': 'refs/heads/main', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                'GITHUB_WORKFLOW': 'Owner release'}
    if any(os.environ.get(key, '').lower() != value.lower() for key, value in expected.items()):
        raise SystemExit('Only the owner-dispatched main Release workflow may build a release snapshot')


def next_release(requested, history):
    pattern = r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)'
    if not re.fullmatch(pattern, requested):
        raise ValueError('Use an exact x.y.z without a v prefix; prerelease is a separate flag')
    versions = [tuple(map(int, name.split('.'))) for name, _ in history]
    if tuple(map(int, requested.split('.'))) <= max(versions, default=(0, 0, 0)):
        raise ValueError('Release version must exceed every existing release tag')
    codes = [code for _, code in history]
    if any(code < 1 for code in codes) or len(set(codes)) != len(codes):
        raise ValueError('Release history has invalid or reused versionCode values')
    code = max(codes, default=0) + 1
    if code > 2100000000:
        raise ValueError('versionCode exceeds the Android limit')
    return code
