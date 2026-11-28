# MeloX手表端上游更新审阅与Android迁移决定

2026-11-16核对后，候选上游没有新增或修改手表UI、播放、账号、歌词或同步源码。此次变化主要属于手机、iPad和macOS。Android继续采用已确认的Apple Music视觉方向与本项目完整契约；本次研究不升级现用复用锁，也不代表应用功能、云端构建或设备验收通过。

## 1. 固定版本与研究证据

| 角色 | 完整提交 | 用途 |
| --- | --- | --- |
| 现用基线 | 09713edb847a0f0a3515a34f779cc630d4fe5985 | [现用锁](../../.ci/upstream-lock.json)中的MeloX来源；原研究快照保留 |
| 本次审阅候选 | 3b937b3682f6274ef6157aef156cd59d32ba3dc5 | 审阅时实际读取的上游main；独立ignored研究快照，不作为新复用基线 |

候选提交为2026-11-13的PR #46合并，内容是“一起听支持随机与循环，并修复切歌同步”。已核对旧提交是候选祖先，两个研究快照均为对应完整SHA、clean detached HEAD。只读取Git对象、源码、许可与图片，不执行上游脚本、不构建或运行上游，也不向上游提交本地账号、设备或文件信息。

审阅范围覆盖45份Watch Swift全文、2份共享同步Swift、2份本地化Swift、手机的PhoneWatchSyncService全文、Watch target配置、全部手表相关diff、README中英文截图分类、根GPLv3及实际二维码依赖的三份MIT许可。逐文件原字节比较包括整个Watch目录、共享同步、本地化和QRCode目录；实际查看了手表15～18及新增iPad四张截图。

原始逐文件散列、共享字符串键和图片记录留在ignored本地研究账本。账本SHA256为b3facb8f783acdf2a93720e229c974b93cfd00b2c20534b6c9d69214805d5c5a；不把研究克隆、原始本地路径或私人调查资料加入产品仓库。

## 2. 完整差异及平台归属

