# LP-05 Media3 元数据分配准入

状态：源码 wrapper/factory 与 35 个测试定义；Kotlin/JVM/Android 编译、测试、lint、云端 APK、设备和原 AC 全部 `NOT_RUN`。本文不把准入上限或测试定义当作设备支持、PSS、耗电和完整格式验收。

Media3 的 extractor 会在 app 收到 `Metadata` 以前创建 tag、block、packet、atom 和内部表。提取后的 `Metadata.length` 检查不能保护这些分配。此增量在原 `DefaultMediaSourceFactory` 中注入 `BoundedExtractorsFactory`，继续使用同一 DataSource、队列、backend 与 session，没有新增播放器、网络客户端、线程、账号依赖或原文件写入。

`ForwardingExtractorInput` 同时覆盖有/无 `allowEndOfInput` 的 read/peek/advance overload。逻辑 peek 窗口在 `DefaultExtractorInput.ensureSpaceForPeek` 之前限制；header 返回给 extractor 前检查其长度，完整 payload 返回给内部解析器前检查嵌套计数。EOF/false 返回也检查取消。阻塞提供者是否能硬中断仍须真实设备证据。

工厂保留 delegate 的 MIME/URI 顺序、subtitle/config 返回值及 `getUnderlyingImplementation`；后者保留 `BundledExtractorsAdapter` 对 MP3 `disableSeeking` 的识别。丢弃的未审读 extractor 由创建方释放。当前接入采用默认 bundled flags；SEF、motion-photo、视频、加密、外部扩展 extractor 的独立语法没有由此获得准入结论。

## 准入路径与错误

下列是已写入源码的路径，均待云端与设备运行；正常路径不会统一回报 unsupported。

| 路径 | 正常准入与保持的行为 | 拒绝的面 |
| --- | --- | --- |
| MP3 / ID3 | 实际 MPEG frame；ID3 v2/v3/v4、extended header、v4 footer、有限 tag/frame unsync；文本/URL/comment/owner/picture 字段、CHAP/CTOC、MLLT | 非 syncsafe v4 兼容 hack、超限 tag/frame/values/深度/MLLT 引用、越过实际 tag 的字段 |
| Native FLAC | STREAMINFO、Vorbis comments、PICTURE、seek table；未知/padding block 可按跨度跳过，原 audio frame 保留 | selected block/累计跨度/数量、内部 comment/picture/seek dimensions 超限或越界 |
| Ogg | Vorbis、Opus、FLAC；在 lacing 返回前累计跨页 packet；评论/图片、Vorbis book 循环和 lookup 位范围按实际 payload 检查 | packet/无完成 packet 页窗口/headers 超限，内部字段越界；其他 bitstream 的 header 未准入 |
| WAV / RF64 | bounded fmt/ds64；PCM、float、IMA ADPCM 与固定 reader 支持的 extensible 路径；保留原 PCM sample bytes | fmt/ds64 尺寸超限，rate/channel/bits/block 参数不适用 |
| MP4 / fMP4 | 音频 track；AAC、ALAC、FLAC、Opus、MP3 sample-description 的窄配置路径；原样输出 sample；解析 leaf/keys/ilst/udta/table/edit/trun/ESDS 前准入 | 未审 codec/config、QT sound-description v2、PCM MP4、crypto/PIFF、越界 atom/child/count；重复 sample-size table 或 edit list |
| ADTS AAC | 7/9-byte header 形状、单字节 transport chunks、夹带 ID3 在完整 metadata sample 提交前检查 | frame/header 参数不适用、超限或损坏 ID3；9-byte 形状不构成新增 CRC 验证结论 |

`MALFORMED` 保留 cause 映射为现有 `SOURCE_DAMAGED`；`RESOURCE_LIMIT` 和 `UNSUPPORTED_SURFACE` 保留 cause 映射为 `SOURCE_UNSUPPORTED`。取消保持 `InterruptedIOException`。未准入容器由既有 unsupported-container 路径处理；这些错误结果不能单独完成原 AC 的正常用例。

MP4/fMP4 的 atom graph 按当前 moov/moof 重置；fragment 表按各 track 已保留的峰值累计，避免把流式历史包数当作产品上限。edit expansion 同时限制每 track 与 movie 总数；重复表不能用末份小计数放松 Media3 实际读取的首份表。

Ogg 使用固定 `OggPacket` page scratch identity；`DefaultOggSeeker` 的另一份 scratch 不修改 packet 状态。固定 `StreamReader.seek(0)` 在 seek map 输出前重读 header，输出后保留 codec 并跳过 header。透明 `ExtractorOutput` 转发记录已完成的 map 阶段；前者重建准入状态，后者仅清 lacing 而保留 header/budget，正常零点重播保持可读。

## 源码上限

