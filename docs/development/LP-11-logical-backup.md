# LP-11 完整逻辑备份与恢复合同

本文件冻结 F-068/F-069 的 v1 实现合同。源码、云编译/测试、APK、设备正常/错误验收、性能/视觉和发布批准分别记账；本包仅为源码提案，新增测试定义未编译或执行，原 AC 均未通过。系统 Auto Backup 的排除继续保留，显式本地导出不构成云备份许可。

## 1. 格式、范围与本地边界

v1 为普通 ZIP：首项 `manifest.json`；按 `BackupRecordKind` 固定类型顺序、UTF-8 二进制稳定键顺序写 `rows/<kind>/<12位块号>.json`；按资源 ID 排序写 `resources/<id>.bin`；最后写 `complete.json`。完整标志绑定导出 UUID、全部类型的行/块数、资源数、逻辑/资源字节和包含名字/字节/摘要的链式 SHA-256。中心目录与本地目录必须一致、连续，拒绝额外字段、注释、链接、特殊文件、多磁盘、ZIP64、未知/重复/逃逸名字、CRC/摘要/长度不符及缺尾标志。

范围为库、收藏、歌单、完整队列、历史/统计、偏好、歌词及关联资源；资源范围必须同时包含库、偏好或歌词之一。v1 固定 `encryption=none`、`audioIncluded=false`，没有音频包含选项，不称加密。导出之前预览可读性和所选范围可能含有的私人名称、目录引用和歌词；目的地必须通过明确选择并核对系统本地文档 tree。没有标准选择器或未知/cloud provider 的正常支持不得推定；补齐对应设备路径后另记实测。

排除凭据、账号/配对密钥、token、Keystore、系统 grant、在线/传输端点、运行中定时器、诊断身份、缓存及音频。不把绝对路径或 URI 字符串恢复为权限。可移植的内容/相对引用只作提示，不据此新开来源。歌词的摘要和编码是一对独立内容引用，文档/来源/URI 提示各自可缺失；清掉不可移植提示不能丢掉内容关联。

[F-084 外部歌词合同](LP-08-F084-bluetooth-lyrics.md)新增的 player schema 6 activation Boolean 也排除；portable backup schema 1 与字段白名单保持不变，恢复明确关闭。配对地址、数字、密钥与接收能力只在内存，不随设置/备份恢复。既有唯一服务关闭会等待外部 read 的实际退出和 transport 退休，不能在 socket 已关而歌词 provider 仍退出时放行播放 owner；此源提案和原恢复/设备验收仍分别记未完成。

[F-070/F-071 查找合同](LP-07-F070-F071-local-metadata.md)的 player schema 7 activation 同样排除并在恢复时实际关闭；查询草稿与临时服务 URL 不导出。Room schema 9 新增两个素材记录类型，歌词属 LYRICS、封面属 LIBRARY，资源仍依原生 owner 校验；schema 9 完整标志必须包含新计数，旧 1～8 缺省零的兼容不改变原 ZIP v1。

输入预算为逻辑压缩 100 MiB/展开 200 MiB、资源另计 200 MiB、目录 32,768 项、JSON 深度 16/字段 128、每行 1 MiB、每批 100 行/2 MiB。资源分别受既有原生 owner 的字体、图片、主题及歌词限额约束。新 input/stage/resource/ZIP 写入检查同一 512 MiB 剩余空间保留量；文档输入在实际 FD 长度预检并每 64 KiB 复核。预算是归档输入及工作窗口限制，完整库/队列没有 10,000 项或当前页容量限制。

## 2. 捕获与严格预览

所有捕获使用真实 Room 读事务和现有七个原生设置/资源 owner；不再创建 DataStore、字体 actor 或第二个播放器。独立 `rows.db` 在私有 UUID 代次索引全部逻辑行，主库表名/SQL、操作日志、授权列和数据文件不进入归档。硬关系、节点无环、列表/队列连续顺序、独立身份、资源实际使用与长度、历史统计分域总和均在封存前校验。

