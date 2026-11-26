# LP-14 手机伴侣 UI 复用

Android手机伴侣采用固定MeloX-Android UI的静态配色、字阶、圆角和分段导航结构，接入现有配对、传歌、主题、曲库、歌单、显示元数据及蓝牙歌词入口。实际功能仍由原ViewModel和协议所有者裁决；源码采用、云端检查与设备验收分别记录。

来源为[lladlam/MeloX-Android](https://github.com/lladlam/MeloX-Android/tree/83632b17e1ee857bc428134528e0fd86c5fd4d01)，完整提交`83632b17e1ee857bc428134528e0fd86c5fd4d01`，与现用上游锁一致，没有版本升级。已核验的研究快照保持干净detached状态；未执行或编译研究工程。

## 固定源码与许可

下表散列对应固定Git blob的LF字节。工作快照仅有已核对的CRLF行尾差异，没有内容差异。

| 源文件 | Git LF SHA-256 |
| --- | --- |
| [MeloXTheme.kt](https://github.com/lladlam/MeloX-Android/blob/83632b17e1ee857bc428134528e0fd86c5fd4d01/android/app/src/main/kotlin/com/lladlam/melox/ui/theme/MeloXTheme.kt) | `94aaf190ef179eddd36d306907b5a7a2eb7861720010661c5e8c2e52f6f7af9f` |
| [MeloXGlassTokens.kt](https://github.com/lladlam/MeloX-Android/blob/83632b17e1ee857bc428134528e0fd86c5fd4d01/android/app/src/main/kotlin/com/lladlam/melox/ui/glass/MeloXGlassTokens.kt) | `57402f3bf8cf15f287dad5e42678da19468882205b3d73345a6711657ff43efc` |
| [LibraryScreen.kt](https://github.com/lladlam/MeloX-Android/blob/83632b17e1ee857bc428134528e0fd86c5fd4d01/android/app/src/main/kotlin/com/lladlam/melox/ui/library/LibraryScreen.kt) | `61e6f7f8fc867cab6ed0fb2ab268a42a5bcab78ebda7c458ef88caa5824d1689` |
| [MeloXApp.kt](https://github.com/lladlam/MeloX-Android/blob/83632b17e1ee857bc428134528e0fd86c5fd4d01/android/app/src/main/kotlin/com/lladlam/melox/ui/MeloXApp.kt) | `1809225b0ba934e33b571dce4cede749040a68c4da74b562174e28ab831003e4` |

上游[完整LICENSE](https://github.com/lladlam/MeloX-Android/blob/83632b17e1ee857bc428134528e0fd86c5fd4d01/LICENSE)采用GNU GPL v3，Git LF SHA-256为`3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986`，与本项目完整GPL文本一致。新增Style和Navigation文件保留来源、完整提交、GPL许可及修改日期说明；手机包继续提供完整项目许可。

## 复用与改造

| 采用位置 | 源段与保留行为 | 适配 |
| --- | --- | --- |
| `CompanionStyle.kt` | `DarkColors`、`MeloXSystemColors`、`MeloXShapes`及`MeloXTypography`的静态令牌 | 保留深色端点、34/41大标题、22/28分组标题、17/22正文、15/20副文及字重；使用系统字体和现有Foundation圆角。按压及必要边界补足对比度，亮红用于符号/选中边界。 |
| `CompanionControls.kt` | `MeloXLibrarySearchField`的字段几何与内边距，以及上述字阶/表面 | 接原输入、最大字符、键盘/密码变换和动作参数；添加明确按压、焦点和标题语义。确认/取消、权限enabled及真实状态保持。 |
| `CompanionNavigation.kt` | `MeloXLibrarySegmentedPicker`的等宽分段，`RootTabButton`的Tab/选中/SemiBold与Medium结构 | 六个现有入口按可用矩形宽度分1/2/3/6列，随原垂直列表滚动。标签可换行增高、热区至少48dp；固定对勾盒与选中语义共同表达当前页。 |
| `CompanionUi.kt` | 页面大标题、导航与原内容层级 | 原返回闭包、许可生命周期、滚动状态、业务body及蓝牙歌词入口保持，导航继续调用原`model.navigate`。 |
| `CompanionActivity.kt` | 固定深色端点对应的系统栏外观 | 通过既有Activity的`SystemBarStyle.dark`配置当前窗口；原选择器、`setContent`及前后台生命周期保持。 |

复用没有增加依赖、schema、构建配置或来源锁变更；没有导入上游账号、凭据、仓库、多平台客户端、远程热更新、玻璃渲染、持续动画、字体或图标资产。认证权限、完整分页、revision、operationId、取消/退休和草稿恢复仍按03/17的LP-13/14契约执行。手表继续采用固定MeloXWatch的Apple Music方向，圆屏优先。

## 验证状态

独立源审已核对全部五份前后源码、原业务/输入字节、固定来源及完整GPL文本，收据SHA-256为`4847d3dc66b74919dd7b06d869b440968489b1e3397cee2bdb335adfc1d9b2a5`。实际静态实色组合的最低文字对比度为6.1961:1，最低必要控件对比度为3.3479:1；这些数值不代表面板或手指操作验收。

没有增加镜像UI测试。后继须运行现有手机取消/退休、授权/revision、分页、草稿恢复、许可和蓝牙歌词的适用套件，并完成双端云端编译、lint与精确APK核验。窄矩形VM及真实手机的六入口、大字、完整输入/IME、焦点、取消和草稿仍待设备验证。全部128项/256个原AC及同候选、真表、真实手机和发布门槛保持未通过。
