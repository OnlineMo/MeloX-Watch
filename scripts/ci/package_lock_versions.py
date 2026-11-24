"""Schema 6 grants exact Watch lock upgrades; earlier acquisition policies stay immutable."""
import re

from build import dependency_lock_snapshot, verification_snapshot
from ci_common import require

KIND = 'owner-pr-package-dependency-upgrade-acquire-strict-compile'
FIELDS = {'package_role', 'module', 'configuration', 'from_version', 'to_version'}
GROUPS = {
    'okio': ('com.squareup.okio:okio', 'com.squareup.okio:okio-jvm'),
    'annotation': ('androidx.annotation:annotation', 'androidx.annotation:annotation-jvm'),
    'startup': ('androidx.startup:startup-runtime',),
}
MODULE_GROUP = {module: group for group, modules in GROUPS.items() for module in modules}
# Metadata aliases can remain at their base version while their actual JVM binding upgrades.
BINARY_MODULES = {
    'okio': ('com.squareup.okio:okio-jvm', 'jar'),
    'annotation': ('androidx.annotation:annotation-jvm', 'jar'),
    'startup': ('androidx.startup:startup-runtime', 'aar'),
}
CONFIGURATION = re.compile(
    r'(?:watchAndroid(?:Debug|Release|Diagnostic)(?:UnitTest|AndroidTest)?(?:Compile|Runtime)Classpath|'
    r'(?:implementation|testImplementation|androidTestImplementation)DependenciesMetadata)')
VERSION = re.compile(r'(?:0|[1-9][0-9]{0,8})(?:\.(?:0|[1-9][0-9]{0,8})){0,3}')


def ordered(upgrades):
    return sorted(upgrades, key=lambda item: tuple(item[name] for name in
                  ('package_role', 'module', 'configuration', 'from_version', 'to_version')))


def version_parts(value):
    require(type(value) is str and VERSION.fullmatch(value), 'Upgrade versions must be explicit stable numeric versions')
    parts = tuple(int(part) for part in value.split('.'))
    return parts + (0,) * (4 - len(parts))


def validate_upgrades(upgrades, watch_lock):
    """Canonicalize an owner grant against the raw frozen baseline, configuration by configuration."""
    require(type(upgrades) is list and 0 < len(upgrades) <= 256, 'Schema 6 requires bounded explicit dependency upgrades')
    baseline = dependency_lock_snapshot(watch_lock)
    seen = set()
    targets = {}
    result = []
    for item in upgrades:
        require(type(item) is dict and set(item) == FIELDS and item.get('package_role') == 'watch' and
                all(type(item[name]) is str for name in FIELDS), 'An upgrade must bind exactly one Watch module/configuration/version pair')
        module, configuration = item['module'], item['configuration']
        require(module in MODULE_GROUP and CONFIGURATION.fullmatch(configuration), 'Unknown modules or tooling/phone configurations cannot upgrade')
        require(configuration in baseline and baseline[configuration].get(module) == item['from_version'],
                'Upgrade from-version must match an existing frozen module/configuration binding')
        require(version_parts(item['to_version']) > version_parts(item['from_version']), 'Dependency upgrades cannot be equal or downgrade')
        key = (module, configuration)
        require(key not in seen, 'Dependency upgrade bindings cannot be duplicated')
        seen.add(key)
        group = MODULE_GROUP[module]
        require(group not in targets or targets[group] == item['to_version'], 'One alias group cannot authorize conflicting target versions')
        targets[group] = item['to_version']
        result.append(dict(item))
    return ordered(result)


def pin_hashes(raw):
    """Keep the existing XML parser's trust/configuration rules and expose its exact artifact hashes."""
    _, pins = verification_snapshot(raw)
    return {key: dict(tree[3][0][1])['value'] for key, tree in pins.items()}


def growth(before, after, upgrades):
    require(set(before) <= set(after), 'Package upgrade removed an admitted input')
    grants = validate_upgrades(upgrades, before['app/gradle.lockfile'])
    require(grants == upgrades, 'Dependency upgrade grants must be canonical')
    old_configuration, old_pins = verification_snapshot(before['gradle/verification-metadata.xml'])
    new_configuration, new_pins = verification_snapshot(after['gradle/verification-metadata.xml'])
    require(old_configuration == new_configuration and all(new_pins.get(key) == value for key, value in old_pins.items()),
            'Existing artifact trust pins cannot be removed or changed by an upgrade')
    by_binding = {(item['module'], item['configuration']): item for item in grants}
    changes = []
    for name, raw in before.items():
        if name == 'gradle/verification-metadata.xml':
            continue
        if name != 'app/gradle.lockfile':
            require(after[name] == raw, 'Companion/root locks and admitted Room schemas must retain their original bytes')
            continue
        previous, updated = dependency_lock_snapshot(raw), dependency_lock_snapshot(after[name])
        require(set(previous) <= set(updated), 'A frozen Watch lock configuration cannot disappear')
        for configuration, modules in previous.items():
            require(set(modules) <= set(updated[configuration]), 'A frozen Watch lock module cannot disappear')
            for module, version in modules.items():
                actual = updated[configuration][module]
                if actual == version:
                    continue
                grant = by_binding.get((module, configuration))
                require(grant is not None and actual == grant['to_version'], 'An undeclared dependency version change is rejected')
                changes.append(dict(grant))
    require(changes, 'A dependency version migration cannot be a complete no-op')
    return ordered(changes)
