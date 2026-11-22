# Debug圆形可视裁切

根据15的圆屏优先与代表性矩阵，Debug应用加入显式圆形绘制裁切，便于在矩形测试窗口观察圆外遮挡。专用intent action为`io.github.onlinemo.meloxwatch.debug.ROUND_MASK`，Boolean extra为`io.github.onlinemo.meloxwatch.debug.round_mask_enabled`；普通入口默认关闭，只有Debug构建接纳该动作。

[绘制框架](../../android/app/src/main/kotlin/io/github/onlinemo/meloxwatch/ui/debugmask/DebugRoundMaskFrame.kt)按实际窗口边界与内容原点缓存圆外黑色路径。启停仅改变绘制，保留Debug内容的同一composition，避免切换时重建播放、备份或选择器状态。标志只在Activity内存中，未增加权限、网络调用或偏好写入；Release直接呈现原内容。原有媒体启动与外部音频入口继续按原动作解析。

两个源码路径已通过独立窄审，回执SHA-256为`31e397953926810b5ea0a9fdecca7850a2b86a85daad8aa5f5f455844579afb7`。新云APK、遮罩启停、恢复与截图尚未验证。该绘制只覆盖应用根内容，独立窗口的Dialog/Popup、IME和系统UI须单独观察；应用形状布局、热区、真实圆屏与方屏验收分别记录，圆形裁切不代替设备AC。测试继续保持虚拟机显示设置原值，实际设备材料仅留本地。
