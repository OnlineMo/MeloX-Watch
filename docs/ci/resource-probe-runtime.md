# 完整 PR 测试的资源试探

普通 PR 的临时 Debug 签名使用 `CI_UNSIGNED_APK=false`。资源试探依据 Actions、
PR 事件和完整 `package-test-debug` 阶段启动，不依据签名标志。
三秒进程期限、完整命名空间及祖先检查、CPU/内存门槛、最多两份 512 MiB heap
和未知条件单 fork 的规则保持。

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
