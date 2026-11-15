# DEV-004-A 最小Android探针建设记录

2026-11-15用户明确恢复完整建设，委托中间开发与审阅、保留最终验收。首版范围仍为网易云、用户配置Meting及全部128项本地功能/256个原AC，按05推进；本记录只描述最小探针，不能替代任何完整LP任务验收。

## 当前状态

- 源码：单`:app`工程正在建设，包含运行时圆/方屏布局、真实输入检查、文件/目录授权能力探测、MediaStore少量样本及单会话本地播放。
- 云端：第三轮资料采集已执行Debug/Release源码编译及Debug assemble，lint失败，应用单测均NO-SOURCE；未完成三变体锁资料、严格构建或任何签名。精确证据见下文。
- 设备：2026-11-15T10:38:11Z只读`adb devices -l`返回空清单。DEV-001的API/ABI/窗口、DEV-002安装/耳机/30分钟后台、DEV-005用户文件及输入、DEV-004-B性能基线均未测；minSdk26仍为候选，不能由云端成功推断设备可用。
- 完整功能：LP-00～15、网易云及Meting业务尚未验收，256个原AC无通过证据。readiness全部false、candidate=null；无公开、tag、Release或发布批准。

## 构建与来源

工具链读取`.ci/toolchain.properties`，应用读取根`version.properties`，开发提案为`0.0.1 / 1`。正式候选仍按10冻结main与完整历史计算code；探针版本不代表首次公开候选。

wrapper来自原云端run`42766330725`、attempt`1`、artifact`13740290230`，源SHA`6b570aae2fa891e3aa16eefd7dc722316802835b`。2026-11-15重新核验归档与GitHub digest一致：SHA-256`6f017f449329c14aa287512b61d49d7ca981f15cd3435c7e2de07163965104a5`，jar为`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`。仅复制已核验文件，没有执行本地Gradle。

应用依赖的精确坐标、来源和许可见[依赖记录](DEV-004-A-dependencies.md)。首次PR缺少校验XML和app锁文件时，`build.py`只在无秘密PR中采集三种变体资料，始终失败且不导出APK；资料由既有always-upload reports保存。完整资料审阅并提交后，接受的构建只执行严格校验，可信分支缺资料直接拒绝。不能把bootstrap成功解析或失败报告当作APK编译验收。

## 探针边界

文件读取在单个可取消IO通道执行。MediaStore最多展示20个样本，授权树最多32个直接子项，名称明确为样本；这不是产品曲库或队列上限，也不代表完整扫描。单文件检查真实音频元数据，可持久化读权由实际返回标志决定，并由用户明确保存；保存URI字符串不授予额外权限。

选择文件与播放分开：选择只检查来源，明确播放才向唯一MediaSession发命令。服务只有一个ExoPlayer及其唯一焦点/noisy处理；Activity退出仅释放Controller，不销毁活动音频。前台提升拒绝时暂停并给稳定错误，不循环重启。数据源只接content URI，探针没有HTTP数据源或INTERNET权限；没有用户文件正常/断网证据时，仍不能通过离线AC。

此次不实现双后端切换、完整Room队列或任何假业务入口。它们仍是05中的必需后续实现，不能以最小探针完成而删减。性能阈值、48dp触控、隐藏UI零视觉tick及实际字体/形状验收仍按14/15/16执行，未测值不填0或通过。

## 后续证据

首轮PR构建run`43707610394`、attempt`1`绑定source`ec141a0a91f4bf9b9cf061f398a0311f311d274b`与merge build`24c1be4cdf48d2b72f6ce084d7c5a5a83eaba485`。preflight通过，编译配置在AGP的`setSigningConfig(null)`重载歧义处失败，签名跳过、cloud-gate失败。报告artifact`14158961653`的归档SHA-256为`2bb9bf9aabad65593964af16b8ee25c2a480792290f14fe129d8e90d892b691b`，与GitHub digest一致；其中只有绑定上述来源的`failed-partial` manifest，没有锁/校验资料或APK，不能导入或验收。初始化脚本改为按固定AGP的公开APK接口选择setter，所有DSL层仍去签名并读回检查；实际结果待下一轮。下载报告还发现API拒绝`application/octet-stream`，改用GitHub API媒体类型，跨主机下载仍不传Authorization。

第二轮run`43708581683`、attempt`1`绑定source`5f94c753647daf5bb7884dcb5f13fb40889a67c5`与merge build`081abfd34331cc21e5bda962e5e0d5f97d801b67`。配置已越过null歧义，但原初始化脚本将AGP展示字符串与规范版本比较，误报漂移；固定8.11.1官方API的`version`才是Maven版本，随后据此修正。报告artifact`14158809988`、归档SHA-256`ab4aa79e6265d977699fd14e6528d3e0337a3807bb4b9b5ee8bf8d2ff1afafa1`核验一致，仅有校验XML，缺少app锁文件，仍为`failed-partial`、不接受。此次实际下载已验证媒体类型修正；35项本地stdlib CI测试及Node政策检查通过，不代替Android测试。

第三轮run`43709825751`、attempt`1`绑定source`401daa9756f4c3cbf3b7db5bbe6660bcc3297bed`与merge build`39ba0603ee61338c215b793b182e2df23506a3d7`，编译job为`132798816688`。Debug及Release的Kotlin源码编译已执行，Debug assemble已运行；两变体单测均为`NO-SOURCE`，不能记作应用单测通过。Debug lint失败18项：11项为固定插件/库的升级建议，7项为实际窗口尺寸、英语数量文案和Core URI扩展问题；Release只见lint分析，Diagnostic未完成。随后修正7项用法，在模块`lint.xml`只对固定wrapper/catalog文件豁免3类升级建议，保留warningsAsErrors与其它检查。新修正待云端验证。

第三轮报告artifact`14159179627`、归档SHA-256`bae9cf287b1e6f8a25532175f05a1428a0854322891f2690ca4b2d9a9393a386`与GitHub digest一致。校验XML为`651c09fe2f027be20bb85ae35f2394217caeb3c916b60bde44bd1a5a603af204`，app锁仍缺失，manifest为`failed-partial`、`accepted=false`、`apk_exported=false`；没有导入这些部分材料，签名跳过、cloud-gate失败。

严格PR产物记录source SHA、实际merge build SHA、run/attempt/artifact、归档和APK哈希、版本与临时证书；可信分支记录隔离签名与固定Debug指纹。下载与安装必须使用该云端原包。连接已授权的手表后补DEV-001，再分别执行DEV-002/005及基线；共享停止条件未验证前不能宣称P0退出或P1集成验收通过。
