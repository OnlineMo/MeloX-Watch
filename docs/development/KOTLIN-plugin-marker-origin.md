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
