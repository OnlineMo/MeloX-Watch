# LP-10 文件写入与批量管理源码提案

2026-11-22。本记录对应 c50257f82e47decef4412e14034ee417f0165668 的独立作者后像。尚未应用到主线，尚未独立代码审查；本记录不把 LP-10、原 F/AC 或发布准备标为完成。需求仍以 21、17、05 为准，匿名来源报告 SHA-256 仍为 `2f1e8217436375a7c27cbddd59e7dbe838365ec4a67c48c56f26b544fde9f167`。

| 状态 | 本轮观察 |
| --- | --- |
| 源码提案 | 已写文件操作、真实 SAF 适配、Room/队列协调、UI、存储及测试定义，等待 Root 独审和组合 |
| Android 编译、测试、lint、APK | NOT_RUN；没有本地 Android/JVM/SQL 执行，没有生成替代 APK |
| 云端验证 | NOT_RUN；Cloud45 未启动 runner，应用步骤执行数为 0，本轮未 rerun |
| 设备、解码、权限、升级 | NOT_RUN；没有设备或平台成功证据 |
| 性能、视觉、互操作 | NOT_RUN；源代码预算和测试定义不是测量结果 |
| AC-052/053/054/063/075/088-N/E | 全部 NOT_RUN，不用 unsupported 或文本检查通过原 AC |
| 候选、签名、发布批准 | 未推进 |

## 实际对象与意图

`FileOperationsRuntime` 持有同一个 noBackup journal、原件/扫描/来源围栏及库桥。选择以持久行或全选例外记录；实际预览冻结完整 producer 的选中 ID、位置/contentRevision、授权和全部实体影响。页预算为 100，结果 UI 单页 10，均不限制完整列表数量。20001 条选择、全选默认和队列的测试定义已写，尚未执行。

移出索引、移除来源、删除原件、删除应用副本、写标签、改名/移动及完成复制后的删源分开命名和绑定确认。索引移出保存实际 `member=false` 和可审阅恢复入口；扫描保留忽略位。原文件、自定义显示层、收藏/歌单/统计及历史关系不因逻辑忽略删除。LP-11 恢复后旧 undo owner 不能继续授权，但无旧 journal owner 的忽略行仍可由明确恢复入口管理。

同一 Location 的多个别名和已准备的 CUE 关系共同预览和协调；独立 Location 对同 provider/objectId 的证明不完整时拒绝扩大原件范围。当前 `cue-v1-` 识别只对应现有准备数据，正式 LP-04 持久化/播放集成仍未完成。

SAF 路径使用实际授权 TREE、父对象、DocID、regular/seekable FD、native dev/inode/ctime 及完整 SHA-256，不由 URI 猜磁盘路径。读权限及声明 flags 不推出写入成功。应用副本删除单独验证 OwnedAudioProvider UUID、私有根、native 身份和完整字节，只删除本次确切 `.audio`。

## 暂存、发布和恢复

原件不以截断模式打开。标签路径先创建确切空候选，完整流式写入、检查实际剩余空间、sync/close，然后另开新流独立复读字段和音频载荷摘要。已核验同 provider 的原件改名为确切备份、候选发布原名；库和完整持久队列协调结束后才清理本次备份。

当前标签写入提案提供原生 FLAC 及 untagged/plain ID3v2.4 MP3 路径，六类字段为标题、艺人列表、专辑、专辑艺人、曲号、碟号。未知 frame/block/comment/vendor 字节保留，音频载荷逐字节保留。格式逻辑参考 RFC 9639 §8 和 ID3v2.4 结构/帧规范，没有复制研究实现或执行研究快照。ID3v2.3、unsynchronisation、扩展头/footer、ID3v1/APEv2、seek/加密链及其他不支持的帧标志仍为格式缺口，不能宣称完整 MP3 覆盖。

同 provider 移动保存实际移动结果，再执行可选改名；改名成功而日志写入失败时，观察同一对象/完整字节的实际名称和 URI，不重放物理移动。跨 provider 路径完整复制及校验后保源，使用独立 `DELETE_COMPLETED_MOVE_SOURCE` 票据删源。删源前持久记录 `sourceDeletionStarted`；观察入口不盲重放删除。删源失败保两份和失败原因，普通 MOVE 重试不能借用二次删源授权。

操作/实体阶段及队列提交绑定保存在独立 BundledSQLite schema 1；主音乐库作者前像仍为 schema 6。外部文件 I/O 不进入 Room 事务。journal 在外部调用前落盘，实际 URI/结果在取消后的收尾中继续观察和协调。未知持久阶段拒绝放行；索引 APPLYING 同样是未协调工作，数据库失败或空来源的零条选择不能推定完成。BUSY/FULL 清理错误保留为 suppressed，不替代原取消/失败。

启动打开并校验私有 journal，必要 schema 创建仅写内部日志；不枚举外部音乐目录，不执行历史草稿，不自动删原件/暂存。当前确认中的标签替换可在先观察排除成功发布后，补偿它自己中断的确切备份；历史恢复和暂存丢弃仍需各自绑定票据。明确重新打开选择时分批退休无运行时所有者的旧草稿；每次回收最多 100 行，退出回收有 2500ms 等候预算，超时留下不可执行的 retired 元数据，不持续运行隐藏 UI 工作。

## 播放、扫描与持久队列

服务关闭 hook 同步暂停并摘除 session/backend 绑定，再等待已接受的命令、初始化、native 释放、checkpoint、定时/统计和物理 I/O 收尾。扫描 `cancelAndAwait` 等待实际游标和终态 settled，包括从未启动的 job，5 秒未确认则 BUSY。文件 host 等候上限 15 秒；未确认关闭/释放保留围栏，不用新 owner、日志刷新或全局 reset 清除 quarantine。

