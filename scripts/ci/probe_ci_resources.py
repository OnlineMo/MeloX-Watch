#!/usr/bin/env python3
"""Bounded Linux resource snapshot; stdout contains only fixed-schema scalar JSON.

The CI caller must still impose a three-second process timeout. No command, network,
environment dump or raw kernel text is emitted. Missing evidence never enables forks.
"""
from fractions import Fraction
import json
import math
import os
import posixpath
import re
import stat
import sys
import time


GIB = 1024 ** 3
MAX_INTEGER = 2 ** 63 - 1
MAX_CPU_ID = 262143
MAX_CPU_SET_SIZE = 65536
MAX_ANCESTORS = 32
MAX_FILES = 224
MAX_TOTAL_BYTES = 1024 ** 2
SOFT_TIMEOUT_SECONDS = 2.4
# Linux include/linux/proc_ns.h, PROC_CGROUP_INIT_INO = 0xEFFFFFFB.
# This pinned initial-namespace ABI value is compared in memory and never emitted.
CGROUP_INITIAL_NAMESPACE_INODE = 4026531835


class ProbeError(Exception):
    def __init__(self, status):
        self.status = status


class LinuxEnvironment:
    """Only fixed proc files and kernel-described cgroup files are read."""
    def __init__(self):
        self.deadline = time.monotonic() + SOFT_TIMEOUT_SECONDS
        self.files = 0
        self.bytes = 0

    def check(self):
        if time.monotonic() >= self.deadline:
            raise ProbeError("probe_timeout")

    def is_linux(self):
        return sys.platform == "linux"

    def cpu_count(self):
        self.check()
        return os.cpu_count()

    def affinity(self):
        self.check()
        try:
            return os.sched_getaffinity(0)
        except (AttributeError, OSError):
            raise ProbeError("read_unavailable") from None

    def initial_cgroup_namespace(self):
        self.check()
        self.files += 1
        if self.files > MAX_FILES:
            raise ProbeError("input_too_large")
        try:
            target = os.readlink("/proc/self/ns/cgroup")
        except (AttributeError, OSError):
            return None
        self.check()
        return namespace_initial_verified(target)

    def read(self, path, limit):
        self.check()
        self.files += 1
        if self.files > MAX_FILES:
            raise ProbeError("input_too_large")
        descriptor = None
        try:
            descriptor = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_CLOEXEC | os.O_NOFOLLOW)
            if not stat.S_ISREG(os.fstat(descriptor).st_mode):
                raise ProbeError("read_unavailable")
            chunks, size = [], 0
            while size <= limit:
                self.check()
                chunk = os.read(descriptor, min(4096, limit + 1 - size))
                if not chunk:
                    break
                chunks.append(chunk)
                size += len(chunk)
                self.bytes += len(chunk)
                if self.bytes > MAX_TOTAL_BYTES:
                    raise ProbeError("input_too_large")
            if size > limit:
                raise ProbeError("input_too_large")
            self.check()
            return b"".join(chunks).decode("utf-8", errors="strict")
        except FileNotFoundError:
            return None
        except (OSError, UnicodeError):
            raise ProbeError("read_unavailable") from None
        finally:
            if descriptor is not None:
                os.close(descriptor)


def result_base(worker_limit, status="unknown"):
    return {
        "schema_version": 1, "measurement_complete": False,
        "max_parallel_forks": 1, "worker_limit": worker_limit,
        "measurement_status": status,
        "logical_cpu_count": None, "affinity_cpu_count": None,
        "cgroup_quota_cpu_count": None, "cgroup_cpuset_cpu_count": None,
        "effective_cpu_count": None,
        "physical_memory_total_bytes": None, "physical_memory_available_bytes": None,
        "cgroup_memory_limit_bytes": None, "cgroup_memory_current_bytes": None,
        "cgroup_memory_available_bytes": None,
        "cgroup_memory_leaf_current_bytes": None,
        "cgroup_memory_leaf_reported_limit_bytes": None,
        "cgroup_memory_available_limit_bytes": None,
        "cgroup_memory_available_current_bytes": None,
        "effective_memory_total_bytes": None, "effective_memory_available_bytes": None,
        "cgroup_version": "unknown",
        "cgroup_namespace_initial_verified": None,
        "cgroup_cpu_quota_status": "unknown", "cgroup_cpuset_status": "unknown",
        "cgroup_memory_status": "unknown",
        "cgroup_cpu_ancestor_count": 0, "cgroup_cpuset_ancestor_count": 0,
        "cgroup_memory_ancestor_count": 0, "cgroup_memory_limit_sample_count": 0,
    }


