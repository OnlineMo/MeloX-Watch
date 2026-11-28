# LP-14 会话视图交接与显示确认

根据 F-124 的完整分页、F-126 的对象预览和17的授权与取消契约，目录视图由有效
peer/session 及原授权 grant 共同约束。物理 socket 的八秒空闲关闭不再删除目录
视图；同一合法会话可在新的 TLS 连接上继续原 view、修订号及 cursor，不重扫
先前页面。原四个视图槽、十分钟视图边界、查询/字段/页面限额保持。

共享 wire schema 为3，新增 `CloseCatalogView` 与只读 `ReadReceipt` 控制操作。
新查询先释放客户端当前精确 view，核对 kind、TLS peer、target、session 及真实
grant 后，收到关闭确认才申请新槽。关闭失败保留原退休状态与占用，不能用新
view 绕过失败。目录视图不进入手机持久记录，撤配对清除其内存身份。

八秒空闲仍关闭实际 socket、TLS 和上传 stage，并清除该 peer 的临时显示预览。
撤权、会话关闭及到期拥有独立的逻辑视图退休收据；即使没有剩余连接，也会
释放目录视图和预览。取消等待不代替物理完成，失败保留可再次关闭的所有者。
监听、raw/TLS 和逻辑视图的关闭分别派发，没有后台保活或自动重连。

显示确认或明确重试先只读查询同 operationId、kind、target、真实 TLS peer 和
payload hash 的持久回执。已提交的原回执直接返回。尚未提交时，在当前授权连接
上重新验证原 entity、base revision、规则修订、patch、名称与成员数量；全部
匹配后才发送原 operationId 的 Apply。变化或异常保留草稿，并要求重新核对，
不把新状态静默当作旧确认。新预览可更新其临时期限，但不能更改原操作输入。

`ReadReceipt` 通过独立 backend 入口，仅查询原 canonical ledger 的已提交记录；
缺失或未提交返回缺失，不委托上传恢复，也不补写回执。原上传开始、结束和
明确重试仍由既有 `committed` 路径处理物理恢复与原所有者的发布事务。

wire 控制版本与持久身份分开：既有 mutation/preview digest namespace 固定2，
SQLite 历史 schema2 回执的字节、operationId 和 hash 不改。旧 wire schema2
对端在帧准入时明确拒绝，不作为此批双端互通证据。没有新增数据库表、数据库
schema 或依赖；手机本地记录的版本、凭据排除和原文件所有权保持。

新增15个回归定义：10个使用实际手机 Client/TLS worker、真实 TLS Server 和原
native Room 的分页、空闲后确认、六次新查询、失败关闭、撤权/到期、历史回执
及权限/异常路径，包括真实空闲预览清理中停止/撤权的交错、实际视图删除及
失败退休后的明确恢复；2个共享帧与历史身份定义；3个原 Room 精确退休、预览
冲突与只读回执定义。只读回归经过原 Processor 和实际 Room，对缺失、未提交及
schema2 音频回执核对无恢复 marker 或回退调用，保留明确重试的原恢复路径。
互通测试只替代选定 Wi-Fi 的 socket 绑定，云端 Test 源集通过固定四份
原手机生产源码编译，没有 tracked 测试实现副本或手表产品源码引用。

九秒真实空闲仅是测试定义，尚未运行。Android 云编译/测试/lint、精确双 APK、
设备及原 AC 全为未验证；此记录不完成128项范围或256项验收。
