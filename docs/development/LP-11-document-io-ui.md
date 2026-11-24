# LP-11 文档导出与手表备份界面

状态：源码建设中。以下合同和测试定义尚未经过云端编译、JVM/Android 测试或真实设备验收；F-068/F-069 及 F-073 的原 AC 仍为未执行。完整逻辑归档、事务恢复和资源代次由 LP-11 核心实现负责，本记录说明其外部文档边界与界面接入。

依据：[全量范围](../21-本地播放器全量需求整合.md)、[任务与依赖](../05-开发阶段与任务清单.md)、[本地合同](../17-本地音乐功能与实施计划.md)、[原 F-068/F-069/F-072/F-073](../requirements/local-player-F001-F128.md)。固定原需求与 AC 编号保持不变。

## 文档输入与输出

`BackupInputContract` 请求一个本地、可打开的文档，只请求读权。`BackupDestinationContract` 请求本地 SAF 目录树，保留系统实际返回的授权 flags。选择器结果仅属当前内存意图，不写入 UI 状态、备份、诊断或可重放操作；不调用 `takePersistableUriPermission`。未知 URI、无授权或不符合文档/树形结构的返回值仍由实际 I/O 层拒绝，选择器提示不是安全检查。

v1 的明确支持路径为系统 `com.android.externalstorage.documents`，同时核对其 package 为 `com.android.externalstorage` 且属于系统或更新的系统应用。其它本地和云提供者在 query/open 前拒绝，须独立验证后才能扩展。正常路径尚未在真实系统提供者运行，不能因此宣称设备支持。

模块内只读函数 `requirePlatformLocalProvider(context, uri)` 供显式外部歌词资源读取复用同一政策。调用者仍须在真实物理读取边界内独立核对当前数据库关联和实际授权；该函数不授予权限、不打开文档，也不新建文档输出所有者。

输出选择目录，而不接管已有的空文件。`LocalArtifactDocuments` 在目录内新建随机 `.part`，获得真实文档和常规文件 FD 身份后记录其设备号/inode；核对空文件、目录成员关系及读写/删除/重命名能力，再写入有界字节流并 fsync。完整读回长度和 SHA-256 必须与生产者回执一致，随后记录 RENAMING、执行同一实体的重命名、再次核对身份与完整内容，最后清日志。已有最终文件报冲突，不覆盖；失败或取消不报告成功。

备份 adapter 在同一真实 I/O 调用内运行实际 `BackupArchiveReader`，验证版本、条目、完整标志和 exportId；外部读回的相同完整字节绑定此验证。它不会把任意文件或仅有 ZIP 头的数据认作完整备份。

## 共用输出所有权

LP-11 备份和 LP-15 脱敏诊断必须注入同一个 `LocalArtifactDocuments` 实例及既有 `LocalProviderIo`。`BackupDocuments(artifacts)` 为模块内组合入口；只使用两个不同的 `BackupDocuments(context, io)` / `LocalArtifactDocuments(context, io)` 构造对象会绕过共用输出 owner，不符合接线合同。

来源域仅允许：

- BACKUP：`noBackupFilesDir/logical-backups-v1/<canonical UUID>/export.zip`，回执与真实归档一致。
- DIAGNOSTIC：`cacheDir/diagnostic-exports/active.json` 或 `<canonical UUID>.json`，使用独立 canonical UUID artifactId、实际长度与 SHA-256。
- 备份输入副本：同类私有 UUID 代次内新建的 `input.zip`，不写任意目录或已有文件。

BACKUP 输入/输出上限 300 MiB，DIAGNOSTIC 输出上限 64 KiB，复制/校验缓冲 64 KiB；这些是归档与文档 I/O 预算，不是曲库或完整队列条目上限。来源路径、文件名、URI 和内容不进入公开进度或错误日志。

私有 `input.zip` 在真实常规文件 FD 长度已核定后检查本次完整复制所需空间，每块写入前再次读取实际剩余空间并保留既有 `ImportLimits` 的 512 MiB 预留。长度未知或文件系统读取失败不作 0 字节，也不把其它应用可回收缓存计入空间。低空间停止新写入，按原创建实体清理这次私有副本；现有库和外部原文件保留。

一个私有、有界、带 SHA-256 的二进制日志保存在 `noBackupFilesDir/local-artifact-output-v1/pending.bin`。读写会核对目录及 `pending.bin`、`.bak`、`.new` 的路径与非符号链接条件；损坏、未知版本或仅剩未完成 `.new` 不补成无待办状态。

恢复无需原来源文件：CREATED 只删除匹配记录文档 id 与原 inode 的 `.part`；RENAMING 下已经完成重命名的文件只有同一身份、完整长度和原 SHA-256 均通过时才保留并清日志。PLANNED 下出现无法证明归属的同名文件、文档被替换、撤权或清理失败都保留实体并报告 `OUTPUT_CLEANUP_REQUIRED`。不按名字猜测归属，也不覆盖或删除其它原文件。

每个物理调用使用 `io.read(admitted = …)`；admitted 回执在占槽锁内同步确定。只有获准调用才在 finally 中以 `NonCancellable` 等待 `awaitIdle()`。取消尚未进入 worker 的已获准任务仍保留 owner，真正 BUSY 的拒绝不会等待别人的任务。输入副本的放弃清理由当前 owner 在真实退出后的 finally 完成，不从放槽后的恢复回调另开文件删除。