def namespace_initial_verified(target):
    if len(target) > 64:
        raise ProbeError("input_too_large")
    match = re.fullmatch(r"cgroup:\[([0-9]{1,10})\]", target)
    return int(match[1]) == CGROUP_INITIAL_NAMESPACE_INODE if match is not None else None


def integer(text, zero=False):
    if not re.fullmatch(r"[0-9]{1,19}", text):
        raise ProbeError("invalid_measurement")
    value = int(text)
    if value > MAX_INTEGER or value < (0 if zero else 1):
        raise ProbeError("invalid_measurement")
    return value


def cpu_set(text):
    value = set()
    if not text.strip():
        raise ProbeError("cgroup_conflict")
    for item in text.strip().split(","):
        if not re.fullmatch(r"[0-9]{1,6}(?:-[0-9]{1,6})?", item):
            raise ProbeError("invalid_measurement")
        edges = item.split("-")
        start, end = int(edges[0]), int(edges[-1])
        if start > end or end > MAX_CPU_ID or end - start + 1 > MAX_CPU_SET_SIZE:
            raise ProbeError("input_too_large")
        if len(value) + end - start + 1 > MAX_CPU_SET_SIZE:
            raise ProbeError("input_too_large")
        segment = set(range(start, end + 1))
        if value.intersection(segment):
            raise ProbeError("invalid_measurement")
        value.update(segment)
    return value


def checked_affinity(value):
    if not isinstance(value, (set, frozenset)) or not value or len(value) > MAX_CPU_SET_SIZE:
        raise ProbeError("invalid_measurement")
    if any(type(cpu) is not int or not 0 <= cpu <= MAX_CPU_ID for cpu in value):
        raise ProbeError("invalid_measurement")
    return frozenset(value)


def memory_info(text):
    if text is None:
        raise ProbeError("read_unavailable")
    fields = {}
    for line in text.splitlines():
        if line.startswith(("MemTotal:", "MemAvailable:")):
            match = re.fullmatch(r"(MemTotal|MemAvailable):\s+([0-9]{1,19})\s+kB\s*", line)
            if match is None or match[1] in fields:
                raise ProbeError("invalid_measurement")
            fields[match[1]] = integer(match[2], zero=True) * 1024
    if set(fields) != {"MemTotal", "MemAvailable"}:
        raise ProbeError("read_unavailable")
    total, available = fields["MemTotal"], fields["MemAvailable"]
    if not 0 <= available <= total <= MAX_INTEGER or total == 0:
        raise ProbeError("invalid_measurement")
    return total, available


def kernel_path(text):
    # Mountinfo escapes are decoded internally and never included in the result.
    text = re.sub(r"\\(040|011|012|134)", lambda match: chr(int(match[1], 8)), text)
    if not text.startswith("/") or any(ord(character) < 32 for character in text):
        raise ProbeError("invalid_measurement")
    if any(piece in (".", "..") for piece in text.split("/")):
        raise ProbeError("invalid_measurement")
    return posixpath.normpath(text)


def cgroup_membership(text):
    if text is None:
        raise ProbeError("read_unavailable")
    v2, controllers, hierarchy_ids = None, {}, set()
    for line in text.splitlines():
        parts = line.split(":", 2)
        if len(parts) != 3 or not re.fullmatch(r"[0-9]{1,10}", parts[0]) or parts[0] in hierarchy_ids:
            raise ProbeError("invalid_measurement")
        hierarchy_ids.add(parts[0])
        member = kernel_path(parts[2])
        if parts[:2] == ["0", ""]:
            if v2 is not None:
                raise ProbeError("cgroup_conflict")
            v2 = member
            continue
        if not parts[1] or parts[0] == "0":
            raise ProbeError("invalid_measurement")
        for controller in parts[1].split(","):
            if not re.fullmatch(r"[a-zA-Z0-9_=.-]{1,128}", controller) or controller in controllers:
                raise ProbeError("cgroup_conflict")
            controllers[controller] = member
    return v2, controllers, bool(hierarchy_ids)


