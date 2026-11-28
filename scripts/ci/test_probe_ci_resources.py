"""Synthetic fixtures only: importing/testing never samples the local hardware."""
import ast
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import stat
from types import SimpleNamespace
import unittest
from unittest import mock


SCRIPT = Path(__file__).with_name("probe_ci_resources.py")
with mock.patch("os.cpu_count", side_effect=AssertionError("Hardware sampling is forbidden")), mock.patch(
    "os.sched_getaffinity", create=True, side_effect=AssertionError("Hardware sampling is forbidden")):
    spec = importlib.util.spec_from_file_location("synthetic_resource_probe", SCRIPT)
    probe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(probe)

GIB = 1024 ** 3
UNLIMITED_V1 = 2 ** 63 - 4096


class FixtureEnvironment:
    def __init__(self, files, logical=8, affinity=None):
        self.files = dict(files)
        self.logical = logical
        self.mask = set(range(logical)) if affinity is None else affinity
        self.reads = {}
        self.timeout = False
        self.changed_membership = False
        self.namespace = True
        self.namespace_checks = 0
        self.changed_namespace = False

    def check(self):
        if self.timeout:
            raise probe.ProbeError("probe_timeout")

    def is_linux(self):
        return True

    def cpu_count(self):
        self.check()
        return self.logical

    def affinity(self):
        self.check()
        return self.mask

    def initial_cgroup_namespace(self):
        self.check()
        self.namespace_checks += 1
        return False if self.changed_namespace and self.namespace_checks > 1 else self.namespace

    def read(self, path, limit):
        self.check()
        self.reads[path] = self.reads.get(path, 0) + 1
        if self.changed_membership and path == "/proc/self/cgroup" and self.reads[path] > 1:
            return "0::/moved\n"
        value = self.files.get(path)
        if isinstance(value, Exception):
            raise value
        if value is not None and len(value.encode("utf-8")) > limit:
            raise probe.ProbeError("input_too_large")
        return value


