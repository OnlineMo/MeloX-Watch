# DEV-104 持久队列、会话恢复与设置建设

本主题从DEV-004-A同一Android工程演进，覆盖DEV-101/102/104/105和LP-00/04/12的部分实现。用户已委托中间建设与审阅；全部128项本地需求/256个原AC、网易云、Meting和真实手机端仍在首版范围内。这里没有声明任何完整LP任务、设备门槛或候选验收通过。

## 实现与所有权

`MeloXApplication`共享一个Room数据库、QueueStore和DataStore设置仓库。Room初始v1只包含实际使用的队列代次、条目和活动指针三表，文件身份为`melox.db`；未加入空的曲库、账号或配套模块。后续真实曲库表须在同一身份上显式迁移，禁止破坏性fallback。初始schema必须取自云端KSP输出、审阅后提交；未手写schema或运行本地Gradle。

队列保存稳定`TrackKey`、位置引用和每次入队的`queueEntryId`，URI不替代逻辑身份，同曲重复使用不同实例ID。一次替换先冻结完整视图，按100条读取并暂存，校验原始序号/播放序号的完整排列，最后短CAS事务发布。失败或取消发生在提交点之前保留旧队列；提交之后新队列完整存在。数据库页最多100条、后端窗口最多当前及前后各一条；这些是工作预算，完整队列没有10000条上限，也不截成当前页面。

唯一`ProbePlaybackService`继续持有一个ExoPlayer和一个MediaSession。协调器从完整数据库决定上一首、下一首和自然结束；标准控制器不能直接改写三条后端窗口或绕过持久队列设置模式。焦点/noisy暂由同一个ExoPlayer负责；系统MediaPlayer与统一双后端切换仍属于LP-05/DEV-103后续必需实现。

启动或显式重载只恢复暂停状态，设置决定是否恢复位置。checkpoint修订独立于队列修订，在播放中每5秒及暂停/seek/切歌时合并保存；不复制整队列。销毁先捕获最后状态、detach并立即释放唯一后端，之后的收尾只写已捕获值，不再访问已释放播放器。窗口读取与等待锁可取消，只有必要的短CAS保留提交语义。

暂停即使在已暂停状态也更新播放意图，取消在途操作；晚到的原子提交可以保留完整新队列，但不得自动播放。命令确认只表示持久提交及同步窗口配置，不冒充已经出声，也不清除之后异步报告的音源错误。已保存队列的窗口失败，通过重读暂停快照或明确选择当前实例修复；历史操作的幂等确认不重新激活旧代次或加载窗口。

## 页面与设置

队列页只在可见时观察和读取当前数据库页，按冻结代次/修订再次校验结果。失败保留一致的旧快照与旧页，明确标出旧数据并禁用旧页选择，提供显式重载；全部页面可前后访问。后台没有队列查询或视觉进度采样。当前播放项来自会话，保存的当前项来自快照，旧数据页不误标新的活动实例。

本次设置schema1只有恢复位置和自动/圆/方形状。编辑草稿与已保存值分开，只有明确保存并核对预期revision才原子提交；取消和返回丢弃预览，重置需要第二次确认且只影响设置。存储失败/未知未来schema保留原字节与可见错误，未知schema只读，不清除音乐、曲库或队列。页面恢复只保存简单导航/草稿值，不重放保存或重置动作。

圆屏避让始终考虑实际窗口与物理形状；手动矩形不取消真实圆边保护。IME只缩小可见滚动区，顶部安全空间仍从未消耗IME的完整窗口尺寸计算。中英资源均已接入；实际手表/代表性方屏、最大字体、键盘和触控矩阵仍按15等待设备证据。

## 验证状态

已新增58个应用测试：Room/实际SQLite18个、真实DataStore17个、队列导航5个、主Looper实际ExoPlayer协调器9个、真实Bundle协议9个。覆盖数据库关闭重开、10000/10001条、CAS冲突、取消/恢复、低空间/回滚、未知schema、暂停与晚回调、释放后零播放器访问、权限/来源字段及稳定身份。第六轮云端实际执行Debug全部58项且全部通过；Release/Diagnostic尚未执行，Debug lint失败。它们不代替音频出声或设备AC；本地均未执行。

