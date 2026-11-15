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

Compose锁定源码确认Activity宿主的`containerSize`使用当前窗口边界，未从IME后测量高度计算；Media3确认暂停命令与命令结果/异步错误的独立时序。修正依据已体现在页面与协调器；OEM行为仍须实测。所有新增实际AAR/JAR、传递许可、完整锁及Room初始schema的接受状态，以后续精确云端run/attempt/artifact/hash记录为准，直接POM存在不能替代构建或设备验收。
