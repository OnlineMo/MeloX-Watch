# LP-01～03 文件接入、音乐库与聆听记录

本主题在DEV-104同一Android工程内建设LP-01～03，并集成LP-00设置、LP-04完整列表播放与LP-12列表外观的部分行为。用户已恢复并委托中间开发与审阅；首个预发布版仍包含128项本地需求、256个原AC、网易云、默认关闭且由用户配置的Meting和真实Android手机伴侣。当前是实现中，云端在应用测试开始前编译失败，尚无本主题应用测试或设备AC通过证据。

## 数据身份、迁移与完整列表

`MeloXApplication`共享一个`melox.db`和一个物理provider IO通道。Room从v1的三张队列表显式迁移到v2，新增27张实际使用的来源、授权、位置/版本、曲目/关系、扫描、视图、导入和聆听表及44个索引；不重建队列表，不使用破坏性fallback。KSP schema必须取自完整云端资料，尚未手写或导入失败运行的schema。CI按实际数据库版本要求已提交的schema1及schema2，真实1→2迁移fixture等待可审阅的schema1基线。

TrackKey、位置、来源对象、授权与每次入队实例分开；同名不是实体证据。扫描先提交本代次已见对象，再提交解析成功或错误，保留首次加入时间和稳定插入序。来源/授权/规则/代次在每次写入重核；只有完整成功范围才能激活分批准备的缺失结论。取消、撤权、坏文件、排除和部分子树失败不会清空旧库或用户关系。旧库先打开，私有副本恢复作为独立可重试工作，失败不阻止已有可用音乐播放。

曲目、专辑、艺人、文件夹、搜索、最近添加、历史和统计使用数据库真实分页。视图冻结revision、查询、排序和统计epoch，按完整键分页及数据库锚点定位；UI不加载全库，完整队列没有10000条上限。服务把UI视图转换为独立QUEUE lease，按100条生产完整队列，最后CAS提交；离开页面不会使已接受的完整队列只剩当前页。后端只取当前及两个相邻项，当前先核验、取消后不继续邻居读取。

完整队列封存后从暂存行取所选实例及相邻窗口，在事务外核验当前资源；缺权、内容未知/变化和取消保留旧队列/后端/checkpoint。发布后复用同一核验窗口，旧行回收移至下次initialize/replace构造前，历史operation只回读receipt。已经接受的新队列发生解码/输出错误时保留完整行并安全暂停；装载失败清除旧backend窗口，显式重试按新活动指针读取，不能凭相同mediaId复播旧URI。

## 接入、预览、扫描与副本

MediaStore、单文档、授权树和应用不可变副本具有实际读取路径；选择器入口先查真实处理组件，缺失时说明可用媒体库或外部传入路径。外部`ACTION_VIEW`/`ACTION_SEND`仅安静预览，进程/Activity恢复不自动重放操作。保留授权、添加引用、重新授权、显式复制和播放分别由用户动作触发。

预览在元数据读取前后重查真实对象、URI、名称、MIME及大小；有可靠provider token时同时核验token。弱来源在预算内完整SHA读取前后相同才保留内容证据，超过4MiB或未读到完整EOF不伪造验证。保留授权、添加引用、重授和显式播放重新核验旧预览并重读元数据。新选择只保留一个最新等待请求，先等待已取消的物理读取真正结束；旧请求和离页回调不能重新发布旧选择。

单项preview命令未携带完整内容证据时，服务重查实际单文档/树/媒体/自有范围及持久权，并真正open/read后才允许发布；不把本次可访问声明为旧字节已验证。来源/预览/库保留最多4096字符的完整标题；单项IPC按2048 UTF-16预算传完整字符簇摘要，超预算单簇用未知标题，曲目/位置身份不变，完整元数据不截短。系统通知以独立显式Intent进入播放页，普通启动/外部音频入口与保存状态恢复不会触发历史播放动作。

全局物理IO并发1，枚举/数据库提交批100，阻塞provider取消时显示正在停止并保留占用。扫描记录持久目录frontier、ACK、规则和完整范围；后台安全批边界暂停，重新开始采用新代次。MediaStore真实版本/generation可靠时可增量，UI默认受控完整刷新；不在进页时自动扫描全库。播放与新派生读取核验已保存内容版本；弱大文件无法证明时明确给出`CONTENT_UNKNOWN`及副本路径。

