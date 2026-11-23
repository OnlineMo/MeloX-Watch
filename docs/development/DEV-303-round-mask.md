# Debug圆形可视裁切

根据15的圆屏优先与代表性矩阵，Debug应用加入显式圆形绘制裁切，便于在矩形测试窗口观察圆外遮挡。专用intent action为`io.github.onlinemo.meloxwatch.debug.ROUND_MASK`，Boolean extra为`io.github.onlinemo.meloxwatch.debug.round_mask_enabled`；首次无保存值时关闭，只有Debug构建接纳实际Boolean操作。无关action、缺失/错误extra或读取异常不形成设置操作，保留当前裁切和保存值。

[绘制框架](../../android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/ui/debugmask/DebugRoundMaskFrame.kt)按实际窗口边界与内容原点缓存圆外黑色路径。启停仅改变绘制，保留Debug内容的同一composition，避免切换时重建播放、备份或选择器状态。显式Debug操作将一个Boolean保存到应用私有偏好`melox_debug_round_mask/enabled`，正常启动读回；Activity重建不重放旧启动动作。读取失败回到无裁切，写入调度失败保留当前显示。采用apply避免同步磁盘commit，调度返回不证明已落盘或重启通过；现有备份规则排除该偏好。Release解析、读取、保存均提前返回并直接呈现原内容；原有媒体与外部音频入口保持。

原两路径的内存裁切独审回执SHA-256为`31e397953926810b5ea0a9fdecca7850a2b86a85daad8aa5f5f455844579afb7`；本次持久化后继单独独审并接入，回执为`b80ac6ede158cbc511fc247137259f1709452bed4e59b897c6d49931dd13f787`。九组生命周期、严格输入、存储失败、Release边界和圆屏观察定义均未执行；新云APK、实际保存、应用/VM重启及触摸仍未验收。该绘制只覆盖应用根内容，独立窗口的Dialog/Popup、IME和系统UI须单独观察；应用形状设置、热区、真实圆屏与方屏验收分别记录，裁切不代替设备AC。没有新增权限、BootReceiver、root调用、网络请求或系统盘修改，测试保持虚拟机设置原值，实际设备材料仅留本地。

外部RoundR3.0.2在API32测试环境的tap/swipe对照失败，四角高不透明度、不可信覆盖窗口会阻挡下层输入；该方案已恢复关闭，真实重启持久化未执行。应用自身绘制层后继须用新云端原APK独立验证，不能沿用外部遮罩的配置保存或BOOT广播结果。
