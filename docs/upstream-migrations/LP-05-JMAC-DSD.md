# LP-05 APE 与 DSD 解码来源及验证记录

本增量为 F-061/F-062 增加真正的 APE 解码以及 DSF/DFF 未压缩 DSD→PCM 路径。源码、云端验证、设备验收和发布批准分别记录。这里的测试均是待云端运行的定义；没有新增合格 APK、设备支持结论或原 AC 通过记录。完整要求仍以 [21](../21-本地播放器全量需求整合.md)、[17](../17-本地音乐功能与实施计划.md)、[03](../03-技术架构与接口.md) 和 [14](../14-性能优先与资源预算.md) 为准。

## 固定来源与许可

APE 窄复用来自 [JMAC](https://github.com/umjammer/jmac/tree/0cedfd83a3005c1c4e4dd5fa7cb3796c79b765f3)，完整提交为 `0cedfd83a3005c1c4e4dd5fa7cb3796c79b765f3`，该提交版本标识为 `1.7.6v`。来源快照已只读核验为 clean detached HEAD；未构建或执行其 Maven、Java、native 或测试项目。固定引用提案追加到 `.ci/upstream-lock.json`，原有五个来源不变。整合者须在 `research/JMAC` 保持同一 clean detached SHA，不能使用浮动分支或把研究仓库变成构建输入。

源码声明为 GPL-2.0-or-later。保留直接复用及衍生源码的原许可、日期、作者声明，并标明本次修改；原 `gpl.txt` 原字节放在 `android/app/src/main/assets/licenses/jmac-GPL-2.0-or-later.txt`。完整应用必须选用兼容的 GPL 发行条件，并交付完整对应源，包含修改、接口及控制编译和安装的脚本。首次向他人提供 APK，包括非公开发包前，就须落实此交付条件。整体应用许可及对应源交付仍由整合与发行任务落实；本来源记录和许可资产不构成它们已经完成的证据。

生产衍生代码的来源前缀为 `src/main/java/davaguine/jmac/`，目标前缀为 `android/app/src/main/java/io/github/onlinemo/meloxwatch/data/codec/jmac/`：

| 固定来源文件 | 目标文件及保留范围 |
| --- | --- |
| `decoder/UnBitArray.java`、`UnBitArrayBase.java`、`UnBitArrayState.java`、`RangeCoderStructDecompress.java`、`DecodeValueMethod.java` | 同名文件；仅 3990 arithmetic range 解码、实际有效字节检查和固定 bit buffer |
| `prediction/PredictorDecompress3950toCurrent.java`、`IPredictorDecompress.java`、`NNFilter.java`、`NNFilter16.java` | 同名文件；Fast/Normal predictor，保留格式要求的 32 位 wrap 运算 |
| `tools/ByteArrayReader.java`、`File.java`、`JMACException.java` | 同名文件；input-only bridge、固定 refill 和调用方错误 |
| `tools/Crc32.java`、`RollBufferFastInt.java`、`RollBufferShort.java` | 同名文件；PCM16 CRC 和实际使用的固定 history/window |
| `decoder/APEDecompress.java`、`tools/Prepare.java` | `decoder/JmacDecoder.java`；现代 frame、seek、stereo reconstruction 和 CRC 行为的窄衍生 |
| `encoder/BitArray.java`、`APECompressCreate.java`；上述 predictor 初始状态和 PCM reconstruction | 仅测试 `CodecFixtures.kt` 的微型 range writer、合法容器/MD5 顺序及已知初始 residual；生产没有 encoder |

主动修改包括：先验证容器维度再分配；只保留版本 3990、Fast/Normal、PCM16；取消和 elapsed work 检查；验证完整 CRC frame 后才复制 PCM；seek 重建所需 frame；refill 清零 padding 并核对实际有效 bit；去除 legacy/native 工厂、文件写入、URL、JavaSound/Tritonus、System.Logger 及未引用辅助方法。未引入 JMAC jar、so、上游构建脚本、外部音频录音或上游二进制测试样本。原项目历史 PC 性能和维护日期不能作为手表耗电、温升或吞吐证据。

DSD extractor 和 FIR 是本项目的实现，未复用 JustDSD 代码。格式依据是 [Sony DSF specification](https://dsd-guide.com/sites/default/files/white-papers/DSFFileFormatSpec_E.pdf) 和 [DSDIFF 1.5 specification](https://dsd-guide.com/sites/default/files/white-papers/DSDIFF_1.5_Spec.pdf)。仅只读查阅格式资料，PDF 不进入应用构建输入。实际输出是 PCM16/88.2kHz；保留输入 DSD 参数，不声明原生 DSD、DoP 或位完美。

Media3 接口依据固定 `1.8.0` 的官方 Google Maven source jars 及提交 `b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9`。这些 source jars 只供 API/源码审阅，没有作为新增运行依赖或本地执行输入。

## 同一播放器中的输入路径

`PlaybackDataSources` 的授权 `content:` 分支接入服务持有的 `LocalPcmDataSources`。按实际签名选择 codec，保留原 URI；扩展名、索引字段及 provider MIME 均不能使格式被认定可解码。普通格式继续交给既有 Media3 路径，在线来源沿既有受控 transport；此 codec 层不发起网络、账号或 companion 请求。

`DsdContainer` 校验 DSF/DFF 结构和样本范围，`DsdPcmDecoder` 将 bitstream 低通后输出 PCM。1025 tap centered FIR 使用 28kHz cutoff 和四项 Blackman-Harris window，32:1 decimation；512 bit 前后有限窗口在 seek 时重建。按照声明 bit count 处理不足一字节的末样本，保留最终 PCM frame，边界以零扩展补滤波窗口。DSF channel block、LSB/MSB 时间顺序和 DFF byte interleave 分别映射，DSF 未用 block/bit 与 DFF odd chunk padding 均校验为零。

`ApeContainer` 在分配 seek table/frame 前验证现代 MAC descriptor、header、seek、audio extent 和参数。`ApePcmDecoder` 调用本包的 Java range decoder、predictor 和 CRC，包含真实非零 residual 路径。一个 CRC frame 在内部完整验证后才输出；随机 seek 从原 seek point 重建该 frame 的 predictor 状态后返回目标 block。损坏、截断、超限和取消不会成为成功 EOF 或“已支持”标志。

`PcmWaveStream` 将 header 与有界 PCM window 作为虚拟 RIFF/RF64 DataSource 交给固定 Media3 的 `WavExtractor`。支持 64 位长度和非对齐 byte seek，不把整首转换驻内存，也不写转换文件。原文件和完整 durable queue 均保留。服务已有唯一 ExoPlayer、session、backend 切换、focus/noisy 和暂停恢复所有权，本增量不增加第二播放器。系统 MediaPlayer 的自定义 codec 路径不在本增量中实现；其请求/实际后端和回退仍须按 LP-05 另验。

元数据路径必须先真的解码至少一小段 PCM 才返回自定义 codec 参数。`LocalAudioMetadata.codecParameters` 保存输入和输出的实际参数；JSON/outcome 往返有源码定义。主库目前的 track 投影尚无参数列，耐久列及 6→7 迁移须随整合任务完成并获得一次真实云端 KSP/schema 验证；本增量没有伪造历史 schema。DSF/DFF 的尾部 ID3 与 APEv2 只作有界标签读取。APEv2 保留 literal ReplayGain 字段，但虚拟 WAV 当前未把原容器标签附到 `Format.metadata`；LP-06 必须把缺失标签作为缺失处理，不能借前后曲或猜增益。

## 当前准入范围与静态上界

下表是实现的准入条件及分配上界，未表示设备支持或实测性能。限制 codec 输入和工作窗口不构成曲库、歌单或完整队列数量上限。

| 路径 | 准入与有界工作 |
| --- | --- |
| DSF | version 1、format 0；DSD64 2822400Hz；mono/stereo，4096 byte channel block；sample count 与数据长度一致，合法 LSB/MSB bit order |
| DFF | FVER 1.4/1.5，uncompressed `DSD `；DSD64 mono/stereo；stereo `SLFT`/`SRGT`，合法 PROP/FS/CHNL/CMPR 与 chunk 范围；DST 明确拒绝 |
| APE | MAC 3990，Fast 1000/Normal 2000，PCM16 mono/stereo；8000/11025/16000/22050/32000/44100/48000/88200/96000Hz；输入 ≤Int.MAX_VALUE byte，total block ≤Int.MAX_VALUE，frame ≤294912 block，seek entries ≤16384 |
| 容器读取 | 每次原始 read ≤16KiB；固定块分配 ≤64KiB；DSD 顶层/PROP 各 ≤128 chunk；所有 extent 使用受检 Long 加乘 |
| DSD 工作区 | 128×256 Float lookup、1025 Double coefficients 为共享固定表；每个 decoder 两个最多 8192 byte slab；每次输出 ≤512 frame，每 32 frame 检查取消/work，read 的 elapsed work 候选上界 2 秒 |
| APE 工作区 | 每 frame 最大 1179648 byte PCM；seek table ≤64KiB及其副本；16KiB refill 和固定 predictor histories；每次输出 ≤512 frame，每 256 block 检查取消/work，含 frame I/O 的 elapsed work 候选上界 2 秒 |
| 来源和功耗 | 服务 registry 最多 3 个输入，每个输入同一时刻只开一个 raw source；已知长度在 reopen 后必须仍可核对；PowerManager power-save 在构造 codec 前及读取期间检查，读取期间最多每秒一次，无隐藏轮询 |
| 标签 | APEv2 ≤1MiB、≤256 item、命中 value ≤8192 byte、保留 text ≤4096 UTF-16 单元且不切断 surrogate；artist split 最多 128；原标签缺失或坏 auxiliary tag 不伪造 gain |

已开始且不合作的 content provider I/O 仍受 Android 底层行为限制：取消 ticket 会阻止后续处理，但这里没有强制中断 provider 的证明。owner close 在服务线程只取消，实际 Loader 持有 physical close；close 失败保留 slot 和显式失败状态。2 秒 elapsed 检查不证明卡住的 provider 可在 2 秒内结束。Power-save 查询也不等于完成真实低电、温控、耗电或长时熄屏验收。

本增量的 DSD/APE 容器门槛不保护普通 MP3、FLAC/Vorbis/Ogg 或 MP4 在 Media3 中分配元数据；提取后的条目数量限制不能冒充 before-allocation 防护。相关固定源码路径须由独立资源审阅落实。

## 待运行验证与正常验收

31 个测试定义位于 `android/app/src/test/kotlin/io/github/onlinemo/meloxwatch/data/codec/`，全部为 `NOT_RUN`：

| 测试类 | 定义数及目的 |
| --- | --- |
| `DsdPcmDecoderTest` | 9：合法 DSF LSB/MSB、DFF 非零 PCM 等价；channel/order、FIR passband/ultrasonic rejection；seek、preroll、尾帧、partial bit、DST/rate/bad block 和取消 |
| `ApePcmDecoderTest` | 8：Fast/Normal 真正非零 mono/stereo entropy/predictor；CRC 未通过前不输出；frame seek/EOF、正常 frame 上界、坏 CRC/header/seek、版本/等级拒绝、bounded ID3 prefix 和取消 |
| `LocalPcmDataSourcesTest` | 8：固定 WavExtractor sniff、原 URI/实际 MIME；unaligned/reopen/EOF；已知长度改变或丢失、unknown length、power-save/cancel、failed physical close、普通 raw 来源 |
| `PcmWaveLargeFileTest` | 2：RF64/Long seek >4GiB 与有界 reads；DSF/DFF padding。稀疏输入是 synthetic fixture，不是实际巨型文件验收 |
| `LocalCodecMetadataTest` | 4：真实解码参数、JSON 旧字段兼容/坏类型、坏 APE 不入库、APEv2 distinct artists |

样本源码自行生成 DSF/DFF sigma-delta 非零音调、合法微型 APE range frames、silence frame 和 APEv2 标签；没有外部录音版权或二进制 codec 依赖。测试 fixture 的编码源码也未在本地执行，其正确性仍须由云端测试确认。单块非零向量和 synthetic 长度不能替代真实完整正常音频及设备验收。

下一次授权云验须记录同一提交的 source/build SHA、run/attempt、JUnit/lint、dependency/schema 输入以及原 artifact/hash；下载并使用完全相同的云端 APK。F-061/F-062 的正常矩阵至少包括 DSF/DFF DSD64 stereo、APE 3990 Fast/Normal PCM16 stereo，以及采样率/声道/位序、开始/中间/尾部 seek、暂停恢复、唯一后端切换、焦点/noisy、熄屏和取消。异常矩阵含损坏/截断/不支持版本与等级/DST/高 DSD rate、未知或变化长度、撤权及低电条件。记录实际 API/ABI/输出路由与资源；未测设备不宣告支持，功能、cloud、device、原 AC 和发布 approval 均保持各自未完成状态。
