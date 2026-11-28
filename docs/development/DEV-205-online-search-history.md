# DEV-205 在线搜索历史

根据 02 的在线搜索“历史/清空”范围及 03 的账号隔离契约，补齐 NetEase 搜索页的本机最近搜索、回选与清理。历史回选仅填入草稿；实际查询由明确搜索动作开始。清空输入、清空搜索历史、重试已接受的清理与清空损坏历史分别命名。历史清理不会访问本地音频、收藏、歌单、播放历史或活动队列。

历史使用应用私有 `noBackupFilesDir` 内的单个 AtomicFile，最多 8 个去重搜索词，每词沿 `OnlineCatalogQuery` 的 256 字符及控制字符规则，单份文件读取上限 16 KiB。查询文本仅留本机，不进入日志、诊断、自动备份或 LP-11 显式本地播放器备份。上述数值是初始资源边界，尚无设备性能测量。

账号归属由 auth 层的持久 opaque lineage 决定。公共 `SessionRepository.commit` 为每次新登录创建 lineage；真正的 `revalidate` 使用 private 提交入口延续捕获的恢复会话 lineage，仍保留账号核验、active/generation/acceptance/timeout/logout 的原排序。用于 seal/write 的同一 prepared session 进入 active/snapshot，历史层不读取凭证。`SessionCipher` 保持 GCM envelope 与 AAD v1，内层记录 v2 保存 lineage；读取旧内层 v1 时，仅在 GCM 认证及原记录验证成功后，从明确 domain 和原密文确定性派生 opaque lineage，重复离线恢复稳定，后续保存写 v2。

历史文件只保存账号 hash、lineage 和有界查询列表。新 QR 登录、同账号重新登录及密文存储重建均按真实 lineage 隔离；旧清理失败留下的文件不能被新会话读取或重新绑定。迟到界面动作同时检查账号 generation、页面意图、视图对象和当前文件结果。损坏或超限文件保留，提供明确损坏清理；已修复文件拒绝旧损坏动作。

已开始但部分失败的普通清理保留一个有界拥有者，记录原 lineage、store mutation epoch 和 exact base/.bak/.new 残留事实。原残留减少或故障目录解除障碍后，可直接重试或重读后明确重试；新文件、新写入及新 lineage 拒绝旧能力。清理事实以 NOFOLLOW 判定存在，替换后出现的悬空链接会在 AtomicFile.delete 之前被拒绝。合法空历史、读取失败和部分清理失败分别呈现。搜索历史错误独立于正常目录查询；目录查询继续沿既有认证与真实分页路径运行。

一个物理文件操作最多 2 个等待调用者，相同退出 lineage 最多 2 个合并等待者；准入初值 2 秒。退出先做 memory-only 退休，再等待凭证及歌词/历史实际清理。已开始的 AtomicFile 写入/清理持有原拥有者直至实际退休，准入超时不表示系统调用已被物理中断。

历史行复用既有 Foundation/WatchContentRow 和主题字体，以两行省略限制长词。新增中英资源进入主题字体覆盖列表，动态字体语料仅采集可见历史键对应的词。Apple Music/MeloXWatch 视觉参考及复用锁保持原固定 SHA。

四个测试类共 28 个回归定义：`OnlineSearchHistoryTest` 12、`OnlineViewModelSearchHistoryTest` 9、`WatchSearchHistoryControlsTest` 3、`SessionLineageTest` 4。定义覆盖真实 AtomicFile、GCM 旧记录迁移、真实 EAPI revalidate/QR、同账号新登录、清理失败后的密文重建、普通部分清理恢复与新文件拒绝、native Room 目录请求、隐藏取消、双语可见字体及受限圆屏/方屏 Compose 控件。受限宿主控件检查与 Main 窗口/键盘/边界及真实手表验收分别记录。

PR #50 的首轮 `46166183461/1` 因菜单缺少 `padding` 导入停止，后继 `46172652851/1` 已通过生产编译，手机 12 份正式 XML 的 134 项测试全部通过。后继整轮 353 秒、compile 329 秒；手表测试编译唯一错误为 `WatchSearchHistoryControlsTest` 导入不存在的顶层 `assertDoesNotExist`。该断言是 `SemanticsNodeInteraction` 成员，删除导入并保留断言正文。真实 merge build `4dbc869a33fdb9ec7625586464a5d3d312ea535b` 的 tree 与 source `a1b2aa91e644205bab8ba60821c5b6a9eb89047c` 相同，parents 为 [contribution, source]；原日志和 reports ZIP 各收取一次，未解压或复制源码。

手表没有正式 JUnit XML，28 个新增回归定义仍未运行；lint、双 APK、设备及完整验收继续未完成。全部 128 本地必需能力及 256 原 AC、既定在线范围、Meting、手机伴侣及双端互操作验收保持完整；readiness 仍 false，candidate 仍为空。