本地45个Python CI边界测试及Node治理用例通过，XML/UTF-8/资源引用/中英资源键和diff文本检查通过，同伴互审未留源级阻断。直接依赖与官方API审阅见[依赖记录](DEV-104-dependencies.md)。已接入P0五阶段资料采集，Room生成schema与锁/XML一起冻结，三变体独立严格验证和报告复制均不得改变其字节；新增schema漂移拒绝测试。完整云端依赖/锁/schema采集、三变体严格58项测试/lint/APK构建、稳定签名和设备测量仍未完成；P0严格锁文件差异仍在处理，不能沿用资料采集成功代替本主题验证。

本主题尚无与云端APK绑定的设备安装、耳机、30分钟后台或14性能预算证据。所有readiness为false、candidate=null、256个原AC未通过；没有公开、tag、Release或发布批准。

首轮云端 [run `43722837976`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43722837976) / attempt `1` 绑定 source `4f6ed75205ed7e3b5e7251d8dd70d7c500a345c2`、merge build `be210a19c46f3b69e8d46b9f3a412ff67c30f72c`、job `132838389943`。preflight通过；资料阶段在Kotlin编译的 `FlagSet` 引用处失败，应用测试/lint/assemble未完成，签名跳过。官方固定Media3 1.8.0 source JAR核对确认其公开类型为 `androidx.media3.common.FlagSet`，已据此修正导入，未换依赖版本。该官方source JAR SHA-256为 `800866952af08b1f1c39b2a56f906a46f3a0e7ca4ae5e92310ea5388beef7a38`，只作API审阅，没有运行或复制实现。

报告artifact `14165955171` 为 `failed-partial`；KSP曾生成三张队列表的schema1，但缺少app锁及完整严格阶段，因此未导入任何失败资料或把它当迁移验收基线。下一轮已沿用P0显式配置解析收集修正，仍须真实云端58项测试、严格锁/schema和APK证据。

第二轮 [run `43726829869`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43726829869) / attempt `1` 绑定 source `24ba3c10847c7b79eceab3adc4ea703a8fdb986a`、merge build `7b6e82797ab2b4ca6b3ca7d8c8e9bc803ebf2b5f`、job `132850534757`。资料第一阶段推进到单元测试源码编译，`ReleaseGuard.getPlayerError()` 返回类型与固定 ExoPlayer 接口不符，尚未执行58项测试；按官方1.8.0公开接口修正为 `ExoPlaybackException?`。日志SHA-256 `506818623460cd720f61367f3f3901b7d2f460cae84566610be493ccdb40c3e5`，报告artifact `14167380191` 继续仅作失败证据，不导入依赖或schema。本地未编译或执行应用测试。

第三轮 [run `43728583277`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43728583277) / attempt `1` 绑定 source `aa4b0224b9f44b8af245cdf3c15745d7b91dc564`、实际 checkout/build `d9c0ff5f6f4b46772b7e2146fbe0dc08dbb4a09b`、job `132855889149`。第一资料阶段在3分23秒失败，实际JUnit XML有Debug 58项，57通过、1失败、0跳过、0错误；失败项为 `RoomQueueStoreTest.realSqliteFullPreservesTheOldQueueAndAllowsExplicitRetry`，原始异常为测试断言 `expected STORAGE_FULL`。其余两变体和第二至第五阶段未执行，没有导出APK或签名。

报告artifact `14167980777` 原始ZIP为81904字节，SHA-256 `ab07370b772e56c0c84fa5ee2d0a3c0e71ae4a178aa14e458e5f39d165c759b3`；job日志SHA-256 `f55537c0712d7fa4b6a6febca125c1467420d0985c30c7d4354b1f58f95a0ff9`。实际RoomQueueStore JUnit XML SHA-256 `4b24d6b5a8a6747cb0d7b86a0482ce10bbc91dc8d4963698e559d27870014b45`，KSP schema1 SHA-256 `3a36d662a8ee80e70d76180d564f8d9858243631dd20e2648c2492df8ff39c79`。这些字节均只保留作 `failed-partial` 诊断证据，未导入为严格锁或迁移schema基线。

