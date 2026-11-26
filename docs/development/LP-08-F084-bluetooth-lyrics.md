# LP-08 F-084 蓝牙歌词通路合同

本合同落实固定匿名需求 F-084 的协议、受控接收端、持久化、权限、资源与取消边界。主责任务保持 LP-08；手机接收端使用已有 Android companion 工程，不等待其他无关手机业务完成。实现源码提案已形成，云编译/测试/lint、两个 APK、互通、设备 AC-084-N/E、14/15/16 和发布批准分别保留未完成状态。匿名原文及其 SHA-256 不改变，普通蓝牙音频连接或“不支持”提示不能替代正常 AC。

## 1. 入口、设置与两端操作

手表入口为“播放设置→蓝牙歌词”。页面先说明选定接收屏幕可看到当前曲名、歌手和歌词，再提供默认关闭的开关草稿、已保存值、保存、取消和草稿恢复默认。唯一共享偏好仓库在实际 DataStore transform 中核验整个快照 revision；保存仅改该 Boolean，保留 Help 六字段和其他设置。失败显示实际已确认的值及草稿，IO 重读/重试与 revision 冲突后明确重新读取分别可达；临时默认或新版只读 schema 不授权连接。

关闭草稿立即撤销当前输出，不等磁盘应答；取消保留已保存值，已经停止的连接不自动重建。启用后必须保存，再明确选 OS 已配对手机。没有扫描、地址输入、自动发现、自动重连或启动命令恢复。配对和蓝牙开关由系统设置处理。

手机入口为配套应用连接页的“蓝牙歌词接收”。可见页面明确开始接收后启动一个 connectedDevice 前台服务，重复启动同一 owner 幂等，空启动 Intent 和进程恢复不监听。两端页面核对八位数字并分别确认后才发送曲名、歌手和歌词。拒绝、页面失焦/隐藏、锁屏或超时会关闭尚未认证的尝试；认证后手机前台服务和唯一手表播放服务承载有限会话，界面不拥有播放器或采样器。

手机通知只含通用会话状态、打开及停止操作，标记仅本机通知；不含曲名、歌词、设备名、数字或密钥。通知拒绝时解释系统活动应用管理及本页停止路径。两端 UI 只在生命周期可见阶段收集状态，接收内容与比较票据不进入 SavedState/Saveable、文件或日志。

两端专用 Activity 各绑定一个 exact presentation；旧 Activity 的焦点/销毁和迟到 UI 动作只能退休自身，不能隐藏后继页面、批准其数字或停止其新会话。新展示仍不自动连接，已认证输出由原服务继续承载。

## 2. 兼容接收端与权限

声明实现路径为两个 Android APK 之间的 secure Classic Bluetooth RFCOMM。实际环境须有经典蓝牙适配器、系统配对以及可用的 RFCOMM 服务。受控接收端是上述 companion 接收页及服务；不宣称任意车机、耳机、AVRCP、厂牌或未测设备兼容。实际支持声明等待同一云快照的两个精确 APK 正常互通。

公共服务 UUID 为 3e46c562-d08d-4a4b-9676-d8c306a4dd2d，仅标识协议，不是凭据。API 31+ 按明确操作请求 BLUETOOTH_CONNECT；旧支持 API 使用 maxSdkVersion=30 的普通 BLUETOOTH。RFCOMM 传输本身不申请扫描/位置权限，不使用 GMS、互联网、局域网发现、HTTP 或文件传输。接收前台服务单独声明 connectedDevice 类型及对应平台权限。

本地来源始终使用原有本地加载分支。当前网易云来源可复用既有认证歌词获取，但外部许可还核验真实服务当前实例和 READY/BUFFERING 状态；队列保留条目或空服务不能授权联网。首次当前实例可读后最多补一次获取，错误不因普通播放事件循环重试；明确新意图或来源代次才重新取权。没有第二个客户端、账号同步、目录获取或元数据补全任务。

## 3. 协议 schema 1 与认证

双方先交换新鲜 hello 的 SHA-256 commitment，再揭示新鲜 P-256 公钥和 16 字节随机 nonce 并验证 commitment。先承诺再揭示阻止在看到另一方 hello 后适配 nonce 来寻找指定比较数字。按 watch/phone 顺序的 transcript 绑定版本、角色和公共 UUID。ECDH 与 HKDF-SHA256 产生独立双向 AES-256-GCM 密钥及八位本地比较数字。

数字不是传输密码，也不作为设备身份持久化。必须在两端明确确认当前同一票据；认证前只有 commitment、hello 和加密确认，不能发送媒体文本。拒绝/撤权/关闭/失效后丢弃内存中的 key、设备句柄和票据；每次明确新连接使用新密钥。协议不改正常 MediaMetadata title。

hello body 上限 256 字节。认证帧为 16 字节头、最多 8192 字节明文和 16 字节 GCM tag，物理帧最多 8224 字节。头含 magic/schema/type/length 和严格递增双向 sequence，作为 AAD 并绑定 transcript；不允许同密钥回绕、重置、跳号或重复。未知 schema/type/字段、额外尾字节、畸形 UTF-8、NUL、认证失败和 track epoch 回退关闭会话。

当前 snapshot 仅含会话内不透明 track epoch、媒体位置、当前行媒体时间或 -1、播放/加载/缺失等状态、实际 isPlaying、title、artist、当前原文组和严格关联的翻译组及 shortened 标志。没有 TrackKey、队列 ID、文件名/路径/URI、账号、地址或访问能力字段。UTF-8 上限为 title/artist 各 512、原文 4096、翻译 2048 字节。截短保持有效 Unicode，接收端显示已缩短；不发送猜测字时间。

