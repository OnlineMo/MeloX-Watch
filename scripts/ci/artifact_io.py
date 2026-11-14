"""只把 Actions 产物当有界数据读取，不执行压缩包内程序。"""
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import stat
import urllib.error
import urllib.parse
import urllib.request
import zipfile

from ci_common import REPOSITORY, require, sha256

MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_EXPANDED_BYTES = 768 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


class GitHub:
    def __init__(self, token=None):
        self.token = token if token is not None else os.environ.get('GH_TOKEN', '')
        require(bool(self.token), 'A scoped GitHub Actions token is required')

    def request(self, method, path, data=None, binary=False, upload=False):
        require(path == '/repos/' + REPOSITORY or path.startswith('/repos/' + REPOSITORY + '/'),
                'Unexpected GitHub repository path')
        host = 'https://uploads.github.com' if upload else 'https://api.github.com'
        headers = {'Authorization': 'Bearer ' + self.token, 'X-GitHub-Api-Version': '2022-11-28',
                   'Accept': 'application/vnd.github+json', 'Content-Type': 'application/octet-stream' if binary else 'application/json'}
        body = data if binary else None if data is None else json.dumps(data).encode()
        request = urllib.request.Request(host + path, method=method, headers=headers, data=body)
        try:
            with urllib.request.build_opener(NoRedirect).open(request, timeout=60) as response:
                content = response.read(MAX_ARCHIVE_BYTES + 1)
                require(len(content) <= MAX_ARCHIVE_BYTES, 'GitHub response exceeds size limit')
                return json.loads(content) if content else None
        except urllib.error.HTTPError as error:
            raise ValueError(f'GitHub {method} failed: HTTP {error.code}') from None

    def get(self, path):
        return self.request('GET', '/repos/' + REPOSITORY + path)

    def graphql(self, query):
        require(query.lstrip().startswith('query '), 'Only read-only GraphQL queries are allowed')
        request = urllib.request.Request('https://api.github.com/graphql',
            data=json.dumps({'query': query}).encode(),
            headers={'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read(2_000_001)
        except urllib.error.HTTPError as error:
            raise ValueError(f'GitHub GraphQL failed: HTTP {error.code}') from None
        require(len(data) <= 2_000_000, 'GraphQL response exceeds limit')
        value = json.loads(data)
        require(not value.get('errors') and value.get('data') is not None, 'GraphQL protection query failed')
        return value['data']

    def pages(self, path, key=None):
        result = []
        separator = '&' if '?' in path else '?'
        for page in range(1, 101):
            value = self.get(f'{path}{separator}per_page=100&page={page}')
            values = value[key] if key else value
            require(isinstance(values, list), 'Unexpected GitHub collection')
            result.extend(values)
            if len(values) < 100:
                return result
        raise ValueError('GitHub collection exceeded the bounded pagination limit')

    def download(self, path):
        require(path.startswith('/repos/' + REPOSITORY + '/'), 'Unexpected download repository')
        request = urllib.request.Request('https://api.github.com' + path,
            headers={'Authorization': 'Bearer ' + self.token, 'Accept': 'application/octet-stream'})
        try:
            with urllib.request.build_opener(NoRedirect).open(request, timeout=60) as response:
                data = response.read(MAX_ARCHIVE_BYTES + 1)
        except urllib.error.HTTPError as error:
            require(error.code == 302, f'Artifact download failed: HTTP {error.code}')
            location = error.headers.get('Location', '')
            require(urllib.parse.urlsplit(location).scheme == 'https', 'Artifact redirect must use HTTPS')
            # 跨主机的临时下载地址不携带 GitHub Authorization。
            with urllib.request.urlopen(location, timeout=60) as response:
                data = response.read(MAX_ARCHIVE_BYTES + 1)
        require(len(data) <= MAX_ARCHIVE_BYTES, 'Artifact archive exceeds size limit')
        return data


def unpack_data(data, destination, allowed):
    destination = Path(destination)
    require(not destination.exists() or not any(destination.iterdir()), 'Artifact destination must be empty')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        members = [entry for entry in archive.infolist() if not entry.is_dir()]
        names = [entry.filename for entry in members]
        require(len(names) == len(set(names)), 'Duplicate artifact member')
        require(set(names) == set(allowed), 'Unexpected or missing artifact members')
        require(sum(entry.file_size for entry in members) <= MAX_EXPANDED_BYTES, 'Expanded artifact exceeds size limit')
        for entry in members:
            path = PurePosixPath(entry.filename)
            require(len(path.parts) == 1 and '\\' not in entry.filename and ':' not in entry.filename,
                    'Only plain artifact filenames are allowed')
            require(not stat.S_ISLNK(entry.external_attr >> 16), 'Artifact symlinks are forbidden')
        destination.mkdir(parents=True, exist_ok=True)
        for entry in members:
            (destination / entry.filename).write_bytes(archive.read(entry))
    verify_checksums(destination)


def verify_checksums(directory):
    directory = Path(directory)
    entries = {}
    for line in (directory / 'SHA256SUMS').read_text(encoding='utf-8').splitlines():
        digest, name = line.split('  ', 1)
        require(name not in entries and Path(name).name == name and '\\' not in name,
                'Invalid checksum entry')
        require(name != 'SHA256SUMS' and sha256(directory / name) == digest, 'Artifact checksum mismatch')
        entries[name] = digest
    require(set(entries) == {path.name for path in directory.iterdir() if path.name != 'SHA256SUMS'},
            'Checksum inventory mismatch')


def validate_run(run, attempt, source_sha, workflow_path, event, branch, require_success=False):
    require(run['repository']['full_name'] == REPOSITORY, 'Foreign artifact repository')
    require(run['run_attempt'] == attempt, 'Run attempt mismatch; use the accepted attempt')
    require(run['head_sha'] == source_sha and run['head_branch'] == branch, 'Artifact source mismatch')
    require(run['path'] == workflow_path and run['event'] == event, 'Unexpected producing workflow')
    require(run['actor']['login'] == run['triggering_actor']['login'] == 'OnlineMo', 'Untrusted producing actor')
    require(not require_success or run['conclusion'] == 'success', 'Candidate run did not succeed')


def fetch_artifact(client, artifact_id, *, run_id, attempt, source_sha, workflow_path,
                   event, branch, job, name, destination, allowed, require_success=False):
    require(type(artifact_id) is int and artifact_id > 0, 'Invalid artifact ID')
    run = client.get(f'/actions/runs/{run_id}/attempts/{attempt}')
    validate_run(run, attempt, source_sha, workflow_path, event, branch, require_success)
    jobs = client.pages(f'/actions/runs/{run_id}/attempts/{attempt}/jobs', 'jobs')
    matches = [entry for entry in jobs if entry['name'] == job]
    require(len(matches) == 1 and matches[0]['conclusion'] == 'success', 'Producing job must have succeeded')
    artifact = client.get(f'/actions/artifacts/{artifact_id}')
    require(artifact['workflow_run']['id'] == run_id and artifact['workflow_run']['head_sha'] == source_sha,
            'Artifact is not from the specified run/source')
    require(artifact['name'] == name and not artifact['expired'], 'Artifact name mismatch or expired')
    require(artifact['size_in_bytes'] <= MAX_ARCHIVE_BYTES, 'Artifact archive exceeds size limit')
    data = client.download(f'/repos/{REPOSITORY}/actions/artifacts/{artifact_id}/zip')
    digest = hashlib.sha256(data).hexdigest()
    require(artifact.get('digest') == 'sha256:' + digest, 'GitHub artifact digest mismatch')
    unpack_data(data, destination, allowed)
    return {'id': artifact_id, 'name': name, 'sha256': digest, 'run_id': run_id, 'run_attempt': attempt}
