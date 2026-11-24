"""Verify offline notice bytes from APK data; never execute packaged code."""
import hashlib
import json
import re
import zipfile

from ci_common import require

INDEX = 'assets/licenses/index.json'
INDEX_BYTES = 128 * 1024
NOTICE_BYTES = 256 * 1024
ENTRY_FIELDS = {'id', 'name', 'version', 'license', 'notice_asset', 'notice_sha256'}


def unique_object(items):
    result = {}
    for key, value in items:
        require(key not in result, 'License JSON contains a duplicate field')
        result[key] = value
    return result


def bounded_read(archive, entry, maximum):
    require(not entry.is_dir() and not entry.flag_bits & 1 and 0 < entry.file_size <= maximum,
            'License asset is empty, encrypted or exceeds its byte limit')
    with archive.open(entry) as stream:
        data = stream.read(maximum + 1)
        require(len(data) == entry.file_size and len(data) <= maximum and not stream.read(1),
                'License asset bytes differ from the archive declaration')
    data.decode('utf-8', errors='strict')
    return data


def verify_license_assets(apk):
    with zipfile.ZipFile(apk) as archive:
        members = [entry for entry in archive.infolist()
                   if entry.filename.startswith('assets/licenses/') and not entry.is_dir()]
        names = [entry.filename for entry in members]
        require(1 < len(names) <= 257 and len(names) == len(set(names)) and INDEX in names,
                'APK license inventory is missing, duplicate or too large')
        by_name = {entry.filename: entry for entry in members}
        raw = bounded_read(archive, by_name[INDEX], INDEX_BYTES)
        root = json.loads(raw.decode('utf-8'), object_pairs_hook=unique_object)
        require(type(root) is dict and set(root) == {'schema_version', 'entries'} and
                type(root['schema_version']) is int and root['schema_version'] == 1,
                'APK license schema differs')
        entries = root['entries']
        require(type(entries) is list and 0 < len(entries) <= 256, 'APK license entry count is invalid')
        ids = set()
        files = {INDEX: hashlib.sha256(raw).hexdigest()}
        total_bytes = 0
        for entry in entries:
            require(type(entry) is dict and set(entry) == ENTRY_FIELDS and
                    all(type(value) is str and value.strip() and
                        len(value.encode('utf-16-le', errors='strict')) // 2 <= 512 for value in entry.values()),
                    'APK license entry fields are invalid')
            require(re.fullmatch(r'[a-z0-9._+-]{1,160}', entry['id']) and entry['id'] not in ids,
                    'APK license ID is invalid or duplicate')
            ids.add(entry['id'])
            path = entry['notice_asset']
            digest = entry['notice_sha256']
            require(re.fullmatch(r'licenses/[A-Za-z0-9._+-]{1,160}\.txt', path) and
                    re.fullmatch(r'[0-9a-f]{64}', digest), 'APK license path or digest is invalid')
            name = 'assets/' + path
            require(name in by_name, 'APK is missing a referenced license notice')
            if name in files:
                require(files[name] == digest, 'APK entries disagree about a shared license notice')
                continue
            data = bounded_read(archive, by_name[name], NOTICE_BYTES)
            observed = hashlib.sha256(data).hexdigest()
            require(observed == digest, 'APK license notice digest differs from its index')
            files[name] = observed
            total_bytes += len(data)
        require(set(files) == set(names), 'APK has an unindexed license asset')
        return {'schema_version': 1, 'index_sha256': files[INDEX], 'entry_count': len(entries),
                'notice_asset_count': len(files) - 1, 'notice_bytes': total_bytes,
                'file_sha256': dict(sorted(files.items()))}


def require_license_evidence(apk, role, manifest):
    expected = manifest.get('license_assets')
    require(type(expected) is dict and set(expected) == {'watch', 'phone'},
            'Paired APK license evidence is incomplete')
    observed = verify_license_assets(apk)
    require(json.dumps(expected.get(role), sort_keys=True, allow_nan=False) ==
            json.dumps(observed, sort_keys=True, allow_nan=False),
            'APK license evidence differs from the accepted build manifest')
    return observed