def cgroup_mounts(text):
    if text is None:
        raise ProbeError("read_unavailable")
    mounts = []
    for line in text.splitlines():
        parts = line.split(" - ")
        if len(parts) != 2:
            raise ProbeError("invalid_measurement")
        before, after = parts[0].split(), parts[1].split()
        if len(before) < 6 or len(after) < 3:
            raise ProbeError("invalid_measurement")
        if after[0] in ("cgroup", "cgroup2"):
            mounts.append((after[0], kernel_path(before[3]), kernel_path(before[4]), set(after[2].split(","))))
    return mounts


def directories(member, candidates):
    candidates = [mount for mount in candidates if mount[1] == "/" or member == mount[1]
        or member.startswith(mount[1] + "/")]
    if len(candidates) != 1:
        raise ProbeError("cgroup_mapping_unavailable")
    _, root, mountpoint, _ = candidates[0]
    if root != "/":
        raise ProbeError("hidden_cgroup_ancestors")
    relative = member.lstrip("/")
    current = posixpath.join(mountpoint, relative) if relative else mountpoint
    nodes = []
    while True:
        if len(nodes) >= MAX_ANCESTORS:
            raise ProbeError("input_too_large")
        nodes.append(current)
        if current == mountpoint:
            return nodes
        current = posixpath.dirname(current)
        if not current.startswith(mountpoint + "/") and current != mountpoint:
            raise ProbeError("cgroup_mapping_unavailable")


def required(environment, path, limit=1024):
    value = environment.read(path, limit)
    if value is None:
        raise ProbeError("read_unavailable")
    return value.strip()


def v2_samples(environment, nodes):
    quotas, sets, memory, leaf_reported = [], [], [], None
    for index, node in enumerate(nodes):
        controllers = required(environment, node + "/cgroup.controllers").split()
        if any(not re.fullmatch(r"[a-z][a-z0-9_]{0,127}", item) for item in controllers):
            raise ProbeError("invalid_measurement")
        root = index == len(nodes) - 1
        quota = environment.read(node + "/cpu.max", 1024)
        if quota is None:
            if not root and "cpu" in controllers:
                raise ProbeError("read_unavailable")
        else:
            parts = quota.strip().split()
            if len(parts) != 2:
                raise ProbeError("invalid_measurement")
            period = integer(parts[1])
            if parts[0] != "max":
                quotas.append(Fraction(integer(parts[0]), period))
        cpus = environment.read(node + "/cpuset.cpus.effective", 16384)
        if cpus is None:
            if "cpuset" in controllers:
                raise ProbeError("read_unavailable")
        else:
            sets.append(cpu_set(cpus))
        maximum = environment.read(node + "/memory.max", 1024)
        current = environment.read(node + "/memory.current", 1024)
        if maximum is None:
            if not root and ("memory" in controllers or current is not None):
                raise ProbeError("read_unavailable")
            if "memory" in controllers and current is None:
                raise ProbeError("read_unavailable")
            # Root memory.current exists without memory.max: the root has no limit.
            memory.append((None, integer(current.strip(), zero=True) if current is not None else None))
        else:
            limit = None if maximum.strip() == "max" else integer(maximum.strip(), zero=True)
            if current is None:
                raise ProbeError("read_unavailable")
            memory.append((limit, integer(current.strip(), zero=True)))
            if index == 0:
                leaf_reported = limit
    return quotas, sets, memory, leaf_reported