## 4. 单一歌词 owner、调度与实际退出

唯一播放服务提供真实 Player clock 与 seek、切曲、速度、暂停/继续、结束事件；独立服务事件流不接收 UI 时钟转发。原有 LyricsEntry 业务 owner 移到 application ViewModelStore，UI presentation 与认证 external read lease 分开，复用同一授权加载器、translation 和 8 MiB reservation。隐藏页面停止 UI sampler；最后一个有效阅读/展示关闭才停止业务订阅。没有第二个解析器、缓存、播放器、整库读取或隐藏页轮询。

输出使用已保存来源和偏移，不发送选择器结果、未确认关联或显示偏移草稿。来源/曲目/seek epoch 改变时替换为当前清空/加载状态，迟到读取和帧再核验 owner。仅调度下一真实行边界；暂停发送一次当前暂停行后取消行定时，继续读取实际时钟，结束清歌词。

单个 conflated 待发 snapshot 和单个接收 snapshot；文本写入最多每 200ms 一次。无文本写入三秒发送无文本 heartbeat，接收静默十秒关闭并清内容。连接/hello 各受十秒边界，监听和数字确认各最多两分钟，物理写两秒，认证会话最长六十分钟。native owner 至多两个物理任务和独立 closer；任务取消不释放仍阻塞的 native slot。

关闭同步撤销取权、清当前文本并取消比较等待。外部源另等待 exact retired lyrics Job 实际退出，transport 等待实际 socket/worker 退出；分别有 2.5 秒等待边界。等待失败保留原失败与对象，阻止替换，明确重试退休，不能把 timeout 当资源已释放。播放服务 quiesce 等待两者后才报告释放；UI 仍可单独持有自己的正常本地阅读。比较阶段 ACL 断连由 native close 信号立即取消等待，不持续留存旧数字直至两分钟超时。

发送器的服务观察器与下一行边界任务纳入这次外部阅读独立的子 Job。关闭同步取消整个子树，awaitClosed 先等待共享读取的精确退休，再在 finally 等待输出子树实际完成；上层原有 2.5 秒退休边界仍约束两者。共享可见歌词页保持自己的读取，不以页面仍健康或 Job 已收到 cancel 作为输出退出的证据。

## 5. 迁移与备份

player preferences 升至 schema 6，唯一新字段 bluetoothLyricsEnabled 默认 false。schema 1–5 读取保留原字段和 revision，新字段默认关闭；成功明确保存才迁移。schema 5 Help 六字段不重置。通用设置页的 draft 保存/恢复/取消保全该 Boolean；runtime 设备/认证/接收能力没有对应恢复字段。

LP-11 portable backup schema 1 不改变，外部输出 activation 不在导出字段白名单；恢复明确为 false，不恢复配对地址、系统 grant、nonce、数字、密钥、socket 或接收能力。恢复仍使用既有 application barrier、唯一服务关闭与原七个配置 owner，没有第二个恢复屏障。备份/恢复和账号退出不影响本地音乐原件、库与关系。

## 6. 回归定义及待取证

新增定义覆盖真实 Wire pair 编解码、commit/reveal、确认前无文本、sequence/epoch 重放、header/ciphertext 篡改、编码和限额、关闭；偏好旧 schema/Help/真实文件 reopen/CAS/失败重试与 backup 排除；原 Entry 的隐藏/离页/共享文档/保存偏移/预览隔离/非协作退出；实际服务 facade 时钟与 UI 事件隔离；会话隐藏、断连、超时和原退休失败。定义尚未云编译或执行，不记为测试通过。

BluetoothLyricsSenderTest 补充真实 LocalLyricsParser、LyricsEntryViewModel、ServiceLyricsPlaybackPort 和两端 BluetoothLyricsWire 的组合定义：保存偏移下的原文/严格关联译文、seek 和切曲、隐藏页面只调度下一真实行边界、暂停取消定时、可见页面继续读取时等待输出子树退休、旧取消加载迟到及 UTF-8 长行截短。这些定义必须通过后续精确源码云运行；内存字节通路和受控 Player 不能替代两个 Android APK 的物理 RFCOMM 互通或原 AC。

| 原 AC / 验收域 | 必须取得的精确证据 | 当前状态 |
| --- | --- | --- |
| AC-084-N | 同快照 watch + companion APK，受控 Classic Bluetooth/系统配对，LRC/同时间原文及关联翻译在播放、seek、切曲对应；分别记录 MediaPlayer/Media3，正常媒体标题正确 | NOT_RUN |
| AC-084-E | 无对端/错协议/拒绝/关闭、断连与明确重连、撤权、过期、旧帧和实际关闭失败不串旧文本、不自动连接；至少一个正常兼容组合先成立 | NOT_RUN |
| 设置与 LP-11 | 保存/取消/默认、ack/IO/冲突，schema 1–5 与 Help 保全，恢复后外部会话关闭且 activation=false | NOT_RUN |
| 14/15/16 | 真实两端 API/ABI/窗口/inset/输入；圆/矩形和大字的可读及 48dp 控制；歌词预算、有限任务、后台 UI 停止、收发时延/功耗/CPU/PSS与实际退出 trace | NOT_RUN |
| 候选/发布 | source/build/snapshot SHA、run/attempt/artifact、两个 APK hash/certificate/identity、协议 schema、互通报告及独立所有者批准 | NOT_RUN |

以上毫秒/字节/任务数是实现边界，不是测得的时延、续航或设备支持结论。只将文本/XML/hash/差分检查记为源提案检查；规划运行、跳过 Android job 或单端构建不能完成双端互通或原 AC。