预览先复制本地输入，再验证 ZIP/schema/预算/摘要并封存逻辑 stage，原生资源重验类型、声明、大小、字体许可与既有配额。只核验选中逻辑域的文件引用；既有权限和完整内容/provider proof 来自本机注册路径，绝不来自备份 URI。单独恢复队列也通过当前已授权 TrackKey/registered route 重验，并保存只在私有 stage 中的 source/auth/alias/content fences。

预览绑定归档摘要、头、选中范围/模式、实际主逻辑摘要、原生设置的修订号/内容、stage 逻辑/权限 binding 摘要与单调到期时间。返回前关闭 stage；确认时用新调用者的 Job 重新 `openVerified`，校验精确 SQLite schema/`quick_check` 与行的索引键/类型/字节/顺序，不能继续持有已经结束的预览 Job。预览模型/范围复制并只读包装，不能通过可变集合改变已确认范围。

影响计数取实际主库/原生 catalog/已关联歌词数据，不用未计量的零占位。资源去重身份放在分页临时索引中，不把整库读入内存；缺文件/配置资源、被保留的冲突和完整输入分别显示。播放或其它状态在预览后改变即失效；第一次确认关闭服务可能更新 checkpoint/历史而使预览失效，必须重新预览、重新确认，不自动越过原影响确认。

## 3. 合并、替换与缺失引用

默认 `MERGE_KEEP_CURRENT` 保留现有稳定 TrackKey 的元数据/人工编辑和删除的歌单头；保旧成员顺序，追加新身份成员并拒绝/去重重复引用。同名不同 ID 的歌单仍是不同对象，不用名字合并身份。完整队列已有活动代次就保留，包括明确清空后的活动空队列；`REPLACE_SELECTED` 才替换勾选队列/列表/库的活动逻辑关系。完整原序、实际随机序、重复实例、当前位置及循环计数进入 Room；玩家窗口仍有界，恢复保持暂停。

库替换保留原件、旧 MVCC 行、导入/原件操作日志和系统授权，不能删除外部音频。未授权来源/位置只建立不可访问的待关联逻辑记录。缺引用的收藏/队列/历史/歌词及无文件关系的库曲目可建立隐藏稳定身份和无 URI/无 grant/无 fingerprint 的占位关系，以便实际重关联，不把占位当作可播放文件或恢复后的授权。

重关联预览只能选当前已授权本地库 TrackKey；源修订/auth epoch、alias/location/content 和元数据在实际 writer 中再查。可以选同一 TrackKey 的另一条已授权位置。相同完整内容摘要才是 `VERIFIED_MATCH`；不同/未知内容必须再次明确确认。真实单事务修关系/版本/活动队列引用，收藏、歌单、历史和统计稳定身份保留，关闭播放后执行，绝不自动播放。关系是权威；丢失 hint 投影应答不会重复改文件。

歌词资源导出从当前数据库关联取得实际本机 URI并重验；已管理副本走原有 copy owner，外部路径先拒绝未核对 provider，再查独立的实际 persisted read/对象/内容。首个外部支持路径是经过 authority、包名和 system flag 核对的 AOSP 本地文档 provider。恢复的歌词资源成为新的应用私有副本/身份，不恢复外部 grant；无资源时仅保留经过当前关联内容/编码核对的既有本地关联，否则报告缺失。

联网素材归档只保存内容摘要、字节、provenance 和资源 ID，不含原 asset UUID/URI；无资源则记录待恢复状态，不授予可读素材。联网 primary 的有效关系由原 URI/hash/metadata marker 精确生成 `primaryLinked`，Stage 核与同归档歌词行一致；恢复仅在两行实际应用且资源确认后重建 marker，不用内容去重替代原选择关系。MERGE 保留已确认/已清除的素材与当前手动歌词，REPLACE 同事务处理 FK 和递增墓碑。私有主事务 TEMP 使用 FILE 且读回核验，并为完整素材前像建立索引，不能将整份 provenance 集合放进未界定内存。

历史与统计分别用归档 SHA-256+当前 domain epoch 的 receipt 幂等。合并携带当前域的既有 receipt，清空历史不能复活旧历史 receipt 或清掉统计 receipt，反之亦然；替换使用新的域。真实 round ledger 不删，导入的关闭历史 round 用不接受真实服务回调的规则版本，旧回调不能重新计次。历史保留预算仍依 LP-03，不能与完整队列容量混淆。

