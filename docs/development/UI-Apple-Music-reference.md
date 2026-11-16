# Apple Music与MeloXWatch界面参考

用户于2026-11-16明确要求模仿Apple Music，并提供MeloXWatch实际UI代码。此记录固定已审阅来源和Android实现边界；新的界面实现、云端APK和设备结果分别记录，当前没有新的界面验收结论。

参考仓库：[youshen2/MeloX](https://github.com/youshen2/MeloX)，提交`09713edb847a0f0a3515a34f779cc630d4fe5985`。它与`.ci/upstream-lock.json`一致；本地参考为干净detached快照，不执行、不编译，也不以分支最新内容替换固定版本。仓库采用GPL-3.0；任何实际代码复用须保留适用版权/许可并记录复用范围。当前Android布局按下表重新实现，不导入Apple字体、SF Symbols或平台专属资产。

| 已全文审阅的UI代码 | 参考行为 | Android落地要求 |
| --- | --- | --- |
| [WatchRootView.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Root/WatchRootView.swift) | 队列/正在播放/歌词横向页面，同一coordinator；顶部圆形菜单 | 同一播放服务与会话；隐藏页面不持有计时/歌词跟随，保留系统返回与标准菜单路径 |
| [WatchNowPlayingView.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Player/WatchNowPlayingView.swift) | 居中圆角封面、半粗歌名、次级艺人；上一曲/较大播放暂停/下一曲 | 按实际圆屏弦宽、inset与字体安排；完整曲名有查看路径，不能强缩文字或裁控制 |
| [WatchCircularControlLabel.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Player/WatchCircularControlLabel.swift) | 白色符号、中性圆形底和细边界 | Compose Foundation共用圆形控件、稳定按下/焦点语义；每个独立热区至少48dp，主键建议56dp |
| [WatchNowPlayingBackground.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Player/WatchNowPlayingBackground.swift) | 黑底、封面模糊、暗遮罩和静态渐变 | 有界解码、离屏处理和缓存；失败稳定黑底，换曲不留旧封面；合成后复核对比度 |
| [WatchQueueView.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Player/WatchQueueView.swift) | 紧凑普通行，当前项有红色符号 | 持久全量队列与有界页面；当前状态同时有符号/文字，错误与恢复入口可完整阅读 |
| [WatchMenuView.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Menu/WatchMenuView.swift) | 原生手表分组导航、明确来源入口 | 普通Android能力适配，紧凑共用行项；本地不依赖账号、联网或配套端 |

视觉令牌、48dp底线与圆/方矩阵以[15](../15-界面设计与视觉验收.md)为准。参考中的30/34pt小控件和强制文字缩小不移植到Android；系统字体回退、长文恢复和可访问焦点保留。使用Material库的文本/语义工具不决定外观，产品控件由共用Foundation样式给出。

验证顺序：原始云端APK身份核验 → 466×466/dpi310虚拟机的圆形布局模拟 → 192/260dp及大字体边界 → 方形/矩形共享控件 → 交付前真实圆屏手表。模拟不等于真表通过。原始设备图、连接地址和个人信息只留ignored本地；对外记录仅包含脱敏能力与公开构建标识。