低空间测试原来通过事务外查询设置 `PRAGMA max_page_count`；WAL连接池可以把查询分配给reader，该连接的页上限没有约束Room writer。修正为短Room事务内读取原上限并设置writer页上限，实际 `store.replace` 保持自身暂存/提交事务；另一个短事务恢复原上限。测试新增真实 `SQLiteFullException` cause断言，继续检查旧队列、重开后完整性和205条显式重试。独立Python SQLite连接实验复现reader上限不约束writer、writer触发code13并回滚；这只证明fixture方向，第四轮Android云端分类断言仍失败。

第四轮 [run `43731373776`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43731373776) / attempt `1` 绑定 source `18562dc1e3a7db2e0a4f0d1c247e7c4518723b31`、实际 checkout/build `fbdcd64639399b2544f623ee00d121f675d0bf2b`、job `132864370046`。第一资料阶段在3分52秒失败，实际Debug JUnit仍为58项、57通过、1失败、0跳过、0错误；同一低空间测试的原始异常为 `java.lang.AssertionError: expected:<STORAGE_FULL> but was:<STORAGE_UNAVAILABLE>`，栈指向 `expectIssue` 第537行和测试第412行。Release/Diagnostic与第二至第五阶段未执行，没有导出APK或签名。

报告artifact `14168691523` 原始ZIP为82079字节，SHA-256 `8939a9f6328a9ad7199dd1f8ecf3f336b07aaa99a59d150bed4e5a44355c8afa`；job日志SHA-256 `097fcf82d9ce6770e4af3c73da856b18bc66a6bdf1be2e53ffdca5b61fb40fe3`。实际RoomQueueStore JUnit XML SHA-256 `9601dc6a70acb73322187daf30b11d806fab4d0730c3a945c616e7855cda827e`；KSP schema1仍为11896字节、SHA-256 `3a36d662a8ee80e70d76180d564f8d9858243631dd20e2648c2492df8ff39c79`，缺少app锁，继续只作 `failed-partial` 证据。

该JUnit没有导出底层cause，system-out/system-err也为空，尚不能确认导致 `STORAGE_UNAVAILABLE` 的实际SQLite异常。`expectIssue`现保持原分类断言，并在断言失败时把原 `QueueStoreException` 附为 `AssertionError` cause，保留其cause/suppressed链供下一轮云端诊断；没有改为接受 `STORAGE_UNAVAILABLE` 或据猜测修改生产分类。完整严格锁/schema、三变体及低空间恢复验收继续未完成。

第五轮 [run `43735143902`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43735143902) / attempt `1` 绑定 source `5ea06616e1d88313aae7199619de02030bbe2022`、实际 checkout/build `a420a10c8af6be1ed944744f96821af868b52492`、job `132876024547`。第一资料阶段在3分33秒失败，71个Gradle任务中61个实际执行、10个来自缓存；实际Debug仍为58项、57通过、1失败、0跳过、0错误。Release/Diagnostic与第二至第五阶段未执行，没有导出APK或签名。

报告artifact `14170197855` 原始ZIP为83786字节，SHA-256 `51b579018f6d42a762401199aa7097a68d04899542e86eed8728e74e02f5b246`；job日志SHA-256 `cc0c661bed065000562cf3779f18c1a2b810b3b46e043e0d4176de174c5700cf`。实际RoomQueueStore JUnit XML SHA-256 `c5767118e910dac5d522cc21b28c69e7287efa8b705c884cbb9670c3fa6bde12`，本轮保留的cause为 `android.database.sqlite.SQLiteException: cannot rollback - no transaction is active (code 1 SQLITE_ERROR)`。栈从API26 `SQLiteSession.endTransactionUnchecked` 第439行，经 `FrameworkSQLiteDatabase.endTransaction` 和 `RoomDatabase.internalEndTransaction`，到 `RoomDatabase.withTransaction` 的finally第2049行。XML没有事务内最先失败的SQL异常，也没有suppressed；已经观测到退出异常，仍未观测到原始 `SQLiteFullException`。

