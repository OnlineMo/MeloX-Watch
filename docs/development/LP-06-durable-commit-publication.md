# LP-06 取消后的真实提交发布

2026-11-22：独审 DSP-I20-02 的独立窄增量。顺序为完整 LP-06 作者包 → EOS 输出所有权 successor → 本包；本包不改 EOS 处理器。三个生产前像来自完整作者包，Root 与 LP-11 phone storage 原生 CAS 接线合成，不能整文件覆盖后续工作。

原实现已经在真实 DataStore actor 完成前保持 mutex 与备份 admission，但调用者取消后，成功 updateData 的设置会在应答前被丢弃发布。没有额外磁盘观察器自动修正这件事，服务和下一次后端重建仍用旧 saved。

现有 completedUpdate 在相同 NonCancellable 实际完成所有权中，收到真实成功的 Preferences 后先解码并发布完整快照，再执行原来的 caller.ensureActive。取消继续传播且没有 Saved 命令应答；未成功的更新不发布提案，也不自动重试。读和 mutate 不在应答返回后重复发布旧快照。LP-11 exclusive 时发布仍走同一 barrier 的暂缓通知，直到实际代次退出或恢复为一致状态。

服务初始化的 IO 应答可能晚于另一提交，服务读取当前 store.state 的已发布值。页面也只采用当前可见代次；迟到 Saved 应答不会把新的 backup CAS 配置改回旧值，也不会显示旧配置保存成功。BUSY 错误保留，不提前发布 exclusive 内未完成的原生记录。未增加 Application 构造、常驻磁盘 observer、后台重试或播放命令。

新增六个真实 DataStore 文件和实际 Service/UI 所有者的测试定义：取消成功提交后不回读而同步到服务/下个 sink；取消失败的提案不发布且文件不变；exclusive 提交在真实退出前不发布；迟到服务读取不盖过后续 backup CAS；迟到保存应答不盖过新页面代次；离页取消的真实提交保留且无成功提示或自动重试。测试只对 actor/应答设置受控时序，不用内存存储代替持久化。全为 NOT_RUN；单个 DSP 原子提交的 exclusive 测试不声明完整 LP-11 多存储事务恢复通过。

没有执行本地 Android/JVM 编译、测试或 lint，也没有云端 dispatch。云链接、APK、设备音频/性能、LP-11 组合恢复和原 AC 均未执行。功能、云验证、设备验收与发布批准仍分别记录。
