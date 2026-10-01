# CyuClear

优雅、轻量且高安全性的 Minecraft 全版本扫地与实体保护引擎。

专为现代商业服与多模组大服打造：告别传统清理插件造成的瞬时卡顿突刺与“一刀切”误清灾难，提供时间片平滑分批清理、村民与机械装置启发式免死保护、误清秒级回滚、虚空垃圾桶二次回收，以及 Prometheus / Grafana 云生监控指标原生接入。

这是 CyuClear 的开源代码仓库，面向服主的详细配置指南与使用手册见 [`用户文档/cyuclear文档.html`](用户文档/cyuclear文档.html)。

---

## 核心特性

- **时间片平滑扫描**：按 Tick 预算切片分批扫描实体与掉落物，彻底消除全服瞬时卡顿与 TPS 骤降
- **启发式资产保护**：开箱即用免配置，自动识别已交易成熟村民、交易所工位、刷铁机核心结构与动力运输矿车，杜绝误清玩家心血
- **误清批次秒级回滚**：每次清理自动打包快照存盘，支持一键撤销恢复误清批次或特定掉落物
- **虚空垃圾桶二次回收**：清理掉落物自动转入全局虚空桶，全服玩家限时自由捞回，支持个人投放与跨服 Redis/MySQL 共享
- **准星检测与安全预演**：支持 `/cc check` 准星现场分析目标清理判定，`/cc preview` 零风险全服模拟预演
- **现代云生监控接入**：内置轻量 `/metrics` 端点，零依赖原生对接 Prometheus 与 Grafana，实时观测清理速率与实体压力
- **多平台原生直连**：原生三端分层架构，完美覆盖 Paper、Folia 区域多线程与 1.8~1.12 旧端 Bukkit/Spigot

---

## 运行包与平台适配

插件编译产物为三端独立 Jar，服主按当前服务端类型将对应文件放入 `plugins` 目录即可：

| 平台端 | 适配版本 | 构建产物 | 平台特性 |
|---|---|---|---|
| **Paper** | 1.13 ~ 最新版本 | `cyuclear-paper-1.4.5.jar` | 默认通用端，适配 Spigot / Paper / Purpur |
| **Folia** | 1.20+ 区域多线程 | `cyuclear-folia-1.4.5.jar` | 原生接入 `RegionScheduler` 区域并发调度 |
| **Legacy** | 1.8 ~ 1.12.2 旧端 | `cyuclear-legacy-1.4.5.jar` | 适配旧版 NMS 与旧物品体系，低版本专属优化 |

> **部署提示**：每台服务器仅需部署一个匹配当前端的 Jar 包；首次启动将在 `plugins/cyuclear` 生成全套默认配置。建议先使用 `/cc preview` 预演清理逻辑，确认黑白名单规则无误后再开启周期自动任务。

---

## 常用指令与权限

| 指令 | 权限 | 用途说明 |
|---|---|---|
| `/cc help` | `cyuclear.use` | 查看交互式命令列表（支持点击补齐与翻页） |
| `/cc bin` | `cyuclear.use` | 打开虚空垃圾桶界面，拾回刚被清理的掉落物 |
| `/cc preview` | `cyuclear.admin` | 零风险预演清理，仅输出预估统计，不实际删除任何物品 |
| `/cc check` | `cyuclear.admin` | 准星对准实体或方块，现场诊断其命中哪些黑白名单规则 |
| `/cc menu` | `cyuclear.admin` | 打开图形化管理中心，可视化调整规则与监控状态 |
| `/cc runs` | `cyuclear.admin` | 查看历史清理批次记录，点击可进一步查看详情或一键撤销回滚 |
| `/cc hotspots` | `cyuclear.admin` | 查看当前全服实体密集的热点区块列表并支持直接传送 |
| `/cc status` | `cyuclear.admin` | 查看当前清理周期、Tick 预算占用与后台线程状态 |
| `/cc doctor` | `cyuclear.admin` | 运行配置体检，快速排查 YAML 格式错误或逻辑冲突 |
| `/cc reload` | `cyuclear.admin` | 自动备份快照并热重载全套配置文件与语言包 |

