# MeloX Watch 许可与来源

MeloX Watch 手表应用、Android 手机伴侣及本仓库的项目代码采用 GNU General Public License version 3，SPDX标识为 `GPL-3.0-only`。Copyright (C) 2026 OnlineMo。可依该许可使用、修改及再分发；在适用法律允许范围内不提供保证。完整原文见 [LICENSE](LICENSE)，两端APK同时内置 `licenses/GPL-3.0.txt`。

第三方源码及依赖保留各自的版权、许可和通知。整体GPLv3条款不移除它们原有的授权，也不覆盖用户的音频、图片、字体或账号资料。

| 来源 | 固定提交及复用范围 | 许可 |
| --- | --- | --- |
| [MeloX](https://github.com/youshen2/MeloX/tree/09713edb847a0f0a3515a34f779cc630d4fe5985) | `09713edb847a0f0a3515a34f779cc630d4fe5985`；手表行为及网易云协议迁移，实际文件分别保留来源说明 | GPLv3 |
| [JMAC](https://github.com/umjammer/jmac/tree/0cedfd83a3005c1c4e4dd5fa7cb3796c79b765f3) | `0cedfd83a3005c1c4e4dd5fa7cb3796c79b765f3`；LP-05窄APE解码衍生代码及测试编码样本 | GPL-2.0-or-later；组合按GPLv3交付，保留原声明与完整GPLv2原文 |

JMAC实际修改、文件映射和原许可见[解码来源记录](docs/upstream-migrations/LP-05-JMAC-DSD.md)及对应源码头部；未整合的增量保持开发状态。其他固定参考和依赖审查记录见[上游实施指南](docs/11-上游复用实施指南.md)和[依赖记录](docs/development/DEV-004-A-dependencies.md)。App的完整依赖通知及许可页面仍由LP-15按实际打包集合完成，不能用本页冒充已完成的APK审查。

向接收者交付APK时，应从同一冻结源码快照提供完整对应源、修改及控制构建和安装的脚本，并保留全部许可通知。后续发行按现有候选/同产物发布链路落实相同快照及原字节绑定；当前没有发行或对应源资产验收结论。签名私密材料、凭据、设备日志和研究克隆不属于应用源码快照，继续保留在授权的本地或独立私有位置。
