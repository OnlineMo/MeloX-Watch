# DEV-004-A 工具链、依赖与云端来源

记录日期：2026-11-15。用户已恢复 Android 探针及应用建设；本记录只覆盖首个 `:app` 探针的构建输入与来源，不表示 APK、实机能力、完整本地功能或发布门槛已经通过。工具链安装、应用编译、签名和设备验收分别留证，实际状态见 [13](../13-仓库状态.md)。

## 1. 固定工具链与应用依赖

既有工具链继续读取根目录 [.ci/toolchain.properties](../../.ci/toolchain.properties)：Ubuntu 24.04、Temurin 17.0.17+10、Gradle 8.13、AGP 8.11.1、Kotlin 与 Compose 编译插件 2.2.21、compile/target SDK 36、候选 minSdk 26、Build Tools 35.0.0。这里没有升级工具链；插件版本由 settings 的 `pluginManagement` 直接读取锁文件，不另设版本来源。

新增应用依赖记录在 [version catalog](../../android/gradle/libs.versions.toml)，只用于普通 Compose 页面、合法本地 URI、MediaController 和唯一播放服务，以及云端测试。没有 GMS/Wear 栈、网络客户端、Room、图片框架或未来配套空模块。业务功能首次实际使用其他依赖时，再独立固定版本、来源、许可和资源成本。

2026-11-15 直接读取以下版本的官方 Maven POM，核验坐标存在并记录其 SHA-256 与许可声明；没有运行本地 Gradle。这只证明当次制品来源与声明，不证明传递依赖已解析、SDK 兼容、云端编译或设备行为已经通过。