def v1_samples(environment, mapping):
    quotas, sets, memory, leaf_reported = [], [], [], None
    for node in mapping.get("cpu", []):
        quota = required(environment, node + "/cpu.cfs_quota_us")
        period = integer(required(environment, node + "/cpu.cfs_period_us"))
        if quota != "-1":
            quotas.append(Fraction(integer(quota), period))
    for node in mapping.get("cpuset", []):
        cpus = environment.read(node + "/cpuset.effective_cpus", 16384)
        if cpus is None:
            cpus = required(environment, node + "/cpuset.cpus", 16384)
            if not cpus:
                continue  # A legacy empty child inherits its visible parent.
        sets.append(cpu_set(cpus))
    for index, node in enumerate(mapping.get("memory", [])):
        reported = integer(required(environment, node + "/memory.limit_in_bytes"), zero=True)
        current = integer(required(environment, node + "/memory.usage_in_bytes"), zero=True)
        hierarchy = required(environment, node + "/memory.use_hierarchy")
        if hierarchy not in ("0", "1"):
            raise ProbeError("invalid_measurement")
        if index == 0:
            leaf_reported = reported
        # Linux's PAGE_COUNTER_MAX sentinel is an unlimited v1 memory counter.
        limit = None if reported >= 2 ** 63 - 4096 else reported
        if index == 0 or hierarchy == "1":
            memory.append((limit, current))
    return quotas, sets, memory, leaf_reported


def conservative_float(value):
    result = float(value)
    return math.nextafter(result, -math.inf) if Fraction(result) > value else result


def apply_samples(output, quotas, sets, memory, leaf_reported, affinity):
    quota = min(quotas) if quotas else None
    allowed = None
    for cpus in sets:
        if allowed is not None and not allowed.issubset(cpus):
            raise ProbeError("cgroup_conflict")
        allowed = cpus if allowed is None else allowed.intersection(cpus)
    if allowed is not None and not affinity.issubset(allowed):
        raise ProbeError("cgroup_conflict")
    output["cgroup_quota_cpu_count"] = conservative_float(quota) if quota is not None else None
    output["cgroup_cpuset_cpu_count"] = len(allowed) if allowed is not None else None
    output["cgroup_cpu_quota_status"] = "finite" if quotas else "unlimited"
    output["cgroup_cpuset_status"] = "finite" if sets else "unlimited"
    output["cgroup_memory_leaf_reported_limit_bytes"] = leaf_reported
    output["cgroup_memory_leaf_current_bytes"] = memory[0][1] if memory else None
    finite = [(limit, current) for limit, current in memory if limit is not None]
    output["cgroup_memory_limit_sample_count"] = len(finite)
    if finite:
        limit, current = min(finite, key=lambda sample: (sample[0], -sample[1]))
        available_limit, available_current = min(finite, key=lambda sample: (max(0, sample[0] - sample[1]), sample[0]))
        output["cgroup_memory_limit_bytes"] = limit
        output["cgroup_memory_current_bytes"] = current
        output["cgroup_memory_available_bytes"] = max(0, available_limit - available_current)
        output["cgroup_memory_available_limit_bytes"] = available_limit
        output["cgroup_memory_available_current_bytes"] = available_current
        output["cgroup_memory_status"] = "finite"
        if any(current > limit for limit, current in finite):
            raise ProbeError("cgroup_conflict")
    else:
        output["cgroup_memory_current_bytes"] = output["cgroup_memory_leaf_current_bytes"]
        output["cgroup_memory_status"] = "unlimited"
    return quota, allowed


