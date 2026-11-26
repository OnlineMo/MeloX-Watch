# LP-07 本地曲目歌词与封面查找

根据[本地合同](../17-本地音乐功能与实施计划.md)和[完整原需求](../requirements/local-player-F001-F128.md)的 F-070/F-071，采用固定网易云 EAPI 通路，实现独立于账号的显式查找、预览、确认关联与清除。当前是源码及回归定义；新的云端编译、测试、lint、schema 9 导出、双端 APK、设备及原 AC 尚未完成，不继承旧产物的验证结果。

## 请求与界面

入口位于当前本地曲目的播放详情和歌词选项。读取当前完整逻辑队列的精确实例，不截取当前列表页；无当前本地曲目时说明状态并保留返回。实际 PageBinding 准入才启动读取，窗口/路由退休同步撤销请求和确认票据。

player preferences schema 7 的 `metadataLookupEnabled` 默认关闭，schema 1～6 的迁移及损坏字段均为关闭。开启只保存设置；进入页面、显示本地封面、播放、恢复、注销或清缓存均不发起查找。每次点按“搜索”或“更多候选”重新核对勾选的曲名/艺人，候选预览是另一个明确动作。

查询只包含勾选的原始标题/艺人或手动填写的替代文字，不以文件名、显示标题、URI、路径或稳定曲目 ID 补齐。路径、地址、邮箱、私有标识形态及控制字符不能选作发送字段。候选仅保留当前 20 条，下一页明确请求并替换当前页；实际 total、offset、重复 ID 与不完整页均校验。没有全库查询或自动追加请求。

`NeteaseMetadataClientOwner` 延迟建立独立匿名 EAPI transport，不读取 SessionStore、不调用 `onlineMusic()`，不携带账号 MUSIC_U/MUSIC_A。EAPI 既有随机协议 nonce 与固定软件字段保留，实际设备标识和窗口信息不填入协议。封面仅走 HTTPS 原生音乐 CDN，逐跳核对 origin，最多三次跳转，详情和图片共用八秒总预算；图片 GET 不带 Cookie/鉴权，不重试或隐式跳转。临时服务 URL 只在当前调用的内存中，既不存数据库，也不进 UI、备份或诊断。

界面沿现有 MeloX 自绘整行控件、字体和圆屏安全滚动容器。当前曲目、所选发送字段、候选来源、分页歌词或已解码图像、确认/取消及清除均可见。真实 lookup target 是否就绪单独表示，不能用错误名称推断可搜索；清除自己的关联使用独立逻辑票据，音频读权失效时仍可执行。

## 预览与事务

搜索与预览均不写关联。歌词使用原解析器和全应用唯一 `LyricsMemoryOwner`；分页只保存原行偏移，每次构造不超过 2,048 个 UTF-16 单元的文本，保留完整字符和换行。原始歌词字节、解析对象、页索引、确认副本与校验缓冲共同服从八 MiB 预算；标准 LRC 时间和真实词时间继续由原解析契约决定，不生成推算字时间。

封面响应上限一 MiB，复用既有原生图片 GET 预算；确认和备份文件仍受原生单图 16 MiB/16 MP 校验上限。预览必须从实际字节解码为共享 `ThemeBitmapOwner` 的 lease，不能用 URL 或 opaque key 代替已显示图像。确认安装前再次校验实际图片，失败保留原关联。

确认票据绑定当前 queue generation/revision/entry、来源授权和内容版本、TrackVersion、素材关联 revision、歌词偏好 revision、查询/预览意图及内容摘要。确认先复核真实音频读取，普通文件与 CUE 完整读取分开；然后安装不可变私有资产，最后在短 Room 事务中同时提交来源记录与有效关联。DML 后再查精确选择和来源，迟到动作抛出并回滚整个事务。

一条 collectLatest worker 等待前一 transport/provider 的实际退休，再处理单个最新待办。关闭、离页、切歌或新意图立即退休 UI 与写票据，物理工作继续持有自己的资源直至真正结束。草稿只进本机 SavedState；候选、URL、读取权限、预览和确认均不跨重建重放。确认丢应答后只能重新读取实际状态，不能自动重复写入。

## 资产、清除与迁移

