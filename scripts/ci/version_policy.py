"""Owner-only release authorization and version sequence, independent of Gradle."""
import os
import re

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
    versions = [tuple(map(int, name.split('.'))) for name, _ in history]
    if tuple(map(int, requested.split('.'))) <= max(versions, default=(0, 0, 0)):
        raise ValueError('Release version must exceed every existing release tag')
    codes = [code for _, code in history]
    if any(code < 1 for code in codes) or len(set(codes)) != len(codes):
        raise ValueError('Release history has invalid or reused versionCode values')
    if not isinstance(main_commit_count, int) or isinstance(main_commit_count, bool) or main_commit_count < 1:
        raise ValueError('main must have a positive complete-history commit count')
    # One additional commit is created for the release version metadata.
    code = max(main_commit_count + 1, max(codes, default=0) + 1)
    if development:
        current_name = tuple(map(int, development['versionName'].split('.')))
        requested_name = tuple(map(int, requested.split('.')))
        if requested_name < current_name:
            raise ValueError('Requested release must not be below the version prepared by the owner in main')
        # The release code comes from complete history and previous tags, never manual input.
    if code > 2100000000:
        raise ValueError('versionCode exceeds the project Google Play-compatible limit')
    return code
