# LP-15 帮助、诊断、更新与离线许可

根据21的全量范围及17的LP-15契约，帮助入口接入实际手表导航，包含FAQ、接入技巧、应用信息、隐私说明、项目支持与许可。首次引导保存四步进度；完成、稍后与说明阅读选择分别持久化，返回页面不自动记录选择。PreferencesCodec升级到schema5，旧安装沿用既有接入流程，避免升级后重复弹出首启引导。

诊断仅保存最多32条固定区域、错误枚举与分钟时间，不接收任意异常文字、地址、文件名、曲库或账号字段。导出通过明确操作写入所选本地文档；取消、过期页面与清理失败分别处理。帮助与许可阅读独立于账号、网络和手机连接。

检查更新只在明确点击后访问固定项目渠道，校验版本、发布记录及候选来源的一致性，离开页面取消请求。更新提示提供可信项目入口；没有自动安装、后台轮询或诊断上传。

两端许可页从APK自带索引读取原文，核对路径、UTF-8、大小与SHA-256。手机许可页面有独立可取消的ViewModel，连接或存储恢复未完成时仍可阅读。当前通知包覆盖上一份已审运行时图的手表83项与手机53项；新增OkHttp和ZXing及其实际传递依赖须在新云端图准入后补齐，现有目录不代表新图已完整。

构建导出检查实际APK内许可资产与冻结源码；双端签名前后再次比对同一证据。六项APK目录政策定义及相关双端签名夹具共十项Python检查已通过。Android帮助、更新、诊断和两端许可测试定义尚未在新快照执行；源码审查通过不替代云端、设备或原AC验收。

实现入口为[帮助状态](../../android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/model/HelpPreferences.kt)、[手表帮助页](../../android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/ui/about/WatchHelpContent.kt)、[诊断存储](../../android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/data/about/SafeDiagnostics.kt)、[更新读取](../../android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/data/about/ProjectUpdateSource.kt)与[APK许可检查](../../scripts/ci/apk_licenses.py)。完整F-072/073/118～121及两端实测仍为未验收，候选与发布门槛保持未通过。
