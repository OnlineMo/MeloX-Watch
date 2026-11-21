# 播放、歌单与 Apple Music 风格界面整合

本记录区分源码实现、云端验证、设备验收和发布批准。PR #31第三十一轮run44796881589/attempt1的已提交source `5df9fe9895051ca7f2489327540e6681b87f2f7c`已完成六阶段采集/STRICT：Debug、Release、Diagnostic各114份原JUnit、1349项全部通过，lint各3条Hint、无Error/Warning。最后按generated-unreviewed合同拒绝导出APK；精确原输入经审阅后已导入，详见[13](../13-仓库状态.md)和[依赖记录](DEV-104-dependencies.md)。本批后续歌词/字体/主题界面、认证传歌和真实Android手机App源码已整合，主库为V6，尚待新增输入采集及正常严格双APK构建；旧V3结果不能算新界面、手机端或V6通过。全部256个原验收用例仍为`not_run`。网易云、默认关闭且用户配置的Meting和其余本地功能继续属于首个预发布版。

## 当前实现

| 范围 | 已写入的行为 | 剩余验证或实现 |
| --- | --- | --- |
| LP-04 完整队列 | durable rows 与有界播放器窗口；下一首插入、移除、重排及模式事务；精确实例与队列修订号围栏；三种点歌策略；原操作 receipt 重试；完整列表与歌单跨页入队 | 云端存储/协调器回归；真实音频、系统控制、后台、焦点和撤权验收；歌词共用媒体时钟的接线 |
| LP-05 后端与速度 | 同一 MediaSession、一个活动后端；Media3 与系统 MediaPlayer 的保存/暂停/释放/准备/seek/暂停恢复；服务统一焦点/noisy；保存请求与真实后端/速度状态分开 | 实际异步 prepare/seek、切换及取消、路由、音高和长时播放；无缝当前没有合格接续路径；APE、DSF/DFF 原生解码仍未实现 |
| LP-05 CUE | 有界解析、相对路径校验、分组及分轨边界模型 | 扫描原子发布、授权源定位、播放器 segment 和实际音频接线；纯解析器不等于 CUE 播放完成 |
| LP-07 歌词核心 | 有界编码/LRC、译文稳定关联和真实字时间；ID3 USLT/SYLT、FLAC/Vorbis与Ogg首流元数据解析 | 第五轮该source的16套234项歌词核心Debug全部通过，Release/Diagnostic未运行；授权来源加载、持久关联/偏移、完整歌词页、字体和显式补全继续建设；不推算缺失字时间 |
| LP-08 定时与输入 | 服务owner持有单一定时与持久截止状态、立即/本曲结束两种策略、取消和可关闭摘要；进度页/手势/表冠共享具体owner及实际任务占用 | 第五轮定时独立8套96项Debug通过，队列/定时整合28项中15失败；Release/Diagnostic及真实到期/重建/通知、触控/标准旋转/焦点、熄屏与路由音量仍待验证；外部蓝牙歌词协议仍未实现 |
| LP-09 歌单与收藏 | 稳定歌单/成员身份；收藏与完整 CRUD、顺序/去重；冻结视图及队列映射；失效成员预览/恢复；目录与关键词生成；M3U/M3U8、JSON 的预览、导入/导出及失败重试 | 新数据库迁移、完整跨页/并发/取消/provider 回归；实际选择器与文件交换；设备交互和原 AC |
| LP-09 文件夹维护 | 仅完整成功 FULL 扫描触发；验证来源/授权/规则/目录覆盖围栏；一个前台 owner、一个 lease、有界 keyset；保留手工成员顺序，以持久 receipt 防重复 | 云端真实 Room 和并发测试；前后台快速切换、扫描失败/取消/空目录/失效目录及活动队列不变的设备验证 |
| 界面 | 按固定 MeloXWatch Swift UI 实现黑底、圆角封面、紧凑队列、白色圆形控制及红色状态；菜单、队列和正在播放入口；物理圆边/inset、当前字形测量及完整滚动后备 | 精确新云端 APK 的圆/方、小窗口、长文本、大字体、焦点和旋转验证；歌词入口、封面背景/主题与完整歌词页继续建设 |

LP-05 无缝、APE 和 DSD 不以设置项或“不支持”提示算实现完成。CUE 和歌词解析核心不代替实际来源、持久关联和服务接线。首版没有删减功能或新增歌曲数量上限。

当前数据库迁移使用固定Room2.8.5与Bundled SQLite2.6.2的公开writer API。真正的COMMIT失败由Room在归还连接前检查并回滚；不关闭重开数据库，不改用私有反射或较弱的故障触发器。生产RawQuery、迁移和Room测试使用同一驱动，历史平台schema及1→2检查保留；主机JNI只从精确JVM制品提取，不重复加载JVM数据库类。第三十一轮已提交V3源码的三变体测试与输入审阅通过；本批新V6迁移、真实传输/主题事务及大库分页定义仍须新云验。

该修复提交 `9e425b20b23f5e00fec8dbcf0d593be17ac92c0d` 的第六轮[run43914527409](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43914527409)/attempt1在Gradle脚本配置编译失败：两处`lowercase(java.util.Locale.ROOT)`无法解析`util`，应用任务与后续阶段未启动。这里只显式导入`Locale`并保留两处`Locale.ROOT`调用，不变更依赖或降低检查；原失败归档和独立诊断保留，精确build、job、artifact/hash见[13](../13-仓库状态.md)。配置修正仍须新云验，不证明数据库迁移或当前界面通过。

