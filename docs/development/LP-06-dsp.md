# LP-06 音效处理建设记录

2026-11-22：作者包包含 F-057、F-058、F-077、F-123 的处理链、持久设置和界面接线。整合状态以13和实际提交为准；没有执行本地 Android/JVM 编译或测试。云端功能检查、设备音频、性能及原AC均未通过。F-076沿用唯一服务的实际路由媒体音量控制，仍需分别验收。

范围和验收继续遵循[21全量需求](../21-本地播放器全量需求整合.md)、[17本地契约](../17-本地音乐功能与实施计划.md)、[03架构](../03-技术架构与接口.md)及14/15/16，不减少128项或256个原AC。

## 处理链与所有权

[DSP源码](../../android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/playback/dsp/)进入既有服务、Media3 renderer/sink及路由所有者，没有增加播放器、AudioTrack或MediaSession。顺序为容器/分段与解码 → Sonic速度/音调 → ReplayGain/preamp → EQ → 左右增益 → 压限器 → 既有音频输出。系统媒体音量继续由系统流控制。

当前实现目标通路为Media3的PCM16，输出采样率8000–192000 Hz、1–8声道；独立左右控制只作用实际双声道。上述范围是代码门槛，不是未测设备的支持声明。整数重采样沿固定Media3 sink；浮点输出、AudioTrack playback params、编码直通、offload和tunneling不会绕过DSP。系统MediaPlayer显示此组软件音效不可用，保留请求设置供明确切回Media3使用。

输出配置携带的原Format.metadata在待用格式中保存，处理器等旧尾帧排完后才在flush采用新标签。同格式完整边界延续EQ历史；seek和重建清旧信号。sink票据拒绝迟到的旧实例状态；后端重建撤销临时预览，只读取磁盘保存值。

接口依据固定Media3 1.8.0、提交b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9，包括[AudioProcessorChain](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/common/src/main/java/androidx/media3/common/audio/AudioProcessorChain.java)、[DefaultAudioSink](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java)及[MediaCodecAudioRenderer](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/MediaCodecAudioRenderer.java)。来源源码只读，未执行研究项目。

## 参数与持久化

| 项目 | 初始实现契约 |
| --- | --- |
| EQ | 31/62/125/250/500/1000/2000/4000/8000/16000 Hz，±12 dB；按实际输出采样率提供低于0.45倍采样率的频段。RBJ peaking、每声道独立历史，参数改变才重算系数。 |
| 预设 | 平直、低音、人声三份静态曲线；最多32份自定义曲线。名称1–64个UTF-16码元、有效Unicode、无控制字符；trim/NFC/Locale.ROOT小写后不得重名。删除独立确认，当前曲线保留。 |
| ReplayGain | 默认OFF、曲目/专辑独立；不偷用另一模式。只识别Media3已提取的ID3 TXXX和Vorbis四个原字段，不新增文件读取或网络请求。 |
| 增益 | 标签有限值±60 dB，preamp±12 dB、默认0；组合限制在−60至+24 dB。原值与实际限制分开报告。所选标签缺失贡献0 dB。 |
| 峰值保护 | 默认开启；使用所选模式原峰值，增益最多1/peak。没有可信峰值时暂缓正增益并说明，可明确关闭保护。EQ之后新增峰值由末端压限器处理。 |
| 声道 | 左右各0–100%、5%步长，默认100%；单/多声道说明独立左右控制不可用。 |
| 压限器 | 默认关闭；阈值−12至0 dB、默认−1 dB；attack 100–5000 µs、默认500；release 20–1000 ms、默认100；lookahead 1–10 ms、默认5。 |
| 切换 | 增益和EQ参数用20 ms过渡；压限延迟按实际采样率计算。有界环缓冲和单调峰值队列，EOS排完全部尾帧。 |

软件设置使用独立no-backup DataStore schema1，不改UserPreferences4。记录只有schema、revision及有界类型payload；预设、选中曲线和全部音效参数一同CAS。取消回到当前已保存值，旧预览不能覆盖后来提交。无效记录只能用匹配原类型和值的恢复token明确重置；较新schema保持原文件。

DataStore互斥和备份admission覆盖真实actor完成；调用者取消不提前释放。LP-11使用同一恢复票据、完整类型配置和真实CAS，排除raw DataStore文件。初始化日志检查完成前阻写；恢复期间暂缓可见发布，失败不得掩盖已验证的配置。

## 资源与验证边界

每次最多处理2048帧；滤波器、延迟和峰值队列按上述输出范围固定有界。没有音频帧驱动的界面ticker，只有配置、格式和生命周期状态。默认全关闭路径保持PCM字节，命名配置只写本地私有设置；隐私材料不进入仓库或云端产物。

界面复用已有Foundation圆屏安全布局、48 dp触区、黑底及强调色。提供请求参数、实际能力、保存/取消、损坏恢复和明确预览，不用Material3替换既有视觉。圆屏遮罩及设备操作仍需可用的授权设备；源代码和静态布局不能代替实测。

当前26个测试定义覆盖已知PCM位一致性、左右分离、曲目/专辑补偿、峰值保护、单频增益、压限/EOS/seek、相同格式连续边界、旧sink/旧标签隔离，以及真实DataStore关闭重开、CAS、较新schema、精确恢复token和取消后真实admission。全部NOT_RUN；不把这些定义记作云端通过或256个原AC通过。

LP-05还在核对上游提取器的元数据**分配前**门槛。此处Metadata.length限制发生在提取之后，不能证明此前没有大数组分配。完成实际源级处理、云端检查后再冻结相应正常/异常路径。设备波形、速度/DSP组合、路由、恢复、首声、控制延迟、PSS和功耗仍未测，14的门槛保持。
