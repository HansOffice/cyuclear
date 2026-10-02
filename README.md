# CyuClear

用于周期清理、规则检查、区块限流、恢复记录和虚空垃圾桶

使用说明见 [`用户文档/cyuclear文档.html`](用户文档/cyuclear文档.html)

## 运行包

| 服务端 | 使用文件 |
|---|---|
| Paper 1.13+ / Spigot | `cyuclear-paper-1.4.6.jar` |
| Folia 1.20+ | `cyuclear-folia-1.4.6.jar` |
| Bukkit / Spigot 1.8~1.12 | `cyuclear-legacy-1.4.6.jar` |
| Velocity 3.3+ | `cyuclear-velocity-1.0.1.jar` |
| BungeeCord | `cyuclear-bungee-1.0.1.jar` |

每台服务端按类型放一个运行包到 `plugins` 即可，首次启动自动生成配置；后端建议先用 `/cc preview` 预演无误后再开启总开关

## 常用入口

| 命令 | 用途 |
|---|---|
| `/cc help` | 查看可用命令 |
| `/cc bin` | 打开虚空垃圾桶 |
| `/cc preview` | 预演清理不删物品 |
| `/cc check` | 检查准星目标清理判定 |
| `/cc menu` | 打开管理菜单 |
| `/cc runs` | 查看历史清理批次与回滚 |
| `/cc hotspots` | 查看实体密集热点区块 |
| `/cc status` | 查看运行状态与预算占用 |
| `/cc cluster` | 查看子服跨服集群状态 |
| `/cc doctor` | 检查配置文件健康度 |
| `/cc reload` | 重载运行设置 |
| `/ccproxy status` | 查看代理端集群监控状态 |
| `/ccproxy reload` | 重载代理端监控配置 |
| `/ccproxy help` | 查看代理端帮助 |

| 权限 | 用途 |
|---|---|
| `cyuclear.use` | 基础使用与虚空垃圾桶 |
| `cyuclear.bin.deposit` | 投放物品进虚空垃圾桶 |
| `cyuclear.admin` | 管理、清理、预演与重载 |
| `cyuclear.proxy.admin` | 代理端集群监控与重载 |

## 配置文件

| 文件 | 用途 |
|---|---|
| `config.yml` | 基础设置、周期、性能预算与世界范围 |
| `rules.yml` | 掉落物与实体黑白名单、启发式保护、区块硬限制与熔断 |
| `areas.yml` | 世界与坐标区域独立规则 |
| `void-bin.yml` | 虚空垃圾桶与个人投放缓冲区 |
| `storage.yml` | 单机文件与 MySQL / Redis 跨服同步 |
| `sounds.yml` | 提示音效 |
| `lang/` | 双语语言文件（`zh_cn.yml` / `en_us.yml`） |
| `menu/` | 字符画 GUI 菜单布局 |
| `代理端/.../config.yml` | 代理端集群只读监控连接配置 |

## 源码结构

```text
src/
├─ main/kotlin/org/cyuCBMclean/cyuclear/
│  ├─ bootstrap/   启动装配与生命周期
│  ├─ bridge/      PAPI 与可选插件联动
│  ├─ cluster/     跨服状态同步与垃圾桶协同
│  ├─ command/     命令鉴权、路由与补全
│  ├─ config/      配置解析与校验
│  ├─ listener/    事件监听与保护
│  ├─ menu/        菜单引擎与图标渲染
│  ├─ platform/    多平台调度直连
│  ├─ service/     扫描清理、启发式、指标与恢复
│  ├─ storage/     批次持久化
│  ├─ task/        周期调度与熔断监控
│  └─ util/        物品与着色兼容工具
│
├─ paper/          Paper 实现
├─ folia/          Folia 区域线程实现
└─ legacy/         Legacy 旧端实现

代理端/
├─ cyuclear-proxy-common/  跨服集群只读状态读取核心
├─ cyuclear-bungee/        BungeeCord 代理端插件
└─ cyuclear-velocity/      Velocity 代理端插件
```

## 构建

```bash
# 后端多平台构建
mvn package -Ppaper -DskipTests
mvn package -Pfolia -DskipTests
mvn package -Plegacy -DskipTests

# 代理端构建
mvn package -f 代理端/cyuclear-bungee/pom.xml -DskipTests
mvn package -f 代理端/cyuclear-velocity/pom.xml -DskipTests
```

构建产物分别位于各工程的 `target/` 目录

## 许可

本项目使用仓库内的 [`LICENSE-CYU.md`](LICENSE-CYU.md)，允许个人学习、内部部署和非商业修改，保留版权与来源说明；禁止商用转售、打包收费或抹除作者信息