复制独立配额1GiB、剩余空间预留512MiB、缓冲64KiB；未知长度逐块校验，临时文件/摘要/操作日志与受控提交保持一致。校验实际音频、原子安装、索引提交后才发布应用自有URI。取消、低空间、重开恢复和重试保留原文件/旧副本，清理只针对本次半成品。应用自有provider只提供已完成不可变对象的读取。

## 元数据、排序、封面与页面

受控解析本地格式的文本标签，保留格式定义的多值艺人、原字段与解析规则版本；缺字段在显示层回退，不写回原标签。标题/艺人/专辑/时长/首次加入/文件名自然序/碟曲号/播放次数/聆听时长九类排序按七个列表上下文分别保存。排序v1的NFC、Locale.ROOT、Unicode码点、英文/数字/汉字/其他/未知分段及自然数字规则在17冻结；不宣称拼音排序。最近添加窗口30天，重扫不重置首次加入。

短音频默认不过滤；启用后严格小于60000ms隐藏，等于阈值及未知时长保留。排除规则按实际来源节点/子树匹配、可见可移除，规则变更使旧代次失效。它们不删除文件或历史关系。

封面可选系统缩略图或受控标签提取，内存8MiB、磁盘32MiB LRU，输入16MiB/16MP，输出长边≤2048px。一个物理worker、等待≤8、显示lease≤32。冷读与刷新核验→读取/解码→再核验后才写缓存；变化不会把新图存到旧键，旧版本已缓存图可继续显示。刷新冻结当前详情/实际可见非空范围≤32项，明确确认、进度和取消；离页/后台停止新读取，播放不等待封面。

Library、Sources、Listening页面和中英资源已接入。列表/网格偏好独立保存，两列须满足实际文字测量和48dp触区，否则显示单列后备。页面只在可见时观察、查询和刷新封面；错误保留一致旧数据并限制旧数据动作，明确重载和授权恢复入口。返回关闭详情lease，观察revision防止旧页回调覆盖新页。

## 唯一服务的聆听记录

`ServiceListeningObserver`消费唯一活动播放器的实际推进。单调时间累计真实聆听，倍速只用来核验媒体推进；准备、暂停、缓冲、seek、未知跳变和失败不累计。每个自然轮次达到`min(30000ms, max(1ms, 已知正曲长/2))`计一次，未知时长用30000ms。实际推进首次才写历史，不以选歌或准备状态代替开始。

每秒仅在实际播放时采样，状态变化和5秒边界合并写入；间隔超过5秒不猜补时间。轮次ACK、去重、日期分段、总/逐曲/逐日聚合同事务；未ACK段≤100、关闭待写轮次≤8及单个待定选曲。迟到ACK保留之后的新推进，自动切歌和detach捕获旧轮次尾段，最终写入不再访问已释放播放器。1500ms观察器收尾预算的单元测试不等于完整Service销毁或设备长时证据。

历史最近1000条明细与总量分开，清历史与清统计分别确认并递增独立epoch，旧轮次不能恢复已清结果。今日按当前ISO日期汇总播放时保存的同日期分段，跨记录时区合计而不重分过去。查询返回实际统计epoch；页面仅可见时观察摘要，并每分钟检测日期/时区变化，单个job合并请求，返回重核日期/时区/epoch；旧查询不能覆盖新的摘要，错误不填假零。

## 验证与未完成证据

当前24个测试文件定义214个应用`@Test`：沿用并扩展P1存储/会话/协议，新增实际Room库及聆听、真实provider授权/替换、复制恢复、扫描代次/部分失败、真实图像缓存与物理IO取消、标签与完整摘要、有效聆听及服务观察器回归。本续轮还覆盖sealed队列的forward-only生产者、事务外预检/竞争CAS、真实provider拒绝/同大小替换/不合作取消、当前优先与有界邻项、历史receipt、提交后暂停/输出错误及旧URI清除，实际SourcesViewModel预览/取消/重授/长标题和通知Intent/Unicode边界。这是测试定义数量；首轮云编译在这些测试执行前失败，没有JUnit/lint/APK通过证据，本地没有运行Gradle、Kotlin/Java编译或Android测试。文本审查及同伴审阅不算AC通过。

