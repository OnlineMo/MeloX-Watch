# 双端 Debug 构建输入审阅

2026-11-21，PR #31 的 [run44886645703](https://github.com/OnlineMo/MeloX-Watch/actions/runs/44886645703)/attempt1 已完成编译型采集与 fresh STRICT。source `57d69fc2c1b78e86057f4342a10a0e4568d00064`、build `d06efc0101b60f7f5d8928dd393e57976e6677e4`，两个 Git tree 独立读回相同；compile job `136793428774`。

acquire实际621.615秒、strict实际526.368秒，均exit0；两个阶段没有compiler error/warning、业务Test或APK装配，两个真实Test.classpath的producer都完成。双端lint无Fatal/Error/Warning，手表3条Hint、手机0 issue。最后seal按 `PACKAGE_INPUT_REPORT_ONLY_REJECTION` 结束，`accepted=false / apk_exported=false`；这份报告本身不是正常APK。

原reports artifact `14699954727`为452455字节、34成员，ZIP SHA-256 `f9853525959e2349923c65bc6153f6208dff03ecf62709086e759fe69b902246`。原请求、原件与每个输入hash已核；acquire及fresh STRICT的7份输入完全一致，schema6独立重生hash一致。仅接纳以下云端原字节，历史1/2/3保持原字节。

| 构建输入 | SHA-256 |
| --- | --- |
| `android/gradle/verification-metadata.xml` | `932660cc3aad48f606c97582c53355f94ea982b740abd32a2a6dd01175aab2ed` |
| `android/app/gradle.lockfile` | `7b3292c05245cec625d324a40704f1540e93e32caf47f5da180059f97696568d` |
| `android/companion/gradle.lockfile` | `4d54b33c90a88ad8b11a634f1ac9cddb419e6e778298993c198fdc41e9b0b3ad` |
| `android/app/schemas/io.github.onlinemo.meloxwatch.data.database.MeloXDatabase/1.json` | `3a36d662a8ee80e70d76180d564f8d9858243631dd20e2648c2492df8ff39c79` |
| `android/app/schemas/io.github.onlinemo.meloxwatch.data.database.MeloXDatabase/2.json` | `bab895d032d46556e4615a44130dc523e8942e942cd34ab68832969ecc7696bf` |
| `android/app/schemas/io.github.onlinemo.meloxwatch.data.database.MeloXDatabase/3.json` | `bfa96f621e166d734338e606704b631162ed007bcd4a71ba0c27c840e4e2b708` |
| `android/app/schemas/io.github.onlinemo.meloxwatch.data.database.MeloXDatabase/6.json` | `9b57e00ca554f04be6be24eaeac10b2400bba13f23ca9e9104a0e3f84fdb6cc2` |

官方新增83项制品/56坐标的字节、POM/module publication和许可证经过独立审阅，原1017项校验及2779条app锁绑定全部保持。app锁现在3014条/205配置，手机新锁1776条/187配置。54个坐标为Apache-2.0，两个Hamcrest坐标为BSD-3-Clause；28份新增archive无native成员。`lifecycle-runtime:2.9.2`根兼容空AAR依据该坐标真实官方POM packaging、canonical URL和hash验证，其module的Android child引用保留，不建立泛化缺文件豁免。

新增coroutines/Compose测试依赖使用现有1.10.2/1.9.3版本，Exif继续已有1.3.6；新手机依赖来自真实双端编译消费者。主库V6的39张旧表完全不变，9张新增表/16项索引与两driver迁移SQL一致，保存的V5音频传入收据在回填前完整验证。历史V3/V4/V5测试库仍明确不导出，没有创建虚构历史生产4/5.json。

准入收据SHA-256 `9015c7e924db7a11fe8daaf57480ab0816d19d51eefadd4b16714ac6977ea59a`；数据复核 `ba21ca3740d947f018042259307a739476e6d16ed2d4b6582645426bacbdbec9`；schema源码复核 `db6a17b0352688ef67a9eb8c1521e379faf16fe07e449363373fe3cf16b7d324`；独立官方原件/许可复核 `e505175db00da6c24bed238ca5a40a53ca915ba63ab68cca4e8d845f1d4b6352`。含私人路径的原始证据及源码副本只保留ignored本地。

该准入只允许退出已完成的采集请求并进入一次正常双端Debug构建。所有业务测试、真实V1/V2/V3至V6迁移测试、双APK、固定Debug签名、保留数据安装、设备/256原AC、候选与发布门槛仍须后继实际证据；没有本地Android执行、readiness提升或发布批准。Native/Meting在线后继仍未进入该源码快照。
