# LP-01～03 文件接入、音乐库与聆听记录

本主题在DEV-104同一Android工程内建设LP-01～03，并集成LP-00设置、LP-04完整列表播放与LP-12列表外观的部分行为。用户已恢复并委托中间开发与审阅；首个预发布版仍包含128项本地需求、256个原AC、网易云、默认关闭且由用户配置的Meting和真实Android手机伴侣。当前是实现中；五阶段生成输入已独立审查并按原字节导入，随后普通严格PR运行的Debug226项、lint及原APK核验通过。新增真实seeded数据库升级回归首轮在云端测试编译失败，已修正可空数据库路径，运行与新快照验证仍待完成；稳定签名覆盖升级、设备行为与256个原AC仍未验收。

## 数据身份、迁移与完整列表

`MeloXApplication`共享一个`melox.db`和一个物理provider IO通道。Room从v1的三张队列表显式迁移到v2，新增27张实际使用的来源、授权、位置/版本、曲目/关系、扫描、视图、导入和聆听表及44个索引；不重建队列表，不使用破坏性fallback。schema1及schema2现取自完整五阶段云端原资料，经字段、约束与DDL独审后导入；失败的部分资料仍不作基线。CI按实际数据库版本要求两份schema；新增回归从原schema1字节创建真实旧SQLite文件，使用生产`MeloXDatabase.open`迁移、核验旧队列并写入/重开新库数据，尚未执行；设备覆盖升级另验。

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

以下记录早期24个测试文件/218个应用`@Test`阶段（原214项保留，新增4项游标生命周期回归）：沿用并扩展P1存储/会话/协议，新增实际Room库及聆听、真实provider授权/替换、复制恢复、扫描代次/部分失败、真实图像缓存与物理IO取消、标签与完整摘要、有效聆听及服务观察器回归。本续轮还覆盖sealed队列的forward-only生产者、事务外预检/竞争CAS、真实provider拒绝/同大小替换/不合作取消、当前优先与有界邻项、历史receipt、提交后暂停/输出错误及旧URI清除，实际SourcesViewModel预览/取消/重授/长标题和通知Intent/Unicode边界。该阶段首轮云编译在测试执行前失败；后续真实结果逐轮记录，最新226项普通严格结果及新增第227项迁移定义见文末。本地没有运行Gradle、Kotlin/Java编译、Android测试或迁移SQL，文本审查及同伴审阅不算AC通过。

PR #29的[run `43743042161`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43743042161)/attempt1绑定source `162c24d9ccf23ea2796608821ed1bfc4ee018011`、实际checkout `0b7c00d9f06ca832180fc346652935676236c2d5`、compile job `132900247397`。verification阶段3分02秒失败，39项任务中36执行/3缓存；Debug/Diagnostic均报告`LocalImportManager`引用非公共`OsConstants.O_DIRECTORY`及`LibraryRecords`缺少`ScanMode`导入。修正使用公共`O_RDONLY | O_NOFOLLOW`，打开后以`S_ISDIR(Os.fstat(fd).st_mode)`核验同一descriptor再fsync/close，保持防止目录符号链接替换的要求；并补齐类型导入。公共API26声明已按AOSP固定提交`97e5130234bcdc1c4a705be4e548302934499bd4`独立核对，签名文件SHA-256 `f37327b7713d170a7c59d8c1d2ac7ed6ad2b90f5cb4ca92ec6362a2fa0d31c5b`。修正没有本地编译，下一轮云结果才可确认。

该轮artifact `14173720904`为59635字节，原ZIP SHA-256 `98f485bd62b3ccb46269c068674baef50a906e360e60da57c9e115644a8bbe2d`，日志SHA-256 `d7b32f22aecbb50891661b6a4a4a68d504d89ffd0f20470850d8ae3bc007efde`。生成schema2为91327字节/30张表，SHA-256 `bab895d032d46556e4615a44130dc523e8942e942cd34ab68832969ecc7696bf`；没有完整锁/schema1，仍属`failed-partial`且不导入基线。

