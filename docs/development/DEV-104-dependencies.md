# DEV-104 依赖与官方接口依据

沿用DEV-004-A工具链：Gradle8.13、AGP8.11.1、Kotlin/Compose编译插件2.2.21、Temurin17.0.17+10、compile/target36、候选min26。未引入GMS或第二播放器。版本以`android/gradle/libs.versions.toml`为唯一应用依赖来源；本记录是直接POM和源码契约审阅，传递制品/锁/schema必须由实际云端资料补齐。

| 精确坐标或插件 | 许可声明 | 当前用途与来源 |
| --- | --- | --- |
| `androidx.room:room-runtime:2.8.5` | Apache-2.0 | 唯一实际SQLite队列存储；[Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/room/room-runtime/2.8.5/room-runtime-2.8.5.pom) |
| `androidx.room:room-compiler:2.8.5` | Apache-2.0 | 仅KSP编译配置生成DAO/schema；[Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/room/room-compiler/2.8.5/room-compiler-2.8.5.pom) |
| Room插件`androidx.room:2.8.5` | Apache-2.0 | schema输出目录接线；[官方实现POM](https://dl.google.com/dl/android/maven2/androidx/room/room-gradle-plugin/2.8.5/room-gradle-plugin-2.8.5.pom) |
| KSP插件`com.google.devtools.ksp:2.2.21-2.0.5` | Apache-2.0 | 实际Room编译器消费者，明确KSP2；[Maven Central POM](https://repo.maven.apache.org/maven2/com/google/devtools/ksp/symbol-processing-gradle-plugin/2.2.21-2.0.5/symbol-processing-gradle-plugin-2.2.21-2.0.5.pom) |
| `androidx.datastore:datastore-preferences:1.1.7` | Apache-2.0 | 两个真实偏好字段与原子revision写入；[Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/datastore/datastore-preferences/1.1.7/datastore-preferences-1.1.7.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate:2.9.4` | Apache-2.0 | 已使用的导航/设置编辑重建值；[Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-savedstate/2.9.4/lifecycle-viewmodel-savedstate-2.9.4.pom) |
| `org.robolectric:robolectric:4.16.1` | MIT | 仅云端测试的真实SQLite/主Looper/ExoPlayer；[Maven Central POM](https://repo.maven.apache.org/maven2/org/robolectric/robolectric/4.16.1/robolectric-4.16.1.pom) |
| `org.robolectric:android-all-instrumented:8.0.0_r4-robolectric-r1-i7` | Apache-2.0 | 仅测试API26运行时；[Maven Central POM](https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/8.0.0_r4-robolectric-r1-i7/android-all-instrumented-8.0.0_r4-robolectric-r1-i7.pom) |

原JUnit4.13.2仍仅测试使用并保留EPL-1.0声明；其他既有依赖沿用[DEV-004-A记录](DEV-004-A-dependencies.md)。新增仓库和模块均有实际消费者。Room/设置懒创建，阻塞SQLite/文件持久化由相应存储层调度，UI不建库或播放器；APK大小、PSS、初始化CPU和耗电尚未设备测量，不填写推测值或通过。

独立直接审查读取18个catalog library POM及Room/KSP各marker/implementation POM共22项，核对官方HTTPS源、精确GAV、POM字节SHA-256与声明；全部一致。Robolectric4.16.1官方`DefaultSdkProvider`的预插桩版本7、AndroidO的`8.0.0_r4/r1`组合与catalog精确坐标一致。通过单独非传递Gradle配置先下载并锁定/校验此SDK，再复制到测试目录；测试启用offline和enabledSdks26，避免默认target36要求JDK21或测试时另发未校验下载。

官方源码审阅仅用于核对API，不复制应用研究源码：Robolectric源归档SHA-256`b354b256648453c5e83eb4ec19d80f23a6d8775e7684c93d5e3c0f327ba8d289`，Compose UI Android1.9.3源归档`9fa260e2e6d2a8e312103a4409ffe8f0ec93cab07bb9c682c03347a9c71ad8ee`，Media3 Session1.8.0源归档`681eab1444fa20be00cc24b2c590d00eb070f8b0196b21c9328971b17eb2f5b4`，Media3 ExoPlayer1.8.0源归档`12d5ee2a00b7824788f38ab990a99034411912603895e5454d084d65c31687af`。完整POM/源码索引保留在ignored审阅区，不入仓库或构建输入。

## 同版本Android runtime实际直依赖

页面直接使用Compose runtime API，现补齐`androidx.compose.runtime:runtime-android:1.9.3`实体直依赖；它已是原解析集合实际选中的Android实现，不更换工具链/Compose、不增加common依赖或force/exclude/锁例外。官方AAR SHA-256为`dc7a8a161d6dfff96801a2d89b1f5d4adb61c0dfa16cb5970215e70aa1d83407`，module为`9492b2ecb5da90bb3f6304e26fe06faff20c573e473a7da6351f0df52180f05a`，POM为`fc721c9301a90febbcdc75c7a58c97e9b766aa8c13c3c80c8eadffaa629c2202`，声明Apache-2.0。

P0的[run43755199209](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43755199209)/attempt1、source`4232fe961f13a454fa6b707bef4e8e63f1799862`、实际checkout`140f072d6bc59aee4dc8f6f695ab90d6241e018c`、artifact`14178523402`已观察五阶段全部成功。811项官方制品核验及三STRICT输入字节审阅完成，common真实边和7配置均保留。独立审阅摘要SHA-256为`389611203b36e824abc9887cd5187ca877c2e89f3591d3bf556e745c0521bec1`；该资料只证明P0。此主题保留自己的Room/KSP/DataStore/Robolectric实际消费者与全部严格要求，须重新取得本主题完整XML/锁/schema1，不能复制P0锁、导入失败schema或据P0结果填通过。初始schema1与后续Library schema2的真实迁移仍待云端基线。原始日志、审查缓存及本地设备信息不入库。

Compose锁定源码确认Activity宿主的`containerSize`使用当前窗口边界，未从IME后测量高度计算；Media3确认暂停命令与命令结果/异步错误的独立时序。修正依据已体现在页面与协调器；OEM行为仍须实测。所有新增实际AAR/JAR、传递许可、完整锁及Room初始schema的接受状态，以后续精确云端run/attempt/artifact/hash记录为准，直接POM存在不能替代构建或设备验收。

## 完整五阶段审查与原字节导入

P1 的 [run43760127225](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43760127225)/attempt1 绑定 source `569b92f66476d9889061fe474af4a8aaa74ea94a`、日志确认的 checkout `aa968753e0b9f16b2bc0f19ecd1c54ae648b896e` 和 compile job `132952890421`。verification-collection、normal-lock-collection、strict-debug、strict-release、strict-diagnostic 五次 Gradle 均实际成功；最终工作流按 generated-unreviewed 合同拒绝作为正常构建，不导出 APK。其 schema1/five-phase-room-resolve 生产合同不要求较新的图观察器。

原 reports artifact `14180746763` ZIP SHA-256 为 `a2264ea39207af772afba4f1b082110b28324d520e659791c50f1431922c75ec`，日志 SHA-256 为 `58fc0e14c71ee3ac7c703582c27d5f87410ecdbcce63c383bd512662ce1e1978`。Debug/Release/Diagnostic 各实际执行5套58个唯一测试，失败/错误/跳过均0，三份清单相同；各 lint XML 为0项。这些结果绑定上述旧快照，后续新输入必须重新构建。

独立审查570组件、1009制品、347锁行/203配置，全部官方 hash 已核验，未核验和来源冲突均0；Guava 所选 `33.4.8-jre` 组件中的 Android JAR 按官方冻结 module 声明和原文件专项核对，没有放宽通用规则。126个运行时许可候选已明确（125 Apache-2.0、1 BSD-3-Clause）；其余58个人工项属于构建/metadata或测试。最终APK包体与许可通知仍待交付审查。独立总结 SHA-256 为 `9b150b16b990a23ce05f127478950df350a97d09e2483d17b6eab6c6acf6aadd`，官方完整 hash 审查为 `fc340402ef16098cf7ef726c6b45fe4d81cd170ab2cb58df5e21c2981ffc1c8e`。

经委托范围内审阅后，仅导入下列原ZIP字节；严格三阶段输入与报告一致，不导入较晚失败 Library 运行的 schema2。

| 原始输入 | SHA-256 |
| --- | --- |
| verification-metadata.xml | `aa1551e0be169554e972604344d6e4a126b04e833269ff7933bfd0aeb45715c8` |
| app/gradle.lockfile | `3da1a521a31e0353a9572a19d36670b21da5d704dfca08577499ebd80a0bf9da` |
| MeloXDatabase/1.json | `3a36d662a8ee80e70d76180d564f8d9858243631dd20e2648c2492df8ff39c79` |

本次导入不是正常APK或设备验收。新提交的正常 STRICT、固定签名、虚拟机/真表以及真实 schema1→2 迁移仍待验证；所有readiness为false，candidate为null，256原AC均未通过。原始设备数据、日志、缓存和私有调查材料仅留本地。

## CI-01实际签名归档缺陷修正

P0经PR #27合入contribution为`fe3f711f7c015371f7149fb122332790a2e90e5f`后，[run43763951759](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43763951759)/attempt1 的独立compile/sign-debug/cloud-gate均成功，实际checkout与source一致；Debug5个真实回归全通过、lint0项。未签名artifact `14182000958` 的ZIP SHA-256为`81a976a0890eea2bfec7927daa41fd403b1c699943fcd1383541a2aa7910a40c`，APK为`e5db198a491f06f70389f7416d79d9a9f1f5fcb9abaaab2d83d2c69a0b9bbda4`，工具核验确为unsigned。

签名artifact `14181617086` 的原ZIP为`67ff50b8d0be2f60c88490f9e954be189e351c95553bac5046c9d430057c3879`，却含默认v4生成的额外`watch-debug.apk.idsig`；正式数据读取器按三成员契约拒绝，不能把成功job追认为完整签名归档或设备验收。保留原归档，不删除侧文件或重新打包追认。

签名脚本现显式关闭v4侧文件，并在写manifest/校验和前核验输出仅含已声明APK。新增stdlib回归覆盖正常结果经原归档读取器往返、异常侧文件在封存前拒绝；不执行Android工具或真实签名。workflow/action和秘密边界未变，修复须经新PR严格构建与合并后的fresh signing原产物重新验证，旧拒绝归档仍未验收。

## Library完整云资料与SDK30基线导入

PR #29的[run43792499334](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43792499334)/attempt1绑定source `e2971ee4688c1a34626d8fe2cb70ca3f78c15a26`、日志确认的checkout `60f0b36378a53da2400ca3ded82badd5ab349c8b`和compile job `133054022987`；两份Git tree精确一致。五个Gradle阶段全部实际成功，最后按bootstrap合同拒绝；不是accepted application build，没有APK可安装。原reports artifact `14195105198`为413857字节，ZIP SHA-256为`ca860dfb0ec8766d2b4076b95432e56e20c5d76c27a17576ca42240f390d131f`。原云证据独审SHA-256为`b06f235452b78b08dbb79605f492fd9255d8560026318d24bd86a341c59f4362`。

Debug/Release/Diagnostic各26套226个唯一测试，failure/error/skipped均0；每变体lint为2条`Hint / SimilarGradleDependency`，Fatal/Error/Warning为0，不能记lint零项。完整列表10000/10001项、过期、失败保旧队列及重开恢复均在原断言和180000ms单项timeout内通过；16阶段观测完整，不以减少用例或放宽timeout取得成功。

独审逐坐标/文件/哈希核验原570组件、1009制品精确保留，仅增加测试配置`robolectricSdk30`及两份`org.robolectric:android-all-instrumented:11-robolectric-6757853-i7`原JAR/POM，全部571组件/1011制品、348锁行/204配置。新增JAR SHA-256为`7f4accae465a47e75e7591877b98e71fb982a908cffc4ba4b3ca0319cb6a0a10`，POM为`579070875a6a33dd57d2937929fa443789e28826cb12e8e716197c222e199682`，再次直接核对Maven Central官方SHA-256 sidecar及原POM，声明Apache-2.0；不进入三个main RuntimeClasspath。旧版本、common边、STRICT及全部校验配置保持，未引入trust/ignore/多哈希例外。旧完整1009项官方审查有原文档pin；没有用较小的1008项effective清单代替它。

完整官方资格报告SHA-256为`cc63871c7cd2d6f5e512b656877c92cbf799c61d4218a3fd90c53015c65cfeb1`，许可资格报告为`a05bc65ba52c3741cf56240a5ab6f549b6e9cdb1c87a471c5c9f83ad5fa91845`，最终四输入资格报告为`f6649be8b531d735792b43191a52d219ae071e9d220868e4cec59adb78f901c1`。Room独审报告`29ffa05c5693bf36178077abbeb0bf28d2cc4835d8b84c511cbc73393b5bc6e1`核验30实体、292字段、30主键、19外键、49索引及显式1→2 DDL；原3张队列表保持，schema1原字节保持。静态DDL核对不等于真实seeded数据库升级测试。

只导入以下原ZIP字节，与三个STRICT输入快照相同；不得重生成或手修哈希/schema。

| 导入输入 | SHA-256 |
| --- | --- |
| verification-metadata.xml | `8423aa796640770d1a7d4cb69bbb599a050afc03748bd9a9afccb69f3f48cc84` |
| app/gradle.lockfile | `2100eb17daf61415d8d7dcfb3fe40f8f200f86bb75845f3913dcf4d1a0b4aa83` |
| MeloXDatabase/1.json | `3a36d662a8ee80e70d76180d564f8d9858243631dd20e2648c2492df8ff39c79` |
| MeloXDatabase/2.json | `bab895d032d46556e4615a44130dc523e8942e942cd34ab68832969ecc7696bf` |

导入后须普通PR严格构建。真实1→2迁移、覆盖升级、声音输出、最终APK许可说明和256原AC仍未通过；readiness全部false、candidate=null。原日志、截图、设备地址和标识只留ignored本地，不上传。