---

## 配置文件结构

所有配置文件均位于 `plugins/cyuclear/` 目录下，头部提供详尽的逐项参数注释：

| 配置文件 | 功能职责 |
|---|---|
| `config.yml` | 基础运行参数、自动周期、性能档位预算、世界生效范围与 Prometheus 监控开关 |
| `rules.yml` | 掉落物黑白名单、实体免清规则、启发式保护、单区块实体硬限制与紧急熔断 |
| `areas.yml` | 独立世界或自定义立方体坐标区域的个性化清理和保护规则 |
| `void-bin.yml` | 虚空垃圾桶开放时长、槽位容量、入桶过滤条件与个人暂存缓冲区配置 |
| `storage.yml` | 批次数据存储方式，支持单机文件与 MySQL / Redis 跨服同步集群模式 |
| `sounds.yml` | 倒计时预警、垃圾桶拾取、成功清理与紧急报警音效 |
| `lang/` | 双语文本文件（`zh_cn.yml` 简体中文 / `en_us.yml` 英文） |
| `menu/` | 基于纯字符画布局的 GUI 菜单配置，支持头颅与自定义物品库（IA/CE）材质 |

---

## 仓库结构

```text
src/
├─ main/kotlin/org/cyuCBMclean/cyuclear/
│  ├─ bootstrap/   生命周期管理与装配启动
│  ├─ bridge/      PAPI 变量、MythicMobs 及实体堆叠插件联动
│  ├─ cluster/     MySQL / Redis 跨服数据与状态同步
│  ├─ command/     子命令路由、鉴权与中英双语智能 Tab 补全
│  ├─ config/      配置解析、版本迁移校验与热重载
│  ├─ listener/    实体生成、掉落追踪与保护判定监听
│  ├─ menu/        纯字符画菜单引擎与交互分流防护
│  ├─ platform/    多平台调度直连与消息通知桥
│  ├─ service/     分批扫描引擎、启发式检测、指标导出与恢复中心
│  ├─ storage/     批次历史落库与持久化队列
│  ├─ task/        周期倒计时与 Panic 熔断监控线程
│  └─ util/        物品 NBT 快照、MiniMessage 渐变着色与兼容工具
│
├─ paper/          Paper 1.13+ 平台原生调度与 API 桥接
├─ folia/          Folia 区域多线程 RegionScheduler 原生直连
└─ legacy/         1.8 ~ 1.12.2 旧版本 Bukkit / Spigot 兼容层
```

---

## 构建方式

项目基于 Maven 构建，三端源码直连不同平台环境：

```bash
# 构建 Paper 端运行包（推荐默认）
mvn -Ppaper package -DskipTests

# 构建 Folia 区域多线程运行包（需 JDK 17 及以上）
mvn -Pfolia package -DskipTests

# 构建 Legacy 旧版本运行包（JDK 8 兼容）
mvn -Plegacy package -DskipTests
```

构建生成的独立 Jar 包位于 `target/` 目录；正式发布产物以 `发行/` 目录归档为准。

---

## 开源许可

本项目采用仓库根目录下的 [`LICENSE-CYU.md`](LICENSE-CYU.md) 许可协议发布。

- 允许个人学习、交流研究、服务器内部部署与非商业性二次修改，但必须在修改版中显式保留原始版权标识、许可证文本与本项目来源链接
- 严禁任何团队或个人以任何形式将本项目原版或修改版打包进行二次收费、商业插件买卖、付费整合包捆绑，或删除原作者信息后宣称原创
- 任何抄袭、逆向后改名二次发布或规避开源协议的商业行为均属侵权，完整协议条款请务必参阅 [`LICENSE-CYU.md`](LICENSE-CYU.md)
