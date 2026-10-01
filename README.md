# CyuClear

CyuClear 用于周期清理、规则检查、区块限流、恢复记录和虚空垃圾桶

这是 CyuClear 的源代码仓库。使用说明见 [`用户文档/cyuclear文档.html`](用户文档/cyuclear文档.html)

## 运行包

| 服务端 | 使用文件 | 产物目录 |
| --- | --- | --- |
| Bukkit / Spigot / Paper 1.13 及以上 | `cyuclear-paper-1.4.5.jar` | `target/` |
| Folia | `cyuclear-folia-1.4.5.jar` | `target/` |
| Bukkit / Spigot 1.8 至 1.12 | `cyuclear-legacy-1.4.5.jar` | `target/` |

每台服务端只放一个运行包到 `plugins`。首次启动后会生成配置文件；先完成规则检查与预演，再开启总开关

## 常用入口

| 命令 | 用途 |
| --- | --- |
| `/cc help` | 查看可用命令 |
| `/cc bin` | 打开虚空垃圾桶 |
| `/cc check` | 检查准星目标的清理判定 |
| `/cc preview` | 预演一次清理，不删除内容 |
| `/cc menu` | 打开管理中心 |
| `/cc status` | 查看当前运行状态与扫描预算 |
| `/cc doctor` | 检查配置文件健康度 |
| `/cc reload` | 保存配置快照后重载运行设置 |

| 权限 | 用途 |
| --- | --- |
| `cyuclear.use` | 帮助与虚空垃圾桶 |
| `cyuclear.bin.deposit` | 向本地虚空垃圾桶投放物品 |
| `cyuclear.admin` | 清理、预演、检查、重载和管理入口 |

## 配置文件

| 文件 | 用途 |
| --- | --- |
| `config.yml` | 总开关、周期、性能档位、恢复与世界范围 |
| `rules.yml` | 掉落物与实体规则、实时拦截、区块硬限制与 Panic 熔断 |
| `areas.yml` | 世界和坐标区域的独立规则 |
| `storage.yml` | Redis 或 MySQL 跨服同步 |
| `void-bin.yml` | 垃圾桶、玩家投放与个人投放缓冲区 |
| `sounds.yml` | 提示音效 |
| `lang/` | 双语语言文件（`zh_cn.yml` / `en_us.yml`） |
| `menu/` | 垃圾桶、规则、批次、热点和管理菜单 |

## 源码结构

```text
src/main/kotlin/org/cyuCBMclean/cyuclear/
├─ bootstrap/  启动装配、关闭与生命周期
├─ bridge/     PlaceholderAPI 与可选插件联动
├─ cluster/    Redis、MySQL 与跨服状态
├─ command/    命令路由、权限检查与智能 Tab 补全
├─ config/     配置读取、迁移、校验与规则解析
├─ listener/   事件监听与保护入口
├─ menu/       字符布局菜单引擎与图标渲染
├─ platform/   平台通知与版本边界
├─ service/    清理、回收、启发式保护、指标导出与限流服务
├─ storage/    清理批次持久化
├─ task/       倒计时与周期调度任务
└─ util/       物品、文本与兼容工具

src/paper/     Paper 1.13+ 平台实现
src/folia/     Folia 1.20+ 区域线程实现
src/legacy/    Bukkit / Spigot 1.8~1.12 旧端平台实现
```

## 构建

Paper 与 Legacy 使用 JDK 8，Folia 使用 JDK 17。

```bash
# 构建 Paper 端运行包
mvn -Ppaper package -DskipTests

# 构建 Legacy 端运行包
mvn -Plegacy package -DskipTests

# 构建 Folia 端运行包（需 JDK 17）
mvn -Pfolia package -DskipTests
```

构建前不需要执行 clean。产物位于 `target/`，发布前检查对应包内的 `plugin.yml`、平台标记、资源文件和类版本

## 许可

本项目使用仓库内的 `LICENSE-CYU.md`。允许个人学习、内部部署和非商业修改，但必须保留版权、许可证和项目来源说明。无论是否收费，都不得把原版或修改版宣称为自己原创、删除来源后重新发布，或将本项目代码抄入其他项目后作为自己的代码

不接受把本项目复制到其他仓库后删除作者信息、改名宣称原创，或以插件售卖、付费服务、商业整合包等方式使用本项目。完整限制以 `LICENSE-CYU.md` 为准
