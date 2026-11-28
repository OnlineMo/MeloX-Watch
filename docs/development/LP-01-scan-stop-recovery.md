# 扫描停止与终态确认

根据 F-008、F-010 及 [LP-01 契约](../17-本地音乐功能与实施计划.md)，
取消、后台暂停与失败停止分别保留首次终态意图。停止请求立即取消自己的扫描
job；物理提供方不合作时，原读取与游标仍保持占用，直到实际退出。

读取退休与数据库终态是两份确认。扫描通过自身 coroutine context 的
ReadRetirement 等待实际准入读取，不借全局空闲状态等待其他操作。从未进入
执行体的 lazy job 仍持有已接受的 token，并沿同一终态确认路径收尾。
Native beginScan 接受 token 后先登记 owner，再跨回可取消边界，避免已提交
token 因调用方取消而丢失。

STOPPING 或终态写入失败时，界面保留 STOPPING、原 token、已核对计数与
具体错误。原 owner 不释放；新的扫描不能借旧 RUNNING token 继续，也不把
未提交的 CANCELLED/FAILED 当作正常完成。停止恢复按钮明确重试原终态，
不启动另一代扫描。

显式重试最多一个写入者，调用方等待期限为五秒。等待超时或页面取消不会
提前放弃已接受的 native writer；原写入者完成前，第二次重试返回 BUSY。
物理读取尚未退休也返回 BUSY。成功写入并取得真实终态确认后才释放 owner，
之后的新扫描使用新代次。

写入可能已提交但回复失败，因此恢复可读回真实 Room 终态，或在同一事务中
确认 source.activeScanId 已实际撤销或替换。单纯规则或来源 revision 改变
不证明原 active pointer 已释放。真实围栏替代以 INTERRUPTED/SOURCE_CHANGED
反馈，不合成普通取消完成。后台暂停的失败留在状态中，不逃出应用协程。

九个回归定义使用真实 Room/SQLite 行及 SQL 阶段故障，涵盖 STOPPING 和
终态写入失败、恢复后新代次、未进入 lazy job、后台错误、已提交但回复丢失、
规则变化不足以确认、真实 active pointer 替代、五秒等待超时及取消等待后的单一 writer。
提供方是受控枚举夹具，磁盘满是明确注入的 statement 错误；不据此声明设备
磁盘满、真实提供方或停止时延通过。

本地仅进行文本、调用点和补丁完整性核对。Android 编译、JVM 回归、云端
验证、设备结果及原 AC 均为 NOT_RUN；范围仍是全部 128 项及 256 个原 AC。