PR #29的[run `43743042161`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43743042161)/attempt1绑定source `162c24d9ccf23ea2796608821ed1bfc4ee018011`、实际checkout `0b7c00d9f06ca832180fc346652935676236c2d5`、compile job `132900247397`。verification阶段3分02秒失败，39项任务中36执行/3缓存；Debug/Diagnostic均报告`LocalImportManager`引用非公共`OsConstants.O_DIRECTORY`及`LibraryRecords`缺少`ScanMode`导入。修正使用公共`O_RDONLY | O_NOFOLLOW`，打开后以`S_ISDIR(Os.fstat(fd).st_mode)`核验同一descriptor再fsync/close，保持防止目录符号链接替换的要求；并补齐类型导入。公共API26声明已按AOSP固定提交`97e5130234bcdc1c4a705be4e548302934499bd4`独立核对，签名文件SHA-256 `f37327b7713d170a7c59d8c1d2ac7ed6ad2b90f5cb4ca92ec6362a2fa0d31c5b`。修正没有本地编译，下一轮云结果才可确认。

该轮artifact `14173720904`为59635字节，原ZIP SHA-256 `98f485bd62b3ccb46269c068674baef50a906e360e60da57c9e115644a8bbe2d`，日志SHA-256 `d7b32f22aecbb50891661b6a4a4a68d504d89ffd0f20470850d8ae3bc007efde`。生成schema2为91327字节/30张表，SHA-256 `bab895d032d46556e4615a44130dc523e8942e942cd34ab68832969ecc7696bf`；没有完整锁/schema1，仍属`failed-partial`且不导入基线。

下一轮[run `43745865130`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43745865130)/attempt1绑定source `ef7ffc56b6e36c533a51e8a3bb0d65aab06494ab`、实际checkout `e25c1991861ea175ddac23066b50fcfd6f85a9f5`、compile job `132908948059`。verification阶段3分24秒失败，44项任务中39执行/5缓存；前两处错误已越过，`OwnedAudioProvider`第47行的String/Long/null `.map(...).toTypedArray()`被Kotlin判为reified交集类型推断警告，`-Werror`拒绝编译。修正显式声明`List<Any?>`，直接使用`MatrixCursor.addRow(Iterable)`；不关闭警告、不改变provider列语义，后续生产代码编译结果见下轮记录。

artifact `14174073206`为59631字节，ZIP SHA-256 `a219f26863a903ad3819055494ff24435fb2008b0112e8f9f6cf6c5fe8b844c7`；日志SHA-256 `d3d69c45bafcbd3b7f34e7b2b46e3aae1e0ecbe8d509905bc37a19964bfec785`。normal及三个STRICT阶段全未执行，日志无UnitTest task、归档无JUnit XML/lint XML/APK；三变体预期各214项不能计为执行或通过。7条graph records为观测，导出仅metadata XML与同hash的schema2，没有完整lock或genuine schema1，仍为`failed-partial`诊断资料。匿名精确绑定汇总SHA-256 `c785eb80051de3733658e196c1defcad3e913cace89f4e862a727591c6e66286`，phase evidence SHA-256 `5f92305c89f32b514da4502ac765532ed1f612bfaa2a68729dfa9cdbd34fe2a5`，unit evidence SHA-256 `452760ee304d52605918d23afafa85f38dbe2b0f20d77ef4af732f24f4f9fe68`。

[run `43749223127`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43749223127)/attempt1绑定source `7ffb9762f5701e2f14ec4d59d8d0bfc98f6c50c3`、实际checkout `a6aef5b1ef8c94ff91a50049e6401fa6b3eebe51`、compile job `132919270285`。生产Debug/Diagnostic Kotlin编译已越过修正点；verification在Debug/Diagnostic的UnitTestKotlin编译失败，4分19秒、53项任务（48执行/5缓存）。`FixtureAudioProvider.kt:69:15`和`FixtureMediaProvider.kt:56:15`的混合String/Long/null数组触发相同reified交集类型推断警告；夹具已改为显式`List<Any?>`和`MatrixCursor.addRow(Iterable)`，保持列顺序、类型/空值及文件不存在时空cursor，等待云端重验。

