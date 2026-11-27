# 备份大队列的分页与解析后继

根据 LP-11 的完整列表、100 行/2 MiB 工作窗口和独立完整性边界，备份暂存的初页与续页采用不同 SQL。续页直接限定主键或顺序键的下界，保留原 BINARY 顺序、返回列、字节预算、取消检查及严格验证；已有数据库与归档 schema 保持原版本。Room 捕获采用相同的初页/续页区分，五次独立的主状态摘要仍分别读取各自完整快照。

CUE 数字解析复用固定语法 `0|[1-9][0-9]*` 的预编译正则，继续拒绝前导零、负数、非整数和溢出。大队列回归的两份固定扫描引用只编码一次，10,003 个重复实例、100 行种子批次、60 秒上限和全部关系、顺序、位置、循环及授权断言继续保留。该案例新增固定阶段名和单调耗时日志，日志不包含曲目、来源、URI、地址或内容。

新增 `BackupStagePaginationTest` 使用真实私有 SQLite stage，覆盖 208 个前缀键、非现存游标、初页/续页、100 行边界、原序与反向播放序，以及同一验证租约下的待关联和 binding 索引。素材前像分页仍由既有 Metadata 组验证。

触发证据为 [PR #46 的运行 45934251951](https://github.com/OnlineMo/MeloX-Watch/actions/runs/45934251951)/attempt 1：source `df074b405b5e27319ffbe3e4d3a72613a21c7adc`、build `82bfa4445d8fa440bcfd682c41d434e985de58fa`。358 份正式 JUnit 记录手表 3,143 项中 1 项失败、手机 134 项全部成功；唯一失败是上述 CUE 备份往返案例的 `TestTimedOutException`，记录耗时 60.098 秒。相同代码树此前该案例耗时 38.339 秒，差异不足以证明具体等待点或宿主原因。

本轮总耗时 984 秒、compile job 928 秒；实际测试进程 889.203 秒自然 exit 1，未触发整体进程期限，已回收全部自有进程。完整捕获日志与转发丢弃字节分别记录；lint、组装、封存与签名未完成，没有新 APK。原 reports artifact `15170691210` 的 ZIP 为 1,300,661 字节、798 个成员，SHA-256 为 `c8f28b7a3c543dcdfe487c823a08fb1dfea2b4ab7181447c9a1e58fba8b65a95`；原件仅保留本地。

首轮后继 [run45945404259](https://github.com/OnlineMo/MeloX-Watch/actions/runs/45945404259)/attempt 1 绑定 source `2bdf2caa09496d03c7bb7feaa150bd0f228f484a`、build `106a246f8f6353430408f8b72aa3aeaa06ef1cc0`，总耗时 924 秒。359 份正式 JUnit 中手表 3,144 项有 1 项失败、手机 134 项全部成功；大队列 CUE 往返与新增分页定义正式成功，CUE 阶段总计 58,913 毫秒，其中预览 19,799 毫秒、确认恢复 21,819 毫秒。这些数值不证明检索计划、超时原因或性能收益。唯一失败是 `ThemePreviewImageNativeTest.cancellingAfterDecodeBeforeResultDeliveryReturnsItsRealBitmapSlot` 在取消前的 `assertFalse(caller.isCompleted)`，尚未执行取消及后续资源断言。该运行的 lint、组装和签名未执行。原 reports artifact `15175400497` 为 1,290,207 字节/800 成员，ZIP SHA-256 为 `32f47b897ac23937942c786f44ba365c70ba6617bc63ef68235b12367d65cbd4`。

根据测试调度契约，worker 完成通知只能证明结果已提交，首次 scheduler drain 仍可能消费快速返回的结果；这是一条静态确认的合法竞争，本次失败的具体线程时序没有实测。测试后继在首次 drain 返回后才放行真实解码，并核对 bitmap 占用从零到正数、取消交付后回到零。门等待超时或中断仍在 `finally` 执行已准入的真实 worker，完成通知等待 worker 与结果提交完整返回；原取消断言、两份有效 lease、第三份 BUSY 和 30/15/5 秒期限保留，生产主题实现没有改动。

该测试后继在 [run45957561724](https://github.com/OnlineMo/MeloX-Watch/actions/runs/45957561724)/attempt 1 正式成功，0.032 秒；该轮 source `5a1e8a5c93efdecd4a9662084f5acb92dd442a7f`、build `9ceb51f5892b73660d3e45318b492b8f9fcac93f`，整轮 888 秒、compile job 864 秒。359 份正式 JUnit 中手表 3144 项有 1 项失败、手机 134 项全部成功；新分页定义正式成功，唯一失败又是大队列 CUE 往返的自身期限，60.087 秒。捕获 7400 毫秒、预览 29685 毫秒、确认恢复 17311 毫秒，最后重关联阶段尚未完成；这些阶段观察仍不证明 SQL 计划或具体等待点。原 reports artifact `15179634182` 为 1289783 字节/800 成员，ZIP SHA-256 为 `767e69832cf5dd9c5c71403746752315f132250e0214cace283d6a0c0aac2c16`。lint、组装与签名未完成，没有新 APK。

根据同一严格提示和有界工作窗口合同，新的 CUE 编码/解码缓存各自只保存最多 8 项精确不可变值，按文本 UTF-16 表示和模型保守余量计费，单缓存最多 128 KiB；这是源码计费边界，实际 heap 尚未测量。缓存不保存解析失败、不以 hash 相同代替完整 key 相等，命中仍拒绝 `portableOnly` 读取原生绑定。当前 source/auth/grant/alias/content 和原生段验证继续逐次执行，缓存不会授予文件读取能力。不可变身份三元组的曲目键采用 publication lazy，避免同一解析对象被重复队列引用时反复生成摘要。

重关联引用页直接惰性生成最多 100 个输入，在同一页内复用两条 SQL 并 reset/rebind 全部参数。每行仍先检查取消、原 trackId 约束与派生输入预算，再读取真实旧 missing 标记/提示；冲突提示先拒绝，完全无变化才省略 UPSERT。新增缺失标记保留 MAX 合并，提示保留 COALESCE 语义，不以去重减少累计输入预算或完整关系。现存单项调用委托相同的有界路径。

新增 4 个 codec 回归定义覆盖可移植守卫、hash 碰撞、有限窗口轮换及并发；新增 1 个真实 SQLite 定义覆盖重复引用、缺失标记、冲突提示和失败后保全。原 10003 实例、完整队列/关系断言与 60 秒期限没有改动。源码独立静审收据为 `8252004b634ab42127bde7aa62696c9b6af95f20c6030e61e24bccd78cd0cfd4`；新字节尚未云端运行，性能改善不作已测结论。实际 SQL 检索计划、F-068/F-069、128 项功能和 256 个原验收案例继续保持未验收；readiness 全部为 false，candidate 为空。