Room schema 9 只新增 `local_metadata_associations`：以本地 TrackKey 与 purpose 为键，保存 schema/revision、私有 asset UUID、SHA-256、实际字节和严格 provenance。现有 schema 1～8、原字段和迁移链保留。新增 8→9 迁移分别覆盖 Bundled 与 Support 入口；真实 9.json 仅由现有云端采集获得，禁止手造或本地生成。新迁移回归使用原追踪 8.json 的完全相同字节和摘要作为历史基线。

歌词使用原 `LyricsDocumentCopies` 和私有只读 provider；匿名接口已将歌词规范编码为 UTF-8，新建 primary 明确记录 `UTF8`，与备份关系校验保持一致。封面保存在 `noBackupFilesDir/local-metadata-artwork-v1`，使用随机 UUID、no-follow、完整摘要、fsync 与原子安装；独立资产配额 32 MiB，保留既有 512 MiB 剩余空间。确认资产与可重建的封面 LRU 分开，清缓存保留关联及资产。前台封面、队列和背景均通过原 `LocalArtworkRepository.derived` 和共享图像预算读取已确认封面，不因显示触发 HTTP。

清除保留递增 revision 墓碑，旧预览不能通过 ABA 回写。歌词只清仍精确指向该素材的 `metadata-v1` primary，后来的手动选择、译文与偏移保留；封面清除后返回原缩略图/内嵌路径。来源撤权不阻止清自己的关联，也不授予原件写删权限。回收在原 reference gate 下核对原始引用，损坏/未知 owner 保守阻止删除；全局损坏检查每轮一次，资产引用走索引，避免逐文件全表扫描。

备份没有资源时可保留来源/摘要但 asset 为 null，界面明确为“素材尚未恢复”，不是可用素材。正常确认必须产生实际 owned UUID；损坏记录或文件读取失败不声明关联已可显示。

## 来源与备份

协议窄复用来源为 `youshen2/MeloX` 的现用 SHA `09713edb847a0f0a3515a34f779cc630d4fe5985`，沿 `.ci/upstream-lock.json` 和既有 EAPI 源码许可说明，不更新研究锁、不执行上游快照、不新增依赖。代码的 GPL-3.0 与音乐/封面素材的使用权分别记录。provenance 明确标注网易云、远端 song ID、曲目标签与来源条款提示；可访问 URL 不构成再分发许可。真实服务可用性和具有使用许可的正常素材仍须单独验收，合成 PNG/文本不提供该证明。

LP-11 逻辑 ZIP v1 增加 METADATA_LYRICS 与 METADATA_ARTWORK 类型，分别属于 LYRICS 和 LIBRARY。资源可随 LIBRARY、PREFERENCES 或 LYRICS 选择；只导出实际选中资产、内容摘要、provenance 与归档资源 ID，不导出原私有 UUID、服务 URL、查询草稿或 activation 开关。完整标志对 schema 9 严格包含新类型计数，旧 schema 1～8 的缺省零兼容保留。

联网 primary 与素材的有效关系通过精确 `primaryLinked` flag 记录，不依据相同内容猜测原选择。恢复仅在 incoming 歌词及素材行都实际应用、资源确认并一致时重建 `metadata-v1` marker；MERGE 保留当前手动歌词时不改 marker。内容相同但原 UUID 不同的手动选择不能在资源去重后被误清。私有 restore journal 的 1→2 迁移先严格验证旧 SQL/schema/回执，在原独占锁和事务中仅扩展素材 owner 范围，保存原回执后更新版本。

## 验证边界

新定义覆盖匿名真实 EAPI 解析/凭据隔离，字段拒绝与完整 Unicode 分页，默认关闭及旧偏好迁移，预览未确认零写入、取消实际退休、切歌/重复实例、未知确认结果重读，真实 Room 关联/primary 同事务与晚取消回滚，私有图片解码/配额/链接拒绝/缓存清理，来源撤权清除，以及资源备份往返、关系保留、CUE 冲突、旧 journal 迁移和损坏保守回收。原迁移回归按新的当前 destination 延伸至 9，历史 Support 目标、全部旧 cell/索引及失败回滚基线保留。

文本/XML/摘要和非作者静审只验证源码。云端以冻结源码编译、测试、lint 并取得真实 schema/双包后，设备验证绑定精确 APK 与来源；虚拟机先验证功能和圆方布局，交付前仍需真实手表、手机与全部原 AC。性能、真实接口/素材使用权、设备正常路径和最终发布批准继续分别记账。
