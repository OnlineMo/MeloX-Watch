"""Request 7 permits only the explicit auxiliary bindings from the admitted Watch baseline."""
from build import dependency_lock_snapshot, verification_snapshot
from ci_common import require
from package_lock_versions import ordered as ordered_upgrades, validate_upgrades

KIND = 'owner-pr-package-dependency-upgrade-retirement-acquire-strict-compile'
FIELDS = {'package_role', 'module', 'configuration', 'from_version'}
APPLICATION_CONFIGURATIONS = (
    'watchAndroidDebugAndroidTestCompileClasspath',
    'watchAndroidDebugCompileClasspath', 'watchAndroidDebugRuntimeClasspath',
    'watchAndroidDebugUnitTestCompileClasspath', 'watchAndroidDebugUnitTestRuntimeClasspath',
    'watchAndroidDiagnosticCompileClasspath', 'watchAndroidDiagnosticRuntimeClasspath',
    'watchAndroidDiagnosticUnitTestCompileClasspath', 'watchAndroidDiagnosticUnitTestRuntimeClasspath',
    'watchAndroidReleaseCompileClasspath', 'watchAndroidReleaseRuntimeClasspath',
    'watchAndroidReleaseUnitTestCompileClasspath', 'watchAndroidReleaseUnitTestRuntimeClasspath',
)
APPROVED_BINDINGS = {
    ('org.jetbrains.kotlin:kotlin-stdlib-common', name, '2.2.21')
    for name in APPLICATION_CONFIGURATIONS if name.endswith('CompileClasspath')
} | {
    ('org.jetbrains.kotlin:' + module, name, '1.8.0')
    for module in ('kotlin-stdlib-jdk7', 'kotlin-stdlib-jdk8') for name in APPLICATION_CONFIGURATIONS
}


def ordered(retirements):
    return sorted(retirements, key=lambda item: tuple(item[name] for name in
                  ('package_role', 'module', 'configuration', 'from_version')))


def permission_shape(retirements):
    require(type(retirements) is list and len(retirements) == len(APPROVED_BINDINGS) == 33,
            'Request 7 requires all 33 exact authorized auxiliary retirement bindings')
    seen = set()
    for item in retirements:
        require(type(item) is dict and set(item) == FIELDS and item.get('package_role') == 'watch' and
                all(type(item[name]) is str for name in FIELDS), 'Retirement requires exact Watch module/configuration/from fields')
        key = (item['module'], item['configuration'], item['from_version'])
        require(key in APPROVED_BINDINGS and key not in seen, 'Unknown, tooling, duplicate or changed retirement binding')
        seen.add(key)
    require(seen == APPROVED_BINDINGS, 'The exact authorized retirement inventory differs')
    return ordered([dict(item) for item in retirements])


def validate_retirements(retirements, watch_lock):
    result = permission_shape(retirements)
    baseline = dependency_lock_snapshot(watch_lock)
    require(all(baseline.get(item['configuration'], {}).get(item['module']) == item['from_version'] for item in result),
            'Retirement from-version must match its existing admitted base binding')
    return result


def validate_actual_retirements(permissions, actual):
    granted = permission_shape(permissions)
    require(type(actual) is list and 0 < len(actual) <= len(granted) and
            all(type(item) is dict and set(item) == FIELDS and all(type(item[name]) is str for name in FIELDS) for item in actual),
            'Actual retirements require a nonempty bounded exact tuple inventory')
    require(actual == ordered(actual) and len({tuple(item[name] for name in sorted(FIELDS)) for item in actual}) == len(actual) and
            all(item in granted for item in actual), 'Actual retirements differ from the canonical explicit permission subset')
    return actual


def growth(before, after, upgrades, retirements):
    require(set(before) <= set(after), 'Retirement migration removed an admitted input')
    grants = validate_upgrades(upgrades, before['app/gradle.lockfile'])
    permissions = validate_retirements(retirements, before['app/gradle.lockfile'])
    require(grants == upgrades and permissions == retirements, 'Upgrade and retirement permissions must be canonical')
    old_configuration, old_pins = verification_snapshot(before['gradle/verification-metadata.xml'])
    new_configuration, new_pins = verification_snapshot(after['gradle/verification-metadata.xml'])
    require(old_configuration == new_configuration and all(new_pins.get(key) == value for key, value in old_pins.items()),
            'Existing artifact trust pins and XML verification configuration must remain')
    by_upgrade = {(item['module'], item['configuration']): item for item in grants}
    by_retirement = {(item['module'], item['configuration']): item for item in permissions}
    changes, removed = [], []
    for name, raw in before.items():
        if name == 'gradle/verification-metadata.xml':
            continue
        if name != 'app/gradle.lockfile':
            require(after[name] == raw, 'Phone/root locks and admitted Room schema originals must remain byte-for-byte')
            continue
        previous, updated = dependency_lock_snapshot(raw), dependency_lock_snapshot(after[name])
        require(set(previous) <= set(updated), 'A frozen Watch configuration cannot disappear')
        for configuration, modules in previous.items():
            for module, version in modules.items():
                actual = updated[configuration].get(module)
                if actual is None:
                    permission = by_retirement.get((module, configuration))
                    require(permission is not None and permission['from_version'] == version,
                            'Only an exact authorized auxiliary module/configuration/from binding may retire')
                    removed.append(dict(permission))
                elif actual != version:
                    grant = by_upgrade.get((module, configuration))
                    require(grant is not None and actual == grant['to_version'], 'An undeclared dependency version change is rejected')
                    changes.append(dict(grant))
    require(changes, 'A retirement migration requires an actual authorized version upgrade')
    removed = validate_actual_retirements(permissions, ordered(removed))
    return ordered_upgrades(changes), removed
