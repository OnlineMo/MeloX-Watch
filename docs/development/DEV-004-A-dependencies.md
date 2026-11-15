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

资料缺失时只允许无秘密的 Debug PR 构建收集材料，所有阶段使用 `CI_UNSIGNED_APK=true`。先对 Debug、Release、Diagnostic 的测试/lint/assemble 任务运行 `--write-verification-metadata sha256 --write-locks`，再用独立进程只加 `--write-locks`，先运行相同的真实变体任务，最后由 CI 初始化脚本的 `:ciResolveAndLockAll` 显式非宽松解析所有 `canBeResolved` 配置并补齐正常图的锁。采集任务对九个真实任务设置 `mustRunAfter`；存在并请求Diagnostic instrumentation assemble时也须先完成它，不能只依赖CLI排列保证并行顺序。已由真实任务消费的图复用，任何配置的解析失败均不得跳过。最后分别执行三变体的独立严格验证，不带任何生成参数。最终锁与校验文件的哈希必须在这三轮中保持一致。五阶段全部完成也只产生待审资料，随后明确失败，不导出可验收 APK，不进入稳定或正式签名，也不能让 cloud-gate 成功。

已有报告上传步骤收集 `android/app/build/reports/dependency-bootstrap/`。预期包含 `gradle/verification-metadata.xml`、`app/gradle.lockfile`、可选根 `gradle.lockfile`，以及 `bootstrap-manifest.json`。manifest 绑定 source/build SHA、run/attempt、各阶段命令和状态、严格验证前的文件哈希、最终文件 SHA-256、缺失项和 `accepted=false`；任一阶段失败或严格验证改变输入字节都只能标 `failed-partial`，不能直接批准。

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

## 6. 严格构建失败与重新收集

导入后的严格 run `43715744440` / attempt `1` 在 Debug AAR 元数据检查中失败：`kotlin-stdlib-common:2.2.21` 存在于锁状态，却未在该配置解析。source 为 `fed57fe45ddce8abe3f0a539af00d4ee537ad7e3`，实际 merge build 为 `7ce14e92c5c7db792147be0835a091f6b9106e5c`。当次命令只有严格校验，没有锁或校验资料生成参数；没有执行本次 Kotlin 编译、lint、assemble，没有 artifact 或签名。

独立读取 Gradle 8.13 的官方固定源码（commit `073314332697ba45c16c0a0ce1891fa6794179ff`）确认 verification writer 在构建末额外解析所有可解析配置；Kotlin 2.2.21 官方 module 将 common 声明为 runtime dependency constraint。调查没有证明额外解析一定是本次不一致的完整原因，也不保证分轮采集必然解决问题。不会添加虚假的 runtime 依赖、手工裁剪锁或降低严格校验。

现撤回该 app 锁作为可接受输入，保留已审校验 XML；原锁字节仍在 Git 提交与已核验云端归档中。下一轮由第 3 节的五阶段重新采集并接受独立审阅，只有全阶段实际通过且报告复制字节与严格输入一致才能导入新资料。调查摘要 SHA-256 为 `8d61dc558ba349d94fbac4cfffc04848bdbb359abaacca90b9a23f26ce71a3b5`；失败日志 SHA-256 为 `0323d8768ab2fa4d81d2ee2ea8c009d4166ab9d076be3edb027bd7d98be18b59`。新增四个 CI 边界测试覆盖正常任务锁替换、独立严格失败、输入字节漂移和最后验证后的报告复制漂移；39 项 Python CI 测试与 Node 政策检查通过，不等于真实 Android 修复已验证。

## 7. 分轮结果与显式配置解析

五阶段首轮 run `43722431450` / attempt `1` 绑定 source `028a05d737192b03c0dfbc5a2d12156b8911d2d6`、build `1dfdd94fc0fbf7ce50cf44dce6aa0975645cdfb2`、job `132837150437`。资料生成阶段 `BUILD SUCCESSFUL in 8m 3s`；正常锁阶段 `BUILD SUCCESSFUL in 17s`，156 项任务中 153 项为 up-to-date，仅三个 lint 包装任务执行。之后严格 Debug 仍因 common 锁状态在 13 秒内失败；新旧锁 SHA-256 完全相同。分轮本身没有解决问题，manifest 是 `failed-partial`，不得导入或接受。

报告 artifact `14165381146` 的 ZIP SHA-256 为 `7dbbd3453abf9d1aadcbb7b75f87235263bc991b446eca96d9b6d4b53cefbb40`；XML 和 app 锁仍为第 5 节的原哈希。完整来源/阶段核验与固定 Gradle 源码调查摘要 SHA-256 为 `48d2027dede0bcbace3306421e307a8fde355fd9b4b6d17a777b379df39dce4c`。

Gradle 8.13 官方文档与固定源码确认只覆写本次实际解析的配置，未触及配置会保留；up-to-date 状态不单独证明哪些配置已解析。第 3 节现增加官方 `resolveAndLockAll` 模式的显式任务，要求 `--write-locks`，禁用此任务的 configuration cache，逐一真正解析可解析配置。它保证下一轮记录配置覆盖，仍须云端严格成功验证效果；没有排除 common、降低 LockMode 或手工更改制品哈希。

