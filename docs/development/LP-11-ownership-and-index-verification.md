# LP-11 物理所有权与封存索引复核

本次源码修正处理三类边界：物理创建完成但可取消结果尚未交付；初始化或验证失败后的实际句柄关闭；预览后的私有派生索引缺失或替换。已有源码包保持原字节，新变化单独绑定前像。源码静审、字节/补丁证明、云编译/测试、设备验收与原 AC 分开；本次没有执行 Kotlin、JVM、Android 测试或构建，没有新 APK 或通过的原 AC。

## 1. 交付之前的实际 owner

`openBackupPhysicalOwner` 在物理 worker 内登记真实 `AutoCloseable`。工厂在取得实际 owner 后、后续可能失败的初始化/验证之前调用 `onOpened`，不是仅在最终返回时登记。Stage 保留原两参数 API，同时提供带登记回调的重载；journal 从拥有真实 FileChannel 的对象开始登记，lock 和 SQLite connection 在同一对象内逐步取得。未完成工厂不向调用者暴露可读状态。

helper 不通过 `abandoned` 回调转移或关闭这个引用。已 admitted 的物理动作实际结束之后，调用者复核取消并直接交付；清引用到返回之间没有新的 suspend 边界。失败或取消则在 `NonCancellable` 中等待真实物理退出、同步关闭同一 owner，完成之后调用者才离开自己的恢复/目录所有权。原故障保留，关闭故障作为 suppressed，不用关闭错误替换原故障。

若真实关闭仍失败，coordinator 以身份集合保留那个实际 owner，进入 `RECOVERY_REQUIRED`，阻止代次目录退休及普通写入。未交付和已经交付的 owner 使用同一集合；一次操作至多两份 stage 加应用 journal 三个实际对象，不把 before/incoming 的第二个失败覆盖掉。重试启动或 owner 退出逐个重试原对象，确认关闭后才移除；一个失败也继续尝试其它对象，不能用同名新句柄替代关闭证据。Journal 按 connection 关闭应答及真实 lock/channel 状态幂等关闭；stage 在 rollback 失败时仍尝试 connection 关闭，并保留两项故障。

预览返回前、确认 began=false 的 incoming 收尾、完整 before/incoming 收尾和应用 journal 退出均调用同一个 `closeOwner`；失败保留真实对象及原 fault/suppressed，成功从集合移除。集合未空也直接拒绝 READY fast-path/新调用者和所有代次退休。已交付 journal 的重试关闭成功后才清应用字段，下次启动重新打开其原始字节，不能继续使用已经关闭的 connection。

五处工厂接入同一 helper：启动 journal、导出 stage、预览 stage、确认 incoming stage、确认 before stage。导出的 nullable stage 在 try/finally 内创建，创建失败也经过完整收尾。新代次在实际目录身份捕获后、后续 fsync 前登记本次引用；交付取消和 register 的 COMMIT/fsync 丢应答会重读 journal owner/run，再按记录与实际 dev/inode 回收。无确认身份不按 UUID 名称猜删，有 active run 或有效 preview 的目录继续受原保护。

## 2. 封存数据和全部派生索引

`openVerified` 取得私有 stage 的 `BEGIN IMMEDIATE` 租约，持有到 close。这个租约保留封存后的已提交数据，后续 bindings/actual_ids 是本次确认的私有 scratch；结束 rollback 不删除主库、授权或恢复 journal 的权威证据。它不持有主 Room 的数据库锁，既有资源 provider 仍可读取已提交资源描述。

重开先验证真实 SQLite 列类型，避免 getLong/getBlob 的转换掩盖错误，然后按 100 行/2 MiB 页面完整读取每个逻辑类型。对每条逻辑行使用相同确定性投影构造临时 expected 表；以下六张表均以全部列双向 EXCEPT 比对，而不是只数总行或检查 inner join 返回的行：

| 表 | 必须复核的完整投影 |
| --- | --- |
| logical_ordinals | family、owner、ordinal、recordKey；覆盖 track-added、history、playlist、queue-original、queue-playback |
| logical_edges | fromKind、recordKey、field、toKind、targetKey、hard |
| logical_identities | family、owner、identity、recordKey |
| node_parents | child、parent |
| resource_usage | resourceId、kind、recordKey、field、encoding |
| resources | resourceId、kind、bytes、sha256、licenseRequired、sealed |

缺行、额外行、孤儿、不同 owner/family/key，以及同总数的替换均拒绝。逻辑总行和真实 payload 字节与 advertised bytes 再核，资源独立预算与 sealed 状态再核；未知类型或未选 scope 的行不能被分页循环忽略。重新构造 node marks 并执行完整关系、顺序和历史/统计校验；不信任旧 marks 作为无环证明。输入预算及临时页面保留既有空间预留，不新增曲库或队列条数上限。

## 3. 回归定义及实际状态

新增 9 个回归方法，均为 **NOT_RUN**：5 个 worker/caller 交付和实际 journal/stage 关闭定义，3 个完整 stage/索引/SQLite 类型与状态定义，1 个真实 coordinator 拒绝截短歌单的定义。原 39 个方法保持，已有 payload-key/ordinal 两种替换的断言提前到 openVerified 拒绝时点。合成数据只用测试目录、模拟名称和文档输入；没有设备地址、真实曲库、账号或外部上传。

这些定义覆盖物理 worker 已完成而 continuation 未交付时的取消、关闭完成先于真实目录退休/barrier 释放、同目录原生 journal 锁重开、工厂已登记但未返回且两次关闭失败、正常交付后幂等关闭；索引矩阵覆盖缺行、错 owner、孤儿/额外行、两种队列序、真实 SQLite 列类型、资源 sealed 与实际字节计数。确认反例保留原歌单：输入 ZIP、逻辑行及 quick_check 未变，仅删去第二个 ordinal，不得把两项输入恢复成一项。

另加 2 个定义，验证已成功交付的真实 stage 在关闭失败后保留、解除原生数据库锁后才能回收，以及 before/incoming 两个相等 wrapper 值仍按真实对象身份各自保留/重试。两项都要求重试后的原生 BEGIN IMMEDIATE 正常完成，不能把环境不支持当作关闭正常路径通过。

当前总计 50 个 Core 回归方法定义；全部仍未编译或执行。云端必须使用当前源码进行 compile/test/lint，并持续监控运行时间，随后绑定精确云 APK 完成设备验收。没有设备证据的项目继续记未验证，源码修正不完成 LP-11 或 F-068/F-069 原 AC。
