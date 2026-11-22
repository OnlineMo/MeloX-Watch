# LP-06 EOS 输出缓冲所有权修复

2026-11-22：针对独审 DSP-I20-01 的窄增量，依赖完整 LP-06 音效源码包及其后续独审。只修改 DSP 的 EOS/输出所有权，不接入独立 App/Service 前像。设置取消提交的 DSP-I20-02 单独处理。

固定 Media3 1.8.0 的真实 `AudioProcessingPipeline.queueEndOfStream()` 可以在首个活动处理器的已返回输出尚未消费完时发出 EOS；`DefaultAudioSink` 也先发 EOS，再排空 AudioTrack 的回压缓冲。原处理器此时用 `replaceOutputBuffer` 排延迟尾帧，会复用并覆盖该对象。

现在 EOS 只记录结束信号。`getOutput()` 先保留已有输出及位置，消费完后才复用有界缓冲生成压限尾帧。`isEnded()` 同时检查 Base 状态、已返回输出和延迟帧；重复 EOS 不重排尾帧。连续相同格式只在真实输出及延迟帧全部排完时延续 EQ 历史；seek/hard flush 和未完成 EOS 的普通 flush 清掉旧信号。

核对了固定版本的 public/protected API：Base `queueEndOfStream` 为 final，只覆写 `onQueueEndOfStream`；`getOutput`/`isEnded` 非 final，并调用 super。源码 ZIP 仅只读，不执行上游项目。

新增七个使用真实 AudioProcessingPipeline 的测试定义，覆盖：唯一 DSP 的部分消费→EOS、上游重复 EOS/部分尾帧、短于前瞻的尾流与 isEnded、关闭压限的未处理字节、带 EQ/压限的同格式连续边界、seek hard flush 和未排完 EOS 的 flush。全为 NOT_RUN。没有本地 Android/JVM 编译、测试、lint；云端链接、APK、设备音频/性能及原 AC 均未执行。

处理帧数与缓冲上限沿 LP-06；此修复不增加播放器、AudioTrack、会话、网络请求或设置写入。代码和有界证明不能代替 T-41–43 / F-057、F-058、F-077、F-123 的真实设备验收。
