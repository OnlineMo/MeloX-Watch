"""在编译之外独立核验 APK 身份和签名；不导入 Gradle 或应用模块。"""
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile

from ci_common import require, run_tool


def build_tools(lock):
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    require(bool(sdk), 'Android SDK is not installed on this cloud runner')
    return Path(sdk) / 'build-tools' / lock['android.buildTools']


def parse_badging(text, xml):
    match = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", text, re.MULTILINE)
    require(match is not None, 'APK has no valid package/version metadata')
    # profileable 是元素而非 application 属性；其 shell=true 才允许 shell 性能采集。
    profileable = re.search(r'E: profileable\b(?:(?!\n\s*E:).)*android:shell[^\n]*=\(type 0x12\)0xffffffff',
                            xml, re.DOTALL) is not None
    return {'package': match[1], 'version_code': match[2], 'version_name': match[3],
            'debuggable': 'application-debuggable' in text.splitlines(), 'profileable': profileable}


def inspect_apk(apk, tools, expected):
    badging = run_tool([str(tools / 'aapt'), 'dump', 'badging', str(apk)])
    xml = run_tool([str(tools / 'aapt'), 'dump', 'xmltree', str(apk), 'AndroidManifest.xml'])
    actual = parse_badging(badging, xml)
    require(actual == expected, 'APK package/version/debug/profileable identity mismatch')
    return actual


def certificate(apk, tools, expected=None):
    verified = run_tool([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)])
    fingerprints = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$', verified, re.MULTILINE)
    require(len(fingerprints) == 1, 'Exactly one APK signing certificate is required')
    digest = fingerprints[0].lower()
    require(expected is None or digest == expected, 'APK certificate differs from the pinned certificate')
    return digest


def require_unsigned(apk, tools):
    require_no_signature_blocks(apk)
    result = subprocess.run([str(tools / 'apksigner'), 'verify', str(apk)], capture_output=True, text=True)
    require(result.returncode != 0, 'Stable signing input must be unsigned')
    # 不能把工具启动失败或普通 IO 错误当作合法未签名 APK。
    require('DOES NOT VERIFY' in result.stderr or 'DOES NOT VERIFY' in result.stdout,
            'APK unsigned-state verification failed unexpectedly')


def require_no_signature_blocks(apk):
    """签名损坏不等于未签名；同时拒绝既有JAR签名及APK Signing Block。"""
    with zipfile.ZipFile(apk) as archive:
        require(not any(re.fullmatch(r'META-INF/[^/]+\.(?:RSA|DSA|EC|SF)', name, re.IGNORECASE)
                        for name in archive.namelist()), 'APK already contains v1 signature records')
    with Path(apk).open('rb') as stream:
        stream.seek(0, 2)
        size = stream.tell()
        stream.seek(max(0, size - 65557))
        tail = stream.read()
        end = tail.rfind(b'PK\x05\x06')
        require(end >= 0 and end + 22 <= len(tail), 'APK ZIP end record is missing')
        comment_size = struct.unpack_from('<H', tail, end + 20)[0]
        require(end + 22 + comment_size == len(tail), 'APK ZIP end record is malformed')
        central_offset = struct.unpack_from('<I', tail, end + 16)[0]
        require(central_offset != 0xffffffff, 'ZIP64 APKs are not supported')
        if central_offset >= 16:
            stream.seek(central_offset - 16)
            require(stream.read(16) != b'APK Sig Block 42', 'APK already contains a v2/v3 signing block')
