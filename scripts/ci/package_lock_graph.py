"""Schema 6 verifies two real producer/configuration graphs against final raw locks and pins."""
import json
from pathlib import Path
import re

from build import dependency_lock_snapshot
from ci_common import require
from package_contract import exact_data, safe_path
from package_lock_versions import BINARY_MODULES, pin_hashes

KIND = 'dependency-package-resolved-graph'
FILES = {'acquire': 'acquire-graph.json', 'strict-inputs': 'strict-graph.json'}
LOCKS = {':': 'gradle.lockfile', ':app': 'app/gradle.lockfile', ':companion': 'companion/gradle.lockfile'}
COORDINATE = re.compile(r'[A-Za-z0-9_.+\-]{1,120}')


def binary_upgrades(changes):
    modules = {module: extension for module, extension in BINARY_MODULES.values()}
    return {(*item['module'].split(':'), item['to_version']): modules[item['module']]
            for item in changes if item['module'] in modules}


def validate(root, record, state, phase, changes):
    from package_migration import (COLLECTOR, STRICT_GRAPH_TASK,
                                   validate_archive_budget, validate_archive_consumers, writer_tasks)
    request = state['collection']['request']
    require(request['schema_version'] == 6 and phase in FILES, 'Resolved graph validation belongs only to the independent schema 6')
    fields = {'schema_version', 'kind', 'phase', 'request_sha256', 'variants', 'writer_tasks', 'graph_task',
              'graph_task_count', 'test_task_count', 'configuration_count', 'unresolved_dependency_count',
              'project_configurations', 'runtime_classpaths', 'consumers', 'upgrade_archives', 'archive_budget'} | set(state['binding'])
    require(type(record) is dict and set(record) == fields and type(record.get('schema_version')) is int and record['schema_version'] == 1 and
            record.get('kind') == KIND and record.get('phase') == phase and
            record.get('request_sha256') == state['collection']['request_sha256'] and
            exact_data(record.get('variants'), request['variants']) and exact_data(record.get('writer_tasks'), writer_tasks(request)) and
            record.get('graph_task') == (COLLECTOR if phase == 'acquire' else STRICT_GRAPH_TASK) and
            all(type(record.get(key)) is type(value) and record[key] == value for key, value in state['binding'].items()),
            'Resolved graph provenance, phase or selected tasks differ')
    require(type(record.get('graph_task_count')) is int and 0 < record['graph_task_count'] <= 4096 and
            type(record.get('test_task_count')) is int and record['test_task_count'] == 0 and
            type(record.get('unresolved_dependency_count')) is int and record['unresolved_dependency_count'] == 0 and
            type(record.get('configuration_count')) is int and 0 < record['configuration_count'] <= 8192,
            'The real Test-free graph must be bounded and fully resolved')
    projects = record.get('project_configurations')
    require(type(projects) is dict and set(projects) == set(LOCKS), 'Both applications and the root graph must be present')
    pins = pin_hashes(safe_path(Path(root) / 'android', 'gradle/verification-metadata.xml', file=True).read_bytes())
    pinned_coordinates = {key[:3] for key in pins}
    selected = set()
    for project, configurations in projects.items():
        require(type(configurations) is dict and len(configurations) <= 4096 and
                all(type(name) is str and re.fullmatch(r'[A-Za-z0-9_.\-]{1,200}', name) for name in configurations),
                'Resolved configuration names are invalid')
        lock_path = safe_path(Path(root) / 'android', LOCKS[project])
        require(not configurations or lock_path.is_file(), 'A real resolved configuration has no final retained lock')
        lock = dependency_lock_snapshot(lock_path.read_bytes()) if lock_path.is_file() else {}
        require(set(configurations) == set(lock), 'Real resolvable configurations must exactly match final lock configurations')
        for configuration, modules in configurations.items():
            require(type(modules) is dict and len(modules) <= 20000 and
                    all(type(module) is str and len(module.split(':')) == 2 and
                        all(COORDINATE.fullmatch(part) for part in module.split(':')) and
                        type(version) is str and COORDINATE.fullmatch(version) for module, version in modules.items()),
                    'Resolved module bindings are invalid')
            require(modules == lock[configuration], 'A final lock does not match its real per-configuration graph')
            coordinates = {(*module.split(':'), version) for module, version in modules.items()}
            require(coordinates <= pinned_coordinates, 'A real selected module version has no retained verification pin')
            if project == ':app':
                selected |= coordinates
    require(sum(len(configurations) for configurations in projects.values()) == record['configuration_count'],
            'Resolved configuration count differs')
    files = validate_archive_consumers(record, request, strict=phase == 'strict-inputs')
    for consumer in record['consumers']:
        if consumer['kind'] not in ('configuration-artifacts', 'buildscript-artifacts'):
            continue
        for item in consumer['files']:
            coordinate = tuple(item['component'])
            require(pins.get((*coordinate, item['name'])) == item['sha256'], 'An actual module archive differs from its exact XML pin')
            if consumer['kind'] == 'configuration-artifacts':
                project, configuration = consumer['id'].rsplit(':', 1)
                require(project in projects and configuration in projects[project] and
                        projects[project][configuration].get(':'.join(coordinate[:2])) == coordinate[2],
                        'A runtime archive is absent from its real resolved configuration')
    archives = record.get('upgrade_archives')
    expected = binary_upgrades(changes)
    require(type(archives) is list and len(archives) == len(expected) and len(archives) <= 256,
            'Actual binary upgrades need separate bounded original archive evidence')
    seen = set()
    for item in archives:
        require(type(item) is dict and set(item) == {'component', 'name', 'size', 'sha256', 'evidence_kind'} and
                type(item.get('component')) is list and len(item['component']) == 3 and
                all(type(part) is str and COORDINATE.fullmatch(part) for part in item['component']),
                'Upgrade archive coordinates are invalid')
        coordinate = tuple(item['component'])
        require(coordinate in expected and coordinate in selected and coordinate not in seen and
                item.get('evidence_kind') == 'detached-artifact-only' and
                item.get('name') == coordinate[1] + '-' + coordinate[2] + '.' + expected[coordinate] and
                type(item.get('size')) is int and 0 < item['size'] <= 256 * 1024 * 1024 and
                type(item.get('sha256')) is str and re.fullmatch(r'[0-9a-f]{64}', item['sha256']) and
                pins.get((*coordinate, item['name'])) == item['sha256'],
                'Supplemental upgrade bytes must match an actually upgraded graph component and exact XML archive pin')
        seen.add(coordinate)
        files.append((item['name'], item['size'], item['sha256']))
    require([json.dumps(item, sort_keys=True, separators=(',', ':'), allow_nan=False) for item in archives] ==
            sorted(json.dumps(item, sort_keys=True, separators=(',', ':'), allow_nan=False) for item in archives),
            'Upgrade archive evidence is not canonical')
    validate_archive_budget(record.get('archive_budget', {}), files)


def compare(acquired, strict):
    require(exact_data(acquired['project_configurations'], strict['project_configurations']),
            'Independent strict resolution changed a configuration/module/version graph')
    def producers(record):
        return {item['consumer_id']: [value['id'] for value in item['producer_tasks']]
                for item in record['runtime_classpaths']}
    require(exact_data(producers(acquired), producers(strict)), 'Independent strict Test.classpath producer graph changed')
    require(exact_data(acquired['upgrade_archives'], strict['upgrade_archives']), 'Independent strict upgrade archive bytes changed')
    def module_archives(record):
        return {item['id']: item['files'] for item in record['consumers']
                if item['kind'] in ('configuration-artifacts', 'buildscript-artifacts')}
    require(exact_data(module_archives(acquired), module_archives(strict)), 'Independent strict runtime/buildscript archives changed')
