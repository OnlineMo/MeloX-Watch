# LP-06 DSD/APE 原 ReplayGain 接线

范围：F-058 的实际 DSD/APE 标签进入既有 PCM 音效链。新增 17 个回归定义；Kotlin/Android 编译、云端测试/lint、设备、性能及原 AC 全部 NOT_RUN。源码、定义和静态 API 核对不授验收信用，128 项范围与 256 个原 AC 保持。

## 当前输入、格式与处理链

LocalCodecDataSource 保留实际内容 URI，在同一授权 CodecInput 上完成格式识别与原标签读取。真实 APEv2 四字段保留原字面值，以 ApeReplayGainEntry 表达 APE 来源；实际 DSD ID3 使用固定 Id3Decoder 产生的 TXXX。前置 ID3 与独立尾部 APEv2 同时存在时两者都提取，保留各自来源及既有字段冲突行为。

CodecPcmResponseHeaders 只有当前尚未取消的 open、匹配的内容 URI 才能准入一份不可变 observation。ReplayGain 字面不进入可序列化的 header 字符串，普通 HTTP Map 及其 X-MeloX 字段无法构造该类型。关闭或取消后，旧 Map 即使 URI 相同也不能授新 period 标签；没有全局 URI 标签缓存或索引元数据回填。

固定 Media3 1.8.0 的 RoutedDataSource、StatsDataSource、ProgressiveMediaPeriod 和 BundledExtractorsAdapter 原样转发此 Map。BoundedExtractorsFactory 继续保留 delegate 的 MIME/URI 排序、配置、真实 underlying implementation、取消和输入准入，只对当前 WAVE 音轨的 Format 增补原 metadata。音频 MIME、PCM16、采样率及声道都须与该实际 decoder observation 一致；已有 metadata 保留，合并前仍检查条目预算。

Format.metadata 沿现有 DspAudioSink 进入待用格式。旧尾帧排完后才在 flush 采用新标签，ReplayGain/preamp 仍只在唯一 DSP 步骤应用一次。固定 WavExtractor 及 adapter 在同一 progressive period 的 seek 重用首个格式；该 period 的已准入原标签随同原 PCM，不改为另一个物理 open 的标签。新 period 使用新的真实 observation，extractor release 清观察引用。已交付的旧 Format 只供其原流排尾，不授新 open 权限。

ReplayGain 默认 OFF、曲目/专辑独立、缺所选字段贡献 0 dB、可信 peak 保护、preamp 与组合上限、EQ/左右/压限顺序均沿 LP-06 原契约。界面增加真实 APE 来源，混合来源使用通用的多格式说明；不把 APE 误报成 Vorbis。

## 有界读取及错误

DSF 的 tagEndExclusive 为当前已验证输入尾部；DFF 为实际 ID3 chunk 的 end，排除 padding 与相邻 chunk。在分配整 ID3 tag 前检查十字节 header、1 MiB 原 payload 和实际容器/资产 end；完整 payload 在固定 decoder 解析前经过现 Id3Admission 的 8192 文本字节、256 entries、深度 8、retained 与 nested-unsync copy 预算。只解码 TXX/TXXX 并保留四个 ReplayGain 原字段，最终补偿条目最多 64；没有放宽已有准入上限。

APE footer 起点不得穿过已验证 audioEnd 与 terminatingBytes 的合计边界。已有 footer-only APEv2 的 1 MiB、256 fields、8192 字节文本、UTF-8 严格解码、重复字段及取消检查保持；普通展示标签保持 4096 字符处理，ReplayGain 不截断原字面。数值解析仍限制原文本 96 字符、有限十进制、gain ±60 dB 和 peak (0, 64]，长或无效字面不能授增益。

标签读取在 Loader 的同一原输入上完成，始终恢复读取前的 raw.position，不修改 decoder 缓冲与帧状态。恢复失败使整个 open 失败，随后沿现有 close 所有权确认物理释放；失败 close 保留原占用。SOURCE_CHANGED、低电限制、取消及其他输入故障继续传播；损坏或超限标签以中性补偿及明确标签问题报告，不替换、转码或修改原音频。

本增量也给索引扫描的 DSD 标签补容器 end，保留原 APE footer 展示标签路径。索引标签仍没有当前播放流权限。CUE 当前是独立验证的原生 PCM WAV 窗口路径，未经过 DSD/APE decoder，本增量不宣称其 APE/DSD 或 WAV ReplayGain 支持，也不借逻辑曲名或邻近曲标签补增益。

## 固定 API 与验证边界

Media3 版本 1.8.0，source commit b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9，Apache-2.0；依赖及锁定来源未升级。仅只读官方 source jar ZIP 成员，未执行或展开研究源码。

| 来源 | 核对的接口 |
| --- | --- |
| [StatsDataSource](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/datasource/src/main/java/androidx/media3/datasource/StatsDataSource.java) | open 后观察及 getResponseHeaders 直接转发 |
| [ProgressiveMediaPeriod](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/ProgressiveMediaPeriod.java) | 当前 DataSource headers 进入 extractor init |
| [BundledExtractorsAdapter](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/BundledExtractorsAdapter.java) | 首次 createExtractors 的原 Map 与后续同 period 重用 |
| [Metadata](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/common/src/main/java/androidx/media3/common/Metadata.java) | Entry 普通接口、List 构造与保留既有 entries |
| [ForwardingTrackOutput](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/extractor/src/main/java/androidx/media3/extractor/ForwardingTrackOutput.java) | 仅增补 format，完整转发 sample/duration |
| [Id3Decoder](https://github.com/androidx/media/blob/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9/libraries/extractor/src/main/java/androidx/media3/extractor/metadata/id3/Id3Decoder.java) | 固定 FramePredicate、TXXX 原字面及同步有界 decode |

源码 jar hash 沿 LP-05 已记录 extractor/common/exoplayer 三份；另核对 media3-datasource-1.8.0-sources.jar，SHA256 为 5f482f1d2105ea5f191ef103f1cf38234ae222822401675155eec65487edc8d7。

正常定义从实际生成 APE/DSF/DFF 字节，经 PlaybackDataSources route、StatsDataSource、真实 BundledExtractorsAdapter/WAV 输出、DspAudioSink.configure 和真实 AudioProcessingPipeline 验证四字段、真实来源、独立曲目/专辑及单次增益。末端 AudioSink 是公开接口 fixture，不冒充 AudioTrack、MediaCodec 或声音验收。另定义旧 Map 撤销、URI 不匹配、普通字符串 Map、同 URI 新 period、DFF chunk 越界、超限、APE terminating 边界、完整长字面、取消与恢复失败。

后续云端先编译、运行新增与原 codec/metadata/DSP/route 回归并完成 lint，再将准确 APK 和证书绑定设备证据。首声、seek、速度/保调与音效组合、路由、PSS、功耗及圆屏/方屏读数继续遵循14/15/16；新增标签读取的 I/O 和原格式成本必须实测。没有本地 Gradle/JVM 运行、设备结果或发布批准。