下一轮[run `43745865130`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43745865130)/attempt1绑定source `ef7ffc56b6e36c533a51e8a3bb0d65aab06494ab`、实际checkout `e25c1991861ea175ddac23066b50fcfd6f85a9f5`、compile job `132908948059`。verification阶段3分24秒失败，44项任务中39执行/5缓存；前两处错误已越过，`OwnedAudioProvider`第47行的String/Long/null `.map(...).toTypedArray()`被Kotlin判为reified交集类型推断警告，`-Werror`拒绝编译。修正显式声明`List<Any?>`，直接使用`MatrixCursor.addRow(Iterable)`；不关闭警告、不改变provider列语义，后续生产代码编译结果见下轮记录。

artifact `14174073206`为59631字节，ZIP SHA-256 `a219f26863a903ad3819055494ff24435fb2008b0112e8f9f6cf6c5fe8b844c7`；日志SHA-256 `d3d69c45bafcbd3b7f34e7b2b46e3aae1e0ecbe8d509905bc37a19964bfec785`。normal及三个STRICT阶段全未执行，日志无UnitTest task、归档无JUnit XML/lint XML/APK；三变体预期各214项不能计为执行或通过。7条graph records为观测，导出仅metadata XML与同hash的schema2，没有完整lock或genuine schema1，仍为`failed-partial`诊断资料。匿名精确绑定汇总SHA-256 `c785eb80051de3733658e196c1defcad3e913cace89f4e862a727591c6e66286`，phase evidence SHA-256 `5f92305c89f32b514da4502ac765532ed1f612bfaa2a68729dfa9cdbd34fe2a5`，unit evidence SHA-256 `452760ee304d52605918d23afafa85f38dbe2b0f20d77ef4af732f24f4f9fe68`。

[run `43749223127`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43749223127)/attempt1绑定source `7ffb9762f5701e2f14ec4d59d8d0bfc98f6c50c3`、实际checkout `a6aef5b1ef8c94ff91a50049e6401fa6b3eebe51`、compile job `132919270285`。生产Debug/Diagnostic Kotlin编译已越过修正点；verification在Debug/Diagnostic的UnitTestKotlin编译失败，4分19秒、53项任务（48执行/5缓存）。`FixtureAudioProvider.kt:69:15`和`FixtureMediaProvider.kt:56:15`的混合String/Long/null数组触发相同reified交集类型推断警告；夹具已改为显式`List<Any?>`和`MatrixCursor.addRow(Iterable)`，保持列顺序、类型/空值及文件不存在时空cursor，等待云端重验。

artifact `14175737555`为59632字节、ZIP SHA-256 `f26bf4b752f9dc7bc748d7330e913784f12d69afd2814fd49dd1126f1157d6eb`，日志SHA-256 `c784c41ba50beea6b4c1da148fb269d4fbdf43107bdf87e0d7f039369d4f896b`。normal与三个STRICT阶段全未执行，无UnitTest运行/JUnit XML/lint XML/APK；三变体预期各214仍只是定义数量。7条graph records为观测，metadata XML和schema2是诊断资料，schema2仍91327字节及上述hash，缺完整lock/schema1。匿名汇总SHA-256 `978bd6fd3fe3f1fd377446285aac24e494d1db1b4671ba9184dd0ea0a52789ba`，phase SHA-256 `17e0e18c2ef09cb31ce34c926402fd3af5117238ad3d1a7e77ae462058ea50c9`，unit SHA-256 `a7f1457e9f529f8d35939c7228f07dcd95be6e06ee0780409f200d43e432ebfe`。后续LP-04/05并行代码不在这些小修复快照中，未改变冻结214数量。