生产者只能在 `writeArtifact` 已返回或抛出、真实 I/O 已退出后 dispose 同一来源对象。即使 pending 外部清理仍失败，也可以释放该来源；恢复不重新打开它。typed `LocalArtifactException` / `BackupException` 从 provider 包装原因中恢复，取消原样传播，额外收尾错误保留为 suppressed。

## 界面与 Activity 接线

设置入口接入 `WatchBackupSettings(state, screenShape, visible, actions, onBack)` 与 Activity 所属的 `BackupViewModel`。显式 `onBack` 绑定统一导航返回，标题后的整行返回动作可触控、可滚到中央；流程内的“返回备份”仍由 `actions.home` 管理。`BackupUiActions.bind` 要求明确传入系统选择器、已授权曲库目标选择、来源管理、外观设置和存储恢复入口，其余操作交给同一 coordinator；不存在产品默认空回调。

Activity 注册两份 contract，只在对应 `begin…Picker()` 返回 true 时 launch。结果分别交给 `inputPicked` / `destinationPicked`；launch 无可用处理器或抛异常时必须调用 `pickerUnavailable()` 释放唯一回执槽并显示替代入口。外部系统选择器遮住窗口期间保持 `setPageSelected(true)`，只将 `setVisible(false)`；真正离开备份流程才调用 `setPageSelected(false)`。

缺文件重关联仅从已经独立授权的曲库选择目标，不从备份引用授予文件访问。`beginRelinkTargetRequest()` 返回 opaque 票据，Activity 用同一对象调用 `relinkTargetPicked(request, target)`，取消传 null。只读 `pendingRelinkTargetRequest` 保留同一尚未退休的票据供 Activity 旋转后重新取得，不以窗口可见性清空。备份页与其目标选择页属于同一流程 owner：选择页打开时保持 selected，备份 UI 隐藏；退出整个流程才退休该票据。新文件授权转到来源管理。迟到或退休的票据、选择器结果不能套到后来草稿。

导出先选范围、展示所选范围的可直接读取私密字段、说明无音频与无密码保护，然后明确确认和选目录。恢复先实际校验包，展示合并/仅替换所选范围、每范围的 incoming/current/conflict/missing 计数，随后分别确认恢复影响与本次字体使用许可。范围或模式改变必须复核；隐藏、取消、返回或重新预览都清除确认。不同/无法核对内容的重关联另须明确确认。

ViewModel 只持有一个业务 Job、一个有界预览退休任务及一个选择器回执槽。隐藏停止 startup/progress 观察和当前 caller；预览 token 等待原业务任务真实退出后才逐个 discard，收尾未完成不提前清除 working。纯退休收尾显示关闭状态；只有实际可取消业务任务显示取消按钮。当前缺文件页最多 100 行，next 游标可遍历所有页，不截断恢复记录或整个库。

`retryStorage()` 仅在可见且已选中的备份页、coordinator 当前状态为 Unavailable、所有业务/退休任务、选择器与排队意图均空闲时接受显式动作。重试直接调用 `initializeStorage()`，共用同一个业务 Job、可见代次与取消收尾，只在当前页面仍有效时呈现 startup/错误结果；成功后等待下一次明确的导出或恢复动作。

界面复用 Foundation 的整行选择/复选/动作控件，最小热区由共用控件保持。布局读取实际窗口、系统 inset、形状和应用边距；圆屏复用歌词固定中央安全阅读弦与首尾留白，矩形保持滚动路径。长说明、大字和首尾动作可以逐段滚到可读位置，表冠仅增强触控。隐藏时不组合备份文本或点击控件，不保留滚动观察。

## 测试定义与证据边界

现有 `src/test` 依赖内的 5 个新增定义尚未运行：选择器 input/output 意图、原返回 flags/opaque 结果、真实 Compose 的双确认与可达热区、隐藏界面无备份控件。Compose 定义沿用仓库已锁定的 API 30/NATIVE/PAUSED 配置，先滚动 LazyColumn 查找未组合节点，未使用本地编译或 JVM 执行核验。

9 个真实 FD/provider 的 Android instrumentation 定义暂存为支持材料，不在本轮默认应用构建输入。仓库尚无其 AndroidTest runner 和依赖声明；在获得依赖、锁和云端编译接线前不能称可运行。定义包含未知 provider 零 query/open、同一 inode 的完整读回与重命名、真实 versioned HISTORY 归档往返、来源域拒绝、读回失败清理、替换实体保留、重命名回执丢失恢复、worker 前取消及真实 BUSY。它们仅操作生成夹具；即使以后通过，也不替代实际系统提供者正常路径和真实手表验收。

本轮证据仅有 UTF-8/LF、引用/资源、源码合同与 ignored 目录中的逐字封包重建。云端编译、JVM/Android 测试、lint、双端 APK、真实 provider、功能原 AC、真表视觉/性能、互操作与最终发布验收全部 `NOT_RUN`。共享 App/Activity 组合以及 LP-11 核心独立审阅须另行记录；源码包或文档说明不自动完成它们。
