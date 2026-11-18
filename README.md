<div align="center">

<img src="images/brand.png" alt="logo" width="180"/>

# 本项目基于 **IzumiiKonata / OpenYSM-Updated** 制作

### 上游原作：**Yes Steve Model**（作者 Tianjin Accessible Care Innovation Development Co., Ltd.，Apache-2.0）
### 移植基础：**OpenYSM-Updated**（作者 **IzumiiKonata**，MIT）

**本仓库只做加法：在 OpenYSM-Updated 的基础上补上了服务端多人模型同步与自定义模型上传。所有模型解析、渲染、加密解密代码均来自上面两个上游项目。**

</div>

---

## 这个仓库改了什么

上游 OpenYSM-Updated 只验证了「单机能进世界并正常渲染模型」，多人游戏与服务端功能明确写着未测试。本仓库补的就是这一块：

| 改动 | 文件 | 说明 |
|---|---|---|
| 模型切换广播 | `common/.../network/message/C2SRequestSwitchModelPacket.java` | 玩家切换模型后广播给全场（上游只改服务端数据、不发包，所以别人看你还是原版皮肤） |
| 新玩家入场补发 | `common/.../event/CapabilityEvent.java` `replayAllModelsTo()` | 有人进服时，把在场所有人的模型单独推给他；死亡重生（`onPlayerCloned`）同样补发 |
| 入场/重生接线 | `common/.../event/EnterServerEvent.java` | 接上上面那个补发逻辑 |
| 服务端模型上传 | `common/.../model/ModelUploadManager.java` | 分块接收、sha256 校验、YSGP 头校验、落盘 `custom/`、自动重载 |
| 上传协议实现 | `common/.../network/message/C2SModelUpload*Packet.java` | 上游只有客户端半截，服务端 `handle` 是空的 |
| 上传开关 | `common/.../config/ServerConfig.java` | 新增 `[upload]`：`AllowUpload` / `PermissionLevel` / `MaxSizeMB` |
| 命令 | `common/.../command/subcommands/client/UploadCommand.java`<br>`.../UploadGuiCommand.java` | `/openysm upload <路径>`、`/openysm uploadgui` |

## 权限与安全

上传**默认关闭**，服务端 `config/yes_steve_model-server.toml`：

```toml
[upload]
	# 是否允许客户端上传模型
	AllowUpload = false
	# 需要的权限等级：0 = 所有人，2 = OP，4 = 谁都不行
	PermissionLevel = 2
	# 单个模型体积上限（MB）
	MaxSizeMB = 16
```

服务端会校验：文件总大小 → 分块 sha256 → YSGP v3 文件头（`EF BB BF 'Y''S''G''P'`）→ 才会写进 `config/yes_steve_model/custom/`，写完自动重载模型表。

## 安装

1. Minecraft **1.21.11** + **Fabric Loader 0.19.2+** + **Fabric API**
2. 客户端和服务端都要装同一个 jar
3. 把 `.ysm` 模型丢进服务端 `config/yes_steve_model/custom/`（或让有权限的玩家用 `/openysm uploadgui` 拖进去）
4. `/ysm model reload`，然后 `/ysm model set <玩家> <模型id> -`，或者玩家自己按 **Y** 打开模型界面挑

> 加密的正式版 `.ysm` 包的模型 id **带 `.ysm` 后缀**，例如 `dafiyu.ysm`。

## 已知问题

- 第一人称手臂位置偏低/偏左，和原版手部对不齐
- 预览界面（按 Y 打开的 GUI）部分缩略图不显示
- `libs/` 目录未包含在本仓库（见下）

## 构建

```bash
# 依赖 mod 的 jar 不在本仓库内（约 172 MB，大多是第三方 mod）
# 从上游仓库拿：https://github.com/IzumiiKonata/OpenYSM-Updated 的 libs/ 目录
# 放到项目根目录 libs/ 下即可

./gradlew :fabric:build -x test
# 产物：fabric/build/libs/openysm-fabric-*.jar
```

## 许可证与致谢

- 本仓库改动部分：随上游 **MIT**
- **OpenYSM-Updated** —— Copyright (c) 2026 OpenYSM，MIT License，https://github.com/IzumiiKonata/OpenYSM-Updated
- **Yes Steve Model** —— Copyright Tianjin Accessible Care Innovation Development Co., Ltd.，Apache-2.0
- 完整许可证见 `LICENSE.txt` 与上游仓库

没有这两个项目，就没有这个仓库。原作者的模型解析、渲染管线、加密解密实现全部原样保留。