[两完整SHA的diff](https://github.com/youshen2/MeloX/compare/09713edb847a0f0a3515a34f779cc630d4fe5985...3b937b3682f6274ef6157aef156cd59d32ba3dc5)共有86个变化路径：22新增、64修改、无删除或重命名。按顶层归属为MeloX手机目录46、MeloXDesktop目录23、共享本地化2、Xcode项目1、上游脚本/测试7、IPAD_BUILD和RELEASE_NOTES各1、iPad截图及记录5。路径数只是静态审阅范围，不是工作量估算。

| 手表相关范围 | 原字节比较结果 | 静态影响 |
| --- | --- | --- |
| MeloXWatch | 60文件，包括45份Swift，全部相同 | 手表UI、账号、在线库、播放器、队列、歌词、设置和资产均无本次源码变更 |
| MeloXWatchShared | 2文件，全部相同 | 功能可用性及账号同步envelope未扩展 |
| MeloXLocalization | 4文件中2份Swift相同；中英Localizable.strings变化 | 资源包字节改变；变更键未出现在Watch Swift字面引用集合中 |
| vendor/QRCode | 185文件，全部相同 | Watch二维码包及其vendored依赖/许可无差异 |
| MeloX.xcodeproj/project.pbxproj | 仅两处歌词Widget的CURRENT_PROJECT_VERSION由9110300变为9110401 | 无Watch target成员、依赖或配置修改 |
| PhoneWatchSyncService、根LICENSE、README中英文 | 各文件全部相同 | 未新增手表跨端播放或手表功能承诺 |
| 手表截图15～18 | 四张全部相同 | 没有新手表截图；原截图仍只能提供视觉观察 |

Watch target实际包含MeloXWatch、MeloXWatchShared和MeloXLocalization源码组，包依赖为QRCode。两种语言各15个变化键的完整分类如下；没有删除键。

| 键组 | 数量 | 内容归属 |
| --- | --- | --- |
| ui.explore.featured.title | 1 | iPad推荐标题 |
| ui.settings.background_mix.title、keywords、always.title、always.subtitle | 4 | 手机后台混合播放设置 |
| ui.floating_lyrics.lock_position、unlock_position、open_main | 3 | 桌面浮窗控制 |
| ui.library.search.prompt、clear、empty.description、empty.title、loading、results | 6 | 桌面收藏库搜索 |
| ui.listen_together.playback_mode_managed | 1 | 手机一起听提示从房间管理播放模式改为禁用Autoplay/AutoMix |

两份字符串的变化键与全部Watch Swift中的ui.*字面键交集均为空。这是静态引用核对；本次没有编译，不能据此宣称新旧安装包相同或运行行为完全等价。

手机一起听通过PlayerStore的transport/queue事件同步随机、循环、切歌意图与进度，增加解析期间意图处理和资源重试/引擎恢复。PhoneWatchSyncService只收发账号Cookie的envelope，不读取PlayerStore，不传队列、歌词或一起听状态，不能把这些手机变化归为Watch更新。

iPad变化是Apple Music侧栏、顶部搜索/底部账号、横向封面信息头、全宽歌曲表、宽底部播放器和随窗口变化的卡片网格。macOS变化是收藏搜索、浮窗锁定/主窗口入口及AutoMix/队列处理。它们不增加手表首版FR，也不为Android引入双deck、桌面窗口、手机重试预算或额外SDK。README仍注明Apple Watch开发中，通用功能清单不能全部归给手表。

## 3. 最新手表完整契约与Android边界

以下均是旧基线已经存在、在候选SHA再次全文核对的行为，不能记为本次新增功能。固定链接用于核对事实；Android行为由02/03/05/14/15/17/21决定。

| 已核对的手表实现 | 上游完整行为要点 | Android迁移决定 |
| --- | --- | --- |
| [Root](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Root/WatchRootView.swift)、[Menu](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Menu/WatchMenuView.swift) | 队列/正在播放/歌词三页共用coordinator；顶部34pt菜单及分组导航 | 保留三页和明确来源入口；以唯一Android播放服务/会话协调；系统返回、标准菜单和本地未登录入口始终可达 |
| [NowPlaying](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Player/WatchNowPlayingView.swift)、[Control](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Player/WatchCircularControlLabel.swift) | 居中圆角封面、歌名/艺人、三键、较大主键；侧键30/36/40pt、主键40/46/50pt；文字minimumScaleFactor为0.78/0.8，watchOS26使用glass | 参考层级、间距和按键关系；Android独立热区至少48dp、主键建议56dp。按实际圆弦/inset和方矩形窗口布局，保留大字及完整文本查看，不移植pt尺寸、强缩字或平台glass |
| [Background](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Player/WatchNowPlayingBackground.swift) | AsyncImage封面、运行时blur18、dim0.62、saturation1.15 | 默认纯黑/静态；图指纹、尺寸或强度改变时才有界离屏处理/缓存，失败稳定黑底。F-099的五种背景模式及F-100分离模糊/遮罩仍必做，参数由14/15实测 |
| [Queue](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Player/WatchQueueView.swift)、[PlaybackStore](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Playback/WatchPlaybackStore.swift) | 内存整数组/index身份、JSON持久化，每5秒保存整个队列；当前行红色状态符号/selected语义，自动居中 | 采用持久QueueEntryId、完整独立逻辑队列、有界分页和播放器窗口；保存冻结顺序、随机顺序及修订号。禁止整库JSON、当前页截断、10000项产品上限；当前状态有符号/文字，浏览位置不被更新抢走 |
| [AudioEngine](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Playback/WatchAudioPlaybackEngine.swift)、[Coordinator](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Playback/WatchPlaybackCoordinator.swift) | 单AVAudioPlayer；远程资源先下载临时文件再播放，释放时删除临时文件；全局100ms时钟，加载有generation | 一个服务/会话同一时刻仅一个活动后端；系统MediaPlayer和Media3均实现暂停/保存/释放/恢复切换。临时播放文件不等于本地库或P4下载；焦点/noisy/迟到回调由03统一，隐藏和熄屏停UI tick |
| [PlaybackSettings](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Settings/WatchPlaybackSettingsView.swift) | 默认high、autoPlaySelection；上一曲超过5秒先回曲首；resumesAfterInterruption开启；列出七档网易云音质 | 本项目默认标准、允许高；七档不泛化为首发能力。上首按已定队列规则移动，进程/后端恢复保持暂停；同一次仍有效播放意图下的短暂焦点恢复按03，旧焦点/noisy回调不能抢播。F-079三种点选策略及全部模式不减项 |
| [AccountStore](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Account/WatchAccountStore.swift)、[QRLogin](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Account/WatchQRLoginView.swift) | UserDefaults明文Cookie、以MUSIC_U=判断登录；803先保存Cookie再可选profile；1秒轮询、过期递归刷新，refresh有未结构化Task | 校验账号成功后才加密保存；账号/二维码generation隔离，前台有限轮询/退避、离开取消；过期由用户刷新或一次有上限自动更新，禁止递归无界刷新。退出/失败不删本地文件和库，登录不是本地前提 |
| [API](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Networking/WatchNeteaseAPI.swift)、[Client](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Networking/WatchNeteaseClient.swift) | 搜索offset固定0，用户歌单limit2000/offset0；详情逐100补歌曲后组成整数组；同时请求网易云歌词和内置公共AMLL TTML，优先TTML | 搜索/歌单真实分页、去重、结束证据和有界任务，不把固定首批当全部。本地无自动补图/歌词；F-070/F-071默认关闭、每次明确请求、最少字段与预览关联。Meting默认关闭且无公共地址，不提供歌词/封面许可或此公共TTML回退 |
| [SyncModels](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatchShared/MeloXWatchSyncModels.swift)、[PhoneWatchSync](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloX/Core/Watch/PhoneWatchSyncService.swift) | WCSession账号Cookie同步；没有库编辑、文件传输或跨端队列同步 | 仅研究思路；不得移植明文账号同步。LP-13/14的认证传输及真实Android手机管理App仍首版必需；账号同步/额外跨端播放是独立后续范围 |

### 歌词时间与用户动作

[MusicModels](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Models/WatchMusicModels.swift)、[LyricParser](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Lyrics/WatchLyricParser.swift)会由下一行或文本长度推测普通LRC的duration，再通过effectiveSyllables均分字符时间；[TextRenderer](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Lyrics/WatchLyricTextRenderer.swift)实际使用该结果。[RomanizationAligner](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Lyrics/WatchLyricRomanizationAligner.swift)还会按字符比例/转写猜关联并拆分时段。F-110只能使用真实字时间；普通行歌词保持逐行显示，有真实词片段时按真实区间显示，不能把推算的字符时间标为源数据。

上游译文/罗马音使用最近时间0.75秒容差、跨LRC/YRC文本匹配5秒窗口等近似关联。Android本地译文按完整归一时间及稳定行关联、消歧重复时间，记录真实来源和格式，不机械搬近似匹配。上游支持LRC/YRC/TTML不使XML成为本项目默认导入格式；递归整树TTML解析不作为有界实现。LP-07的增强LRC、内嵌歌词和译文路径仍必做。罗马音、和声、PV/EVA也不由本次研究增加为新的128项门槛。

[LyricsView](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Lyrics/WatchLyricsView.swift)在手动滚动后2.6秒恢复跟随，点击行仅seek。Android保留显式“回到当前”，更新不能抢回用户浏览。F-128的点击跳转并播放必须先明确可能发声、预览/确认，再提交seek及播放；旧曲/旧会话动作失效，权限、输出安全和焦点约束优先。

[LyricsPage](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Lyrics/WatchLyricsPage.swift)隐藏时黑底、只active时加载；[LyricsSettings](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/MeloXWatch/Settings/WatchLyricsSettingsView.swift)提供30/60fps、辉光、长音、按字/按词抬升等配置，很多动效默认开启。Android采用默认低成本、可选真实逐字和静态强调；隐藏/熄屏停止UI时钟，常亮只在可见歌词页显式开启并在离开/关闭时释放。30/60fps是上游设置事实，不是本项目性能目标或实测结果。歌词字号/间距/位置、字体导入/字重及颜色样式按F-108～116完整实现，不以参考中的简化控件替代。

## 4. LP、DEV、FR映射及主契约修订决定

主契约已经确认的行为继续生效。下表明确整合规划需要补充的固定审阅引用和迁移边界；本报告本身不修改这些主文件，不调整原始128项/256个AC或依赖，也不把任何实现状态置为完成。

| 领域与映射 | 主契约落点 | 具体决定 |
| --- | --- | --- |
| 来源：DEV-201 | [03](../03-技术架构与接口.md)§10；[11](../11-上游复用实施指南.md)固定来源/升级流程 | 补本报告的候选审阅引用；现用MeloX SHA保持09713。锁文件当前没有独立审阅记录字段，本次用本报告记录3b937审阅，不新增无关锁字段或升级依赖 |
| 三页/菜单：DEV-105/301/303，LP-04 F-127、LP-12；圆屏与可访问操作F-073归LP-15 | [15](../15-界面设计与视觉验收.md)§2/4/11；[05](../05-开发阶段与任务清单.md)DEV-301/303 | 补候选静态核对及15～18截图索引；保留Apple Music方向、48dp热区、建议56dp主键、圆优先/方矩形兼容和完整大字路径 |
| 背景/外观：LP-12 F-099/100/103，DEV-304 | 15§7/8；[17](../17-本地音乐功能与实施计划.md)LP-12 | 明确上游运行时模糊/glass及歌词高频效果只作视觉参考；保留五种背景、强度分离、有界离屏缓存、隐藏停UI与显式常亮生命周期 |
| 播放/恢复：FR-05/06/07/16，LP-04/05，DEV-102/103/104/206 | 03§4/6/6.0/6.1；05对应DEV；17 LP-04/05 | 补不能搬整数组/index/全量JSON和自动恢复的理由；保留持久完整队列、唯一活动后端、双后端切换、焦点/noisy单点所有权与恢复暂停 |
| 登录/在线库：FR-02/03/04，DEV-003/202/203/204/205 | 03§5；05对应DEV；17共同不变量 | 补明文Cookie、递归轮询、固定首批和未结构化Task的迁移边界；仍须账号校验/加密、真实分页、generation与有界取消；不让本地入口触发账号或网络 |
| 歌词：FR-08，LP-07 F-037～042/070～071/108～116/128，DEV-302/304 | 17 LP-07；05 DEV-302/304；15§4/7 | 补有效字符时间推算和公共AMLL请求事实，禁止默认引入；真实逐字、稳定译文关联、显式补全、手动浏览与F-128确认后seek并播放均保留 |
| 旋转/进度/音量：LP-08 F-082/083/101、LP-06 F-076，DEV-303 | 17 LP-06/08；15§3/4/8 | 参考圆形控件不等于数字表冠能力；按实际标准事件增强，触控路径完整可达；映射/方向/触感、进度事务及真实系统音量仍按原契约 |
| 手机端：LP-13/14 F-066/067/097/124～126 | 03§9；05 LP-13/14；17 LP-13/14 | 明确WCSession Cookie同步不能替代真实Android配套端；认证文件/主题/库与信息编辑、修订冲突、撤配对及双APK协议证据仍首版必需；不新增一起听模块 |
| 全范围：FR-18，DEV-305 | 05 DEV-305；17§3/5；[21](../21-本地播放器全量需求整合.md) | 不减少128项及256原AC；双后端、解码/DSP、字体、备份、原文件真实写删移、Meting和手机端仍必需。正常AC必须有声明支持路径，unsupported只覆盖异常情形 |

## 5. 截图索引

手表四张均实际查看，带Apple Watch Series 11模拟器外框；不能作为Android真表、性能或触控验收。表内散列在两SHA均相同，点击固定链接可查看原图，本项目不导入这些图片或Apple资产。

| 固定候选图片 | 已观察内容 | SHA256（旧/新一致） |
| --- | --- | --- |
| [15.png](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/15.png) | 紧凑队列、当前项红暂停、顶部模式/菜单 | 759c83d68ba7f9580c45aded2efdad67c7f11e7c9756e7f8fce75a28d5dc2a08 |
| [16.png](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/16.png) | 中央封面、标题/艺人、三键及较大主键 | a8e50617634036435212348bca1a6fe74b17d75d2757380d143597e3465e07b9 |
| [17.png](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/17.png) | 日文/罗马音/译文、当前行强调 | 816b4aca1b0dd971046d79e999fd5d28618d0dbe2a6744c338b6c56e0ee5a860 |
| [18.png](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/18.png) | 60/30fps说明、辉光等歌词设置 | 8a9a78fcee7f63682b8119f06e67147cc01575f826d0b77e28c337f950d99699 |

新增iPad四图也实际查看，仅用于判断此次变化属于大窗口；它们不作为手表布局模板。

| 固定候选图片 | SHA256 |
| --- | --- |
| [ipad/home.png](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/ipad/home.png) | c4720a6087b06a89cf359b84ae117cb761b9c4dbe5885a0d67f733f80836cfe9 |
| [ipad/discover.png](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/ipad/discover.png) | 0fad19d0f3badc0fe5a8a5ccd6cd1a1d4bb1b85a3fbd5b7b963cb522d6c8f6ca |
| [ipad/collection.png](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/ipad/collection.png) | b86825ac43a11b0edeb68fc865b21d2a271868f889b4355815b432c00bc69117 |
| [ipad/player.png](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/ipad/player.png) | e221123f8b3df4ceb33960eb96ccf9c73875da7e9798bcca2ad81aa0f8040ac8 |

上游[validation.json](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/docs/screenshots/ipad/validation.json)自述iPad Pro 13-inch (M4)、runtime27.0、6项Passed，绑定的是620d10ab0be00399d014c1b45aab0a11dc540288；不是候选3b937的测试，更不是本项目或Watch验收。README的0～14图属于手机/横屏/PV/EVA等，已分类及字节核对但不作本次手表视觉证据；macOS官网浮动外链未获取，不作为固定证据。

## 6. 许可与复用记录

根[LICENSE](https://github.com/youshen2/MeloX/blob/3b937b3682f6274ef6157aef156cd59d32ba3dc5/LICENSE)全文为GPLv3，旧/新SHA256均为230184f60bae2feaf244f10a8bac053c8ff33a183bcc365b4d8b876d2b7f4809。Watch使用的QRCode20.5.0、swift-qrcode-generator2.0.2和SwiftImageReadWrite1.9.2三份MIT许可已全文核对，相关目录字节一致；不为Android增加Swift包。

本次研究只观察布局/交互，未复制候选代码或资产；Android行为按本项目契约实现。以后实际复用GPL适用代码时，在DEV-201/迁移记录中保留完整来源SHA、文件、版权/许可、修改点、依赖及对应源码/回归记录。根GPL不代替远程封面、Apple字体/SF Symbols或PV Tool、BeatNet等独立素材/组件的许可审阅。不迁入上游身份、开发团队配置、凭据或平台资源。

## 7. 迁移和回归退出条件

本次候选没有手表源码变化，继续现用09713基线即可；研究完成不触发机械锁升级。以后需要采用候选变化时，按11单独开迁移Issue，在独立ignored候选快照和迁移工作区比较已复用文件/接口/模型/许可；实现、锁及对应clean detached研究快照在同一迁移中更新。所有者PR同时提交实现、锁、来源及回归记录；通过并合并后其它工作区才同步，失败保留旧基线。研究快照不进入产品Git，日常工作区不先升锁或混用新代码。

每次实际迁移的退出条件如下，证据不足记录未测，不能记零值或通过。

1. 对实际Android变更运行适用云端编译、单测和lint；不在本地执行Gradle、Kotlin/Java/Swift编译或上游测试。记录source/build SHA、run/attempt/artifact、APK及证书散列，下载和测试原始云产物。
2. 验证唯一服务/会话和两个后端的正常切换；完整跨页队列、重复曲目稳定身份、随机/有限模式及进程/后端恢复暂停；同一次仍有效意图下短暂焦点恢复按03，过时解析/seek/焦点/noisy回调不能抢播。
3. 验证账号校验、加密保存、二维码过期与取消、真实分页和重试上限；在手表可用网络下手机离线不破坏在线路径；本地冷启动/扫描/浏览/整理/播放零隐式HTTP或DNS请求，退出账号不删本地文件/库。
4. 验证真实行/字/词时间、异常格式、重复时间译文关联、切歌/偏移/seek隔离、手动浏览与F-128发声确认；关闭显式补全零请求，启用后每次确认、预览关联和取消正确；字体/歌词完整本地能力按原AC覆盖。
5. 按15代表矩阵复核圆形真实裁切与方矩形布局、默认/最大字、48dp独立热区、长标题和状态可读、输入/返回/页滑冲突。隐藏/熄屏无UI tick，常亮/动效/缓存释放完整；iPad或方窗模拟不能代替真表。
6. 按14在受影响页面和声明设备条件测资源、帧/响应、后台及在线60/本地120分钟熄屏链路，保留紧凑证据和真实工具限制；不把30/60fps设置或一次静态截图写成性能通过。
7. LP-13/14在真实支持路径验证配对、认证、传歌/主题/库与信息编辑、冲突/撤配对、断连独立播放。首版双端原云APK从同一snapshot产生，分别绑定身份/版本/散列/证书和协议互操作证据；云签发/发布扩充仍走既有Actions Issue维护路线。
8. DEV-305及17/21的128项、256原AC和06/14/15/16适用验收继续成立；至少一条声明支持正常路径通过，设备不支持的异常处理不能代替正常AC。冻结候选验收、最终发布批准和公开可见性仍各自依据10/12，不由本研究状态代替。

## 8. 最近固定 main 的只读追踪（2026-11-28）

最近读取的完整 main 为 [`86d33693380b638280229bba9f86fbbee0f13a19`](https://github.com/youshen2/MeloX/tree/86d33693380b638280229bba9f86fbbee0f13a19)。与现用 `09713edb847a0f0a3515a34f779cc630d4fe5985` 相比，整个 `MeloXWatch`、`MeloXWatchShared`、`vendor` 的 Git tree OID 分别仍为 `0becbb29ee31796028476052b35f84397e7a182c`、`fc7bab5a040d78dc66a37d13d6549008afa4a68d`、`be4dd2634cba738386f3c1ff23f61248984bc4a9`；手表截图 15～18 的原字节与第5节摘要一致。没有新的 Watch 源码或截图迁移任务，保持现用复用锁和干净 detached 快照。

共享本地化的中英文各有39个变化键，与固定 Watch 的45份 Swift 中166个 `ui.*` 字面键交集为空。新增共享源组与 ZIPFoundation 只接入手机/桌面 target，Watch target 源组及包依赖未变。此结论来自 Git 对象、完整相关 diff 和原字节核对，没有执行或构建上游，也不宣称平台安装包或动态资源行为等价。原始研究材料继续只留本地；本地评估摘要 SHA256 为 `fcb99287993bbd32b94820c6fda98e08a0508a9735c8379c57d9d8fd5c341182`。

现有菜单分组差距归 DEV-301/303。后继以 Foundation 行和静态双语标题组织聆听、资料库与来源、偏好设置、工具，保留原操作、48dp热区、圆形滚动与方形可用区域；源码审查、实际新云验、设备视觉与原AC继续分开。既有播放、歌词、账号、离线与配套回归及128项/256原AC门槛完整保留。
