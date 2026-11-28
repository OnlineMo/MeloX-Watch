"""Fixed Watch/Android-phone artifact contract; source and APK reads are data only."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import zipfile

from ci_common import ROOT, expected_identity, require, sha256

ROLES = ('watch', 'phone')
MODES = ('debug', 'release', 'diagnostic')
SPECS = {
    'watch': {'module': 'app', 'flavor': 'watchAndroid'},
    'phone': {'module': 'companion', 'flavor': 'phoneAndroid'},
}
PROTOCOL_FILES = tuple('android/shared-transfer/src/main/kotlin/io/github/onlinemo/meloxwatch/data/transfer/' + name
                       for name in ('TransferContracts.kt', 'TransferProtocolCodec.kt', 'TransferTls.kt'))
LEGACY_WIRE_FILES = tuple('android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/data/transfer/' + name
                         for name in ('TransferContracts.kt', 'TransferProtocolCodec.kt'))
DEPENDENCY_FILES = ('gradle/verification-metadata.xml', 'app/gradle.lockfile',
                    'companion/gradle.lockfile', 'gradle.lockfile')
DATABASE_SOURCE = 'android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/data/database/MeloXDatabase.kt'
SCHEMA_DIRECTORY = 'app/schemas/io.github.onlinemo.meloxwatch.data.database.MeloXDatabase'
ONLINE_DATABASE_SOURCE = 'android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/data/online/cache/OnlineCatalogDatabase.kt'
# Schema 6 keeps its admitted bytes when the later current schema is collected.
HISTORICAL_SCHEMA_VERSIONS = (1, 2, 3, 6)
ROOM_DECLARATIONS = (
    (DATABASE_SOURCE, 'io.github.onlinemo.meloxwatch.data.database.MeloXDatabase', None, True),
    (ONLINE_DATABASE_SOURCE, 'io.github.onlinemo.meloxwatch.data.online.cache.OnlineCatalogDatabase', 1, True),
) + tuple(
    ('android/app/src/test/kotlin/io/github/onlinemo/meloxwatch/data/database/HistoricalMeloXV' + str(version) + 'Database.kt',
     'io.github.onlinemo.meloxwatch.data.database.HistoricalMeloXV' + str(version) + 'Database', version, False)
    for version in (3, 4, 5)) + (
    ('android/app/src/test/kotlin/io/github/onlinemo/meloxwatch/data/playlists/PlaylistTestDatabase.kt',
     'io.github.onlinemo.meloxwatch.data.playlists.PlaylistTestDatabase', 1, False),)
HASH = re.compile(r'[0-9a-f]{64}')


def exact_data(left, right):
    # JSON bindings distinguish booleans, integers and floats even when Python equality does not.
    return json.dumps(left, sort_keys=True, allow_nan=False) == json.dumps(right, sort_keys=True, allow_nan=False)


def safe_path(root, relative, *, file=False):
    root = Path(root)
    name = PurePosixPath(relative)
    require(type(relative) is str and not name.is_absolute() and name.as_posix() == relative and
            '\\' not in relative and all(part not in ('', '.', '..') for part in name.parts),
            'Package path must be canonical')
    path = root.joinpath(*name.parts)
    require(not root.is_symlink() and not path.is_symlink() and
            not any(parent.is_symlink() for parent in path.parents if parent.is_relative_to(root)) and
            path.resolve().is_relative_to(root.resolve()), 'Package path is linked or outside the project')
    require(not file or path.is_file(), 'Required package data is missing')
    return path


def git_bytes(root, revision, relative, *, optional=False):
    require(re.fullmatch(r'[0-9a-f]{40}', revision) is not None, 'Invalid package source revision')
    safe_path(root, relative)
    result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', 'show', revision + ':' + relative],
                            cwd=root, capture_output=True)
    if optional and result.returncode != 0:
        return None
    require(result.returncode == 0 and len(result.stdout) <= 1024 * 1024,
            'Package source data is unavailable or exceeds its bound')
    return result.stdout


def data_bytes(root, relative, revision=None, *, optional=False):
    if revision is not None:
        return git_bytes(root, revision, relative, optional=optional)
    path = safe_path(root, relative)
    if optional and not path.exists():
        return None
    require(path.is_file() and path.stat().st_size <= 1024 * 1024, 'Package source data is missing or too large')
    return path.read_bytes()


def committed_hash(root, relative, revision='HEAD'):
    """Compare raw bytes to the Git blob; worktree filters and prior exports cannot grant trust."""
    path = safe_path(root, relative, file=True)
    result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', 'rev-parse', revision + ':' + relative],
                            cwd=root, capture_output=True, text=True)
    require(result.returncode == 0 and re.fullmatch(r'[0-9a-f]{40}', result.stdout.strip()),
            'Package input is not present in its frozen commit')
    before = path.stat()
    require(before.st_size <= 4 * 1024 * 1024, 'Package data exceeds its bound')
    blob = hashlib.sha1(b'blob ' + str(before.st_size).encode() + b'\0')
    digest = hashlib.sha256()
    count = 0
    with path.open('rb') as stream:
        while block := stream.read(64 * 1024):
            count += len(block)
            require(count <= before.st_size, 'Package input grew while hashing')
            blob.update(block)
            digest.update(block)
    after = safe_path(root, relative, file=True).stat()
    require(count == before.st_size and (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) ==
            (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) and blob.hexdigest() == result.stdout.strip(),
            'Package input bytes differ from the committed input')
    return digest.hexdigest()


def has_phone(root=ROOT):
    return any(safe_path(root, 'android/companion/' + suffix).exists()
               for suffix in ('build.gradle.kts', 'src/main/AndroidManifest.xml'))


def project_plan(root=ROOT, revision=None, *, require_phone=True):
    phone_build = data_bytes(root, 'android/companion/build.gradle.kts', revision, optional=True)
    phone_manifest = data_bytes(root, 'android/companion/src/main/AndroidManifest.xml', revision, optional=True)
    require((phone_build is None) == (phone_manifest is None), 'A partial companion project cannot be admitted')
    phone = phone_build is not None
    if not phone and not require_phone:
        return {'application_roles': ['watch'], 'instrumentation_roles': [], 'protocol': None}
    for role in ROLES:
        spec = SPECS[role]
        for suffix in ('build.gradle.kts', 'src/main/AndroidManifest.xml'):
            data = data_bytes(root, 'android/' + spec['module'] + '/' + suffix, revision,
                              optional=role == 'phone' and not require_phone)
            require(data is not None or role == 'phone' and not require_phone, 'Both Android applications are required')
    require(phone or not require_phone, 'The first-release candidate requires the Android companion')
    roles = list(ROLES if phone else ('watch',))
    tests = []
    for role in roles:
        module = SPECS[role]['module']
        if revision is None:
            present = safe_path(root, 'android/' + module + '/src/androidTest').is_dir()
        else:
            result = subprocess.run(['git', '-c', 'core.hooksPath=/dev/null', 'ls-tree', revision, '--',
                                     'android/' + module + '/src/androidTest'], cwd=root, capture_output=True)
            require(result.returncode == 0, 'Instrumentation source scope cannot be read')
            present = result.stdout.startswith(b'040000 tree ')
        if present:
            tests.append(role)
    protocol = None
    if phone:
        for relative in LEGACY_WIRE_FILES:
            require(data_bytes(root, relative, revision, optional=True) is None,
                    'Legacy app wire copies must be deleted before compiling shared source')
        hashes = {}
        for relative in PROTOCOL_FILES:
            data = data_bytes(root, relative, revision)
            hashes[relative] = hashlib.sha256(data).hexdigest()
        source = data_bytes(root, PROTOCOL_FILES[0], revision).decode('utf-8')
        schemas = re.findall(r'\bconst\s+val\s+SCHEMA\s*=\s*([0-9]+)\b', source)
        require(len(schemas) == 1 and 0 < int(schemas[0]) <= 65535, 'Shared wire schema must be one literal')
        for role in ROLES:
            script = data_bytes(root, 'android/' + SPECS[role]['module'] + '/build.gradle.kts', revision).decode('utf-8')
            require('java.srcDir("../shared-transfer/src/main/kotlin")' in script,
                    'Each application must compile the actual shared wire source')
        protocol = {'schema': int(schemas[0]), 'source_hashes': hashes}
    return {'application_roles': roles, 'instrumentation_roles': tests, 'protocol': protocol}


def current_schema(root=ROOT):
    source = data_bytes(root, DATABASE_SOURCE).decode('utf-8')
    versions = re.findall(r'@Database\s*\([\s\S]*?\bversion\s*=\s*([0-9]+)\s*[,)]', source)
    require(len(versions) == 1 and int(versions[0]) > 0, 'Watch Room must declare one literal schema version')
    return SCHEMA_DIRECTORY + '/' + str(int(versions[0])) + '.json'


def room_declarations(root=ROOT, revision=None):
    """Bind registered exported main databases and nonexporting historical/test declarations."""
    result = {}
    for relative, qualified, fixed_version, exported in ROOM_DECLARATIONS:
        raw = data_bytes(root, relative, revision, optional=relative != DATABASE_SOURCE)
        if raw is None:
            continue
        source = raw.decode('utf-8')
        package, name = qualified.rsplit('.', 1)
        declarations = re.findall(r'@Database\s*\(([\s\S]*?)\)\s*(?:internal\s+)?abstract\s+class\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*RoomDatabase\s*\(\s*\)', source)
        require(re.findall(r'^package\s+([A-Za-z0-9_.]+)\s*$', source, re.MULTILINE) == [package] and
                len(declarations) == 1 and declarations[0][1] == name, 'Room source namespace/declaration differs')
        arguments = declarations[0][0]
        versions = re.findall(r'\bversion\s*=\s*([0-9]+)\s*(?:,|$)', arguments)
        flags = re.findall(r'\bexportSchema\s*=\s*(true|false)\s*(?:,|$)', arguments)
        require(len(versions) == 1 and 0 < int(versions[0]) <= 2147483647 and
                (fixed_version is None or int(versions[0]) == fixed_version) and
                flags == ['true' if exported else 'false'], 'Room version/exportSchema must match its actual literal contract')
        result[relative] = {'class': qualified, 'version': int(versions[0]), 'export_schema': exported,
                            'source_sha256': hashlib.sha256(raw).hexdigest(), 'source_set': 'main' if exported else 'test',
                            'schema_output': 'app/schemas/' + qualified + '/' + versions[0] + '.json' if exported else None}
    return result


def current_schemas(root=ROOT, revision=None):
    # Only the exact registered, actually present exported databases define current outputs.
    declarations = room_declarations(root, revision)
    paths = sorted(entry['schema_output'] for entry in declarations.values() if entry['export_schema'])
    require(1 <= len(paths) <= 2 and len(paths) == len(set(paths)) and
            declarations[DATABASE_SOURCE]['schema_output'] in paths, 'Current Room schema inventory differs')
    return paths


def historical_schema_paths(main_version):
    """Retain admitted predecessors; the first single-current6 contract stays unchanged."""
    require(type(main_version) is int and 0 < main_version <= 2147483647,
            'Watch history requires one actual integer main schema version')
    versions = (HISTORICAL_SCHEMA_VERSIONS + ((7,) if main_version >= 8 else ()) +
                ((8,) if main_version >= 9 else ()))
    return {SCHEMA_DIRECTORY + '/' + str(version) + '.json' for version in versions}


def allowed_schema_paths(root=ROOT, revision=None):
    declarations = room_declarations(root, revision)
    history = historical_schema_paths(declarations[DATABASE_SOURCE]['version'])
    return history | set(current_schemas(root, revision))


def schema_output_paths(root=ROOT, *, allow_unexpected=False):
    android = Path(root) / 'android'
    directory = safe_path(android, 'app/schemas')
    require(not directory.exists() or directory.is_dir(), 'Room schema root is invalid')
    paths = sorted(directory.rglob('*.json'))
    require(len(paths) <= 128, 'Room schema output inventory exceeds its bound')
    result = []
    allowed = set() if allow_unexpected else allowed_schema_paths(root)
    for path in paths:
        relative = path.relative_to(android).as_posix()
        parts = PurePosixPath(relative).parts
        require(len(parts) == 4 and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_.]{0,240}', parts[2]) and
                re.fullmatch(r'[1-9][0-9]*\.json', parts[3]), 'Room schema output path is invalid')
        safe_path(android, relative, file=True)
        require(allow_unexpected or relative in allowed, 'Unregistered or noncurrent Room schema output')
        result.append(relative)
    return result


def input_hashes(root=ROOT):
    result = {}
    android = Path(root) / 'android'
    for relative in DEPENDENCY_FILES:
        path = safe_path(android, relative)
        if path.exists():
            result[relative] = sha256(safe_path(android, relative, file=True))
    for relative in schema_output_paths(root):
        result[relative] = sha256(safe_path(android, relative, file=True))
    return result


def required_inputs(root=ROOT):
    current = current_schemas(root)
    # Later main schemas retain every admitted predecessor even without the online cache.
    if len(current) == 1 and int(PurePosixPath(current[0]).stem) <= max(HISTORICAL_SCHEMA_VERSIONS):
        return ('gradle/verification-metadata.xml', 'app/gradle.lockfile', 'companion/gradle.lockfile',
                SCHEMA_DIRECTORY + '/1.json', current[0])
    history = historical_schema_paths(int(PurePosixPath(current_schema(root)).stem))
    return tuple(sorted({'gradle/verification-metadata.xml', 'app/gradle.lockfile', 'companion/gradle.lockfile'} |
                        history | set(current)))


def tasks_for(mode, plan, *, tests=False, outputs=True):
    require(mode in MODES, 'Unknown package build variant')
    variant = mode.title()
    tasks = []
    for role in plan['application_roles']:
        spec = SPECS[role]
        prefix = ':' + spec['module'] + ':'
        if tests:
            tasks.append(prefix + 'test' + spec['flavor'][0].upper() + spec['flavor'][1:] + variant + 'UnitTest')
        if outputs:
            tasks += [prefix + 'lint' + spec['flavor'][0].upper() + spec['flavor'][1:] + variant,
                      prefix + 'assemble' + spec['flavor'][0].upper() + spec['flavor'][1:] + variant]
        if outputs and mode == 'diagnostic' and role in plan['instrumentation_roles']:
            tasks.append(prefix + 'assemble' + spec['flavor'][0].upper() + spec['flavor'][1:] + variant + 'AndroidTest')
    return tasks


def artifact_layout(mode, plan):
    entries = [{'role': role, 'kind': 'application', 'file': role + '-' + mode + '.apk'} for role in plan['application_roles']]
    if mode == 'diagnostic':
        entries += [{'role': role, 'kind': 'instrumentation', 'file': role + '-diagnostic-test.apk'}
                    for role in plan['instrumentation_roles']]
    return entries


def artifact_files(mode, plan):
    return {entry['file'] for entry in artifact_layout(mode, plan)} | {'build-manifest.json', 'SHA256SUMS', 'debug-tests.zip'}


def native_abis(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        require(len(names) <= 65536 and len(names) == len(set(names)), 'APK central directory is duplicate or too large')
        abis = {name.split('/')[1] for name in names if re.fullmatch(r'lib/[A-Za-z0-9_-]{1,32}/[^/]+\.so', name)}
    return sorted(abis)


def validate_artifacts(manifest, mode, version, plan, *, signing):
    require(type(manifest.get('schema_version')) is int and manifest['schema_version'] == 2 and
            manifest.get('mode') == mode and manifest.get('version') == version,
            'Multi-package build manifest version or mode differs')
    require(exact_data({key: manifest.get(key) for key in plan}, plan), 'Package/protocol source binding differs')
    from package_test_evidence import BINDING_KEYS, validate_summary
    validate_summary(manifest.get('debug_test_evidence'), {key: manifest.get(key) for key in BINDING_KEYS})
    entries = manifest.get('artifacts')
    expected = artifact_layout(mode, plan)
    require(type(entries) is list and len(entries) == len(expected), 'Required APK collection is incomplete')
    for entry, layout in zip(entries, expected):
        require(type(entry) is dict and all(entry.get(key) == value for key, value in layout.items()),
                'APK role, kind or filename differs')
        require(type(entry.get('sha256')) is str and HASH.fullmatch(entry['sha256']) and
                entry.get('signing') == signing, 'APK hash or signing state differs')
        identity = expected_identity(mode, version, entry['role'])
        if entry['kind'] == 'application':
            require(all(type(entry.get(key)) is type(value) and entry[key] == value for key, value in identity.items()),
                    'APK package, version or flags differ')
        else:
            require(entry.get('package') == identity['package'] + '.test' and entry.get('debuggable') is True and
                    entry.get('profileable') is False and entry.get('target_package') == identity['package'],
                    'Instrumentation identity or target differs')
        abis = entry.get('abis')
        require(type(abis) is list and
                all(type(abi) is str and re.fullmatch(r'[A-Za-z0-9_-]{1,32}', abi) for abi in abis) and
                abis == sorted(set(abis)),
                'Actual APK ABI inventory is invalid')
        if signing == 'stable':
            require(type(entry.get('certificate_sha256')) is str and HASH.fullmatch(entry['certificate_sha256']) and
                    type(entry.get('unsigned_sha256')) is str and HASH.fullmatch(entry['unsigned_sha256']),
                    'Stable APK certificate or unsigned binding is missing')
    return entries


def package_identity(entries, protocol):
    require([entry['role'] for entry in entries] == list(ROLES) and
            all(entry['kind'] == 'application' for entry in entries), 'Accepted candidate must contain watch and phone APKs')
    fields = ('role', 'file', 'package', 'version_name', 'version_code', 'abis', 'sha256', 'certificate_sha256')
    packages = [{key: entry[key] for key in fields} for entry in entries]
    canonical = json.dumps({'packages': packages, 'protocol': protocol}, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()
    return {'packages': packages, 'protocol': protocol, 'package_set_sha256': hashlib.sha256(canonical).hexdigest()}