官方Room 2.8.5 Android source JAR SHA-256 `bab25e3479739fbb6fa855a165ed4e71211fc259b859a05ff90ce52e080f57f2` 的 `RoomDatabase.android.kt` 第2041～2050行确认外层finally不单独保留block异常；API26官方源码固定提交 `97e5130234bcdc1c4a705be4e548302934499bd4` 的 [SQLiteSession.java](https://android.googlesource.com/platform/frameworks/base/+/97e5130234bcdc1c4a705be4e548302934499bd4/core/java/android/database/sqlite/SQLiteSession.java) 第404～449行确认退出先移除事务栈、再执行COMMIT/ROLLBACK。此源码机制与本轮实际退出栈一致；它不等于已经证明事务内原错的类型。

据此新增独立 `withPreservedTransaction` 包装，并让QueueStore全部12处事务使用它，没有复制Room实现、改变SQL/fixture或按错误消息重分类。包装只保存block抛出的原 `Throwable`：退出另抛时将退出异常加为suppressed、继续抛原异常；原取消仍作为 `CancellationException` 传播。begin、成功标记或退出单独失败时没有block原错，保留该原异常。现有真实FULL cause、旧队列、重开/显式重试及取消测试将核验修复；修复后云端尚未执行。KSP schema1仍为11896字节、SHA-256 `3a36d662a8ee80e70d76180d564f8d9858243631dd20e2648c2492df8ff39c79`，缺少app锁，仍只作 `failed-partial`，未导入或宣布迁移基线通过。

第六轮 [run `43737529727`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43737529727) / attempt `1` 绑定 source `9abf664ae30a28c74bf41c570400f1af337259c8`、实际checkout/build `5ecd517afa7c8e3a5a95385813e3d27579125ae0`、job `132883386122`。Debug实际5份JUnit、58项全部通过，0失败/错误/跳过；真实低空间case在0.274秒通过，冻结测试断言直接 `SQLiteFullException` cause、旧队列、重开后旧队列、独立连接完整性/3条及205条显式重试。成功XML无异常堆栈，原测试未断言suppressed，不能声称已观测退出栈被保留；下一轮在同一真实case增加独立 `SQLiteException` cleanup的suppressed类型断言。

本轮第一资料阶段7分59秒停在Debug lint，91个任务中80实际执行/11缓存；实际lint为3个 `WrongConstant`（ProbePlaybackService.kt 163/179/214）和1个 `UseKtx`（QueuePlaybackCoordinator.kt 602），并非4个常量错误。固定Media3 1.8.0 `SessionResult.Code` 的IntDef列 `SessionError.ERROR_*`，不列数值相同的 `SessionResult.RESULT_ERROR_*` 别名；依官方接口改用原错误码的 `SessionError` 符号，RESULT_SUCCESS保留。URI解析改用已有core-ktx的 `String.toUri`。没有suppress、减少lint门槛或修改测试预期。

报告artifact `14170988432` 原始ZIP104179字节、SHA-256 `42c318ec10c2b2dd954255d9b92ca9102cd412589310eea7fc648b9156b51069`；job日志SHA-256 `400592cbef13828b78934ec9071410def527981699659bfbe240f98b2b862676`；实际Room JUnit XML `ea9674378db62da759c8cf9b9a21b73d335863464a290c655cb5bb2e495725ec`；lint XML `787e0e2d3c6c0d238da59cdea4cb2fae64210902aa8ac87398eaf72b125b1cf8`。缺少app锁、其余变体/阶段，schema1 hash不变；继续仅作 `failed-partial` 诊断，不导入基线或导出APK。上述lint及suppressed修正后尚未云执行。

## 后续必需实现

本主题只提供完整持久队列与设置基础，探针来源检查仍是少量样本。LP-01/02的完整扫描与授权恢复、LP-03真实曲库/统计、LP-04完整增删重排/点击策略/可配置模式、LP-05双后端，以及歌词/音效/文件写入/备份/主题/传输/手机端和在线业务，均继续按05及21实现。初始化schema、实际迁移与库→队列的完整冻结查询将随对应主题重验。