## 4. 原生提交、崩溃与退出

取权次序：LP-10 原件/扫描/来源 mutation guard → 真实服务保存/checkpoint、暂停与确认原生释放 → 同一队列 preparation 租约 → backup exclusive ticket → 同一字体 editor lease。pending 原件操作拒绝新的恢复。Root 服务关闭 hook 在 pending 启动时不得等待自身 READY；三个恢复 hook 只初始化文件日志/guard，不在 backup ticket 前初始化主库。

私有 `restore.db` v2 是严格 schema、quick-check 和完整模型校验的持久 owner journal。v1→v2 在原独占锁和事务中先完整验证旧 SQL/schema/回执，只扩展资源 owner 约束并保留原数据，最后更新版本；既有损坏/缺表不能 `CREATE IF NOT EXISTS` 补成空库。无 run 的所有者/hint 行也要完整检查，孤立配置、资源和 pending 行拒绝 READY。无 pending 时只检查 journal 和数据库 owners，不要求所有无关偏好健康，保留各功能自己的显式恢复默认入口。源码迁移和回归定义仍须云端验证。

run 先绑定来包、before 代次、范围/模式和真实 before 主摘要；保留旧字体/图片/歌词/主题及其原生身份，再逐项记录资源计划/实际确认。偏好七步为 player、appearance、lyrics-display、lyrics-fonts、background、theme、DSP，每步绑定 before/forward、当前 attempt 和真实 CAS 应答。player 包含 six-field Help：完成/关闭版本、已完成步骤 `0..3`、tips、notice 版本/选择；阅读记录不构成总使用同意。在线、传输、播放和运行中的 timer 不随恢复启用。

主事务使用真正的库 mutex/Room preserved writer，内写后计算全部逻辑域及真实 epoch/revision 的目标摘要，持久记录 `MAIN_PREPARED` 后才离开实际 COMMIT。丢 COMMIT/投影应答时查真实 committed digest：已经提交就幂等完成 forward；仍为 before 就原生 CAS 回滚；未知状态保留 run 和原件、阻普通写入。Native step/资源 owner 的字段、类型、范围和精确身份必须全部解码，不能只数键或猜修订。

主库、配置与资源完整协调之前，所有 Room/设置观察者只保留旧可见值；读投影等待在 writer/mutex 之外，确认的 native value不能被迟到 observer 错误覆盖。物理 IO 同步 admitted 和 owner fence，caller 取消不能在真实 worker/FD 退出前释放 slot；失败报告 callback 或 dispatcher 抛异常也必须释放并回原故障。

资源 session 和来包/before stage 数据库全部关闭后才能按持久记录的目录 dev/inode 退休代次；同名新目录不是旧 owner 的对象。目录退休完成后原子清 run/steps/plans，失去 ack 可按同一 owner 重读重试。取消在 COMMIT 前回旧有效状态；COMMIT 后保持已经接受的状态并完成协调，不假报回滚。owner 退出先停止新调用者，忙时不关闭有效 journal；关闭保留 journal 字节和未完成恢复证据。

## 5. 文档发布与证据

`BackupDocuments` 只做格式 wrapper，和诊断共用同一个 `LocalArtifactDocuments`。目的地为 tree，新建随机 `.part`，完整写入/读回/校验后对同一实体重命名；取消只清本次精确原件，不认选择器返回的已有空文件为本次暂存。外部输出 cleanup/recovery 是独立域，失败用 `OUTPUT_CLEANUP_REQUIRED` 阻当前导出，不阻 Core READY。设备完整验收、实际 provider/FD/rename、低空间、撤权和丢 ack 场景仍须逐项实测。

本包定义了原生 Room/归档/设置 owner/文档与 UI 的回归源码，包括跨预览 caller、启动三个 hook、损坏/半包/逃逸/摘要、完整大队列/列表、真实歌词文件、cancel-before-worker、确认发布、COMMIT 前取消、独立历史/统计清空及重关联。文本/hash/XML/patch 检查不等于编译、测试执行、APK、设备或原 AC。最终合成须保留 Root Room 7/codec、six-field Help、DSP 新修及 LP-10 既有实现；本包的共享前像/hunk 不可 whole-file 覆盖较新代码。
