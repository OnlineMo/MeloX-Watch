# 完整 Debug 测试的资源试探

普通 PR 与 main/contribution push 在同一无秘密 compile runner 内执行完整
`package-test-debug`；两种入口采用相同的三秒 CPU、内存和 cgroup 试探。
试探依据 Actions、事件、精确稳定分支和测试阶段启动，不依据 `CI_UNSIGNED_APK`。
普通 PR 使用临时 Debug 签名；稳定分支编译未签名 APK，稳定证书只在后续独立 runner 使用。
其它事件、候选准备、依赖采集、lint/build 与本地调用不启动试探。

三秒进程期限、完整命名空间及祖先检查、CPU/内存门槛、最多两份 512 MiB Watch heap
和未知条件单 fork 的规则保持。Phone 保持单 fork，原 C1 参数与全部测试范围不变。
只有完整测得有效 CPU 至少 2、有效总内存至少 6 GiB、可用内存至少 3 GiB，
且 worker limit 至少 2，才允许 Watch 使用最多两个 fork；实际未知或不完整时仍为一个。

[run46080624321](https://github.com/OnlineMo/MeloX-Watch/actions/runs/46080624321)/attempt 1
实际启动了试探，结果为 `incomplete/read_unavailable`，因此保持单 fork。
该轮未取得完整有效 CPU、内存和 cgroup 限制，不能从物理内存或逻辑 CPU 数推断
已经满足并行条件。

固定 Linux 源码
[mm/memcontrol.c](https://github.com/torvalds/linux/blob/f4585067150b85e33f60876149a6e742928da75e/mm/memcontrol.c)
中，`memory.current` 与 `memory.max` 均带 `CFTYPE_NOT_ON_ROOT`。
旧试探错误要求根节点的 current 计数，正常 v2 根节点缺少该接口时会报告
`read_unavailable`；本轮日志没有输出原内核路径，不认定已经定位其实际失败节点。

后继仅允许完整可见层级的根节点省略这两个接口；子节点、祖先限制、CPU 集合、
命名空间及迁移检查保持。测试环境同步真实接口形状，补根节点自身、隐藏命名空间、
父级低内存/子级低 CPU 与子节点计数缺失的回归，28 项 Python 测试通过。
这些均为合成环境政策检查，没有采集本地硬件或编译 Android。

真实并行准入、进程用量、完整测试耗时和总预算效果须由后继云端运行证明。
原 1200/240/300 秒期限和签名隔离保持；完整 Android、精确双 APK、设备、性能
与原始 AC 验收独立记录。

main 的 [run46154313125](https://github.com/OnlineMo/MeloX-Watch/actions/runs/46154313125)/attempt 2
记录 Watch 试探为 `not-requested`、单 fork、512 MiB heap，CPU、有效内存及 cgroup
命名空间字段均未测得。本轮两个已启动 C1 worker 分别属于 Phone 和 Watch。
372 份正式 JUnit 的 3277 项 Watch 与 134 项 Phone 测试全部通过，3411 个身份、结果及
完整 start/end 对一致。测试进程自然 exit 0，用时 973.862 秒；lint/build 开始时总预算
仅剩 218.133 秒，随后由 process 原件的 `overall build deadline` 终止。

后继仅扩展同一试探的稳定 push 准入；这些历史时长与单点线程栈不构成提速或完整性能归因。
是否实际准入两个 Watch fork、进程用量、OOM、完整测试与总预算效果仍须后继云端运行验证。
lint/assemble、精确双 APK、设备、原始 AC 与发布验收均未因此通过。