def physical(total=16 * GIB, available=10 * GIB):
    return "MemTotal:       " + str(total // 1024) + " kB\nMemAvailable:   " + str(available // 1024) + " kB\n"


def v2_fixture(member="/job"):
    files = {
        "/proc/meminfo": physical(), "/proc/self/cgroup": "0::" + member + "\n",
        "/proc/self/mountinfo": "29 23 0:26 / /sys/fs/cgroup rw - cgroup2 cgroup rw\n",
    }
    nodes = ["/sys/fs/cgroup"]
    current = "/sys/fs/cgroup"
    for component in member.strip("/").split("/") if member != "/" else []:
        current += "/" + component
        nodes.append(current)
    for node in nodes:
        files[node + "/cgroup.controllers"] = "cpu cpuset memory\n"
        files[node + "/cpuset.cpus.effective"] = "0-7\n"
        if node != "/sys/fs/cgroup":
            files[node + "/cpu.max"] = "max 100000\n"
            files[node + "/memory.max"] = "max\n"
            files[node + "/memory.current"] = str(2 * GIB) + "\n"
    return FixtureEnvironment(files)


def v1_fixture():
    files = {
        "/proc/meminfo": physical(),
        "/proc/self/cgroup": "2:cpu,cpuacct:/job\n3:cpuset:/job\n4:memory:/job\n",
        "/proc/self/mountinfo": (
            "29 23 0:26 / /sys/fs/cgroup/cpu rw - cgroup cgroup rw,cpu,cpuacct\n"
            "30 23 0:27 / /sys/fs/cgroup/cpuset rw - cgroup cgroup rw,cpuset\n"
            "31 23 0:28 / /sys/fs/cgroup/memory rw - cgroup cgroup rw,memory\n"),
    }
    for suffix in ("", "/job"):
        files["/sys/fs/cgroup/cpu" + suffix + "/cpu.cfs_quota_us"] = "-1\n"
        files["/sys/fs/cgroup/cpu" + suffix + "/cpu.cfs_period_us"] = "100000\n"
        files["/sys/fs/cgroup/cpuset" + suffix + "/cpuset.effective_cpus"] = "0-7\n"
        files["/sys/fs/cgroup/memory" + suffix + "/memory.limit_in_bytes"] = str(UNLIMITED_V1) + "\n"
        files["/sys/fs/cgroup/memory" + suffix + "/memory.usage_in_bytes"] = str(2 * GIB) + "\n"
        files["/sys/fs/cgroup/memory" + suffix + "/memory.use_hierarchy"] = "1\n"
    return FixtureEnvironment(files)


class ResourceProbeTests(unittest.TestCase):
    def assert_single(self, output, status=None, complete=False):
        self.assertEqual(1, output["max_parallel_forks"])
        self.assertEqual(complete, output["measurement_complete"])
        if status is not None:
            self.assertEqual(status, output["measurement_status"])

    def test_v2_unlimited_caps_use_actual_affinity_and_memavailable(self):
        output = probe.measure(v2_fixture(), 2)
        self.assertTrue(output["measurement_complete"])
        self.assertEqual(2, output["max_parallel_forks"])
        self.assertEqual(8, output["logical_cpu_count"])
        self.assertEqual(8, output["affinity_cpu_count"])
        self.assertEqual(8, output["effective_cpu_count"])
        self.assertEqual(16 * GIB, output["effective_memory_total_bytes"])
        self.assertEqual(10 * GIB, output["effective_memory_available_bytes"])
        self.assertEqual(2 * GIB, output["cgroup_memory_current_bytes"])
        self.assertEqual("v2", output["cgroup_version"])

    def test_strict_cpu_threshold_uses_all_visible_parent_quotas(self):
        environment = v2_fixture("/team/job")
        environment.files["/sys/fs/cgroup/team/cpu.max"] = "200000 100000\n"
        environment.files["/sys/fs/cgroup/team/job/cpu.max"] = "300000 100000\n"
        output = probe.measure(environment, 2)
        self.assertEqual(2, output["effective_cpu_count"])
        self.assertEqual(2, output["cgroup_quota_cpu_count"])
        self.assertEqual(3, output["cgroup_cpu_ancestor_count"])
        self.assertEqual(2, output["max_parallel_forks"])
        environment.files["/sys/fs/cgroup/team/cpu.max"] = "199999 100000\n"
        self.assert_single(probe.measure(environment, 2), "low_resources", complete=True)

    def test_quota_float_rounding_cannot_promote_a_value_below_two(self):
        environment = v2_fixture()
        environment.files["/sys/fs/cgroup/job/cpu.max"] = "1999999999999999999 1000000000000000000\n"
        output = probe.measure(environment, 2)
        self.assert_single(output, "low_resources", complete=True)
        self.assertLess(output["cgroup_quota_cpu_count"], 2)
        self.assertLess(output["effective_cpu_count"], 2)

    def test_low_affinity_caps_an_otherwise_large_runner(self):
        output = probe.measure(v2_fixture(), 2)
        self.assertEqual(2, output["max_parallel_forks"])
        environment = v2_fixture()
        environment.mask = {0}
        self.assert_single(probe.measure(environment, 2), "low_resources", complete=True)

    def test_disjoint_or_nonnested_cpu_sets_are_conflicting_evidence(self):
        environment = v2_fixture()
        environment.files["/sys/fs/cgroup/job/cpuset.cpus.effective"] = "4-7\n"
        environment.files["/sys/fs/cgroup/cpuset.cpus.effective"] = "0-3\n"
        self.assert_single(probe.measure(environment, 2), "cgroup_conflict")

    def test_memory_total_and_headroom_can_have_different_limiting_ancestors(self):
        environment = v2_fixture("/team/job")
        environment.files["/sys/fs/cgroup/team/job/memory.max"] = str(8 * GIB)
        environment.files["/sys/fs/cgroup/team/job/memory.current"] = str(GIB)
        environment.files["/sys/fs/cgroup/team/memory.max"] = str(12 * GIB)
        environment.files["/sys/fs/cgroup/team/memory.current"] = str(10 * GIB)
        output = probe.measure(environment, 2)
        self.assert_single(output, "low_resources", complete=True)
        self.assertEqual(8 * GIB, output["effective_memory_total_bytes"])
        self.assertEqual(2 * GIB, output["effective_memory_available_bytes"])
        self.assertEqual(8 * GIB, output["cgroup_memory_limit_bytes"])
        self.assertEqual(GIB, output["cgroup_memory_current_bytes"])
        self.assertEqual(12 * GIB, output["cgroup_memory_available_limit_bytes"])
        self.assertEqual(10 * GIB, output["cgroup_memory_available_current_bytes"])
        self.assertEqual(2, output["cgroup_memory_limit_sample_count"])

    def test_memory_boundary_is_six_total_and_three_available_gib(self):
        environment = v2_fixture()
        environment.files["/proc/meminfo"] = physical(6 * GIB, 3 * GIB)
        self.assertEqual(2, probe.measure(environment, 2)["max_parallel_forks"])
        for total, available in ((6 * GIB - 1024, 3 * GIB), (6 * GIB, 3 * GIB - 1024)):
            environment.files["/proc/meminfo"] = physical(total, available)
            self.assert_single(probe.measure(environment, 2), "low_resources", complete=True)

    def test_worker_limit_is_authoritative_and_forks_never_exceed_two(self):
        self.assert_single(probe.measure(v2_fixture(), 1), "worker_limit", complete=True)
        self.assertEqual(2, probe.measure(v2_fixture(), 512)["max_parallel_forks"])

    def test_v1_unlimited_sentinel_and_actual_counters_are_recorded(self):
        output = probe.measure(v1_fixture(), 2)
        self.assertTrue(output["measurement_complete"])
        self.assertEqual(2, output["max_parallel_forks"])
        self.assertEqual("v1", output["cgroup_version"])
        self.assertIsNone(output["cgroup_memory_limit_bytes"])
        self.assertEqual(UNLIMITED_V1, output["cgroup_memory_leaf_reported_limit_bytes"])
        self.assertEqual(2 * GIB, output["cgroup_memory_current_bytes"])

    def test_v1_parent_quota_and_cpuset_fallback_both_limit_execution(self):
        environment = v1_fixture()
        environment.files["/sys/fs/cgroup/cpu/cpu.cfs_quota_us"] = "150000\n"
        for suffix in ("", "/job"):
            del environment.files["/sys/fs/cgroup/cpuset" + suffix + "/cpuset.effective_cpus"]
            environment.files["/sys/fs/cgroup/cpuset" + suffix + "/cpuset.cpus"] = "0-3\n"
        environment.mask = {0, 1, 2, 3}
        output = probe.measure(environment, 2)
        self.assert_single(output, "low_resources", complete=True)
        self.assertEqual(1.5, output["effective_cpu_count"])
        self.assertEqual(4, output["cgroup_cpuset_cpu_count"])

    def test_v1_nonhierarchical_parent_is_not_claimed_as_a_child_limit(self):
        environment = v1_fixture()
        environment.files["/sys/fs/cgroup/memory/job/memory.limit_in_bytes"] = str(8 * GIB)
        environment.files["/sys/fs/cgroup/memory/job/memory.usage_in_bytes"] = str(GIB)
        environment.files["/sys/fs/cgroup/memory/memory.limit_in_bytes"] = str(2 * GIB)
        environment.files["/sys/fs/cgroup/memory/memory.usage_in_bytes"] = str(GIB)
        environment.files["/sys/fs/cgroup/memory/memory.use_hierarchy"] = "0"
        output = probe.measure(environment, 2)
        self.assertEqual(2, output["max_parallel_forks"])
        self.assertEqual(8 * GIB, output["effective_memory_total_bytes"])
        self.assertEqual(7 * GIB, output["effective_memory_available_bytes"])

    def test_missing_memavailable_does_not_guess_from_free_or_cache(self):
        environment = v2_fixture()
        environment.files["/proc/meminfo"] = "MemTotal: 16777216 kB\nMemFree: 12000000 kB\nCached: 100000 kB\n"
        self.assert_single(probe.measure(environment, 2), "read_unavailable")

    def test_missing_active_controller_file_and_counter_disable_parallelism(self):
        for field in ("cpu.max", "cpuset.cpus.effective", "memory.max", "memory.current"):
            environment = v2_fixture()
            del environment.files["/sys/fs/cgroup/job/" + field]
            self.assert_single(probe.measure(environment, 2), "read_unavailable")

    def test_verified_v2_root_has_neither_memory_limit_nor_current_interface(self):
        environment = v2_fixture()
        self.assertNotIn("/sys/fs/cgroup/memory.current", environment.files)
        output = probe.measure(environment, 2)
        self.assertTrue(output["measurement_complete"])
        self.assertEqual(2, output["max_parallel_forks"])
        self.assertEqual("unlimited", output["cgroup_memory_status"])
        self.assertEqual(2 * GIB, output["cgroup_memory_leaf_current_bytes"])

    def test_v2_root_only_uses_host_limits_and_still_requires_initial_namespace(self):
        environment = v2_fixture("/")
        self.assertNotIn("/sys/fs/cgroup/memory.current", environment.files)
        output = probe.measure(environment, 2)
        self.assertTrue(output["measurement_complete"])
        self.assertEqual(2, output["max_parallel_forks"])
        self.assertEqual(16 * GIB, output["effective_memory_total_bytes"])
        self.assertEqual(10 * GIB, output["effective_memory_available_bytes"])
        self.assertIsNone(output["cgroup_memory_leaf_current_bytes"])
        self.assertEqual(1, output["cgroup_memory_ancestor_count"])
        for namespace, status in ((False, "hidden_cgroup_ancestors"), (None, "read_unavailable")):
            environment.namespace = namespace
            self.assert_single(probe.measure(environment, 2), status)

    def test_absent_root_memory_interfaces_do_not_hide_parent_or_child_limits(self):
        environment = v2_fixture("/team/job")
        self.assertNotIn("/sys/fs/cgroup/memory.current", environment.files)
        environment.files["/sys/fs/cgroup/team/memory.max"] = str(8 * GIB)
        environment.files["/sys/fs/cgroup/team/memory.current"] = str(6 * GIB)
        output = probe.measure(environment, 2)
        self.assert_single(output, "low_resources", complete=True)
        self.assertEqual(8 * GIB, output["effective_memory_total_bytes"])
        self.assertEqual(2 * GIB, output["effective_memory_available_bytes"])
        environment.files["/sys/fs/cgroup/team/memory.current"] = str(4 * GIB)
        environment.files["/sys/fs/cgroup/team/job/cpu.max"] = "150000 100000\n"
        self.assert_single(probe.measure(environment, 2), "low_resources", complete=True)
        del environment.files["/sys/fs/cgroup/team/job/memory.current"]
        self.assert_single(probe.measure(environment, 2), "read_unavailable")

    def test_hidden_mount_root_never_implies_unmeasured_ancestors_are_unlimited(self):
        environment = v2_fixture()
        environment.files["/proc/self/mountinfo"] = "29 23 0:26 /job /sys/fs/cgroup rw - cgroup2 cgroup rw\n"
        self.assert_single(probe.measure(environment, 2), "hidden_cgroup_ancestors")

    def test_unmapped_or_ambiguous_cgroup_mounts_are_unknown(self):
        environment = v2_fixture()
        environment.files["/proc/self/mountinfo"] = "29 23 0:26 /elsewhere /sys/fs/cgroup rw - cgroup2 cgroup rw\n"
        self.assert_single(probe.measure(environment, 2), "cgroup_mapping_unavailable")
        environment = v2_fixture()
        environment.files["/proc/self/mountinfo"] += "30 23 0:26 / /second rw - cgroup2 cgroup rw\n"
        self.assert_single(probe.measure(environment, 2), "cgroup_mapping_unavailable")

    def test_hybrid_controller_membership_and_migration_are_not_reused(self):
        environment = v2_fixture()
        environment.files["/proc/self/cgroup"] += "2:cpu:/job\n"
        self.assert_single(probe.measure(environment, 2), "cgroup_conflict")
        environment = v2_fixture()
        environment.changed_membership = True
        output = probe.measure(environment, 2)
        self.assert_single(output, "cgroup_conflict")
        self.assertIsNone(output["effective_cpu_count"])

    def test_no_cgroup_has_an_explicit_none_basis(self):
        environment = FixtureEnvironment({"/proc/meminfo": physical(), "/proc/self/cgroup": "",
            "/proc/self/mountinfo": "25 1 0:20 / /proc rw - proc proc rw\n"})
        output = probe.measure(environment, 2)
        self.assertEqual(2, output["max_parallel_forks"])
        self.assertEqual("none", output["cgroup_version"])
        self.assertEqual("none", output["cgroup_memory_status"])

    def test_deep_ancestry_and_oversized_kernel_input_are_bounded(self):
        environment = v2_fixture("/" + "/".join(["level"] * probe.MAX_ANCESTORS))
        self.assert_single(probe.measure(environment, 2), "input_too_large")
        environment = v2_fixture()
        environment.files["/proc/self/mountinfo"] = "x" * 131073
        self.assert_single(probe.measure(environment, 2), "input_too_large")

    def test_conflicting_memory_numbers_and_bad_cpu_ranges_do_not_enable_two(self):
        environment = v2_fixture()
        environment.files["/sys/fs/cgroup/job/memory.max"] = str(GIB)
        environment.files["/sys/fs/cgroup/job/memory.current"] = str(2 * GIB)
        self.assert_single(probe.measure(environment, 2), "cgroup_conflict")
        environment = v2_fixture()
        environment.files["/sys/fs/cgroup/job/cpuset.cpus.effective"] = "0-3,2-7"
        self.assert_single(probe.measure(environment, 2), "invalid_measurement")

    def test_read_errors_timeout_and_internal_exception_never_echo_private_text(self):
        environment = v2_fixture()
        environment.timeout = True
        self.assert_single(probe.measure(environment, 2), "probe_timeout")
        environment = v2_fixture()
        private = "PRIVATE_FIXTURE_TEXT path identifier address"
        environment.files["/proc/meminfo"] = RuntimeError(private)
        output = probe.measure(environment, 2)
        self.assert_single(output, "internal_error")
        self.assertNotIn(private, probe.encode_result(output))

    def test_namespace_gate_accepts_only_initial_and_rejects_hidden_or_unknown(self):
        output = probe.measure(v2_fixture(), 2)
        self.assertTrue(output["cgroup_namespace_initial_verified"])
        self.assertEqual(2, output["max_parallel_forks"])
        for value, status in ((False, "hidden_cgroup_ancestors"), (None, "read_unavailable")):
            environment = v2_fixture()
            environment.namespace = value
            output = probe.measure(environment, 2)
            self.assert_single(output, status)
            self.assertIs(output["cgroup_namespace_initial_verified"], value)
            self.assertEqual(16 * GIB, output["physical_memory_total_bytes"])
            self.assertIsNone(output["effective_cpu_count"])
        environment = v2_fixture()
        environment.changed_namespace = True
        output = probe.measure(environment, 2)
        self.assert_single(output, "cgroup_conflict")
        self.assertFalse(output["cgroup_namespace_initial_verified"])

    def test_namespace_abi_comparison_is_memory_only_and_does_not_emit_identifiers(self):
        self.assertTrue(probe.namespace_initial_verified("cgroup:[4026531835]"))
        self.assertFalse(probe.namespace_initial_verified("cgroup:[4026539000]"))
        self.assertIsNone(probe.namespace_initial_verified("PRIVATE_FIXTURE_IDENTIFIER"))
        with mock.patch.object(probe.time, "monotonic", return_value=0), mock.patch.object(
            probe.os, "readlink", return_value="cgroup:[4026531835]") as readlink:
            environment = probe.LinuxEnvironment()
            self.assertTrue(environment.initial_cgroup_namespace())
            self.assertEqual(1, environment.files)
            readlink.assert_called_once_with("/proc/self/ns/cgroup")
        output = probe.measure(v2_fixture(), 2)
        encoded = probe.encode_result(output)
        self.assertNotIn("4026531835", encoded)
        self.assertNotIn("/proc/self/ns/cgroup", encoded)
        with self.assertRaises(probe.ProbeError) as error:
            probe.namespace_initial_verified("x" * 65)
        self.assertEqual("input_too_large", error.exception.status)

    def test_real_reader_byte_count_and_soft_deadline_use_only_mocked_io(self):
        flags = {"O_NONBLOCK": 1, "O_CLOEXEC": 2, "O_NOFOLLOW": 4}
        with mock.patch.object(probe.time, "monotonic", return_value=0), contextlib.ExitStack() as stack:
            for name, value in flags.items():
                stack.enter_context(mock.patch.object(probe.os, name, value, create=True))
            opened = stack.enter_context(mock.patch.object(probe.os, "open", return_value=55))
            stack.enter_context(mock.patch.object(probe.os, "fstat", return_value=SimpleNamespace(st_mode=stat.S_IFREG)))
            stack.enter_context(mock.patch.object(probe.os, "read", side_effect=[b"123456789"]))
            closed = stack.enter_context(mock.patch.object(probe.os, "close"))
            environment = probe.LinuxEnvironment()
            with self.assertRaises(probe.ProbeError) as error:
                environment.read("/synthetic/file", 8)
            self.assertEqual("input_too_large", error.exception.status)
            self.assertEqual(9, environment.bytes)
            self.assertEqual(1, opened.call_count)
            closed.assert_called_once_with(55)
            environment = probe.LinuxEnvironment()
            with mock.patch.object(probe.time, "monotonic", return_value=3):
                with self.assertRaises(probe.ProbeError) as error:
                    environment.read("/synthetic/file", 8)
                self.assertEqual("probe_timeout", error.exception.status)
            self.assertEqual(1, opened.call_count)

    def test_cli_valid_and_invalid_arguments_emit_only_one_bounded_json_line(self):
        allowed_strings = {"ok", "worker_limit", "low_resources", "invalid_arguments", "unknown", "v2", "finite", "unlimited"}
        for arguments in (["--worker-limit", "2"], ["--worker-limit", "0"], ["--worker-limit", "9999999"],
            ["--worker-limit", "PRIVATE_FIXTURE_ARG\n"], ["--worker-limit", "2", "extra"], []):
            stdout, stderr = io.StringIO(), io.StringIO()
            with mock.patch.object(probe, "LinuxEnvironment", return_value=v2_fixture()), contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(0, probe.main(arguments))
            raw = stdout.getvalue()
            self.assertEqual(1, raw.count("\n"))
            self.assertLessEqual(len(raw.encode("utf-8")), 4096)
            self.assertEqual("", stderr.getvalue())
            self.assertNotIn("PRIVATE_FIXTURE_ARG", raw)
            output = json.loads(raw)
            for value in output.values():
                self.assertTrue(value is None or type(value) in (bool, int, float) or value in allowed_strings)
            self.assertEqual(1, output["schema_version"])
            self.assertIn(output["max_parallel_forks"], (1, 2))

    def test_source_has_no_subprocess_network_or_environment_dump(self):
        source = SCRIPT.read_text(encoding="utf-8")
        tree = ast.parse(source)
        imports = {node.names[0].name for node in ast.walk(tree) if isinstance(node, ast.Import)}
        self.assertFalse(imports.intersection({"subprocess", "socket", "requests", "urllib", "platform"}))
        self.assertNotIn("os.environ", source)
        self.assertNotIn("gethostname", source)
        self.assertNotIn("traceback", imports)


if __name__ == "__main__":
    unittest.main()