Locale修正源码 `b349522f3db0c08a22232139a0b6c546674770dd` 的第七轮[run43924826977](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43924826977)/attempt1在5分47秒、59任务（52执行/7缓存）后因测试Kotlin的两个`Accidental override`诊断失败。完整诊断确认`PlaylistDocumentFixtureParcelShadow`与固定Robolectric 4.16.1基类的`dup(FileDescriptor)`具有相同JVM签名；窄修仅改实现声明名并显式映射`methodName = "dup"`，保留原行为、SDK26/30回归和检查期限。Debug/Diagnostic生产Kotlin通过不构成单测或构建接受。六成员`failed-partial`中schema3新生成、XML新增三个SQLite组件，但normal和strict未启动，不能导入schema/锁/XML；精确build、job、原artifact/hash及独立诊断见[13](../13-仓库状态.md)。

## 清理与生命周期契约

歌单视图清理保留原 token，实际成功后才放弃所有权；超时呈现可重试 `BUSY`，真正调用方取消继续传播。具体库 closer 在 `NonCancellable` 内部设置期限，覆盖 mutex 和排队的 Room 事务。应用共享 closer 使用 2500ms；文件夹维护器独立使用 5000ms，前台 owner 退出还可能重试清理。这些期限不证明已开始的原生 SQLite 调用可被硬中断，也不构成 STOP 的物理耗时上界。

前台维护的 START/STOP 事件保留不同对象身份，避免快速 STOP→START 被布尔状态合并。`collectLatest` 先取消并等待旧 owner 的清理，再启动新 owner；隐藏页面不自建维护任务或歌词计时器。扫描失败、覆盖不完整或撤权不能清空旧歌单；只有已覆盖的真实空目录可清除自动成员。

## 证据与后续验证

本批源码已接线真实歌词加载/偏移持久化、媒体时钟与字体/主题设置，圆/方形状与大字组件已有完整源码及资源独审；没有新APK或设备可见结果。LP-13认证会话、共享配额、校验后提交和取消回收，以及LP-14手机端配对、传歌、库/歌单/专辑艺人编辑与主题上传已整合。两端共享协议是源码目录，主Room库为V6；HistoricalV3/V4/V5测试库不导出历史生产schema。新增91项手机定义、真实主题事务与20001项库/歌单分页定义均未运行，不能记为AC或云端通过。尚未接线的在线NetEase/Meting和无缝/APE/DSD、DSP、原文件写入、备份及外部歌词继续按完整任务依赖建设。

本次独立静态审阅已覆盖歌单核心、迁移定义、预览 lease、交换的物理 provider 所有权、typed service receipt、失效成员恢复及文件夹维护/前台接线。审阅记录固定到本地不可变源码副本；历史发现及其修正分别保留，不把修改后的结论套到旧字节。

音乐库性能修正的独立来源为 `570954fe637ade93092804665b51701256fba208`，[云端运行 43785225368](https://github.com/OnlineMo/MeloX-Watch/actions/runs/43785225368) / attempt1 被35分钟job上限取消；该历史快照的三个变体各226项真实通过，但第五阶段未完成且没有合格APK。其后Library完整资料、普通严格构建和迁移已有独立新证据，详见[仓库状态](../13-仓库状态.md)。原10000/10001、180000ms、全量读取及重开断言不放宽；本整合仍须自己的云验。查询等价依据当前合法生产writer的关系唯一性，不保证任意损坏数据库；后续备份恢复或新增关联writer必须重新检查这一不变量。

播放输入、定时与歌词核心汇总为 `465e0bc393e252491107d69a9f7e40ddc38ea15f`，117条精确暂存路径已经提交。20文件分号展开及七文件EOF调整有单独的文本比较与独立delta审阅；旧freeze不替换，当前提交通过新暂存manifest绑定。后续正常合入contribution的历史1→2迁移测试，并继续维护真实1→2→3升级链；所有审阅和定义数量均不算Android运行结果。形状偏好改为AUTO跟随系统、ROUND/RECTANGULAR分别明确选择，与歌词页一致；该最小布局改动仍待新云验和圆/方设备验证。

整合采用正常 Git 合并保留两边历史和已审 WIP。只有完整成功的云端阶段及独立产物审阅，才可导入原始锁/schema；失败或部分资料不成为基线。下一轮记录实际 source/build SHA、run/attempt、artifact/hash、JUnit/lint 和签名，下载并测试完全相同的云端 APK。

先期虚拟机已恢复ADB并安装可信run43817332453的Library稳定Debug原APK：固定证书覆盖P1、保留应用数据且读回APK与云端原字节一致，仅验证有限启动，不含本整合界面或歌单。显示为466×466/density310，系统报告矩形配置；圆屏须单独明确选择和验证。未以root、卸载、清数据或内置音频替代正常应用验收；音频、完整升级行为、全部原AC及交付前真表测试未通过。精确source/run/artifact/APK绑定见[13](../13-仓库状态.md)。原始截图、日志、设备标识、地址和个人路径只留ignored本地，不进入Git、Issue/PR或云端产物。

界面来源、许可和固定提交见 [Apple Music / MeloXWatch 参考](UI-Apple-Music-reference.md)；完整范围与验收映射仍以 [21](../21-本地播放器全量需求整合.md)、[17](../17-本地音乐功能与实施计划.md) 和冻结匿名需求为准。所有 readiness 保持 `false`，candidate 为 `null`。