成功关闭后仅从未分配播放器的真实 lease 可进入文件/库修改。完成不自动发声，后续 controller 连接可重建空服务并恢复暂停。原件/扫描/来源共享 mutation guard，队列修复使用同一实际 `RoomQueueStore` 的 preparation lease，不用另一个 facade 或 mutex 代替。

队列修复遍历全部持久实例，保留实例 ID、顺序、generation、checkpoint 和循环设置；页可重放，最终 revision 只增加一次，私有确认丢失后按已绑定的 final revision 观察确认。普通播放仍遵守 LP-01 的 4 MiB 弱验证预算；没有自动完整读取大 SAF 文件或把 fstat 当强 provider proof。显式文件变更已经完整验证结果时，队列仍更新真实 URI/contentRevision；普通 `CONTENT_UNKNOWN` 保留其含义，不能永久停在旧 URI 或宣称已验证。

标签与完整移动复制不改变 audio payload。`RoomFileOperationLibrary.patched()` 和 `writeTrack()` 使用旧 metadata/entity 的 copy；Root 的独立 schema 7 codecParameters successor 应保留已观察 codec 字段，普通标签/改名没有新的 codec 测量证据。

## LP-11 必需接线

运行时新增必需构造参数 `requireFileMutationReady: () -> Unit`，没有 no-op 默认。该同步检查在同一原件 guard 内覆盖普通 prepare/confirm/retry/recover 和明确 restore/discard/index 恢复，不先占 backup lease。journal 初始化及 LP-11 自己的 exclusive restore hook 不调用此检查。

Root 组合 App 时必须接实际 `BackupMutationBarrier.requireReadyForFileMutation()` 并映射异常：

```kotlin
requireFileMutationReady = {
    try {
        backupMutationBarrier.requireReadyForFileMutation()
    } catch (failure: BackupException) {
        throw FileOperationException(
            if (failure.issue == BackupIssue.BUSY) FileOperationIssue.BUSY
            else FileOperationIssue.RECOVERY_REQUIRED,
            failure,
        )
    }
}
```

本作者 App 后像保留 LP-10 接入前的调用外形，该必需参数由 Root 的 LP-10/LP-11/DSP 组合补齐；单独应用本包不能据此宣称可编译。LP-11 确认恢复顺序为共享 `withExclusiveLibraryMutation(requireNoPending=true)` → `withClosedPlayback` → 实际 `roomQueues.withFileLibraryRestore(reservation)` → `BackupMutationBarrier.exclusively`。不能先占 backup barrier 使服务 checkpoint 被拒；restore 后在同一队列 lease 的 finally 使旧验证缓存失效，并清实际 index undo。

## 存储和界面

存储只在明确命令中统计可访问原件引用、完成导入/传入副本、封面/获取歌词缓存、恢复/传输暂存及实际可用空间。未知保留未知数量/原因；清理显示真实前后观察值，不相减推算释放量。仅缓存所有者清自己的派生文件并重建索引；不删音频、副本、保存歌词、字体主题、收藏歌单或恢复 journal。新增缓存写入有 512 MiB 预留检查，实际手表条件和性能仍待测。

界面提供单曲和跨页选择、完整全选、独立意图、完整影响/停播确认、标签差异、实际登记目标根、结果/待处理/恢复和忽略分页。目标为授权的实际 TREE；附属 LRC 默认不移动，预览说明歌词及其他应用影响，当前没有另行选择/移动附属文件路径。屏幕可见性由 Activity 管理；lazy 行滚出不会丢选择，隐藏取消只等待捕获的旧命令并关闭旧草稿。中文/英文资源均已写，圆/方、大字、权限与设备交互矩阵未验收。

## 尚未执行的定义与缺口

云端回归定义使用真实 Files-backed DocumentsProvider 的 create/write/rename/move/delete、精确 PFD 与套件限定 Linux Os shadow；不固定成功、不全局清 quarantine。独立 FLAC/ID3 reader 验字段、未知内容和完整 audio bytes；native SQLite journal reopen、未知阶段、意图隔离、只读/撤权、同字节新 inode、冲突、取消/补偿、复制删源失败、完整 20001 行及队列重放、全选视图借用、cache 保留原文件和释放失败围栏均已有定义。FULL-before-external 用例为明确 journal 故障注入，不冒充真实 SQLite 磁盘已满验收。

Debug 专用、非导出 provider 和 plain Android Instrumentation 已定义真实 MMR 与 MediaExtractor/MediaCodec 前后 PCM 验证。静音 FLAC/MP3 输入按规范生成；其有效性、元数据和实际解码尚未执行确认，不称已测有效样本。没有新测试框架、依赖、build.gradle、workflow/actions 修改。未来可在设备上对已下载的同一 Cloud Debug APK 明确执行：

```text
adb shell am instrument -w -e case all io.github.onlinemo.meloxwatch.dev/io.github.onlinemo.meloxwatch.data.fileoperations.fixture.FileOperationNativeInstrumentation
```

设备报告须绑定实际 APK hash/certificate、source/build SHA、run/attempt/artifact 和实测 API/ABI/window/provider 条件。此 probe 只检查隔离测试文件的 adapter/metadata/decoder，不能代替主库 UI、系统删除确认、后台播放、升级、性能或整个原 AC。

MediaStore 不同 API/所有权的系统删源确认、独立 DOCUMENT/虚拟/非 seekable provider 写路径、上述 ID3 格式、正式 CUE 集成、可选附属 LRC 逐项操作及全部设备/性能/视觉正常与错误矩阵仍未完成。当前不支持环境的明确错误不是完成声明。后续必须在支持路径拿到真实正常案例，并继续保留原 128 项与 256 个 AC。
