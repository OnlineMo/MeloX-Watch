# LP-05 资产偏移与解码读取

状态：源码修复及回归定义，Android/JVM 编译、云构建、APK、设备与原 AC 全部 `NOT_RUN`。

已知长度的 `AssetFileDescriptor.createInputStream()` 返回 `AssetFileDescriptor.AutoCloseInputStream`。该流在 `read/skip` 内递减独立的 `mRemaining`，但继承的 `FileChannel.position` 不重置这个计数。旧解码输入通过 channel seek、通过 stream read；正常的 header、padding 与 FIR 回读会把计数重复消耗，可能提前 EOF。

解码输入改用原流的同一 channel 读取，仍按资产的 `startOffset/declaredLength`、当前相对位置与 16 KiB 单次窗口约束，不扩大到包装文件大小。四字节 prefix 继续通过原流读取；普通格式的 custom sniff 回退先恢复 channel 到 `count` 的相对位置，再把原 `PushbackInputStream` 交给 native 回调，因此原流仍只消耗 prefix 的计数。原件、权限、队列和唯一播放后端保持现有契约。

固定官方 AOSP 镜像源码仅用于 API 核对，没有复制到应用、执行源码或声明研究仓库已经物化：

| 版本 | 完整 commit SHA | 核对位置 |
| --- | --- | --- |
| android-8.0.0_r4 | `97e5130234bcdc1c4a705be4e548302934499bd4` | [AFD createInputStream/AutoCloseInputStream](https://github.com/aosp-mirror/platform_frameworks_base/blob/97e5130234bcdc1c4a705be4e548302934499bd4/core/java/android/content/res/AssetFileDescriptor.java#L173)、[PFD 继承 FileInputStream](https://github.com/aosp-mirror/platform_frameworks_base/blob/97e5130234bcdc1c4a705be4e548302934499bd4/core/java/android/os/ParcelFileDescriptor.java#L902) |
| android-11.0.0_r1 | `34a1b9c951c38537ab96b69bc308f6e0884823f5` | [AFD read 的独立剩余计数](https://github.com/aosp-mirror/platform_frameworks_base/blob/34a1b9c951c38537ab96b69bc308f6e0884823f5/core/java/android/content/res/AssetFileDescriptor.java#L229)、[PFD 继承 FileInputStream](https://github.com/aosp-mirror/platform_frameworks_base/blob/34a1b9c951c38537ab96b69bc308f6e0884823f5/core/java/android/os/ParcelFileDescriptor.java#L967) |

`LocalCodecAssetDescriptorTest` 在 API 26/30 定义真实 AFD 非零偏移、前后哨兵、DSF/DFF/APE 小 PCM decode、空 ID3 sniff 后完整 native 字节回退、普通 WAV 回退、声明截断 APE 拒绝、descriptor 释放及原件字节保持。它验证读取与 native 回调的移交；实际系统 decoder、provider 硬中断、设备功耗与可用性仍待云产物/设备验证。

同包仅展开 `RoutedCodecRetirementTest` 中的分号步骤，以满足 docs16；断言与失败阶段条件保持原封包语义。该修订依赖 LP-05 主包与 close-retirement 窄包的精确 after 字节，不能直接套用到未经组合的源码。