| 对象 | 当前上限 |
| --- | --- |
| ID3 tag、selected FLAC block、单 Ogg packet、parsed MP4 leaf | 1 MiB 原始 payload/leaf |
| Initial ID3/FLAC/Ogg retained 原始 metadata | 4 MiB；ID3 nested-unsync preflight copies 另按单次验证 4 MiB 约束 |
| 单 moov/moof retained leaf graph | 4 MiB |
| 逻辑 peek 跨度 | 2 MiB；固定 `DefaultExtractorInput` resize 仍另有 64–512 KiB 容量余量 |
| 单文本字节、metadata entries、嵌套深度 | 8192 / 256 / 8 |
| FLAC/header/no-packet block、FLAC metadata span、atom 数、track 数 | 128 / 32 MiB / 8192 / 8 |
| MP4 sample 表及 edit expansion、seek point、codec config、audio sample/frame | 262144 / 16384 / 256 KiB / 1 MiB |
| WAV fmt/ds64、ADTS frame | 64 KiB / 8191 byte |
| Vorbis 单 book entries、所有 book entries | 65536 / 262144；lookup 位数不得越过实际 packet |

这些是输入结构和数组维度边界，各 parser 仍有对象开销、数组增长余量和旧/新对象交接。它们不是统一堆或 PSS 上限，也不是性能测量；相关预算与实测按 [14](../14-性能优先与资源预算.md) 分别记录。sample table 边界是单媒体输入的安全准入，与完整队列的 durable rows 无关，不截断队列或当前列表页。

## 固定来源与回归

Media3 精确版本 `1.8.0`，对应 [androidx/media source commit](https://github.com/androidx/media/tree/b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9)，完整 SHA `b7bbc6e2bc3e45ff3ed99884c114c50f03bba5c9`，许可证 Apache-2.0。坐标为 `androidx.media3:media3-extractor:1.8.0`、`androidx.media3:media3-common:1.8.0`、`androidx.media3:media3-exoplayer:1.8.0`，来源 [Google Maven](https://dl.google.com/dl/android/maven2/androidx/media3/media3-extractor/1.8.0/media3-extractor-1.8.0-sources.jar)。依据官方精确 source jar 的只读 ZIP 成员核对 API/分配语法，没有执行 jar/source，没有声称已经物化新的 clean detached Media3 研究快照，也没有升级依赖。

| source jar | 字节数 | SHA256 |
| --- | --- | --- |
| media3-extractor-1.8.0-sources.jar | 672871 | `6c3abb55bedb41cc40779845fde6f62cece850496e51f12e226ef973443faedd` |
| media3-common-1.8.0-sources.jar | 449085 | `800866952af08b1f1c39b2a56f906a46f3a0e7ca4ae5e92310ea5388beef7a38` |
| media3-exoplayer-1.8.0-sources.jar | 1135602 | `12d5ee2a00b7824788f38ab990a99034411912603895e5454d084d65c31687af` |

关键来源：固定 `Id3Peeker` 的整 tag 分配、`Id3Decoder` 的 frames/MLLT，`FlacMetadataReader` 的 selected blocks，`VorbisUtil` 的 comment 数组与 `skipBook` 的 entry 循环/lookup int skip，`PictureFrame` 的内部图片长度，`OggPacket` 的跨页扩容，`Mp4Extractor` / `FragmentedMp4Extractor` 的 leaf 分配，`Sniffer` 在 moov 扩大搜索范围后的 ftyp scratch/品牌数组，`BoxParser` 的 keys/sample/edit/ESDS，`WavHeaderReader` 的 extraData，以及 `AdtsReader` 的夹带 metadata。

固定 1.8.0 的 Vorbis book 方法是 `skipBook`，没有旧版 `new int[entries]`；当前准入保护的是循环、signed skip 与真实 payload extent，不把旧版数组形状写成现有事实。MP4 和 Ogg 私有 I/O 形状不是 public API 的通用保证；升级必须重新审读并运行本包回归。

35 个定义使用固定 SDK 26：真实 extractor lifecycle/sniff/read/sample/seek，普通 PCM WAV、MPEG/多版本 ID3、CRC FLAC/comments/PNG picture、Ogg 三种 codec、Ogg 两阶段零点 replay、audio-only M4A/mdta/freeform、1700 个小 fragment、ADTS 两种 header 与夹带 ID3；另覆盖短恶意 header/count/picture/lacing/ESDS/cover、retained peaks、重复表、factory 转发与 EOF 取消。fixture 为源码自生成，不导入录音；sample 输出不冒充 MediaCodec 解码、系统 backend、真实声音或设备验收。

`DEPRECATION` / `OVERRIDE_DEPRECATION` 仅用于固定 `ExtractorsFactory` 已弃用配置入口及其最小测试 override；准确接口在 source jar 核对，仓库 `allWarningsAsErrors` 仍开启。升级该接口后移除窄抑制，并重跑 factory 配置/identity 回归。文本/delimiter/声明检查不能代替编译或 API linking。

尚需云端实际编译/测试/lint、对应 APK hash 与设备矩阵；MP4 多 codec 正常样本、MediaPlayer native 内部内存行为、provider 硬中断与功耗/PSS均不因本文存在而完成。DSD/APE 虚拟 WAV 原 ReplayGain 的同源有界提取、实际来源及 Format 接线详见[LP-06 记录](LP-06-codec-replaygain.md)；新增回归定义与云端/设备验收分开记账，不猜、不借用相邻曲值。