[run `43750598070`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43750598070)/attempt1绑定source `abfccb282b51406736513ed23724647f54b6ec6c`、实际checkout `878ee8dbc225a3697d4d6909bdec33305fae7494`、compile job `132923523703`。Debug/Diagnostic生产和测试Kotlin编译通过之前的修正点；verification在真实 `testWatchAndroidDebugUnitTest`失败，7分19秒、71项任务（61执行/10缓存）。JUnit XML核验Debug214项实际执行、173通过/41失败/0error/0skip；Release/Diagnostic没有XML，不能记为执行。失败涉及来源/封面/来源页面、统计轮次、扫描和队列，未运行normal及三个STRICT阶段，没有lint XML/APK或签名。

artifact `14176716511`为178294字节，原ZIP SHA-256 `a9ebb34857dc00f65d5ed13fd476114c61cbc8e318fb69bab07f73331a65e1b4`，日志SHA-256 `cb6500b88504676fad76c49c11e749363ad66ba1218d77bc646e944be7806151`。phase证据SHA-256 `e152c72054fa5249717ecf70d31c32f286db87ddea6e3bb3505b0609985fac24`，unit证据SHA-256 `fc626f89fa7742d0ec5df1d608192db09949ca01668b7402d25bd32a36e6b3c0`，匿名失败分类SHA-256 `d47891a86590e5cb35f5b5f276238a3ba8797261fe869cf81640c98308df32a9`。仅verification尝试，7条bounded图记录、metadata XML及schema2是失败诊断；缺完整锁/schema1，不导入基线。原始日志与JUnit内容只在ignored本地留存。

本轮小修复只涉及四个文件，原214个测试定义保持：FixtureAudioProvider仅 `image/*`选择缩略图，其余typed读取委托平台音频路径；统计测试的35字符UUID改为合法36字符，不放松生产轮次身份校验。规则/来源变化已经阻断扫描写入时，取消仍停止自己持有的job并等待物理IO释放，只容忍STOPPING写入的SOURCE_CHANGED，其他存储失败仍传播。provider内容在提取期间变化保留旧metadata但标访问UNAVAILABLE；修正对应测试断言，普通METADATA_UNREADABLE仍可保持AVAILABLE。现有实际provider/扫描/聆听测试将云端重跑，修复未先行记为通过，LP-04/05并行代码仍不在这一快照。

[run `43752940907`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43752940907)/attempt1绑定上述四文件修复source `f57fc7e6f81d5e45123f28a9aa883fee172fe131`、实际checkout `05a5f3065560c9cfe51e277148fec30623e2a952`、compile job `132930754524`。verification在Debug/Diagnostic测试Kotlin编译失败，5分19秒、50任务（46执行/4缓存）；`FixtureAudioProvider.kt:54:49`委托的`ContentProvider.openTypedAssetFile`返回可空`AssetFileDescriptor?`，覆写声明非空类型。补充Elvis分支将平台空结果映射为`FileNotFoundException("Fixture unavailable")`；不使用非空断言、不改生产校验、不再拦截普通音频读取。该次未执行JUnit任务、normal或三个STRICT阶段，无lint XML/APK/签名；原214定义保持，等待精确新快照重跑。

artifact `14177146391`为59631字节，ZIP SHA-256 `183db3bf93c12f515832534e19921abdb990e5268c4f831a0bdc8b185f039fea`，日志SHA-256 `d918dea09f6565145b2dfd1fc5750a03998a8cb7b7e0f22d6d99ceb1b01f7127`，匿名编译错误摘要SHA-256 `9a95f43ffba995e7035e89c7047d1a61b067be56d895aaa26c6e5d9252b7ba11`。归档仅失败诊断，缺完整锁/schema1，schema2仍为上述hash；不导入基线。此小修复仍不包含LP-04/05并行WIP，256原AC/readiness未通过。