def measure(environment, worker_limit):
    output = result_base(worker_limit)
    try:
        if not environment.is_linux():
            raise ProbeError("unsupported_platform")
        logical = environment.cpu_count()
        if type(logical) is not int or not 1 <= logical <= MAX_CPU_ID + 1:
            raise ProbeError("invalid_measurement")
        output["logical_cpu_count"] = logical
        affinity = checked_affinity(environment.affinity())
        output["affinity_cpu_count"] = len(affinity)
        if len(affinity) > logical:
            raise ProbeError("cgroup_conflict")
        physical, available = memory_info(environment.read("/proc/meminfo", 65536))
        output["physical_memory_total_bytes"], output["physical_memory_available_bytes"] = physical, available
        namespace = environment.initial_cgroup_namespace()
        output["cgroup_namespace_initial_verified"] = namespace if type(namespace) is bool else None
        membership_raw = environment.read("/proc/self/cgroup", 16384)
        mounts_raw = environment.read("/proc/self/mountinfo", 131072)
        v2, controllers, has_membership = cgroup_membership(membership_raw)
        mounts = cgroup_mounts(mounts_raw)
        relevant = {name: member for name, member in controllers.items() if name in ("cpu", "cpuset", "memory")}
        if v2 is not None and relevant:
            raise ProbeError("cgroup_conflict")
        if v2 is not None:
            output["cgroup_version"] = "v2"
            nodes = directories(v2, [mount for mount in mounts if mount[0] == "cgroup2"])
            for field in ("cpu", "cpuset", "memory"):
                output["cgroup_" + field + "_ancestor_count"] = len(nodes)
            quotas, sets, memory, leaf_reported = v2_samples(environment, nodes)
        elif has_membership:
            output["cgroup_version"] = "v1"
            mapping = {name: directories(member, [mount for mount in mounts
                if mount[0] == "cgroup" and name in mount[3]]) for name, member in relevant.items()}
            for field in ("cpu", "cpuset", "memory"):
                output["cgroup_" + field + "_ancestor_count"] = len(mapping.get(field, []))
            quotas, sets, memory, leaf_reported = v1_samples(environment, mapping)
        else:
            if mounts:
                raise ProbeError("cgroup_conflict")
            output["cgroup_version"] = "none"
            quotas, sets, memory, leaf_reported = [], [], [], None
        quota, allowed = apply_samples(output, quotas, sets, memory, leaf_reported, affinity)
        if namespace is not True:
            raise ProbeError("hidden_cgroup_ancestors" if namespace is False else "read_unavailable")
        if output["cgroup_version"] == "none":
            for field in ("cpu_quota", "cpuset", "memory"):
                output["cgroup_" + field + "_status"] = "none"
        effective_cpu = min(Fraction(logical), Fraction(len(affinity)),
            quota if quota is not None else Fraction(logical),
            Fraction(len(allowed)) if allowed is not None else Fraction(logical))
        effective_total = min(physical, output["cgroup_memory_limit_bytes"]
            if output["cgroup_memory_limit_bytes"] is not None else physical)
        effective_available = min(available, effective_total, output["cgroup_memory_available_bytes"]
            if output["cgroup_memory_available_bytes"] is not None else available)
        # Re-read identity/configuration; a migrated caller cannot reuse another group's evidence.
        if environment.read("/proc/self/cgroup", 16384) != membership_raw or environment.read(
            "/proc/self/mountinfo", 131072) != mounts_raw or checked_affinity(environment.affinity()) != affinity:
            raise ProbeError("cgroup_conflict")
        namespace_again = environment.initial_cgroup_namespace()
        if namespace_again is not True:
            output["cgroup_namespace_initial_verified"] = namespace_again if type(namespace_again) is bool else None
            raise ProbeError("cgroup_conflict")
        environment.check()
        output["effective_cpu_count"] = conservative_float(effective_cpu)
        output["effective_memory_total_bytes"] = effective_total
        output["effective_memory_available_bytes"] = effective_available
        output["measurement_complete"] = True
        enough = effective_cpu >= 2 and effective_total >= 6 * GIB and effective_available >= 3 * GIB
        output["max_parallel_forks"] = 2 if worker_limit >= 2 and enough else 1
        output["measurement_status"] = "ok" if output["max_parallel_forks"] == 2 else (
            "worker_limit" if enough else "low_resources")
    except ProbeError as error:
        output["measurement_status"] = error.status
    except Exception:
        output["measurement_status"] = "internal_error"
    return output


def worker_argument(arguments):
    if len(arguments) != 2 or arguments[0] != "--worker-limit" or not re.fullmatch(r"[0-9]{1,7}", arguments[1]):
        return None
    value = int(arguments[1])
    return value if 1 <= value <= 1048576 else None


def encode_result(output):
    encoded = json.dumps(output, sort_keys=True, separators=(",", ":"), allow_nan=False)
    if len(encoded.encode("utf-8")) > 4095:
        return json.dumps(result_base(1, "internal_error"), sort_keys=True, separators=(",", ":"))
    return encoded


def main(arguments=None):
    worker_limit = worker_argument(sys.argv[1:] if arguments is None else arguments)
    output = result_base(1, "invalid_arguments") if worker_limit is None else measure(LinuxEnvironment(), worker_limit)
    sys.stdout.write(encode_result(output) + "\n")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except BaseException:
        # Never emit exception messages, traceback paths, arguments or raw proc/cgroup text.
        sys.stdout.write(encode_result(result_base(1, "internal_error")) + "\n")