第二轮显式收集 [run `43726131569`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43726131569) / attempt `1` 绑定 source `ff7a77cb13bf88db3b6b777da5892f844cc18ebd`、build `336128609979c340c1df710adfbac39ec980511b`、job `132848389888`。第一阶段成功；第二阶段对 AndroidTest compile 配置直接取文件时，AGP 的多个 secondary artifact 缺少消费者 artifactType，导致选择歧义。没有进入严格阶段，artifact `14166856436` 为 `failed-partial`；ZIP SHA-256 `36451a27f0f9dd561b0435a0119cf55cd583451bb93f36ccd7bd5ca53b5749f5`，日志 SHA-256 `3dba13ccaeb904c85d4157ca1530aa2499c8e08ae382a1ddd6996a0c08cafd0c`。

固定官方源码的 [完整 ResolutionResult](https://github.com/gradle/gradle/blob/073314332697ba45c16c0a0ce1891fa6794179ff/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/result/DefaultResolutionResult.java#L73) 与 [锁记录时点](https://github.com/gradle/gradle/blob/073314332697ba45c16c0a0ce1891fa6794179ff/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/ivyservice/ResolutionExecutor.java#L384)已核对。显式任务改为物化每个可解析配置的完整依赖图并检查全部 `UnresolvedDependencyResult`，任一失败立即抛出并保留原因；图解析本身的其他异常同样传播。真实测试/lint/assemble任务继续选择AGP所需的具体文件。没有跳过无测试源码的配置、使用报告的宽松解析或放松STRICT。新增第二阶段失败边界测试；修正效果仍等待下一轮五阶段和严格云端证据。

## 8. 完整图收集后的严格差异与消费顺序

第三轮显式收集 [run `43728464376`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43728464376) / attempt `1` 绑定 source `78e917e406b904de1763725e59c07d4f963de536`、实际 checkout/build `f718267936e1c075bcfceb1cf6b4762b7ae74541`、job `132855491335`。verification阶段 `BUILD SUCCESSFUL in 10m 11s`，156项任务含141执行、15缓存；正常锁阶段 `BUILD SUCCESSFUL in 23s`，157项含4执行、153 up-to-date，实际显式成功解析186个配置。它已消除上一轮secondary artifact选择歧义，但第三阶段严格Debug仍在16秒因 `watchAndroidDebugRuntimeClasspath` 锁里的 `kotlin-stdlib-common:2.2.21` 未解析而失败。Release/Diagnostic严格阶段没有执行；应用单测仍为 `NO-SOURCE`，没有导出APK或签名。

报告artifact `14167473838` 的原始ZIP为55234字节，SHA-256 `335aef4ed85577b0d07b49a39a236767c3ee443096d82609f067210b70edf220`；job日志SHA-256 `8b010258667db49d30d6f20c6c9c3c528c4a8f0be4c1175fbb1291fea8c19837`。XML SHA-256仍为 `651c09fe2f027be20bb85ae35f2394217caeb3c916b60bde44bd1a5a603af204`，app锁仍为 `59e819943f974ff13698d0bf601db920acb87ce835280c2ac3eda0ccdfb57bf7`；报告复制字节与严格前输入一致，但整个manifest仍是 `failed-partial`，这些锁不得导入为可接受输入。

新锁的common 2.2.21只出现在 `implementationDependenciesMetadata` 和三变体的应用/UnitTest六个RuntimeClasspath，未出现在CompileClasspath。固定Gradle 8.13的 [首次图解析与缓存](https://github.com/gradle/gradle/blob/073314332697ba45c16c0a0ce1891fa6794179ff/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/configurations/DefaultConfiguration.java#L714)及 [write-locks状态读取](https://github.com/gradle/gradle/blob/073314332697ba45c16c0a0ce1891fa6794179ff/platforms/software/dependency-management/src/main/java/org/gradle/internal/locking/DefaultDependencyLockingProvider.java#L103)已核对：正常完整写锁不加载旧锁约束，已解析配置会被覆写。因此186配置覆盖并没有解决实际严格图差异，不能仅凭枚举成功接受锁。

本轮将第3节正常阶段改为真实消费者任务在前、补锁任务在后，并增加明确调度约束，保持全配置图解析、全部失败拒绝及三轮独立严格校验。AGP 8.11.1和Kotlin 2.2.21官方sources JAR仅作为数据审阅，SHA-256分别为 `65bc5d04cc52655664894e196f274da781091602a219126b67fd13bd2621ddd6` 和 `f79965870973bf7cc2b5ec9e762077b82dfb190b6452f43d4d91618a8311f263`；没有执行或复制实现。源码显示主要variant属性在配置阶段设置，调查尚未证明晚设属性是唯一原因。Python边界用例检查正常阶段九个任务与可选instrumentation均在采集任务前、失败不晋升、独立严格阶段不生成及输入字节不漂移；新时序的真实Android效果仍待下一轮五阶段云端证据。

## 9. 时序修正仍未解决锁差异与被动图诊断

第四轮显式收集 [run `43733936353`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43733936353) / attempt `1` 绑定 source `9e48e33fa877a22a7d7fb349325b2163b89ed20b`、日志确认的实际 checkout/build `6bb0e96faee6332285bf8545a35e483e018bb3a9`、job `132872315616`。verification阶段成功，耗时8m41s、156项任务含141执行和15缓存；正常锁阶段成功，耗时20s、157项含4执行和153 up-to-date。日志证明全部九个真实测试/lint/assemble任务先完成、采集器最后运行，仍显式解析186配置并覆盖三变体应用RuntimeClasspath。严格Debug在14s再次因锁中的 `kotlin-stdlib-common:2.2.21` 未解析而失败；严格Release/Diagnostic未尝试。时序改变没有修复阻塞，应用单测为 `NO-SOURCE`，三份lint XML零issue不代表设备或完整功能验收。

artifact `14170260864` 原始ZIP为55231字节，SHA-256 `a5ed550fec4005546378d99065254bfb00a2b2a9c48959991908da39c2153fb0`；日志SHA-256 `26c9079a9b6a34d7a61613e651e1c58f53e544d1090a077e98817401b9d6d626`。XML与app锁仍分别为 `651c09fe2f027be20bb85ae35f2394217caeb3c916b60bde44bd1a5a603af204` 和 `59e819943f974ff13698d0bf601db920acb87ce835280c2ac3eda0ccdfb57bf7`。报告字节虽与严格输入快照一致，manifest仍是 `failed-partial`，不得导入；没有导出APK或签名。

下一轮仅在缺锁的无秘密PR引导过程中，由build.py为原有五阶段设置各自确切的 `CI_DEPENDENCY_DIAGNOSTIC_PHASE`。普通构建清除此继承标记，不注册图观察器。enforce.gradle只对 `implementationDependenciesMetadata` 和三变体应用/UnitTest六个RuntimeClasspath注册被动 `incoming.afterResolve`，从已缓存的首次解析图记录数据；origin区分 `after-resolve-inside-collector`、`after-resolve-outside-collector`，不据此声称触发解析的具体任务。采集器在原有全配置解析后另记 `collector-snapshot`，保留每个 `canBeResolved` 配置、完整未解析边拒绝、mustRunAfter和STRICT，不请求artifact文件、不修改依赖或属性。

固定前缀 `CI_DEPENDENCY_GRAPH` 的单行JSON包含两组请求属性、Kotlin四个stdlib模块的实际选中组件/变体/属性/外部变体，以及全部请求这些模块的边。边保留from/requested/selected、 `isConstraint`、required/strict/preferred版本和resolvedVariant；未解析边只记录异常类与数量。属性名称使用固定allowlist并报告未展示属性数量；组件、图边、目标边、变体、外部变体深度、属性、异常类、字符串和JSON字节均有上界，超界或观察失败明确记录 `unavailable`，不把截断数据称为完整观察。日志不含任意异常message、文件路径、artifact URL或凭据，也不在bootstrap报告目录增加未经manifest声明的文件。

Gradle8.13固定源码已核验Git blob： [afterResolve之前保存已解析状态](https://github.com/gradle/gradle/blob/073314332697ba45c16c0a0ce1891fa6794179ff/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/configurations/DefaultConfiguration.java#L755)、 [锁失败进入公开未解析依赖结果](https://github.com/gradle/gradle/blob/073314332697ba45c16c0a0ce1891fa6794179ff/platforms/software/dependency-management/src/main/java/org/gradle/api/internal/artifacts/ivyservice/ResolutionExecutor.java#L355)；公开ResolutionResult、DependencyResult、ModuleComponentSelector/VersionConstraint及resolved/external variant API也按同一SHA审阅，未运行这些源码。Kotlin官方 `stdlib-common:2.2.21.module` 只有无files/available-at的common变体并依赖stdlib，SHA-256 `3ffdb451aa2d314fe7263782d37eabddcf8b941bcb940fe3192ac01a62b57f7b`；stdlib module的JVM API/runtime将common列为constraint，SHA-256 `bfbc657ddd3a8e37e4c8ad417968d5eb0b0b171c9fce48f962c2ad32ee434c21`。这尚未证明严格图差异的根因，须用下一轮真实正常/严格图比较，不能通过添加虚假依赖、删锁行或降低校验绕过。

新增Python边界用例覆盖五个确切标记、继承标记覆盖/清除、无效阶段或可信事件拒绝和token剥离；原有五阶段命令、失败不接受及无APK导出边界继续保留。42项Python CI测试、Node政策检查与diff空白检查通过；这些本地检查只验证CI数据合同。Groovy API调用、真实图观察和严格Android构建尚待云端执行，P0阻塞仍未完成。
