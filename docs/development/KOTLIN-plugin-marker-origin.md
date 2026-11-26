# Kotlin 插件标记的构建仓库来源

2026-11-27，普通 [run45880109302](https://github.com/OnlineMo/MeloX-Watch/actions/runs/45880109302)/attempt1 绑定 source `65b158a35871c9a8d091ef15ff41e65bd4082f44`、实际 build `7107d72e80bc24ff1e080c10cf7b70dacd7ed474`。整轮113秒，preflight52秒成功，android-compile50秒失败；实际Gradle进程在11.074秒自然exit1，未开始应用编译、Test、lint或组装。

原 reports artifact `15146189340` 为107554字节，SHA-256 `e5cc8b9bb6c3b566924d2bc7d8b90538adbc6df40d72538a2c22894e4bd85f3d`。7个原成员的CRC、归档字节和保存字节独立复核；完整日志与依赖报告同时定位 `android/build.gradle.kts:3` 的 `detachedConfiguration2`。唯一真实故障为 Kotlin Android 2.2.21 插件标记 POM 校验不同，通用签名帮助文本不代表其它依赖失败。没有JUnit或新APK，34个回归目标均为NOT_RUN，不填零通过或复用前一轮3243/2结果。

## 原件与既有信任

坐标为 `org.jetbrains.kotlin.android:org.jetbrains.kotlin.android.gradle.plugin:2.2.21`，packaging=pom。两个权威来源分别只读一次，均HTTP200、无重定向；原字节只保存在本地。

| 来源 | 字节 | SHA-256 |
| --- | --- | --- |
| [Maven Central](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/android/org.jetbrains.kotlin.android.gradle.plugin/2.2.21/org.jetbrains.kotlin.android.gradle.plugin-2.2.21.pom) | 1392 | `8b4103cc1412eb838a33c36b0e5c6e63bd0cace4c30ab9658f3861e41fc08389` |
| [Gradle Plugin Portal](https://plugins.gradle.org/m2/org/jetbrains/kotlin/android/org.jetbrains.kotlin.android.gradle.plugin/2.2.21/org.jetbrains.kotlin.android.gradle.plugin-2.2.21.pom) | 681 | `45c2d0d6f1f62b1f724900693d48a68f58be3d73602e29f9167489d2332a5fb6` |

Central原件精确匹配现有唯一已准入pin；Portal原件精确匹配本轮失败报告的实际值。两者的唯一依赖均为 `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.21`；差量在XML排版、schemaLocation及Central的名称、Apache-2.0许可和JetBrains发布元数据。原运行没有保存失败POM本体，来源读回与失败值一致不证明此次Central为何未被采用。

## 来源修正与后续验证

`pluginManagement.repositories` 的Google和Plugin Portal分别以 `content.excludeModule` 排除这一精确标记模块，原Maven Central对象与URL保留。该标记只从Central获取，其余插件、实现依赖、应用仓库和仓库顺序保持。Central不可用时不改用不同字节的标记，也不增加第二可信hash。

工具链、依赖图、模块版本、已有锁、verification配置及每个原制品pin、Room9和既有输入原件均保持。没有新的依赖或schema采集请求，不调用writer，不放宽任何构建/静默/测试期限；此差量须一次新的普通完整双端Debug验证。原文件关闭修复、四个新SDK组合、34个回归目标、精确APK、设备及原AC仍待各自实际结果。

## 解析失败诊断

来源限定的 [run45887339281](https://github.com/OnlineMo/MeloX-Watch/actions/runs/45887339281)/attempt1 绑定 source `3ceb9ca5de37eff40fb5a74428e666f6190fa7a8`、build `b2f8a49563e892f130827f447bee9f1c902ccb81`。整轮114秒，实际Gradle进程15.813秒自然exit1，唯一明确故障为同一插件的 `UnknownPluginException`，没有具体底层失败链。报告 artifact `15150421362` 为4278字节，SHA-256 `d438704b73cde5816d4675fcd0e116b1e254d808255bb5bc1c65f49ae113e985`；两个原成员从canonical ZIP直接核CRC、SHA及字节数，未另存解压副本。

固定 [Gradle 8.13](https://github.com/gradle/gradle/tree/073314332697ba45c16c0a0ce1891fa6794179ff) 的插件解析器以非传递lenient ArtifactView检查marker；任何解析失败都被归为notFound，仓库列表则列出全部配置对象。列表不能证明每个仓库都发起HTTP，也不能据此推定内容排除失效。插件仓库包装保留精确group/module过滤，原件没有证明Central的实际HTTP、缓存或元数据校验结果。

普通双端Test阶段已加入 `--info`，后继run45892568249已解析同一Kotlin Android插件并进入测试；原件不确定前两轮Central解析失败的具体HTTP或缓存原因。该轮手表宿主JVM在C2编译器处SIGSEGV退出，缺完整JUnit；手机120项原JUnit通过，业务目标的成功事件不替代完整验收。最新宿主Test配置与完整验证边界见[JVM测试稳定性](JVM-host-test-stability.md)。既有严格校验、仓库规则、依赖/schema输入、构建期限及后续lint/组装门保持，不启用writer或图采集监听器；所有设备及发布门槛保持未通过。