[run `43754414573`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43754414573)/attempt1绑定source `27251af74dc6398c293d23f91570dacb4391d012`、实际checkout `b918071170b865ee092f37d2ab088fd7394555bc`、compile job `132935312198`。24份Debug JUnit XML确认214项真实执行、214通过/0failure/0error/0skip，定义inventory SHA-256仍为`ecd2dda7fb8be48018a4a1b0afdd1d0fd82b5635d27017e7334b226cee9f7fb1`；上一轮41个失败已清除。verification在13分55秒因`lintWatchAndroidDebug`失败：3项PluralsCandidate及4项UseKtx；另有2项独立Robolectric SDK版本Hint。Release/Diagnostic尚无测试XML，normal及三个STRICT阶段未尝试，没有APK或签名。

artifact `14178550202`为173830字节，ZIP SHA-256 `ca49a83e7af8c48ede0d4e8cdcf849c1e7008ee2df61b396363032e3030508c2`，日志SHA-256 `002ef436a185e8a465d332346323cf4c600f5a9608ef8734a206316ea0d95027`。匿名build证据SHA-256 `86b08bc525cecd3d3eaae5e5106c6cf567914ac1e665c22e36caa3397cac1833`，phase证据`9bdc0ef3b68cfbaa40c8c4d1ea591a92ffc19875142f765a74a2eddc76bcc1d8`，unit证据`eb17878c33cb3e12b8ef9ade5ffaab7622689c156b5078d3436aa475523b95b6`，lint证据`a79245f280fcbcdc152b24c56443b33eba983216385f3a529a49f670300ae945`，原lint XML `e319bb852feef3c68babd8ea6e159a73169062d85a526b5b5516bf91c7bf7534`。schema2与XML仍是失败诊断字节，缺完整app lock/schema1，不导入基线。

此次修正把组内曲数、封面确认项数和今日播放次数改为真实plurals，中英文资源和所有调用同步；Android分类使用饱和Int，仍以原Long格式化曲数/播放次数，不限制完整库。4处URI解析使用现有core-ktx的`String.toUri`，范围与授权校验保持。未suppress lint、未新增依赖或测试定义；原214定义和LP-04/05并行WIP边界保持，等待精确新快照云验。全部256原AC/readiness仍未通过。

合并P1已云验修正与P0被动图诊断后，本地49项Python CI数据/政策用例、Node政策场景和diff空白检查通过，JSON/catalog/manifest可解析，全部381个中英string key相等无重复，匿名源摘要不变。检查未执行应用或研究快照；214项应用测试仍须云端结果，设备隐私扫描只报告位置/类别，未发现待上传内容包含本地敏感值。

