import copy
import unittest
from workflow_policy import validate_workflow


class WorkflowBoundaryTests(unittest.TestCase):
    def test_compiler_must_not_receive_keys_or_write_permission(self):
        workflow = {'permissions': {'contents': 'read'}, 'jobs': {'compile': {
            'name': 'android-compile', 'steps': [{'run': 'python3 scripts/ci/build.py debug'}]}}}
        validate_workflow(workflow)
        with_key = copy.deepcopy(workflow)
        with_key['jobs']['compile']['steps'][0]['env'] = {'SIGNING_KEY_B64': '${{ secrets.KEY }}'}
        with_write = copy.deepcopy(workflow)
        with_write['jobs']['compile']['permissions'] = {'contents': 'write'}
        for value in [with_key, with_write]:
            with self.assertRaises(ValueError):
                validate_workflow(value)

    def test_signer_cannot_run_gradle_setup_with_key(self):
        workflow = {'permissions': {'contents': 'read'}, 'jobs': {'sign': {
            'name': 'sign-release', 'runs-on': 'ubuntu-24.04', 'steps': [
                {'run': 'python3 scripts/ci/sign.py prepare release'},
                {'run': 'python3 scripts/ci/sign.py sign release', 'env': {'SIGNING_KEY_B64': '${{ secrets.KEY }}'}}]}}}
        validate_workflow(workflow)
        workflow['jobs']['sign']['steps'].insert(0, {'uses': './.github/actions/setup-build'})
        with self.assertRaisesRegex(ValueError, 'application build'):
            validate_workflow(workflow)

    def test_publisher_cannot_rebuild_resign_or_use_signing_secrets(self):
        workflow = {'permissions': {'contents': 'read'}, 'jobs': {'publish': {
            'name': 'publish-accepted-candidate', 'permissions': {'contents': 'write', 'actions': 'read'},
            'if': "inputs.operation == 'publish-candidate'", 'steps': [{'run': 'python3 scripts/ci/release.py'}]}}}
        validate_workflow(workflow)
        for command in ['python3 scripts/ci/build.py release', 'python3 scripts/ci/sign.py sign release']:
            changed = copy.deepcopy(workflow)
            changed['jobs']['publish']['steps'].append({'run': command})
            with self.assertRaises(ValueError):
                validate_workflow(changed)


if __name__ == '__main__':
    unittest.main()