artifact `14175737555`为59632字节、ZIP SHA-256 `f26bf4b752f9dc7bc748d7330e913784f12d69afd2814fd49dd1126f1157d6eb`，日志SHA-256 `c784c41ba50beea6b4c1da148fb269d4fbdf43107bdf87e0d7f039369d4f896b`。normal与三个STRICT阶段全未执行，无UnitTest运行/JUnit XML/lint XML/APK；三变体预期各214仍只是定义数量。7条graph records为观测，metadata XML和schema2是诊断资料，schema2仍91327字节及上述hash，缺完整lock/schema1。匿名汇总SHA-256 `978bd6fd3fe3f1fd377446285aac24e494d1db1b4671ba9184dd0ea0a52789ba`，phase SHA-256 `17e0e18c2ef09cb31ce34c926402fd3af5117238ad3d1a7e77ae462058ea50c9`，unit SHA-256 `a7f1457e9f529f8d35939c7228f07dcd95be6e06ee0780409f200d43e432ebfe`。后续LP-04/05并行代码不在这些小修复快照中，未改变冻结214数量。

合并P1已云验修正与P0被动图诊断后，本地49项Python CI数据/政策用例、Node政策场景和diff空白检查通过，JSON/catalog/manifest可解析，全部381个中英string key相等无重复，匿名源摘要不变。检查未执行应用或研究快照；214项应用测试仍须云端结果，设备隐私扫描只报告位置/类别，未发现待上传内容包含本地敏感值。

新增API30 provider版本/generation测试使用固定`org.robolectric:android-all-instrumented:11-robolectric-6757853-i7`，沿用API26 runtime。两个SDK独立非传递配置解析并由Gradle锁/SHA校验后复制到同一offline目录，避免同一配置的版本冲突只留下一个SDK；`enabledSdks`明确包含26和30，不能跳过API30用例当通过。官方Robolectric4.16.1 source JAR SHA-256 `b354b256648453c5e83eb4ec19d80f23a6d8775e7684c93d5e3c0f327ba8d289` 的 `DefaultSdkProvider.java` 固定API30映射11/6757853、预插桩7及所需Java9；当前Temurin17符合该门槛。官方Maven Central [POM](https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/11-robolectric-6757853-i7/android-all-instrumented-11-robolectric-6757853-i7.pom) 为1390字节、SHA-256 `579070875a6a33dd57d2937929fa443789e28826cb12e8e716197c222e199682`，声明Apache-2.0；实际SDK JAR和传递制品仍须完整云端资料审阅，本地未运行SDK。

P0严格依赖锁仍有实际consumer graph差异；P1的保留原事务错误修正已使真实SQLiteFullException和旧队列/重开/重试测试通过，源码与云证据见DEV-104。Library中央事务入口已使用同一包装，后续集成最新队列修正；仍需完整五阶段XML/锁/schema1，再验证云端schema2、真实1→2迁移和三变体构建/测试/lint。失败资料不导入基线，不以NO-SOURCE或阶段未执行填通过。

用户新授权的adb测试虚拟机已只读观测：API32，主ABI x86_64，物理466×466、density310，系统报告矩形；root可用于测试控制，不替代普通应用权限或设备支持。只读证据SHA-256为`3845c34c1fb39bf91d5bc321dcebb3aa7f5ef8026c1f3126f917fe71ac4fcdad`，实际音频、输入、持久权和后台能力未测。待有合格云端APK后按source/build/run/attempt/artifact/APK hash绑定安装与测试；不使用本地替代APK、不卸载擦除已有数据。交付前再使用Watch X2真表完成对应14/15/06及全部AC，虚拟机结果不作为真表性能或音频支持结论。

圆屏优先，先验圆边的热区/首尾内容/长文/最大字，再补方屏。用户允许临时调整测试VM分辨率；记录原size/density/override并测试后恢复。原始设备信息、IP、adb端点、个人路径及其他隐私只留ignored本地或不留，禁止进入Git、Issue/PR、云日志或artifact；共享结果必须实际脱敏，不因仓库私有而上传。

LP-04完整增删重排/点击策略、LP-05双后端与解码、LP-06音效、LP-07/08歌词和输入、LP-09歌单/交换、LP-10真实文件写入、LP-11备份、LP-12其余主题/字体、LP-13/14传输和手机端、LP-15帮助/更新及网易云/Meting仍继续建设。手机APK构建/签名/候选集合须通过Actions Issue维护，当前没有把文档要求算成已实现。所有readiness仍false，candidate=null，256个原AC未通过，未申请最终公开/发布批准。