新增API30 provider版本/generation测试使用固定`org.robolectric:android-all-instrumented:11-robolectric-6757853-i7`，沿用API26 runtime。两个SDK独立非传递配置解析并由Gradle锁/SHA校验后复制到同一offline目录，避免同一配置的版本冲突只留下一个SDK；`enabledSdks`明确包含26和30，不能跳过API30用例当通过。官方Robolectric4.16.1 source JAR SHA-256 `b354b256648453c5e83eb4ec19d80f23a6d8775e7684c93d5e3c0f327ba8d289` 的 `DefaultSdkProvider.java` 固定API30映射11/6757853、预插桩7及所需Java9；当前Temurin17符合该门槛。官方Maven Central [POM](https://repo.maven.apache.org/maven2/org/robolectric/android-all-instrumented/11-robolectric-6757853-i7/android-all-instrumented-11-robolectric-6757853-i7.pom) 为1390字节、SHA-256 `579070875a6a33dd57d2937929fa443789e28826cb12e8e716197c222e199682`，声明Apache-2.0；实际SDK JAR和传递制品仍须完整云端资料审阅，本地未运行SDK。

P0实体runtime-android候选五阶段已经成功，正式严格APK待验证；本主题补齐同一实际直依赖并等待自己的完整资料；P1的保留原事务错误修正已使真实SQLiteFullException和旧队列/重开/重试测试通过，源码与云证据见DEV-104。Library中央事务入口已使用同一包装，后续集成最新队列修正；仍需完整五阶段XML/锁/schema1，再验证云端schema2、真实1→2迁移和三变体构建/测试/lint。失败资料不导入基线，不以NO-SOURCE或阶段未执行填通过。

用户新授权的adb测试虚拟机已只读观测：API32，主ABI x86_64，物理466×466、density310，系统报告矩形；root可用于测试控制，不替代普通应用权限或设备支持。只读证据SHA-256为`3845c34c1fb39bf91d5bc321dcebb3aa7f5ef8026c1f3126f917fe71ac4fcdad`，实际音频、输入、持久权和后台能力未测。待有合格云端APK后按source/build/run/attempt/artifact/APK hash绑定安装与测试；不使用本地替代APK、不卸载擦除已有数据。交付前再使用Watch X2真表完成对应14/15/06及全部AC，虚拟机结果不作为真表性能或音频支持结论。

圆屏优先，先验圆边的热区/首尾内容/长文/最大字，再补方屏。用户允许临时调整测试VM分辨率；记录原size/density/override并测试后恢复。原始设备信息、IP、adb端点、个人路径及其他隐私只留ignored本地或不留，禁止进入Git、Issue/PR、云日志或artifact；共享结果必须实际脱敏，不因仓库私有而上传。

LP-04完整增删重排/点击策略、LP-05双后端与解码、LP-06音效、LP-07/08歌词和输入、LP-09歌单/交换、LP-10真实文件写入、LP-11备份、LP-12其余主题/字体、LP-13/14传输和手机端、LP-15帮助/更新及网易云/Meting仍继续建设。手机APK构建/签名/候选集合须通过Actions Issue维护，当前没有把文档要求算成已实现。所有readiness仍false，candidate=null，256个原AC未通过，未申请最终公开/发布批准。

## 游标关闭竞态与新快照

[run `43756864869`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43756864869)/attempt1绑定source `fd4d926a01cd3b7dc01113c1c1abead7b42d2fdf`、实际checkout `fb7cc17d7a302f863153d2e56754de6c7b080bf3`、compile job `132942860190`。Debug214项真实执行、213通过/1failure/0error/0skip；失败为`AndroidLocalSourcesTest.nativeMediaTokenChangesAndLossAreObservedThroughTheAndroidProviderApi`，实际`SourceReadException: BUSY`来自closeAndAwait后立即执行的`verifyObservedCandidate`。verification在5分46秒失败（71任务，61执行/10缓存）；lint、Release/Diagnostic及后四阶段未执行，无APK或签名，不将上一轮214全通过当作本轮结论。

artifact `14178678082` ZIP SHA-256为`fa18eeb5b6cd2405bd4f205e25956d6b1a4ac3712a400d6011322904326b5f19`，日志SHA-256为`12dc19bea5c9b9c83b960d43c439ba02e73e4059267e12f5b5d37684e8023fb8`。实际失败JUnit XML SHA-256为`416bdcb21cc5b2f9a7db6ef1c63989d61b9c9e51fb4f9e945d44a38a22bbef3e`，精简失败来源证据为`c5e2fb73cefbabb953e0f73b487eff2caed67d65164a1784e2e004288389e12b`；unit/phase摘要分别为`a0dbb883e5253e880dc338e1090dfe71d9a11a071c5f9be901cbfb7c7cd81d71`和`a13361e6bf23c6cb8ccd89cdeb699501577b0643db906fdb47fc93559d073659`。失败schema2仍是诊断字节，缺完整lock/schema1，不导入基线；原始材料只留ignored本地。

游标原来在物理关闭内部完成ACK，随后才清`cursorLive`并释放IO占用；可内联恢复的顺序调用会提前遇到BUSY。现把物理关闭结果交给唯一有界退役槽，在物理关闭、guard及IO占用全部释放后确认ACK，保留关闭异常的原始cause。取消不会提前释放不合作的provider worker。另修正SingleCursor关闭幂等性，旧游标重复close/closeAndAwait不再清新游标的guard。4项新增回归以受控调度器/Unconfined和真实API30 provider覆盖立即顺序读取、活动读取中的延迟关闭、关闭失败后重试和旧游标重复关闭；原214项及原断言保留，24套218个定义的实际新结果见下文。

[run `43759999653`](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43759999653)/attempt1绑定source `af46b131a27b8f91e5e5ec078f9947852dcd4a48`、实际checkout `4f5be3f32f75aad7325de94f2fad1fc9670a1d07`、compile job `132952509373`。Debug24套218项真实执行、217通过/1failure/0error/0skip，原214项和另外3项新增回归通过。唯一失败为`LocalProviderIoTest.failedCursorCloseKeepsTheCauseAndReleasesBeforeAnInlineRetry`：物理关闭callback内的原异常引用断言已经通过，`CompletableDeferred.await()`恢复协程栈后复制IOException，直接引用断言失败。现保留callback原引用、错误类/消息、原cause及所有inline retry/释放断言；不关闭恢复、不删除测试或改变生产ACK契约。verification在7分51秒失败（71任务，61执行/10缓存），Release/Diagnostic、lint及后四阶段未执行，没有APK或签名。

artifact `14179844026`为148555字节，ZIP SHA-256 `da695d787bb458c54482f04f2f2cc0bddee9abe527770361d4714a7efef1feda`；日志SHA-256 `9fae2401507e4c2f18a5c71e26a2db429ee10f9383434672a3b47bc9084f4b73`。匿名build证据SHA-256 `3406aa02fbfac0760aa5a77c442ab2785c873c650c75bfcb3bc16697da40152c`，失败证据 `bff4344f681bf544636bb0ce21fa42d3ecad2628efc0858ab00c134b3182f762`，unit摘要 `9720a6a124ca6a1d556f5a237a560f94885c6e23500247ba531daff851c5b4a1`。本次仍为failed-partial，schema/锁不导入基线；原始记录只留ignored本地。修正后的218项仍待云端重跑。

P0同版本`androidx.compose.runtime:runtime-android:1.9.3`实际直依赖在run43755199209五阶段全部成功，common真实边及7个配置保留，811项官方哈希独立核验。此主题补齐同一真实API直依赖，保持工具链版本和全部STRICT规则；不复制P0的app锁或不相符的schema。该主题自身五阶段、完整依赖及迁移仍待验证，不能凭P0结果填通过。来源AAR SHA-256为`dc7a8a161d6dfff96801a2d89b1f5d4adb61c0dfa16cb5970215e70aa1d83407`，官方module为`9492b2ecb5da90bb3f6304e26fe06faff20c573e473a7da6351f0df52180f05a`，POM声明Apache-2.0。LP-04/05、CUE、LP-09及exchange并行WIP不进入这一小修复快照，所有256原AC/readiness仍未通过。

## 当前完整资料与后续严格构建

上文为历史轮次。[run43792499334](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43792499334)/attempt1现在提供本主题完整五阶段原资料，source `e2971ee4688c1a34626d8fe2cb70ca3f78c15a26`与实际checkout `60f0b36378a53da2400ca3ded82badd5ab349c8b`的tree相同。Debug/Release/Diagnostic各26套226项真实通过，完整列表10000/10001项及保旧队列/重开路径保留原180000ms timeout。每变体lint为2条Hint，Fatal/Error/Warning为0。

工作流最终按bootstrap合同拒绝，没有可接受APK。原XML、lock、schema1/2经独立资格审查后仅按原ZIP字节导入，细节及全部SHA见[依赖记录](DEV-104-dependencies.md#library完整云资料与sdk30基线导入)。下面记录其后的普通严格PR结果；真实seeded schema1→2、覆盖升级与256原AC仍待验证，LP-04/05/07/08/09并行WIP未进入这个冻结源码。

## 普通严格PR与真实迁移回归

[run43800615907](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43800615907)/attempt1绑定source `71d380bf703cd006fea97015479c0bc62cd4bbea`、原日志checkout `9d7c6ab0b796af05727578df7d114dc1b4185782`、compile job `133079783429`。source是checkout的合并父提交且完整tree相同，tree为`45191eca07770335e8bccd3f1aaca33845b7bff3`；普通严格构建及cloud-gate成功，PR稳定签名job按合同跳过。Debug26套226个唯一用例通过，failure/error/skipped为0；完整列表10000/10001及到期保旧队列、重开路径通过，76.562秒，原180000ms期限保持。本轮不执行Release/Diagnostic，不能套用bootstrap三变体结果。lint实为2条Hint/SimilarGradleDependency，Fatal/Error/Warning为0，不记为lint0。

原reports artifact `14196128947`，APK artifact `14196675761` ZIP SHA-256 `6f62f689725cd2e6ca69e397da56427acabe079ba8bcff7b5dc00df3f4e2998d`。原APK为15988511字节，SHA-256 `503053e0d0ee49eafc2b04ddc990c42fbb78e9c3b12c92ebdbf24b43bd16b9f9`，包`io.github.onlinemo.meloxwatch.dev`、0.0.1/1；manifest、单证书签名和16KiB/4B alignment独立核验。临时PR证书`168a1db603a9ca3ca3a6de00fa26588c0617f51b3dbad7baf859d7449ace0cc3`与稳定Debug证书不同，不能覆盖VM现有安装，未安装/卸载/擦数据。独审记录SHA-256 `d5bdc028bd4aee7b8f14d490a7b69419bb33d6199edd25bbd9e9cb9d0fdd9a4a`，原材料只留ignored本地；本轮不证明设备性能、出声或任何原AC。

新增`RoomMigrationOneToTwoTest`只定义一项原生SQLite/Room回归，原226项及应用/依赖输入保持。夹具资源与原schema1完整字节相等，SHA-256 `3a36d662a8ee80e70d76180d564f8d9858243631dd20e2648c2492df8ff39c79`；初次测试源码SHA-256 `4293bd07cf26a1e154d0e1a7c8b02aee542fb93867442084269d7f3e251fc731`，初次独立静态审查SHA-256 `5ea3b669ac2a1fd6dc3736e190de748665adc4392aaa533e2699e097c83a97c6`。旧三表种入乱序/重复逻辑曲目的不同实例、授权位置及在线账号引用、54321ms位置、checkpoint revision7与cycles2；生产open强制迁移及Room验证，逐字段和有界窗口核验，再写真实library实体并reopen。当前共27套227个测试定义，首轮失败及修正见下；稳定签名覆盖升级和256原AC仍未验收。

[run43805805052](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43805805052)/attempt1绑定source `ef56f06592fa016e745a6a8343546861689cf0cc`、原日志checkout `e6c46f0c8af18e4a28c12cbb95acccf91d870fb4`、compile job `133096243034`；完整tree相同，tree为`a688ff2b17eb41f85bd6237387b38029a0729add`。严格Debug测试Kotlin编译在`File(sqlite.path)`失败：实际路径类型为`String?`，Java构造器要求非空，`-Werror`按合同拒绝该警告。该轮没有运行227项测试、没有JUnit/lint报告或APK，稳定签名跳过。原日志SHA-256 `230dca27c462080c77539bee10a6d73ed18148d789cb3a734e1c73a441b70e67`，失败独审SHA-256 `16972fe994f996d9ce72e054d021bd191dfbdcae41ab91afb6d5fd76f756c56f`；不沿用旧226项结果作为本轮通过。

修正只以`requireNotNull(sqlite.path)`要求真实文件路径，保留canonicalPath同文件断言、旧字段与reopen验证，不放宽编译或改生产迁移。修正后测试源码SHA-256 `4b7a828f2494a1679795a780c1f2ad9794084d0a4e7384fef869904648ce8611`；资源、原226项及生产/依赖输入保持原字节。新快照仍待云端验证；本地未执行Android/Kotlin/Java编译、迁移SQL或应用测试，readiness及256原AC保持未通过。