| 精确坐标 | 许可声明 | 当前用途 | 制品来源 |
| --- | --- | --- | --- |
| `androidx.activity:activity-compose:1.11.0` | Apache-2.0 | Activity 的 Compose 容器与系统选择/权限结果入口 | [Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/1.11.0/activity-compose-1.11.0.pom) |
| `androidx.core:core-ktx:1.17.0` | Apache-2.0 | AndroidX 基础兼容与 Kotlin 扩展 | [Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/1.17.0/core-ktx-1.17.0.pom) |
| `androidx.compose.ui:ui:1.9.3` | Apache-2.0 | 按实际窗口/形状布局、文字与可访问语义 | [Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui/1.9.3/ui-1.9.3.pom) |
| `androidx.compose.foundation:foundation:1.9.3` | Apache-2.0 | 有界列表、滚动和触控基础 | [Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.9.3/foundation-1.9.3.pom) |
| `androidx.compose.material3:material3:1.4.0` | Apache-2.0 | 共用按钮、状态和输入控件 | [Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.4.0/material3-1.4.0.pom) |
| `androidx.lifecycle:lifecycle-runtime-ktx:2.9.4` | Apache-2.0 | 页面生命周期与可见性任务边界 | [Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx/2.9.4/lifecycle-runtime-ktx-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime-compose:2.9.4` | Apache-2.0 | 可见页面按生命周期收集状态 | [Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose/2.9.4/lifecycle-runtime-compose-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4` | Apache-2.0 | 页面来源检查任务的 ViewModel scope | [Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-ktx/2.9.4/lifecycle-viewmodel-ktx-2.9.4.pom) |
| `androidx.media3:media3-exoplayer:1.8.0` | Apache-2.0 | 唯一活动 ExoPlayer 的本地解码、焦点与 noisy 所有权 | [Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/media3/media3-exoplayer/1.8.0/media3-exoplayer-1.8.0.pom) |
| `androidx.media3:media3-session:1.8.0` | Apache-2.0 | 唯一 MediaSessionService、MediaSession 和页面 MediaController | [Google Maven POM](https://dl.google.com/dl/android/maven2/androidx/media3/media3-session/1.8.0/media3-session-1.8.0.pom) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2` | Apache-2.0 | main-safe 来源读取、任务取消和页面采样 | [Maven Central POM](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.10.2/kotlinx-coroutines-android-1.10.2.pom) |
| `junit:junit:4.13.2` | EPL-1.0 | 仅 `testImplementation` 的云端单测框架；当前探针尚无应用单测，不进入 APK | [Maven Central POM](https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom) |

Media3 两组件使用同一版本。以上许可是直接 POM 的声明；发布前还须按实际解析与打包集合核对传递依赖及必要通知，不能用本表代替完整开源许可审查。包体、初始化、PSS、CPU 和最低 API 的实际成本尚未测量，遵守 [14](../14-性能优先与资源预算.md)；UI 不创建播放器，阻塞内容读取进入 IO，播放器和 controller 遵守所属 Looper。

## 2. Wrapper 的既有云端来源

wrapper 来自 [Bootstrap cloud toolchain run 42766330725](https://github.com/OnlineMo/MeloX-Watch/actions/runs/42766330725)，attempt `1`，source `6b570aae2fa891e3aa16eefd7dc722316802835b`，artifact ID `13740290230`。归档 SHA-256 为 `6f017f449329c14aa287512b61d49d7ca981f15cd3435c7e2de07163965104a5`；下载时已与 GitHub artifact digest 对照，恢复后的文件逐项核对既有 provenance。

| 云端归档中的文件 | SHA-256 |
| --- | --- |
| `gradle/wrapper/gradle-wrapper.jar` | `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f` |
| `gradle/wrapper/gradle-wrapper.properties` | `c040b6ef2fb893ff5beea5a281614f2848f1c3996a7886f650b713f8240656a2` |
| `gradlew` | `734b3879d3501dce471cf0522d3bcbafe76873d9fc5129345b67fb43bd15e933` |
| `gradlew.bat` | `57931b17dd228e5c24dac90e815d0bf82477e831a4618dfab4136f5446b42a9f` |

Gradle 发行包的独立 SHA-256 仍为 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`，写入 wrapper properties 并由 CI 对照工具链锁。归档与 Gradle 发行包是不同对象；wrapper 安装证据不算应用构建。未执行本地 wrapper、Gradle sync、编译、单测或 lint。

## 3. 依赖校验资料的首次生成

正常编译要求以下两个构建输入已进入当前 Git 提交：

- `android/gradle/verification-metadata.xml`：插件、元数据及制品的 SHA-256 校验。
- `android/app/gradle.lockfile`：应用实际解析配置的完整依赖锁。

所有项目配置使用 `lockAllConfigurations()` 和 `LockMode.STRICT`；插件版本精确读取既有工具链锁。根 `android/gradle.lockfile` 没有可锁配置时可以不存在；若云端实际生成则一并保存、审阅。

资料缺失时只允许无秘密的 Debug PR 构建收集首次材料：运行 Debug、Release、Diagnostic 的测试/lint/assemble 任务，并以 `--write-verification-metadata sha256 --write-locks` 写出材料，使用 `CI_UNSIGNED_APK=true`。收集完成后明确失败，不导出可验收 APK，不进入稳定或正式签名，也不能让 cloud-gate 成功。

已有报告上传步骤收集 `android/app/build/reports/dependency-bootstrap/`。预期包含 `gradle/verification-metadata.xml`、`app/gradle.lockfile`、可选根 `gradle.lockfile`，以及 `bootstrap-manifest.json`。manifest 绑定 source/build SHA、run/attempt、实际任务、各文件 SHA-256、缺失项和 `accepted=false`；Gradle 失败留下的部分材料只能标 `failed-partial`，不能直接批准。

下载报告后核对 producing run/attempt/source/build 与哈希，审阅坐标、版本、来源、许可和传递依赖，将完整材料提交到同一 PR，再运行 `--dependency-verification=strict` 的正常云端检查。运行时生成的未提交材料不能替代构建输入。后续新增依赖同样审阅锁与校验变更，不通过通配白名单、关闭校验或自动信任下载内容放行。

## 4. 当前验证边界

本记录的直接 POM 来源核对和 wrapper 文件校验已执行；依赖解析、真实 APK 编译、签名与设备测试的结论以实际云端和设备记录为准，不在本页预填成功。当前探针尚无应用单测，Gradle 单测任务可能为 `NO-SOURCE`，不能把 CI Python/Node 政策用例或空任务记为应用单测验收。没有当前受控调度用例的 coroutines-test 已移除；后续真实测试首次需要时再引入。应用读取所有者开发提案 `0.0.1 / 1`；正式候选仍按 [10](../10-仓库治理与发布.md) 从冻结 main 的完整历史和发布历史计算 code，本页不另定公式。

2026-11-15 的只读 ADB 查询未发现连接设备，因此当前测试手表的 API、ABI、窗口、选择器、输入法和后台行为均未测量，minSdk 26 仍是工程候选。不能从用户提供的型号描述推定这些能力，也不把无设备结果记为通过。DEV-002/005/004-B 在可追溯云端包与已授权实际设备条件具备后验证；完整首版继续包含 [21](../21-本地播放器全量需求整合.md) 的 128 项本地功能、手机配套与在线路径。

## 5. 完整资料审阅与原字节导入

第五轮云端run`43712653642`/attempt`1`/artifact`14161820850`提供完整资料；source/build SHA与归档哈希见[建设记录](DEV-004-A.md)。本次导入的是原始校验XML与app锁，哈希分别为`651c09fe2f027be20bb85ae35f2394217caeb3c916b60bde44bd1a5a603af204`和`59e819943f974ff13698d0bf601db920acb87ce835280c2ac3eda0ccdfb57bf7`，没有手工改写哈希、可信制品通配规则或坐标版本。

完整审阅覆盖459个组件、809个制品、256个锁行与186个解析配置。809项SHA-256均与Google Maven或Maven Central的官方sidecar/原始下载匹配；51项逻辑文件名不等于下载文件名，使用已验证`.module`的`files[].name/url`和Android子模块的`available-at`双向关系解析。Guava的Android元数据明确引用JRE路径，目标精确版本也存在于本次冻结资料，已独立核对官方原文件哈希；没有猜测重命名或改用镜像。最终零未解决、零哈希不匹配、零来源冲突。

许可审阅逐项读取并绑定官方POM及父POM来源：455/459组件有声明；按实际三变体RuntimeClasspath锁行得到的100个运行时候选组件均声明Apache-2.0。另52项由人工分类，包含50项构建工具/元数据和JUnit、Hamcrest两个仅测试组件：保留其SDK协议、MPL、CDDL/GPL Classpath例外、EPL、EDL、BSD、JNA双许可、Bouncy Castle和JDOM声明，不把它们统一写成Apache-2.0。使用范围限于未修改的云端工具/解析元数据与测试，不复制这些实现到应用源码或测试以外配置。

`net.java:jvnet-parent:1/3`与`org.sonatype.oss:oss-parent:7/9`四个父POM没有许可声明；已核验它们只有用于Maven解析的POM元数据，不是随应用发布的代码制品，明确保留缺声明状态。此次批准只包含校验值与锁文件，不分发这些POM或工具二进制。运行时候选清单不等于最终APK打包清单，LP-15及最终发布审查仍须按精确APK核对实际许可/NOTICE。

本地审阅摘要的SHA-256：结构报告`c500f428de9f34016d388e3f13b6bdc753f95ba46f927b2aeb6ded3f67231be7`、完整官方哈希报告`73d30835f31ee82b6239fffd35e42f0c3966c62be305e8869b297d1f1032e58e`、许可链报告`6f5ab21a4660f64424ee1ea0e78c9e7a6afdd3f8e49ca68409abe47ef156a75b`、导入清单`a7f665f44449d68b69414b672d92bce2ecca609891dcea41b4eb46a4610e4cd8`。29个Python审阅工具边界用例通过，工具与完整缓存保留在ignored操作区，未执行本地Android工具；这些用例不是应用测试或设备验收。
