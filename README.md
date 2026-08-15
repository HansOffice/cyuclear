# CyuClear

CyuClear 用于周期清理、规则检查、区块限流、恢复记录和虚空垃圾桶

这是 CyuClear 的源代码仓库。使用说明见 [`docs/cyuclear文档.html`](docs/cyuclear文档.html)

## 运行包

| 服务端 | 使用文件 |
| --- | --- |
| Bukkit / Spigot / Paper 1.13 及以上 | `cyuclear-paper-1.4.0.jar` |
| Folia | `cyuclear-folia-1.4.0.jar` |
| Bukkit / Spigot 1.8 至 1.12 | `cyuclear-legacy-1.4.0.jar` |

每台服务端只放一个运行包到 `plugins`。首次启动后会生成配置文件；先完成规则检查与预演，再开启总开关

## 常用入口

| 命令 | 用途 |
| --- | --- |
| `/cc help` | 查看可用命令 |
| `/cc bin` | 打开虚空垃圾桶 |
| `/cc check` | 检查准星目标的清理判定 |
| `/cc preview` | 预演一次清理，不删除内容 |
| `/cc menu` | 打开管理中心 |
| `/cc status` | 查看当前运行状态 |
| `/cc reload` | 保存配置快照后重载运行设置 |

| 权限 | 用途 |
| --- | --- |
| `cyuclear.use` | 帮助与虚空垃圾桶 |
| `cyuclear.bin.deposit` | 向本地虚空垃圾桶投放物品 |
| `cyuclear.admin` | 清理、预演、检查、重载和管理入口 |

## 配置文件

| 文件 | 用途 |
| --- | --- |
| `config.yml` | 总开关、周期、性能、恢复与世界范围 |
| `rules.yml` | 掉落物、实体、实时拦截、区块限制与 Panic |
| `areas.yml` | 世界和坐标区域的独立规则 |
| `storage.yml` | Redis 或 MySQL 跨服同步 |
| `void-bin.yml` | 垃圾桶、玩家投放与个人投放缓冲区 |
| `sounds.yml` | 音效 |
| `messages.yml` | 玩家提示与帮助文本 |
| `menu/` | 垃圾桶、规则、批次、热点和管理菜单 |

## 源码结构

```text
src/main/kotlin/org/cyuCBMclean/cyuclear/
├─ bootstrap/  启动、关闭与重载流程
├─ bridge/     PlaceholderAPI 与可选插件联动
├─ cluster/    Redis、MySQL 与跨服状态
├─ command/    命令、权限与补全
├─ config/     配置读取、迁移、校验与规则解析
├─ listener/   Bukkit 事件入口
├─ menu/       客制化菜单与图标渲染
├─ platform/   平台通知与版本边界
├─ service/    清理、回收、限流与运行状态
├─ storage/    清理批次持久化
├─ task/       倒计时与周期任务
└─ util/       物品、文本与兼容工具

src/paper/     Paper 实现
src/folia/     Folia 实现
src/legacy/    Legacy 实现
```

## 构建

Paper 与 Legacy 使用 JDK 8，Folia 使用 JDK 17

```powershell
$env:JAVA_HOME = '你的 JDK 8 目录'
mvn -Ppaper package -DskipTests
mvn -Plegacy package -DskipTests

$env:JAVA_HOME = '你的 JDK 17 目录'
mvn -Pfolia package -DskipTests
```

构建前不需要执行 `clean`。产物位于 `target/`，发布前检查对应包内的 `plugin.yml`、平台标记、资源文件和类版本

## 许可

本项目使用仓库内的 `LICENSE-CYU.md`。允许个人学习、内部部署和非商业修改，但必须保留版权、许可证和项目来源说明。无论是否收费，都不得把原版或修改版宣称为自己原创、删除来源后重新发布，或将本项目代码抄入其他项目后作为自己的代码

不接受把本项目复制到其他仓库后删除作者信息、改名宣称原创，或以插件售卖、付费服务、商业整合包等方式使用本项目。完整限制以 `LICENSE-CYU.md` 为准
