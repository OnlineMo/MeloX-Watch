# Apple Music与MeloXWatch界面参考

用户于2026-11-16明确要求模仿Apple Music，并提供MeloXWatch实际UI代码。此记录固定已审阅来源和Android实现边界；新的界面实现、云端APK和设备结果分别记录，当前没有新的界面验收结论。

参考仓库：[youshen2/MeloX](https://github.com/youshen2/MeloX)，提交`09713edb847a0f0a3515a34f779cc630d4fe5985`。它与`.ci/upstream-lock.json`一致；本地参考为干净detached快照，不执行、不编译，也不以分支最新内容替换固定版本。仓库采用GPL-3.0；任何实际代码复用须保留适用版权/许可并记录复用范围。当前Android布局按下表重新实现，不导入Apple字体、SF Symbols或平台专属资产。

2026-11-16另外全文审阅候选`3b937b3682f6274ef6157aef156cd59d32ba3dc5`的45份Watch Swift及相关共享/同步/许可，保留原快照并另建ignored、clean detached快照。Watch目录60文件、共享同步2文件及手表截图15～18均原字节相同；共享本地化只有手机/iPad/桌面键变化，与Watch字面引用集合交集为空。没有编译或运行候选，不能由静态结果声称安装包相同。现用复用SHA仍为09713；完整差异、截图散列、LP/DEV/FR映射与主规划修订决定见[上游手表更新审阅](UPSTREAM-WATCH-UPDATE.md)。此次iPad大窗口更新不作为手表布局模板。

| 已全文审阅的UI代码 | 参考行为 | Android落地要求 |
| --- | --- | --- |
| [WatchRootView.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Root/WatchRootView.swift) | 队列/正在播放/歌词横向页面，同一coordinator；顶部圆形菜单 | 同一播放服务与会话；隐藏页面不持有计时/歌词跟随，保留系统返回与标准菜单路径 |
| [WatchNowPlayingView.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Player/WatchNowPlayingView.swift) | 居中圆角封面、半粗歌名、次级艺人；上一曲/较大播放暂停/下一曲 | 按实际圆屏弦宽、inset与字体安排；完整曲名有查看路径，不能强缩文字或裁控制 |
| [WatchCircularControlLabel.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Player/WatchCircularControlLabel.swift) | 白色符号、中性圆形底和细边界 | Compose Foundation共用圆形控件、稳定按下/焦点语义；每个独立热区至少48dp，主键建议56dp |
| [WatchNowPlayingBackground.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Player/WatchNowPlayingBackground.swift) | 黑底、封面运行时模糊、暗遮罩和静态渐变 | 默认纯黑/静态；图指纹、尺寸或强度变化才有界解码、离屏处理和缓存；失败稳定黑底，换曲不留旧封面；F-099五种背景及F-100强度分离不减项，合成后复核对比度 |
| [WatchQueueView.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Player/WatchQueueView.swift) | 紧凑普通行，当前项有红色符号和selected语义，自动居中 | 持久QueueEntryId、全量队列与有界页面；当前状态同时有符号/文字，错误与恢复入口可完整阅读；不复制整数组/index身份、5秒整队列JSON保存或抢回浏览位置 |
| [WatchMenuView.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Menu/WatchMenuView.swift) | 原生手表分组导航、明确来源入口 | 普通Android能力适配，紧凑共用行项；本地不依赖账号、联网或配套端 |
| [WatchLyricsView.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Lyrics/WatchLyricsView.swift)、[WatchLyricTextRenderer.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Lyrics/WatchLyricTextRenderer.swift) | 当前行/译文/罗马音层级，逐字强调，手动滚动后2.6秒恢复跟随，点击行seek | 真实行/字/词时间，不能用均分字符生成伪字时间；显式“回到当前”；F-128告知发声并预览/确认后seek及播放，旧曲动作失效，输出安全优先 |
| [WatchLyricsPage.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Lyrics/WatchLyricsPage.swift)、[WatchLyricsSettingsView.swift](https://github.com/youshen2/MeloX/blob/09713edb847a0f0a3515a34f779cc630d4fe5985/MeloXWatch/Settings/WatchLyricsSettingsView.swift) | 隐藏页黑底且仅active加载；30/60fps、辉光、长音及按字/按词抬升配置 | 默认低成本，可选真实逐字与静态强调；隐藏/熄屏停UI tick，显式歌词常亮离开即释放。字号/间距/位置、TTF/OTF/字重与样式全部保留；30/60fps不是Android目标或实测 |

视觉令牌、48dp底线与圆/方矩阵以[15](../15-界面设计与视觉验收.md)为准。参考中的30/34pt小控件和强制文字缩小不移植到Android；系统字体回退、长文恢复和可访问焦点保留。使用Material库的文本/语义工具不决定外观，产品控件由共用Foundation样式给出。

视觉参考不引入上游默认业务链：公共AMLL TTML和网易云歌词并行请求不能进入本地入口；F-070/F-071默认关闭、每次明确请求后预览关联，Meting不提供此歌词/封面回退。唯一服务/会话、双后端暂停切换、完整队列、恢复暂停和账号加密依03/17实施；上游WCSession账号同步不替代首版真实Android手机配套端。

固定手表截图为[队列15](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/15.png)、[播放16](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/16.png)、[歌词17](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/17.png)、[设置18](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/18.png)。均已查看，旧/新字节一致且带Apple Watch模拟器外框；只作布局观察，散列见更新审阅，不作Android验收。

验证顺序：原始云端APK身份核验 → 466×466/dpi310虚拟机的圆形布局模拟 → 192/260dp及大字体边界 → 方形/矩形共享控件 → 交付前真实圆屏手表。模拟不等于真表通过。原始设备图、连接地址和个人信息只留ignored本地；对外记录仅包含脱敏能力与公开构建标识。
