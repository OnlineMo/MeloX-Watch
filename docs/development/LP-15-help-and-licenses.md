# LP-15 帮助、诊断、更新与离线许可

根据21的全量范围及17的LP-15契约，帮助入口接入实际手表导航，包含FAQ、接入技巧、应用信息、隐私说明、项目支持与许可。首次引导保存四步进度；完成、稍后与说明阅读选择分别持久化，返回页面不自动记录选择。PreferencesCodec升级到schema5，旧安装沿用既有接入流程，避免升级后重复弹出首启引导。

诊断仅保存最多32条固定区域、错误枚举与分钟时间，不接收任意异常文字、地址、文件名、曲库或账号字段。导出通过明确操作写入所选本地文档；取消、过期页面与清理失败分别处理。帮助与许可阅读独立于账号、网络和手机连接。

检查更新只在明确点击后访问固定项目渠道，校验版本、发布记录及候选来源的一致性，离开页面取消请求。更新提示提供可信项目入口；没有自动安装、后台轮询或诊断上传。

两端许可页从APK自带索引读取原文，核对路径、UTF-8、大小与SHA-256。手机许可页面有独立可取消的ViewModel，连接或存储恢复未完成时仍可阅读。第54轮两份实际锁图与真实Test.classpath原件准入后，通知目录接入手表86项与手机53项：手表三项运行时版本置换、OkHttp Android与ZXing新增通知，以及既有JMAC原GPL v2文档的索引入口。手机原目录、索引及通知字节保留。

OkHttp保留Apache原文与源码归属、MPL2的PSL原通知及源形式可得说明、Unicode原输入归属与Unicode License v3；ZXing保留固定release的Apache原文、Barcode4J归属及core源码法律头部。55个原文片段、5个实际变化的classpath容器、三种运行时锁范围和全部许可目录已独审。两份纯许可数据ZIP通过目录政策检查；它们没有Android包结构，不是可安装APK。GPLv3本地CRLF原件与实际Git LF blob分别记录，通知索引绑定后者；没有修改手机或原许可来掩盖序列化差异。

构建导出检查实际APK内许可资产与冻结源码；双端签名前后再次比对同一证据。六项APK目录政策定义及相关双端签名夹具共十项Python检查已通过。Android帮助、更新、诊断和两端许可测试定义尚未在新快照执行；源码审查通过不替代云端、设备或原AC验收。

实现入口为[帮助状态](../../android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/model/HelpPreferences.kt)、[手表帮助页](../../android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/ui/about/WatchHelpContent.kt)、[诊断存储](../../android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/data/about/SafeDiagnostics.kt)、[更新读取](../../android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/data/about/ProjectUpdateSource.kt)与[APK许可检查](../../scripts/ci/apk_licenses.py)。完整F-072/073/118～121及两端实测仍为未验收，候选与发布门槛保持未通过。
